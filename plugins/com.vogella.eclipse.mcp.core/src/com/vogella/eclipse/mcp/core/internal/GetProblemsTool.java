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
		return "Returns compilation errors and warnings from the Eclipse workspace, as computed by the incremental builder. Refreshing from disk and building are separate flags, both on by default and both restricted to the project when one is named: a client that has just written a file wants the refresh and not the build, and a client that has just called eclipse_build wants the wait and not the refresh. With auto-build off, 'build' is what costs the time, not 'refresh'. messageFilter narrows to problems whose message contains a substring, which is how to ask for deprecation warnings alone rather than pulling every warning and filtering them yourself."; //$NON-NLS-1$
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
				    "refresh":    {"type":"boolean","default":true,"description":"Read changes made outside the IDE into the workspace first, restricted to 'project' when one is named. Cheap: seconds for the whole workspace."},
				    "build":      {"type":"boolean","default":true,"description":"Build and wait before reading the markers, restricted to 'project' when one is named. This is the expensive half with auto-build off; set false for a fast answer that may be stale."}
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
		boolean build = args.getBoolean("build", true); //$NON-NLS-1$
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
		if (refresh || build) {
			org.eclipse.core.resources.IProject project = projectName == null ? null
					: ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
			IResource scope = project == null ? ResourcesPlugin.getWorkspace().getRoot() : project;
			try {
				if (refresh) {
					WorkspaceSync.refresh(scope, monitor);
				}
				// up to date means the markers reflect the files, which needs both: a
				// build without a refresh has not seen edits made outside the IDE, and
				// a refresh without a build has not turned them into markers
				boolean built = !build || WorkspaceSync.build(project, monitor);
				upToDate = refresh && build && built;
			} catch (CoreException e) {
				throw new McpToolException("Could not refresh and build the workspace", e); //$NON-NLS-1$
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
		JsonArray resolved = new JsonArray();
		if (baseline != null) {
			java.util.Set<String> now = new java.util.LinkedHashSet<>();
			problems.forEach(problem -> now.add(key(problem)));
			for (String gone : baseline) {
				if (!now.contains(gone)) {
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
				.put("built", Boolean.valueOf(build)) //$NON-NLS-1$
				.put("upToDate", upToDate) //$NON-NLS-1$
				.put("autoBuild", WorkspaceSync.isAutoBuilding()) //$NON-NLS-1$
				.put("problems", reported); //$NON-NLS-1$
		if (problemMarker != null) {
			result.put("marker", problemMarker) //$NON-NLS-1$
					.put("sinceMarker", Boolean.TRUE) //$NON-NLS-1$
					.put("resolvedCount", Integer.valueOf(resolved.size())) //$NON-NLS-1$
					.put("resolved", resolved) //$NON-NLS-1$
					.put("note", //$NON-NLS-1$
							"'problems' are the ones that appeared since the marker and 'resolved' the ones that went away. Everything unchanged is omitted, which is the point."); //$NON-NLS-1$
		}
		return McpToolResult.of(result.toString());
	}

	/** Identity of a problem across two builds: where it is and what it says. */
	static String key(Problem problem) {
		return "%s:%d:%d:%s".formatted(problem.path(), Integer.valueOf(problem.line()), //$NON-NLS-1$
				Integer.valueOf(problem.severity()), problem.message());
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
