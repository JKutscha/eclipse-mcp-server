package com.vogella.eclipse.mcp.core.internal;

import java.util.Map;

import org.eclipse.core.runtime.IProgressMonitor;

import com.vogella.eclipse.mcp.core.IMcpTool;
import com.vogella.eclipse.mcp.core.McpToolResult;
import com.vogella.eclipse.mcp.core.ToolArguments;
import com.vogella.eclipse.mcp.core.json.JsonArray;
import com.vogella.eclipse.mcp.core.json.JsonObject;

/**
 * Waits until the workspace has stopped building and the jobs have run out.
 */
public final class WaitUntilQuietTool implements IMcpTool {

	@Override
	public String getName() {
		return "eclipse_wait_until_quiet"; //$NON-NLS-1$
	}

	@Override
	public String getDescription() {
		return "Waits until the auto-build, the manual build and the refresh jobs have finished and no other job is running, then answers what it waited for and how long each part took. Changes nothing. THIS IS THE TOOL TO CALL BEFORE TIMING ANYTHING: after a restart the workspace builds for a minute or two on its own, and a measurement taken in that window measures the build. Watching the process from outside cannot tell the quiet before the build starts from the quiet after it, which is the mistake this exists to prevent; from inside the two are different job states. waitedFor is empty when nothing was running, which is itself the answer that the IDE was already idle. It does NOT cover the Java index: JDT runs that in a queue of its own outside the job manager, and eclipse_search_types with a narrow pattern is what blocks until the index is ready."; //$NON-NLS-1$
	}

	@Override
	public String getInputSchema() {
		return """
				{
				  "type": "object",
				  "properties": {
				    "timeoutSeconds": {"type":"integer","default":120,"minimum":1,"maximum":900,"description":"How long to wait before answering with state 'timeout'. A build after a restart takes a couple of minutes on a large workspace, which is why the default is not small."}
				  },
				  "additionalProperties": false
				}"""; //$NON-NLS-1$
	}

	@Override
	public McpToolResult call(Map<String, Object> arguments, IProgressMonitor monitor) {
		int timeoutSeconds = ToolArguments.of(arguments).getInt("timeoutSeconds", 120, 1, 900); //$NON-NLS-1$
		JsonObject before = WorkspaceJobs.snapshot();
		long startedAt = System.currentTimeMillis();
		WorkspaceJobs.Quiet quiet = WorkspaceJobs.waitUntilQuiet(startedAt + timeoutSeconds * 1000L);
		long elapsed = System.currentTimeMillis() - startedAt;
		boolean timedOut = quiet.timedOut();
		JsonArray waited = quiet.waitedFor();
		JsonObject after = WorkspaceJobs.snapshot();
		return McpToolResult.of(new JsonObject().put("state", timedOut ? "timeout" : "quiet") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
				.put("elapsedMillis", Long.valueOf(elapsed)) //$NON-NLS-1$
				.put("waitedFor", waited) //$NON-NLS-1$
				.put("jobsBefore", before) //$NON-NLS-1$
				.put("jobsAfter", after) //$NON-NLS-1$
				.put("note", note(timedOut, waited.size(), timeoutSeconds)) //$NON-NLS-1$
				.toString());
	}

	private static String note(boolean timedOut, int waitedFor, int timeoutSeconds) {
		if (timedOut) {
			return "Gave up after %d seconds; jobsAfter names what is still running. A job that never ends, an auto-refresh over a slow file system for instance, keeps the workspace from ever going quiet." //$NON-NLS-1$
					.formatted(Integer.valueOf(timeoutSeconds));
		}
		if (waitedFor == 0) {
			return "Nothing was running when this was called, so nothing was waited for. That is the answer a measurement wants, and it is not the same as the quiet right after a restart, where the build has not begun yet."; //$NON-NLS-1$
		}
		return "The workspace is quiet as far as the job manager is concerned. JDT indexing runs outside it and is not covered."; //$NON-NLS-1$
	}
}
