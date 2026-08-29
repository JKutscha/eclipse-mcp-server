package com.vogella.eclipse.mcp.ui.internal;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.PlatformUI;

import com.vogella.eclipse.mcp.core.CallBudget;
import com.vogella.eclipse.mcp.core.ClientSessions;
import com.vogella.eclipse.mcp.core.IMcpTool;
import com.vogella.eclipse.mcp.core.McpToolResult;
import com.vogella.eclipse.mcp.core.ToolArguments;
import com.vogella.eclipse.mcp.core.json.JsonObject;

/**
 * The two halves of a screencast, start and stop.
 */
public final class ScreencastTools {

	private static final long UI_TIMEOUT_SECONDS = 15;

	private ScreencastTools() {
	}

	private static JsonObject describe(Screencast.Session session) {
		return new JsonObject().put("sessionId", session.id()) //$NON-NLS-1$
				.put("target", session.target()) //$NON-NLS-1$
				.put("directory", session.directory().toString()) //$NON-NLS-1$
				.put("intervalMillis", Integer.valueOf(session.intervalMillis())) //$NON-NLS-1$
				.put("frames", Integer.valueOf(session.frames())) //$NON-NLS-1$
				.put("frameSize", session.frameSize()) //$NON-NLS-1$
				.put("zoom", Integer.valueOf(session.zoom())) //$NON-NLS-1$
				.put("running", Boolean.valueOf(session.running())); //$NON-NLS-1$
	}

	/** Starts recording and returns a session id. */
	public static final class Start implements IMcpTool {

		@Override
		public String getName() {
			return "eclipse_start_screencast"; //$NON-NLS-1$
		}

		@Override
		public String getDescription() {
			return "Starts recording a shell or a part as a sequence of PNG frames, and returns a sessionId for eclipse_stop_screencast, which assembles them into an animated GIF. This is what shows a change in motion rather than before and after: a tab overflowing while a sash is dragged, a hover appearing, a view repainting after a theme switch. WRITES FILES to a directory of its own under the temp directory, or under directory when given, and changes nothing in the IDE. Each frame is painted on the UI thread through Control.print, the same path eclipse_screenshot uses, so it records what the IDE paints and NOT native popups, menus or dialogs, and the window decorations are missing from a shell recording. A frame costs the UI thread one paint; 2 to 5 frames a second is what a full shell sustains without slowing the IDE, and a single part is cheaper. Recording stops on its own at maxFrames or when the target is disposed, and the stop answer reports late ticks, which is what a busy UI thread looks like from here: gaps in the recording rather than a smooth lie."; //$NON-NLS-1$
		}

		@Override
		public String getInputSchema() {
			return """
					{
					  "type": "object",
					  "properties": {
					    "target":         {"type":"string","enum":["part","shell"],"default":"shell","description":"What to record. Inferred from part when that is given."},
					    "part":           {"type":"string","description":"Part id, from eclipse_list_ui_targets. It has to be visible; a part behind another tab is not painted at all."},
					    "shellTitle":     {"type":"string","description":"Title of the shell to record, or a substring. Omit for the active shell."},
					    "intervalMillis": {"type":"integer","default":500,"minimum":100,"maximum":10000,"description":"Time between frames. The paint itself is added on top, so the real spacing is reported per frame in the GIF."},
					    "maxFrames":      {"type":"integer","default":120,"minimum":1,"maximum":1000,"description":"Recording stops on its own after this many frames."},
					    "maxWidth":       {"type":"integer","default":800,"minimum":100,"maximum":4000,"description":"Downscale each frame to this width. A GIF of full HD frames is tens of megabytes."},
					    "directory":      {"type":"string","description":"Absolute directory for the frames. Created when missing; defaults to a temporary directory."}
					  },
					  "additionalProperties": false
					}"""; //$NON-NLS-1$
		}

		@Override
		public McpToolResult call(Map<String, Object> arguments, IProgressMonitor monitor) {
			ToolArguments args = ToolArguments.of(arguments);
			String part = args.getString("part"); //$NON-NLS-1$
			String target = args.getString("target", part != null ? "part" : "shell"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
			if ("part".equals(target) && part == null) { //$NON-NLS-1$
				return McpToolResult.error("The target 'part' needs a 'part' id. Use eclipse_list_ui_targets."); //$NON-NLS-1$
			}
			String shellTitle = args.getString("shellTitle"); //$NON-NLS-1$
			int interval = args.getInt("intervalMillis", 500, 100, 10000); //$NON-NLS-1$
			int maxFrames = args.getInt("maxFrames", 120, 1, 1000); //$NON-NLS-1$
			int maxWidth = args.getInt("maxWidth", 800, 100, 4000); //$NON-NLS-1$
			Path directory;
			try {
				String given = args.getString("directory"); //$NON-NLS-1$
				directory = given != null ? Files.createDirectories(Path.of(given))
						: Files.createTempDirectory("eclipse-screencast-"); //$NON-NLS-1$
			} catch (IOException | RuntimeException e) {
				return McpToolResult.error("Could not create the frame directory: " + e.getMessage()); //$NON-NLS-1$
			}
			return UiThread.call(UI_TIMEOUT_SECONDS, () -> {
				Display display = PlatformUI.getWorkbench().getDisplay();
				Control control;
				String described;
				boolean composed = false;
				if ("shell".equals(target)) { //$NON-NLS-1$
					Shell shell = ScreenshotTools.Capture.findShell(display, shellTitle);
					if (shell == null) {
						throw new IllegalArgumentException(shellTitle == null ? "This IDE has no window to record." //$NON-NLS-1$
								: "No shell matching '%s'.".formatted(shellTitle)); //$NON-NLS-1$
					}
					control = shell;
					composed = shell.getChildren().length > 0;
					described = "shell '" + shell.getText() + "'"; //$NON-NLS-1$ //$NON-NLS-2$
				} else {
					control = ScreenshotTools.Capture.findPart(part, false);
					if (control == null) {
						throw new IllegalArgumentException(
								"No part '%s', or it is not visible. A part behind another tab is not painted at all; activate it first with eclipse_set_part_state." //$NON-NLS-1$
										.formatted(part));
					}
					described = "part " + part; //$NON-NLS-1$
				}
				Screencast.Session session = Screencast.getInstance().start(display, control, composed, described,
						interval, maxFrames, maxWidth, directory);
				if (session.frames() == 0) {
					throw new IllegalStateException("The first frame could not be painted: " + session.stoppedBy()); //$NON-NLS-1$
				}
				return describe(session).put("maxFrames", Integer.valueOf(maxFrames)) //$NON-NLS-1$
						.put("note", //$NON-NLS-1$
								"Recording. Drive the IDE now, then call eclipse_stop_screencast with this sessionId. Native popups, menus and dialogs are not in the frames."); //$NON-NLS-1$
			});
		}
	}

	/** Stops recording and assembles the GIF. */
	public static final class Stop implements IMcpTool {

		@Override
		public String getName() {
			return "eclipse_stop_screencast"; //$NON-NLS-1$
		}

		@Override
		public String getDescription() {
			return "Stops a screencast and assembles its frames into an animated GIF, whose path is returned along with the frame directory. Each frame stays as long as it really did on screen, so a stall in the IDE is visible as a held frame. The GIF uses a fixed 256 colour palette, which posterises gradients; the PNG frames stay lossless in the directory for anything better, such as ffmpeg through eclipse_run_command. averagePaintMillis is what one frame cost the UI thread, which is added to the interval between frames; lateTicks and maxLatenessMillis say how long the timer waited past its due time, which is the recording's own measurement of how busy the IDE was with other things."; //$NON-NLS-1$
		}

		@Override
		public String getInputSchema() {
			return """
					{
					  "type": "object",
					  "properties": {
					    "sessionId":  {"type":"string","description":"Session returned by eclipse_start_screencast. Omit for the most recent."},
					    "gif":        {"type":"boolean","default":true,"description":"Assemble the animated GIF. Off leaves only the PNG frames."},
					    "loop":       {"type":"boolean","default":true,"description":"Loop the GIF instead of playing it once."},
					    "outputPath": {"type":"string","description":"Absolute file for the GIF. Defaults to screencast.gif in the frame directory."},
					    "keepFrames": {"type":"boolean","default":true,"description":"Leave the PNG frames on disk after the GIF is written."}
					  },
					  "additionalProperties": false
					}"""; //$NON-NLS-1$
		}

		@Override
		public McpToolResult call(Map<String, Object> arguments, IProgressMonitor monitor) {
			ToolArguments args = ToolArguments.of(arguments);
			String sessionId = args.getString("sessionId"); //$NON-NLS-1$
			Screencast registry = Screencast.getInstance();
			if (sessionId == null && !ClientSessions.canAssumeASingleClient()) {
				return McpToolResult.error(ClientSessions.ambiguousDefault("screencast", "sessionId", registry.ids())); //$NON-NLS-1$ //$NON-NLS-2$
			}
			Screencast.Session session = sessionId == null ? registry.findLatest() : registry.find(sessionId);
			if (session == null) {
				return McpToolResult.error(sessionId == null ? "No screencast has been started." //$NON-NLS-1$
						: "No screencast with the id '%s'.".formatted(sessionId)); //$NON-NLS-1$
			}
			session.stop("stop"); //$NON-NLS-1$
			int budget = Math.max(1, CallBudget.maxWaitSeconds() - 5);
			boolean encoded = session.awaitEncoded(budget);
			JsonObject result = describe(session).put("stoppedBy", session.stoppedBy()) //$NON-NLS-1$
					.put("durationMillis", Long.valueOf(session.elapsedMillis())) //$NON-NLS-1$
					.put("framesWritten", Integer.valueOf(session.written())) //$NON-NLS-1$
					.put("framesFailed", Integer.valueOf(session.failed())) //$NON-NLS-1$
					.put("lastFailure", session.lastFailure()) //$NON-NLS-1$
					.put("averagePaintMillis", Long.valueOf(session.averagePaintMillis())) //$NON-NLS-1$
					.put("lateTicks", Integer.valueOf(session.lateTicks())) //$NON-NLS-1$
					.put("maxLatenessMillis", Long.valueOf(session.maxLatenessMillis())); //$NON-NLS-1$
			if (!encoded) {
				return McpToolResult.of(result.put("gifPath", null) //$NON-NLS-1$
						.put("note", "The frames were still being encoded after %d seconds, so no GIF was written. Call again to assemble it once the encoder has caught up." //$NON-NLS-1$ //$NON-NLS-2$
								.formatted(Integer.valueOf(budget)))
						.toString());
			}
			if (!args.getBoolean("gif", true)) { //$NON-NLS-1$
				return McpToolResult.of(result.put("gifPath", null).toString()); //$NON-NLS-1$
			}
			Path output = args.getString("outputPath") != null ? Path.of(args.getString("outputPath")) //$NON-NLS-1$ //$NON-NLS-2$
					: session.directory().resolve("screencast.gif"); //$NON-NLS-1$
			try {
				List<ImageData> frames = new ArrayList<>();
				int[] delays = session.delaysMillis();
				List<Integer> kept = new ArrayList<>();
				for (int i = 0; i < delays.length; i++) {
					Path frame = session.frame(i);
					if (Files.exists(frame)) {
						frames.add(ScreencastGif.read(frame));
						kept.add(Integer.valueOf(delays[i]));
					}
				}
				long bytes = ScreencastGif.write(frames, kept.stream().mapToInt(Integer::intValue).toArray(),
						args.getBoolean("loop", true), output); //$NON-NLS-1$
				result.put("gifPath", output.toAbsolutePath().toString()) //$NON-NLS-1$
						.put("gifFrames", Integer.valueOf(frames.size())) //$NON-NLS-1$
						.put("gifBytes", Long.valueOf(bytes)); //$NON-NLS-1$
				if (!args.getBoolean("keepFrames", true)) { //$NON-NLS-1$
					for (int i = 0; i < delays.length; i++) {
						Files.deleteIfExists(session.frame(i));
					}
					result.put("framesKept", Boolean.FALSE); //$NON-NLS-1$
				}
			} catch (IOException | RuntimeException e) {
				result.put("gifPath", null).put("gifError", String.valueOf(e)); //$NON-NLS-1$ //$NON-NLS-2$
			}
			return McpToolResult.of(result.put("note", session.lateTicks() > 0 //$NON-NLS-1$
					? "The UI thread delivered %d frames late, so the recording has gaps where the IDE was busy; each frame's delay in the GIF is the time it really stayed." //$NON-NLS-1$
							.formatted(Integer.valueOf(session.lateTicks()))
					: "Every frame arrived on time.").toString()); //$NON-NLS-1$
		}
	}
}
