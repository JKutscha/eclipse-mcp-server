package com.vogella.eclipse.mcp.ui.internal;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.ui.PlatformUI;

import com.vogella.eclipse.mcp.core.IMcpTool;
import com.vogella.eclipse.mcp.core.McpToolResult;
import com.vogella.eclipse.mcp.core.ToolArguments;
import com.vogella.eclipse.mcp.core.json.JsonObject;

/**
 * The two halves of a sampling run, start and stop.
 */
public final class SamplingTools {

	private SamplingTools() {
	}

	/** Resolves the thread ids to sample. Reading the display's thread never blocks. */
	private static long[] resolve(String threads, Map<String, Object> arguments) {
		if ("all".equals(threads)) { //$NON-NLS-1$
			List<Long> ids = new ArrayList<>();
			for (Thread thread : Thread.getAllStackTraces().keySet()) {
				ids.add(Long.valueOf(thread.threadId()));
			}
			return ids.stream().mapToLong(Long::longValue).toArray();
		}
		if (arguments != null && arguments.get("threadNames") instanceof List<?> names) { //$NON-NLS-1$
			List<Long> ids = new ArrayList<>();
			for (Thread thread : Thread.getAllStackTraces().keySet()) {
				for (Object name : names) {
					if (thread.getName().contains(String.valueOf(name))) {
						ids.add(Long.valueOf(thread.threadId()));
					}
				}
			}
			return ids.stream().mapToLong(Long::longValue).toArray();
		}
		if (!PlatformUI.isWorkbenchRunning()) {
			return new long[0];
		}
		return new long[] { PlatformUI.getWorkbench().getDisplay().getThread().threadId() };
	}

	/** Starts sampling and returns a session id. */
	public static final class Start implements IMcpTool {

		@Override
		public String getName() {
			return "eclipse_start_sampling"; //$NON-NLS-1$
		}

		@Override
		public String getDescription() {
			return "Starts sampling thread stacks at a fixed interval, so an operation can be profiled or a freeze diagnosed, and returns a sessionId to stop. Sampling runs on a daemon thread through ThreadMXBean, which needs neither the UI thread nor any workspace lock, so it keeps working while the IDE is frozen. Note that this is safepoint biased: tight loops without safepoint polls are under-represented, so treat it as 'where is the time going' rather than as an exact profiler."; //$NON-NLS-1$
		}

		@Override
		public String getInputSchema() {
			return """
					{
					  "type": "object",
					  "properties": {
					    "threads":        {"type":"string","enum":["ui","all"],"default":"ui","description":"Which threads to sample. 'ui' is the workbench display thread."},
					    "threadNames":    {"type":"array","items":{"type":"string"},"description":"Sample threads whose name contains one of these, instead of 'threads'."},
					    "intervalMillis": {"type":"integer","default":100,"minimum":10,"maximum":10000},
					    "maxSamples":     {"type":"integer","default":300,"minimum":1,"maximum":5000,"description":"Sampling stops on its own after this many samples."},
					    "maxDepth":       {"type":"integer","default":80,"minimum":1,"maximum":512,"description":"Frames per sample."}
					  },
					  "additionalProperties": false
					}"""; //$NON-NLS-1$
		}

		@Override
		public McpToolResult call(Map<String, Object> arguments, IProgressMonitor monitor) {
			ToolArguments args = ToolArguments.of(arguments);
			long[] ids = resolve(args.getString("threads", "ui"), arguments); //$NON-NLS-1$ //$NON-NLS-2$
			if (ids.length == 0) {
				return McpToolResult.error("No thread matched, so there would be nothing to sample."); //$NON-NLS-1$
			}
			SamplingRegistry.Session session = SamplingRegistry.getInstance().start(ids,
					args.getInt("intervalMillis", 100, 10, 10000), //$NON-NLS-1$
					args.getInt("maxSamples", 300, 1, 5000), //$NON-NLS-1$
					args.getInt("maxDepth", 80, 1, 512)); //$NON-NLS-1$
			return McpToolResult.of(new JsonObject().put("sessionId", session.id()) //$NON-NLS-1$
					.put("threads", ids.length) //$NON-NLS-1$
					.put("intervalMillis", session.intervalMillis()) //$NON-NLS-1$
					.put("running", true) //$NON-NLS-1$
					.toString());
		}
	}

	/** Stops sampling and returns the aggregated result. */
	public static final class Stop implements IMcpTool {

		@Override
		public String getName() {
			return "eclipse_stop_sampling"; //$NON-NLS-1$
		}

		@Override
		public String getDescription() {
			return "Stops a sampling session and returns the aggregated result: the frames where the time was actually spent, the frames most often present on the stack, and the samples merged into one call tree. The raw samples are not returned unless asked for, because a hundred samples of seventy frames is seven thousand lines."; //$NON-NLS-1$
		}

		@Override
		public String getInputSchema() {
			return """
					{
					  "type": "object",
					  "properties": {
					    "sessionId":   {"type":"string","description":"Session returned by eclipse_start_sampling. Omit for the most recent."},
					    "topMethods":  {"type":"integer","default":15,"minimum":1,"maximum":200},
					    "minSamples":  {"type":"integer","default":2,"minimum":1,"description":"Prune call tree branches seen fewer times than this."},
					    "includeRawSamples": {"type":"boolean","default":false,"description":"Also return every sample. Large."},
					    "keepRunning": {"type":"boolean","default":false,"description":"Report the aggregate so far without stopping."}
					  },
					  "additionalProperties": false
					}"""; //$NON-NLS-1$
		}

		@Override
		public McpToolResult call(Map<String, Object> arguments, IProgressMonitor monitor) {
			ToolArguments args = ToolArguments.of(arguments);
			String sessionId = args.getString("sessionId"); //$NON-NLS-1$
			SamplingRegistry.Session session = sessionId == null ? SamplingRegistry.getInstance().findLatest()
					: SamplingRegistry.getInstance().find(sessionId);
			if (session == null) {
				return McpToolResult.error(sessionId == null ? "No sampling session has been started." //$NON-NLS-1$
						: "No sampling session with the id '%s'.".formatted(sessionId)); //$NON-NLS-1$
			}
			if (!args.getBoolean("keepRunning", false)) { //$NON-NLS-1$
				session.stop();
			}
			return McpToolResult.of(SamplingRegistry
					.aggregate(session, args.getInt("topMethods", 15, 1, 200), //$NON-NLS-1$
							args.getInt("minSamples", 2, 1, 1000), //$NON-NLS-1$
							args.getBoolean("includeRawSamples", false)) //$NON-NLS-1$
					.toString());
		}
	}
}
