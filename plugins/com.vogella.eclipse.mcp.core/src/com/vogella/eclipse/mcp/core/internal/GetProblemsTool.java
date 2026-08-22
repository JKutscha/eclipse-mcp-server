package com.vogella.eclipse.mcp.core.internal;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.eclipse.core.resources.IMarker;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.OperationCanceledException;

import com.vogella.eclipse.mcp.core.IMcpTool;
import com.vogella.eclipse.mcp.core.McpToolException;
import com.vogella.eclipse.mcp.core.McpToolResult;
import com.vogella.eclipse.mcp.core.ToolArguments;
import com.vogella.eclipse.mcp.core.WorkspaceSync;
import com.vogella.eclipse.mcp.core.json.JsonArray;
import com.vogella.eclipse.mcp.core.json.JsonObject;

/**
 * Reports the problem markers the incremental builder produced.
 */
public final class GetProblemsTool implements IMcpTool {

	private static final int DEFAULT_MAX_RESULTS = 200;

	private static final Set<String> SEVERITIES = Set.of("error", "warning", "info", "all"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$

	private record Problem(String path, String project, int line, int severity, String message, String type) {
	}

	@Override
	public String getName() {
		return "eclipse_get_problems"; //$NON-NLS-1$
	}

	@Override
	public String getDescription() {
		return "Returns compilation errors and warnings from the Eclipse workspace, as computed by the incremental builder. It refreshes from disk by default, scoped to the project when one is named, and WAITS for a build already running, but it NEVER STARTS ONE. Asking for markers used to start an incremental build that JDT turned into a batch compile of every open project, so reading them cost thirty seconds; starting a build is eclipse_build's job. With auto-build off the markers are therefore from the last build and upToDate says so. messageFilter narrows to problems whose message contains a substring, which is how to ask for deprecation warnings alone rather than pulling every warning and filtering them yourself."; //$NON-NLS-1$
	}

	@Override
	public String getInputSchema() {
		return """
				{
				  "type": "object",
				  "properties": {
				    "severity":   {"type":"string","enum":["error","warning","info","all"],"default":"error","description":"Only return problems of exactly this severity. Use 'all' for every severity."},
				    "project":    {"type":"string","description":"Restrict to this project name."},
				    "pathPrefix": {"type":"string","description":"Restrict to workspace paths starting with this prefix."},
				    "maxResults": {"type":"integer","default":200,"minimum":1,"maximum":2000},
				    "marker":     {"type":"string","description":"A marker from eclipse_mark_problems. Reports only the problems that appeared since it, plus the ones that went away, instead of everything."},
				    "messageFilter": {"type":"string","description":"Only problems whose message contains this text, case insensitive. Use 'deprecated' for deprecation warnings."},
				    "refresh":    {"type":"boolean","default":true,"description":"Read changes made outside the IDE into the workspace first, restricted to 'project' when one is named. Cheap: seconds for the whole workspace."}
				  },
				  "additionalProperties": false
				}"""; //$NON-NLS-1$
	}

	@Override
	public McpToolResult call(Map<String, Object> arguments, IProgressMonitor monitor) throws McpToolException {
		ToolArguments args = ToolArguments.of(arguments);
		String severity = args.getString("severity", "error"); //$NON-NLS-1$ //$NON-NLS-2$
		if (!SEVERITIES.contains(severity)) {
			return McpToolResult.error("Unknown severity '%s', expected one of error, warning, info, all." //$NON-NLS-1$
					.formatted(severity));
		}
		String projectName = args.getString("project"); //$NON-NLS-1$
		String pathPrefix = args.getString("pathPrefix"); //$NON-NLS-1$
		int maxResults = args.getInt("maxResults", DEFAULT_MAX_RESULTS, 1, 2000); //$NON-NLS-1$
		boolean refresh = args.getBoolean("refresh", true); //$NON-NLS-1$
		String messageFilter = args.getString("messageFilter"); //$NON-NLS-1$
		String problemMarker = args.getString("marker"); //$NON-NLS-1$
		java.util.Set<String> baseline = null;
		if (problemMarker != null) {
			baseline = ProblemBaselines.of(problemMarker);
			if (baseline == null) {
				return McpToolResult.error(
						"'%s' is not a problem marker this server still holds. Take one with eclipse_mark_problems; only the last few are kept. Known: %s" //$NON-NLS-1$
								.formatted(problemMarker, ProblemBaselines.ids()));
			}
		}

		boolean upToDate = false;
		if (refresh) {
			org.eclipse.core.resources.IProject project = projectName == null ? null
					: ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
			IResource scope = project == null ? ResourcesPlugin.getWorkspace().getRoot() : project;
			try {
				WorkspaceSync.refresh(scope, monitor);
			} catch (CoreException e) {
				throw new McpToolException("Could not refresh the workspace", e); //$NON-NLS-1$
			} catch (OperationCanceledException e) {
				upToDate = false;
			}
		}

		IMarker[] markers;
		try {
			markers = ResourcesPlugin.getWorkspace().getRoot().findMarkers(IMarker.PROBLEM, true,
					IResource.DEPTH_INFINITE);
		} catch (CoreException e) {
			throw new McpToolException("Could not read the problem markers of the workspace", e); //$NON-NLS-1$
		}

		List<Problem> problems = new ArrayList<>();
		for (IMarker marker : markers) {
			if (monitor.isCanceled()) {
				return McpToolResult.error("The request was cancelled."); //$NON-NLS-1$
			}
			Problem problem = toProblem(marker, severity, projectName, pathPrefix);
			if (problem != null && (messageFilter == null || problem.message()
					.toLowerCase(java.util.Locale.ROOT).contains(messageFilter.toLowerCase(java.util.Locale.ROOT)))) {
				problems.add(problem);
			}
		}
		// waits for a build already running, and never starts one. Asking for markers
		// used to trigger an incremental build that JDT turned into a batch compile of
		// every open project, so a read took thirty seconds; starting a build is
		// eclipse_build's job, where the caller asked for it
		boolean waited = WorkspaceSync.waitForBuild(monitor);
		upToDate = refresh && waited && WorkspaceSync.isAutoBuilding();

		JsonArray resolved = new JsonArray();
		int resolvedTotal = 0;
		if (baseline != null) {
			java.util.Set<String> now = new java.util.LinkedHashSet<>();
			problems.forEach(problem -> now.add(key(problem)));
			for (String gone : baseline) {
				// the diff has to be taken over the scope the caller asked about, not
				// over the whole workspace the marker recorded
				if (!inScope(gone, projectName, pathPrefix, severity, messageFilter) || now.contains(gone)) {
					continue;
				}
				resolvedTotal++;
				if (resolved.size() < maxResults) {
					resolved.add(gone);
				}
			}
			java.util.Set<String> was = baseline;
			problems = new ArrayList<>(problems.stream().filter(problem -> !was.contains(key(problem))).toList());
		}
		problems.sort(Comparator.comparingInt(Problem::severity).reversed().thenComparing(Problem::path)
				.thenComparingInt(Problem::line));

		JsonArray reported = new JsonArray();
		for (Problem problem : problems.subList(0, Math.min(maxResults, problems.size()))) {
			reported.add(new JsonObject().put("path", problem.path()) //$NON-NLS-1$
					.put("project", problem.project()) //$NON-NLS-1$
					.put("line", problem.line() < 0 ? null : Integer.valueOf(problem.line())) //$NON-NLS-1$
					.put("severity", severityName(problem.severity())) //$NON-NLS-1$
					.put("message", problem.message()) //$NON-NLS-1$
					.put("type", problem.type())); //$NON-NLS-1$
		}
		JsonObject result = new JsonObject().put("total", problems.size()) //$NON-NLS-1$
				.put("truncated", problems.size() > reported.size()) //$NON-NLS-1$
				.put("refreshed", Boolean.valueOf(refresh)) //$NON-NLS-1$
				.put("waitedForBuild", Boolean.valueOf(waited)) //$NON-NLS-1$
				.put("upToDate", upToDate) //$NON-NLS-1$
				.put("autoBuild", WorkspaceSync.isAutoBuilding()) //$NON-NLS-1$
				.put("problems", reported); //$NON-NLS-1$
		if (!WorkspaceSync.isAutoBuilding()) {
			result.put("staleness", //$NON-NLS-1$
					"Auto-build is off and this tool never starts a build, so these markers are from the last build and may predate recent edits. Run eclipse_build first when that matters."); //$NON-NLS-1$
		}
		if (!WorkspaceSync.isAutoBuilding()) {
			result.put("staleness", //$NON-NLS-1$
					"Auto-build is off and this tool never starts a build, so these markers are from the last build and may predate recent edits. Run eclipse_build first when that matters."); //$NON-NLS-1$
		}
		if (problemMarker != null) {
			result.put("marker", problemMarker) //$NON-NLS-1$
					.put("sinceMarker", Boolean.TRUE) //$NON-NLS-1$
					.put("resolvedCount", Integer.valueOf(resolvedTotal)) //$NON-NLS-1$
					.put("resolvedTruncated", Boolean.valueOf(resolvedTotal > resolved.size())) //$NON-NLS-1$
					.put("resolved", resolved) //$NON-NLS-1$
					.put("note", //$NON-NLS-1$
							"'problems' are the ones that appeared since the marker and 'resolved' the ones that went away. Everything unchanged is omitted, which is the point."); //$NON-NLS-1$
		}
		return McpToolResult.of(result.toString());
	}

	/**
	 * Identity of a problem across two builds: where it is and what it says.
	 * <p>
	 * The four fields are parsed back out when a baseline is narrowed to a query's
	 * scope, so the order matters: a workspace path never contains a colon and the
	 * message may, which is why the message is last and the split is bounded.
	 */
	static String key(Problem problem) {
		return "%s:%d:%d:%s".formatted(problem.path(), Integer.valueOf(problem.line()), //$NON-NLS-1$
				Integer.valueOf(problem.severity()), problem.message());
	}

	/**
	 * Whether a baseline entry is inside the scope this call is asking about.
	 * <p>
	 * A marker is taken workspace wide and a query is usually not. Diffing the two
	 * as they stand reports every problem outside the scope as resolved: one
	 * project-scoped call answered with three thousand problems it had never been
	 * asked about, all of them still present.
	 */
	private static boolean inScope(String key, String projectName, String pathPrefix, String severity,
			String messageFilter) {
		String[] parts = key.split(":", 4); //$NON-NLS-1$
		if (parts.length < 4) {
			return true;
		}
		String path = parts[0];
		if (projectName != null && !path.startsWith("/" + projectName + "/")) { //$NON-NLS-1$ //$NON-NLS-2$
			return false;
		}
		if (pathPrefix != null && !path.startsWith(pathPrefix)) {
			return false;
		}
		Integer wanted = severityOf(severity);
		if (wanted != null && !String.valueOf(wanted).equals(parts[2])) {
			return false;
		}
		return messageFilter == null || parts[3].toLowerCase(java.util.Locale.ROOT).contains(messageFilter.toLowerCase(java.util.Locale.ROOT));
	}

	/** Every problem in the workspace, as keys, for a baseline. */
	static java.util.Set<String> allProblemKeys() {
		java.util.Set<String> keys = new java.util.LinkedHashSet<>();
		try {
			for (IMarker marker : ResourcesPlugin.getWorkspace().getRoot().findMarkers(IMarker.PROBLEM, true,
					IResource.DEPTH_INFINITE)) {
				Problem problem = toProblem(marker, "all", null, null); //$NON-NLS-1$
				if (problem != null) {
					keys.add(key(problem));
				}
			}
		} catch (CoreException e) {
			// an unreadable marker set yields an empty baseline, which reports
			// everything as new rather than silently reporting nothing
		}
		return keys;
	}

	private static Problem toProblem(IMarker marker, String severity, String projectName, String pathPrefix) {
		IResource resource = marker.getResource();
		if (resource == null || !resource.exists()) {
			return null;
		}
		IProject project = resource.getProject();
		if (projectName != null && (project == null || !projectName.equals(project.getName()))) {
			return null;
		}
		String path = resource.getFullPath().toString();
		if (pathPrefix != null && !path.startsWith(pathPrefix)) {
			return null;
		}
		int markerSeverity = marker.getAttribute(IMarker.SEVERITY, IMarker.SEVERITY_INFO);
		Integer wanted = severityOf(severity);
		if (wanted != null && wanted.intValue() != markerSeverity) {
			return null;
		}
		String type;
		try {
			type = marker.getType();
		} catch (CoreException e) {
			// the marker disappeared between findMarkers and this read
			return null;
		}
		int line = marker.getAttribute(IMarker.LINE_NUMBER, -1);
		String message = marker.getAttribute(IMarker.MESSAGE, ""); //$NON-NLS-1$
		return new Problem(path, project == null ? null : project.getName(), line, markerSeverity, message, type);
	}

	/** Returns the marker severity to match, {@code null} for {@code all} and for unknown names. */
	private static Integer severityOf(String severity) {
		return switch (severity) {
		case "error" -> Integer.valueOf(IMarker.SEVERITY_ERROR); //$NON-NLS-1$
		case "warning" -> Integer.valueOf(IMarker.SEVERITY_WARNING); //$NON-NLS-1$
		case "info" -> Integer.valueOf(IMarker.SEVERITY_INFO); //$NON-NLS-1$
		default -> null;
		};
	}

	private static String severityName(int severity) {
		return switch (severity) {
		case IMarker.SEVERITY_ERROR -> "error"; //$NON-NLS-1$
		case IMarker.SEVERITY_WARNING -> "warning"; //$NON-NLS-1$
		default -> "info"; //$NON-NLS-1$
		};
	}
}
