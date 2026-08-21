package com.vogella.eclipse.mcp.core.internal;

import java.util.Map;

import org.eclipse.core.runtime.IProgressMonitor;

import com.vogella.eclipse.mcp.core.IMcpTool;
import com.vogella.eclipse.mcp.core.McpToolResult;
import com.vogella.eclipse.mcp.core.ToolArguments;
import com.vogella.eclipse.mcp.core.json.JsonArray;
import com.vogella.eclipse.mcp.core.json.JsonObject;

/**
 * Reports the state of a build started through {@code eclipse_build}.
 */
public final class GetBuildStatusTool implements IMcpTool {

	@Override
	public String getName() {
		return "eclipse_get_build_status"; //$NON-NLS-1$
	}

	@Override
	public String getDescription() {
		return "Reports the state of work started through eclipse_build or eclipse_refresh: running, done, failed or cancelled, how long the refresh and the build each took, the builder failures it hit and the error and warning counts that followed it. Without a buildId it reports the most recent build."; //$NON-NLS-1$
	}

	@Override
	public String getInputSchema() {
		return """
				{
				  "type": "object",
				  "properties": {
				    "buildId": {"type":"string","description":"Identifier returned by eclipse_build. Omit for the most recent build."}
				  },
				  "additionalProperties": false
				}"""; //$NON-NLS-1$
	}

	@Override
	public McpToolResult call(Map<String, Object> arguments, IProgressMonitor monitor) {
		String buildId = ToolArguments.of(arguments).getString("buildId"); //$NON-NLS-1$
		if (buildId == null && !com.vogella.eclipse.mcp.core.ClientSessions.canAssumeASingleClient()) {
			return McpToolResult.error(com.vogella.eclipse.mcp.core.ClientSessions.ambiguousDefault("build", //$NON-NLS-1$
					"buildId", BuildRegistry.getInstance().ids())); //$NON-NLS-1$
		}
		BuildRegistry.Build build = buildId == null ? BuildRegistry.getInstance().findLatest()
				: BuildRegistry.getInstance().find(buildId);
		if (build == null) {
			if (buildId != null) {
				return McpToolResult
						.error("No build with the id '%s'. Only the last few builds are kept.".formatted(buildId)); //$NON-NLS-1$
			}
			// nothing built yet is an answer, not a failure
			return McpToolResult.of(new JsonObject().put("state", "none") //$NON-NLS-1$ //$NON-NLS-2$
					.put("message", "No build has been started through eclipse_build yet.").toString()); //$NON-NLS-1$ //$NON-NLS-2$
		}
		return McpToolResult.of(toJson(build).toString());
	}

	static JsonObject toJson(BuildRegistry.Build build) {
		JsonArray projects = new JsonArray();
		build.projects().forEach(projects::add);
		JsonArray failures = new JsonArray();
		build.builderFailures().forEach(failures::add);
		return new JsonObject().put("buildId", build.id()) //$NON-NLS-1$
				.put("kind", build.kind()) //$NON-NLS-1$
				.put("state", build.state()) //$NON-NLS-1$
				.put("scope", build.projects().isEmpty() ? "workspace" : "projects") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
				.put("projects", projects) //$NON-NLS-1$
				.put("elapsedMillis", build.elapsedMillis()) //$NON-NLS-1$
				.put("refreshMillis", build.refreshMillis() < 0 ? null : Long.valueOf(build.refreshMillis())) //$NON-NLS-1$
				.put("buildMillis", build.buildMillis() < 0 ? null : Long.valueOf(build.buildMillis())) //$NON-NLS-1$
				.put("note", build.note()) //$NON-NLS-1$
				.put("errors", build.errors() < 0 ? null : Integer.valueOf(build.errors())) //$NON-NLS-1$
				.put("warnings", build.warnings() < 0 ? null : Integer.valueOf(build.warnings())) //$NON-NLS-1$
				.put("builderFailures", failures); //$NON-NLS-1$
	}
}
