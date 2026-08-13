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

import com.vogella.eclipse.mcp.core.IMcpTool;
import com.vogella.eclipse.mcp.core.McpToolException;
import com.vogella.eclipse.mcp.core.McpToolResult;
import com.vogella.eclipse.mcp.core.ToolArguments;
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
		return "Returns compilation errors and warnings from the Eclipse workspace, as computed by the incremental builder. Reflects the state of the last build."; //$NON-NLS-1$
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
				    "maxResults": {"type":"integer","default":200,"minimum":1,"maximum":2000}
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
			if (problem != null) {
				problems.add(problem);
			}
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
				.put("problems", reported); //$NON-NLS-1$
		return McpToolResult.of(result.toString());
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
