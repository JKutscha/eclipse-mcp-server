package com.vogella.eclipse.mcp.core.internal;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.eclipse.core.resources.IMarker;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.IncrementalProjectBuilder;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.OperationCanceledException;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;

import com.vogella.eclipse.mcp.core.WorkspaceSync;

/**
 * Runs builds as jobs and keeps their outcome so that a client can poll instead of
 * holding an HTTP request open for the length of a build.
 */
public final class BuildRegistry {

	/** How many finished builds stay queryable. */
	private static final int HISTORY = 20;

	public static final String CLEAN = "clean"; //$NON-NLS-1$

	public static final String REFRESH = "refresh"; //$NON-NLS-1$

	private static final BuildRegistry INSTANCE = new BuildRegistry();

	private final AtomicLong ids = new AtomicLong();

	private final Map<String, Build> builds = new LinkedHashMap<>() {
		private static final long serialVersionUID = 1L;

		@Override
		protected boolean removeEldestEntry(Map.Entry<String, Build> eldest) {
			return size() > HISTORY && !"running".equals(eldest.getValue().state()); //$NON-NLS-1$
		}
	};

	private String lastId;

	public static BuildRegistry getInstance() {
		return INSTANCE;
	}

	private BuildRegistry() {
	}

	/** One build, its outcome, and the problems the workspace had once it ended. */
	public static final class Build {

		private final String id;
		private final String kind;
		private final List<String> projects;
		private final long startedAt = System.currentTimeMillis();
		private final CountDownLatch finished = new CountDownLatch(1);

		private volatile String state = "running"; //$NON-NLS-1$
		private volatile long endedAt;
		private volatile long refreshMillis = -1;
		private volatile long buildMillis = -1;
		private volatile String note;
		private volatile List<String> builderFailures = List.of();
		private volatile int errors = -1;
		private volatile int warnings = -1;

		Build(String id, String kind, List<String> projects) {
			this.id = id;
			this.kind = kind;
			this.projects = List.copyOf(projects);
		}

		public String id() {
			return id;
		}

		public String kind() {
			return kind;
		}

		public List<String> projects() {
			return projects;
		}

		public String state() {
			return state;
		}

		public long elapsedMillis() {
			return (endedAt == 0 ? System.currentTimeMillis() : endedAt) - startedAt;
		}

		/** Time spent refreshing from disk, {@code -1} when no refresh was asked for. */
		public long refreshMillis() {
			return refreshMillis;
		}

		/** Time spent building, {@code -1} for a refresh that never built. */
		public long buildMillis() {
			return buildMillis;
		}

		/** Set when the outcome needs a caveat, such as a clean that rebuilt nothing. */
		public String note() {
			return note;
		}

		/**
		 * Builder exceptions that never became markers. A build that reports no
		 * problems while its builder threw is the misleading case worth avoiding.
		 */
		public List<String> builderFailures() {
			return builderFailures;
		}

		/** Error count once the build ended, {@code -1} while it is still running. */
		public int errors() {
			return errors;
		}

		public int warnings() {
			return warnings;
		}

		boolean await(long timeout, TimeUnit unit) throws InterruptedException {
			return finished.await(timeout, unit);
		}
	}

	/**
	 * What one call asked for. {@code projectNames} empty means the whole
	 * workspace; {@code kind} is {@code refresh} for a refresh that never builds.
	 */
	public record Request(String kind, List<String> projectNames, boolean countProblems, boolean refresh,
			boolean buildAfterClean) {
	}

	/**
	 * Starts the work and returns immediately.
	 * <p>
	 * Everything slow happens inside the job, the refresh included. A refresh done
	 * before scheduling would make a call block for its whole duration even when
	 * the caller asked not to wait, which defeats the point of handing back an id.
	 */
	public synchronized Build start(Request request) {
		String id = "build-" + ids.incrementAndGet(); //$NON-NLS-1$
		Build build = new Build(id, request.kind(), request.projectNames());
		builds.put(id, build);
		lastId = id;

		Job job = Job.create("MCP " + request.kind(), monitor -> { //$NON-NLS-1$
			run(build, request, monitor);
			return Status.OK_STATUS;
		});
		job.setRule(ResourcesPlugin.getWorkspace().getRuleFactory().buildRule());
		job.setUser(false);
		job.schedule();
		return build;
	}

	private static void run(Build build, Request request, IProgressMonitor monitor) {
		IWorkspace workspace = ResourcesPlugin.getWorkspace();
		String kind = request.kind();
		List<String> projectNames = request.projectNames();
		List<String> failures = new ArrayList<>();
		String state = "done"; //$NON-NLS-1$

		if (request.refresh()) {
			long startedRefresh = System.currentTimeMillis();
			try {
				for (IResource scope : scopes(projectNames)) {
					WorkspaceSync.refresh(scope, monitor);
				}
			} catch (CoreException e) {
				state = "failed"; //$NON-NLS-1$
				collect(e.getStatus(), failures);
			} catch (OperationCanceledException e) {
				state = "cancelled"; //$NON-NLS-1$
			}
			build.refreshMillis = System.currentTimeMillis() - startedRefresh;
		}

		if (!REFRESH.equals(kind) && "done".equals(state)) { //$NON-NLS-1$
			long startedBuild = System.currentTimeMillis();
			try {
				build(workspace, kindOf(kind), projectNames, failures, monitor);
				if (CLEAN.equals(kind) && request.buildAfterClean()) {
					build(workspace, IncrementalProjectBuilder.FULL_BUILD, projectNames, failures, monitor);
				}
			} catch (CoreException e) {
				state = "failed"; //$NON-NLS-1$
				collect(e.getStatus(), failures);
			} catch (OperationCanceledException e) {
				state = "cancelled"; //$NON-NLS-1$
			}
			build.buildMillis = System.currentTimeMillis() - startedBuild;
		}

		if (CLEAN.equals(kind) && !request.buildAfterClean()) {
			// a clean deletes the markers, so the counts below describe an unbuilt
			// workspace and say nothing about whether it compiles
			build.note = "A clean deletes build state without rebuilding, so the error and warning counts below only mean that nothing is built. Pass buildAfterClean to get a verdict."; //$NON-NLS-1$
		}
		collectLogged(build, failures);
		build.builderFailures = List.copyOf(failures);
		if (request.countProblems()) {
			countProblems(build, projectNames);
		}
		build.endedAt = System.currentTimeMillis();
		build.state = state;
		build.finished.countDown();
	}

	private static void build(IWorkspace workspace, int buildKind, List<String> projectNames, List<String> failures,
			IProgressMonitor monitor) throws CoreException {
		if (projectNames.isEmpty()) {
			workspace.build(buildKind, monitor);
			return;
		}
		for (String name : projectNames) {
			IProject project = workspace.getRoot().getProject(name);
			if (project.isAccessible()) {
				project.build(buildKind, monitor);
			} else {
				failures.add("Project '%s' is not open, so it was not built.".formatted(name)); //$NON-NLS-1$
			}
		}
	}

	/** The resources to refresh: the named projects, or the whole workspace. */
	private static List<IResource> scopes(List<String> projectNames) {
		if (projectNames.isEmpty()) {
			return List.of(ResourcesPlugin.getWorkspace().getRoot());
		}
		List<IResource> scopes = new ArrayList<>();
		for (String name : projectNames) {
			scopes.add(ResourcesPlugin.getWorkspace().getRoot().getProject(name));
		}
		return scopes;
	}

	/**
	 * Adds the errors and warnings the platform logged while the build ran.
	 * <p>
	 * A builder that throws does not fail the build: {@code BuildManager} runs
	 * builders inside a {@code SafeRunner}, which catches the exception and logs it,
	 * so {@code IProject.build} returns normally and there is nothing to catch. The
	 * failure only exists in the log, and without this a project whose
	 * {@code JavaBuilder} threw reports a clean build.
	 * <p>
	 * The entries are correlated by time, not by causation, so anything else logged
	 * during the same window is included too. That is the honest trade: over-report
	 * rather than call a broken build clean.
	 */
	private static void collectLogged(Build build, List<String> into) {
		var location = org.eclipse.core.runtime.Platform.getLogFileLocation();
		if (location == null) {
			return;
		}
		java.time.LocalDateTime since = java.time.Instant.ofEpochMilli(build.startedAt)
				.atZone(java.time.ZoneId.systemDefault()).toLocalDateTime();
		try {
			for (PlatformLogFile.Entry entry : PlatformLogFile.read(location.toFile().toPath())) {
				if (entry.time() == null || entry.time().isBefore(since)) {
					continue;
				}
				if (entry.severity() == IStatus.ERROR || entry.severity() == IStatus.WARNING) {
					into.add("%s logged: %s".formatted(entry.plugin(), entry.message())); //$NON-NLS-1$
				}
			}
		} catch (java.io.IOException e) {
			// the log is a diagnostic aid here, not the result; a build that ran still ran
		}
	}

	/** Flattens a build's status tree, because builder failures arrive as a multi status. */
	private static void collect(IStatus status, List<String> into) {
		if (status == null) {
			return;
		}
		if (status.getMessage() != null && !status.getMessage().isBlank() && !status.isMultiStatus()) {
			into.add(status.getMessage());
		}
		for (IStatus child : status.getChildren()) {
			collect(child, into);
		}
	}

	private static void countProblems(Build build, List<String> projectNames) {
		int errors = 0;
		int warnings = 0;
		try {
			List<IResource> scopes = new ArrayList<>();
			if (projectNames.isEmpty()) {
				scopes.add(ResourcesPlugin.getWorkspace().getRoot());
			} else {
				for (String name : projectNames) {
					scopes.add(ResourcesPlugin.getWorkspace().getRoot().getProject(name));
				}
			}
			for (IResource scope : scopes) {
				if (!scope.isAccessible()) {
					continue;
				}
				for (IMarker marker : scope.findMarkers(IMarker.PROBLEM, true, IResource.DEPTH_INFINITE)) {
					int severity = marker.getAttribute(IMarker.SEVERITY, IMarker.SEVERITY_INFO);
					if (severity == IMarker.SEVERITY_ERROR) {
						errors++;
					} else if (severity == IMarker.SEVERITY_WARNING) {
						warnings++;
					}
				}
			}
			build.errors = errors;
			build.warnings = warnings;
		} catch (CoreException e) {
			// the counts stay at -1, which the tool reports as unknown
		}
	}

	private static int kindOf(String kind) {
		return switch (kind) {
		case "full" -> IncrementalProjectBuilder.FULL_BUILD; //$NON-NLS-1$
		case "clean" -> IncrementalProjectBuilder.CLEAN_BUILD; //$NON-NLS-1$
		default -> IncrementalProjectBuilder.INCREMENTAL_BUILD;
		};
	}

	public synchronized Build find(String id) {
		return builds.get(id);
	}

	public synchronized Build findLatest() {
		return lastId == null ? null : builds.get(lastId);
	}
}
