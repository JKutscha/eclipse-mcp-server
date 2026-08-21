package com.vogella.eclipse.mcp.core.internal;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.Platform;

import com.vogella.eclipse.mcp.core.IMcpTool;
import com.vogella.eclipse.mcp.core.McpToolResult;
import com.vogella.eclipse.mcp.core.ToolArguments;
import com.vogella.eclipse.mcp.core.json.JsonArray;
import com.vogella.eclipse.mcp.core.json.JsonObject;

/**
 * Resets what the Error Log reports, by marking a point in it or by clearing it.
 */
public final class LogStateTools {

	static final String MARKER_PREFIX = "mcplog"; //$NON-NLS-1$

	private LogStateTools() {
	}

	static Path logFile() {
		var location = Platform.getLogFileLocation();
		return location == null ? null : location.toFile().toPath();
	}

	/** The rotated sibling Eclipse moves the log to when it grows. */
	static Path rotatedFile(Path log) {
		return log.resolveSibling(log.getFileName().toString() + ".bak"); //$NON-NLS-1$
	}

	/** Records a point in the log, so that "everything after this" needs no clock. */
	public static final class Mark implements IMcpTool {

		@Override
		public String getName() {
			return "eclipse_mark_log"; //$NON-NLS-1$
		}

		@Override
		public String getDescription() {
			return "Records the current end of the Error Log and returns an opaque marker, so that a later eclipse_get_log_entries can report only what was logged after it. Changes nothing and destroys nothing. Prefer this to eclipse_clear_log before a long run: it gives the same 'everything here is from this run' boundary while keeping the log that may turn out to contain the thing you needed. It is also exact where a 'since' timestamp is not, because the marker is a position in the IDE's own file rather than a time from the caller's clock, and a log that has been rotated or cleared since is reported as such instead of silently returning less."; //$NON-NLS-1$
		}

		@Override
		public String getInputSchema() {
			return """
					{"type":"object","properties":{},"additionalProperties":false}"""; //$NON-NLS-1$
		}

		@Override
		public McpToolResult call(Map<String, Object> arguments, IProgressMonitor monitor) {
			Path log = logFile();
			if (log == null) {
				return McpToolResult.error("This platform has no log file location."); //$NON-NLS-1$
			}
			long position = 0;
			try {
				position = Files.exists(log) ? Files.size(log) : 0;
			} catch (IOException e) {
				return McpToolResult.error("Could not measure the log at %s: %s".formatted(log, e.getMessage())); //$NON-NLS-1$
			}
			long at = System.currentTimeMillis();
			return McpToolResult.of(new JsonObject().put("marker", "%s-%d-%d".formatted(MARKER_PREFIX, //$NON-NLS-1$ //$NON-NLS-2$
					Long.valueOf(position), Long.valueOf(at)))
					.put("logFile", log.toString()) //$NON-NLS-1$
					.put("position", Long.valueOf(position)) //$NON-NLS-1$
					.put("markedAt", Long.valueOf(at)) //$NON-NLS-1$
					.put("note", //$NON-NLS-1$
							"Pass this as 'marker' to eclipse_get_log_entries to see only what was logged after this point.") //$NON-NLS-1$
					.toString());
		}
	}

	/** Deletes the log, the way the Error Log view's own delete action does. */
	public static final class Clear implements IMcpTool {

		@Override
		public String getName() {
			return "eclipse_clear_log"; //$NON-NLS-1$
		}

		@Override
		public String getDescription() {
			return "Deletes the Error Log file, the same thing the Error Log view's delete action does. DESTROYS THE LOG IRREVERSIBLY, including entries from earlier sessions, and runs as a dry run unless dryRun is set to false. Consider eclipse_mark_log instead: it gives the same 'everything after this point' boundary for a test run without throwing away a log that may turn out to hold the thing you needed. The rotated .log.bak sibling is removed too unless includeRotated is false, because leaving it means a later query can still reach entries from before the clear. After a real clear this writes one entry and reads it back, and reports whether that worked, because a framework still holding the old file open would leave later entries going nowhere."; //$NON-NLS-1$
		}

		@Override
		public String getInputSchema() {
			return """
					{
					  "type": "object",
					  "properties": {
					    "dryRun":         {"type":"boolean","default":true,"description":"Report what would be discarded and delete nothing."},
					    "includeRotated": {"type":"boolean","default":true,"description":"Also remove the rotated .log.bak sibling."}
					  },
					  "additionalProperties": false
					}"""; //$NON-NLS-1$
		}

		@Override
		public McpToolResult call(Map<String, Object> arguments, IProgressMonitor monitor) {
			ToolArguments args = ToolArguments.of(arguments);
			boolean dryRun = args.getBoolean("dryRun", true); //$NON-NLS-1$
			boolean includeRotated = args.getBoolean("includeRotated", true); //$NON-NLS-1$
			Path log = logFile();
			if (log == null) {
				return McpToolResult.error("This platform has no log file location."); //$NON-NLS-1$
			}
			Path rotated = rotatedFile(log);

			JsonObject result = new JsonObject().put("logFile", log.toString()) //$NON-NLS-1$
					.put("dryRun", Boolean.valueOf(dryRun)); //$NON-NLS-1$
			JsonArray files = new JsonArray();
			int entries = count(log);
			long bytes = size(log);
			if (Files.exists(log)) {
				files.add(log.toString());
			}
			if (includeRotated && Files.exists(rotated)) {
				files.add(rotated.toString());
				entries += count(rotated);
				bytes += size(rotated);
			}
			result.put("files", files).put("entriesDiscarded", Integer.valueOf(entries)) //$NON-NLS-1$ //$NON-NLS-2$
					.put("bytes", Long.valueOf(bytes)); //$NON-NLS-1$
			if (files.size() == 0) {
				return McpToolResult.of(result.put("cleared", Boolean.FALSE) //$NON-NLS-1$
						.put("note", "There is no log file to clear.").toString()); //$NON-NLS-1$ //$NON-NLS-2$
			}
			if (dryRun) {
				return McpToolResult.of(result.put("cleared", Boolean.FALSE) //$NON-NLS-1$
						.put("note", //$NON-NLS-1$
								"Nothing was deleted. Pass dryRun false to clear it, or use eclipse_mark_log to keep it.") //$NON-NLS-1$
						.toString());
			}

			JsonArray failed = new JsonArray();
			try {
				Files.deleteIfExists(log);
			} catch (IOException e) {
				failed.add(log.toString());
			}
			if (includeRotated) {
				try {
					Files.deleteIfExists(rotated);
				} catch (IOException e) {
					failed.add(rotated.toString());
				}
			}
			if (failed.size() > 0) {
				return McpToolResult.of(result.put("cleared", Boolean.FALSE).put("couldNotDelete", failed).toString()); //$NON-NLS-1$ //$NON-NLS-2$
			}
			return McpToolResult.of(result.put("cleared", Boolean.TRUE).put("stillLogging", verify()).toString()); //$NON-NLS-1$ //$NON-NLS-2$
		}

		/**
		 * Writes one entry and reads it back.
		 * <p>
		 * The framework holds the log open through its own writer, so a delete
		 * underneath it can leave it appending to a file nothing can reach any more.
		 * That failure is silent and would only surface as an empty log much later, so
		 * it is checked from the consuming end here rather than assumed from the delete
		 * having returned true.
		 */
		private static JsonObject verify() {
			String probe = "Error Log cleared through eclipse_clear_log"; //$NON-NLS-1$
			ILog.get().info(probe);
			Path log = logFile();
			JsonObject verified = new JsonObject();
			try {
				if (log == null || !Files.exists(log)) {
					return verified.put("verified", Boolean.FALSE) //$NON-NLS-1$
							.put("warning", //$NON-NLS-1$
									"The log file did not reappear after an entry was written, so the framework may still be writing to the deleted file. Restart the IDE to be sure logging works."); //$NON-NLS-1$
				}
				boolean found = Files.readString(log).contains(probe);
				return verified.put("verified", Boolean.valueOf(found)) //$NON-NLS-1$
						.put("warning", found ? null //$NON-NLS-1$
								: "An entry written after the clear was not readable back from the log file."); //$NON-NLS-1$
			} catch (IOException e) {
				return verified.put("verified", Boolean.FALSE).put("warning", String.valueOf(e.getMessage())); //$NON-NLS-1$ //$NON-NLS-2$
			}
		}

		private static int count(Path file) {
			try {
				return Files.exists(file) ? PlatformLogFile.read(file).size() : 0;
			} catch (IOException e) {
				return 0;
			}
		}

		private static long size(Path file) {
			try {
				return Files.exists(file) ? Files.size(file) : 0;
			} catch (IOException e) {
				return 0;
			}
		}
	}
}
