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

/**
 * Runs builds as jobs and keeps their outcome so that a client can poll instead of
 * holding an HTTP request open for the length of a build.
 */
public final class BuildRegistry {

	/** How many finished builds stay queryable. */
	private static final int HISTORY = 20;

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
	 * Starts a build and returns immediately. {@code projectNames} empty means the
	 * whole workspace.
	 */
	public synchronized Build start(String kind, List<String> projectNames, boolean countProblems) {
		String id = "build-" + ids.incrementAndGet(); //$NON-NLS-1$
		Build build = new Build(id, kind, projectNames);
		builds.put(id, build);
		lastId = id;

		Job job = Job.create("MCP " + kind + " build", monitor -> { //$NON-NLS-1$ //$NON-NLS-2$
			run(build, kind, projectNames, countProblems, monitor);
			return Status.OK_STATUS;
		});
		job.setRule(ResourcesPlugin.getWorkspace().getRuleFactory().buildRule());
		job.setUser(false);
		job.schedule();
		return build;
	}

	private static void run(Build build, String kind, List<String> projectNames, boolean countProblems,
			IProgressMonitor monitor) {
		IWorkspace workspace = ResourcesPlugin.getWorkspace();
		int buildKind = kindOf(kind);
		List<String> failures = new ArrayList<>();
		String state = "done"; //$NON-NLS-1$
		try {
			if (projectNames.isEmpty()) {
				workspace.build(buildKind, monitor);
			} else {
				for (String name : projectNames) {
					IProject project = workspace.getRoot().getProject(name);
					if (project.isAccessible()) {
						project.build(buildKind, monitor);
					} else {
						failures.add("Project '%s' is not open, so it was not built.".formatted(name)); //$NON-NLS-1$
					}
				}
			}
		} catch (CoreException e) {
			state = "failed"; //$NON-NLS-1$
			collect(e.getStatus(), failures);
		} catch (OperationCanceledException e) {
			state = "cancelled"; //$NON-NLS-1$
		}
		build.builderFailures = List.copyOf(failures);
		if (countProblems) {
			countProblems(build, projectNames);
		}
		build.endedAt = System.currentTimeMillis();
		build.state = state;
		build.finished.countDown();
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
