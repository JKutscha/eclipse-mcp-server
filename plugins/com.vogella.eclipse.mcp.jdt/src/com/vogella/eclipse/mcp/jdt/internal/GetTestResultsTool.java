package com.vogella.eclipse.mcp.jdt.internal;

import java.util.Map;

import org.eclipse.core.runtime.IProgressMonitor;

import com.vogella.eclipse.mcp.core.IMcpTool;
import com.vogella.eclipse.mcp.core.McpToolResult;
import com.vogella.eclipse.mcp.core.ToolArguments;
import com.vogella.eclipse.mcp.core.json.JsonObject;

/** Reports a test run started through {@code eclipse_run_tests}. */
public final class GetTestResultsTool implements IMcpTool {

	@Override
	public String getName() {
		return "eclipse_get_test_results"; //$NON-NLS-1$
	}

	@Override
	public String getDescription() {
		return "Reports a test run started through eclipse_run_tests: how many passed, failed, errored and were ignored, and the failing cases with their stack traces and expected and actual values. Passing tests are omitted by default, because the failures are what the question was about."; //$NON-NLS-1$
	}

	@Override
	public String getInputSchema() {
		return """
				{
				  "type": "object",
				  "properties": {
				    "runId":         {"type":"string","description":"Identifier returned by eclipse_run_tests. Omit for the most recent run."},
				    "includePassed": {"type":"boolean","default":false},
				    "maxResults":    {"type":"integer","default":50,"minimum":1,"maximum":2000}
				  },
				  "additionalProperties": false
				}"""; //$NON-NLS-1$
	}

	@Override
	public McpToolResult call(Map<String, Object> arguments, IProgressMonitor monitor) {
		ToolArguments args = ToolArguments.of(arguments);
		String runId = args.getString("runId"); //$NON-NLS-1$
		TestRunRegistry.Run run = runId == null ? TestRunRegistry.getInstance().findLatest()
				: TestRunRegistry.getInstance().find(runId);
		if (run == null) {
			if (runId != null) {
				return McpToolResult.error("No test run with the id '%s'.".formatted(runId)); //$NON-NLS-1$
			}
			return McpToolResult.of(new JsonObject().put("state", "none") //$NON-NLS-1$ //$NON-NLS-2$
					.put("message", "No test run has been started.").toString()); //$NON-NLS-1$ //$NON-NLS-2$
		}
		return McpToolResult.of(TestRunRegistry
				.toJson(run, args.getInt("maxResults", 50, 1, 2000), args.getBoolean("includePassed", false)) //$NON-NLS-1$ //$NON-NLS-2$
				.toString());
	}
}
