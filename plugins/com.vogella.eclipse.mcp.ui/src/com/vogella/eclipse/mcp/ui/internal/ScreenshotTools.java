package com.vogella.eclipse.mcp.ui.internal;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.graphics.ImageLoader;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.IViewReference;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchPart;
import org.eclipse.ui.IWorkbenchPartReference;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;

import com.vogella.eclipse.mcp.core.IMcpTool;
import com.vogella.eclipse.mcp.core.McpToolResult;
import com.vogella.eclipse.mcp.core.ToolArguments;
import com.vogella.eclipse.mcp.core.json.JsonArray;
import com.vogella.eclipse.mcp.core.json.JsonObject;

/**
 * Captures the IDE, and lists what can be captured.
 */
public final class ScreenshotTools {

	private static final long UI_TIMEOUT_SECONDS = 15;

	private ScreenshotTools() {
	}

	/**
	 * On GTK4 the SWT code that fills a GC from a window is a no-op, so a capture
	 * returns a blank image with no error at all. Refusing is better than a silently
	 * empty screenshot, which is the worst possible answer.
	 */
	static String unsupportedReason() {
		if (!"gtk".equals(SWT.getPlatform())) { //$NON-NLS-1$
			return null;
		}
		String gtk4 = System.getenv("SWT_GTK4"); //$NON-NLS-1$
		if (gtk4 != null && !"0".equals(gtk4) && !"false".equalsIgnoreCase(gtk4)) { //$NON-NLS-1$ //$NON-NLS-2$
			return "SWT is running on GTK4, where capturing a window produces a blank image rather than an error."; //$NON-NLS-1$
		}
		// Deliberately no Wayland check. WAYLAND_DISPLAY and XDG_SESSION_TYPE stay set
		// even when GDK_BACKEND=x11 binds the X11 backend through XWayland, where
		// capture works, so the environment cannot answer the question and sniffing it
		// refused on machines that were fine. The uniform-image check after the capture
		// is the real backstop, so this can afford to be permissive.
		return null;
	}

	private static <T> McpToolResult onUi(java.util.function.Supplier<T> work, java.util.function.Function<T, String> render) {
		if (!PlatformUI.isWorkbenchRunning()) {
			return McpToolResult.error("There is no running workbench."); //$NON-NLS-1$
		}
		CompletableFuture<T> pending = new CompletableFuture<>();
		// asyncExec, never syncExec: a busy UI must not block the HTTP worker
		PlatformUI.getWorkbench().getDisplay().asyncExec(() -> {
			try {
				pending.complete(work.get());
			} catch (RuntimeException e) {
				pending.completeExceptionally(e);
			}
		});
		try {
			return McpToolResult.of(render.apply(pending.get(UI_TIMEOUT_SECONDS, TimeUnit.SECONDS)));
		} catch (TimeoutException e) {
			pending.cancel(false);
			return McpToolResult.error("The Eclipse UI is busy, try again."); //$NON-NLS-1$
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return McpToolResult.error("The request was interrupted."); //$NON-NLS-1$
		} catch (ExecutionException e) {
			Throwable cause = e.getCause() == null ? e : e.getCause();
			return McpToolResult.error("The capture failed: " + cause); //$NON-NLS-1$
		}
	}

	/** Lists shells and parts, so that a caller does not have to guess ids. */
	public static final class ListTargets implements IMcpTool {

		@Override
		public String getName() {
			return "eclipse_list_ui_targets"; //$NON-NLS-1$
		}

		@Override
		public String getDescription() {
			return "Lists what eclipse_screenshot can capture: every open shell with its title, whether it is modal and its bounds, and every workbench part with its id, title and whether it is currently visible. Also the answer to 'which dialog is open right now', which nothing else here can tell you."; //$NON-NLS-1$
		}

		@Override
		public String getInputSchema() {
			return """
					{"type":"object","properties":{},"additionalProperties":false}"""; //$NON-NLS-1$
		}

		@Override
		public McpToolResult call(Map<String, Object> arguments, IProgressMonitor monitor) {
			return onUi(ListTargets::collect, JsonObject::toString);
		}

		private static JsonObject collect() {
			Display display = PlatformUI.getWorkbench().getDisplay();
			JsonArray shells = new JsonArray();
			for (Shell shell : display.getShells()) {
				Rectangle bounds = shell.getBounds();
				shells.add(new JsonObject().put("title", shell.getText()) //$NON-NLS-1$
						.put("modal", (shell.getStyle() & (SWT.APPLICATION_MODAL | SWT.PRIMARY_MODAL //$NON-NLS-1$
								| SWT.SYSTEM_MODAL)) != 0)
						.put("visible", shell.isVisible()) //$NON-NLS-1$
						.put("bounds", bounds.x + "," + bounds.y + " " + bounds.width + "x" + bounds.height)); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
			}
			JsonArray parts = new JsonArray();
			IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
			IWorkbenchPage page = window == null ? null : window.getActivePage();
			if (page != null) {
				for (IWorkbenchPartReference reference : allReferences(page)) {
					parts.add(new JsonObject().put("id", reference.getId()) //$NON-NLS-1$
							.put("title", reference.getTitle()) //$NON-NLS-1$
							.put("kind", reference instanceof IViewReference ? "view" : "editor") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
							.put("visible", page.isPartVisible(reference.getPart(false)))); //$NON-NLS-1$
				}
			}
			JsonObject result = new JsonObject().put("shells", shells).put("parts", parts); //$NON-NLS-1$ //$NON-NLS-2$
			String unsupported = unsupportedReason();
			if (unsupported != null) {
				result.put("captureUnsupported", unsupported); //$NON-NLS-1$
			}
			return result;
		}

		private static java.util.List<IWorkbenchPartReference> allReferences(IWorkbenchPage page) {
			java.util.List<IWorkbenchPartReference> references = new java.util.ArrayList<>();
			references.addAll(java.util.Arrays.asList(page.getViewReferences()));
			references.addAll(java.util.Arrays.asList(page.getEditorReferences()));
			return references;
		}
	}

	/** Captures the display, a shell or a part. */
	public static final class Capture implements IMcpTool {

		@Override
		public String getName() {
			return "eclipse_screenshot"; //$NON-NLS-1$
		}

		@Override
		public String getDescription() {
			return "Captures the IDE as a PNG and writes it to a file, returning the path. Targets are a workbench part by id, a shell by title, or the whole display. Use it for UI work such as layout, theming and dialog rendering; for anything textual the other tools answer better and shorter. The display target captures whatever else is on the screen, so it is not the default. A part that is not visible is refused rather than captured blank, unless activate is set."; //$NON-NLS-1$
		}

		@Override
		public String getInputSchema() {
			return """
					{
					  "type": "object",
					  "properties": {
					    "target":     {"type":"string","enum":["part","shell","display"],"default":"part"},
					    "part":       {"type":"string","description":"Part id, e.g. org.eclipse.ui.views.ProblemView. Use eclipse_list_ui_targets to find it."},
					    "shellTitle": {"type":"string","description":"Title of the shell to capture, or a substring of it. Omit for the active shell."},
					    "activate":   {"type":"boolean","default":false,"description":"Bring the part to the front first. This visibly rearranges the user's IDE, so it is off by default."},
					    "maxWidth":   {"type":"integer","default":1200,"minimum":100,"maximum":4000,"description":"Downscale to this width. A full HD PNG is over a megabyte once base64 encoded."},
					    "outputPath": {"type":"string","description":"Absolute file to write. Defaults to a temporary file."},
					    "includeBase64": {"type":"boolean","default":false,"description":"Also return the image inline. Large; prefer reading the file."}
					  },
					  "additionalProperties": false
					}"""; //$NON-NLS-1$
		}

		@Override
		public McpToolResult call(Map<String, Object> arguments, IProgressMonitor monitor) {
			String unsupported = unsupportedReason();
			if (unsupported != null) {
				return McpToolResult.error("Cannot capture on this platform. " + unsupported); //$NON-NLS-1$
			}
			ToolArguments args = ToolArguments.of(arguments);
			String target = args.getString("target", "part"); //$NON-NLS-1$ //$NON-NLS-2$
			String part = args.getString("part"); //$NON-NLS-1$
			if ("part".equals(target) && part == null) { //$NON-NLS-1$
				return McpToolResult.error("The target 'part' needs a 'part' id. Use eclipse_list_ui_targets."); //$NON-NLS-1$
			}
			String shellTitle = args.getString("shellTitle"); //$NON-NLS-1$
			boolean activate = args.getBoolean("activate", false); //$NON-NLS-1$
			int maxWidth = args.getInt("maxWidth", 1200, 100, 4000); //$NON-NLS-1$
			String outputPath = args.getString("outputPath"); //$NON-NLS-1$
			boolean includeBase64 = args.getBoolean("includeBase64", false); //$NON-NLS-1$

			return onUi(() -> capture(target, part, shellTitle, activate, maxWidth, outputPath, includeBase64),
					JsonObject::toString);
		}

		private static JsonObject capture(String target, String partId, String shellTitle, boolean activate,
				int maxWidth, String outputPath, boolean includeBase64) {
			Display display = PlatformUI.getWorkbench().getDisplay();
			Rectangle area;
			Shell shell = null;
			if ("display".equals(target)) { //$NON-NLS-1$
				area = display.getBounds();
			} else if ("shell".equals(target)) { //$NON-NLS-1$
				shell = findShell(display, shellTitle);
				if (shell == null) {
					return failure("No shell matching '%s'.".formatted(shellTitle)); //$NON-NLS-1$
				}
				area = shell.getBounds();
			} else {
				Control control = findPart(partId, activate);
				if (control == null) {
					return failure(
							"No part '%s', or it is not visible. A part behind another tab is not rendered at all; pass activate to bring it forward." //$NON-NLS-1$
									.formatted(partId));
				}
				// map to display coordinates and capture the real pixels, rather than
				// Control.print(), which has GTK gaps the widget on screen does not
				area = display.map(control.getParent(), null, control.getBounds());
			}
			if (area.width <= 0 || area.height <= 0) {
				return failure("The capture area is empty."); //$NON-NLS-1$
			}

			Image image = new Image(display, area.width, area.height);
			GC gc = new GC(display);
			try {
				gc.copyArea(image, area.x, area.y);
			} finally {
				gc.dispose();
			}
			try {
				ImageData data = image.getImageData();
				if (isBlank(data)) {
					return failure(
							"The capture came back uniform, which is what a silently failing capture produces on GTK4 or a native Wayland backend. Nothing was written; do not trust screenshots on this display."); //$NON-NLS-1$
				}
				return write(display, image, data, area, maxWidth, outputPath, includeBase64);
			} finally {
				image.dispose();
			}
		}

		private static JsonObject write(Display display, Image image, ImageData data, Rectangle area, int maxWidth,
				String outputPath, boolean includeBase64) {
			ImageData scaled = data;
			if (data.width > maxWidth) {
				int height = Math.max(1, data.height * maxWidth / data.width);
				scaled = data.scaledTo(maxWidth, height);
			}
			ImageLoader loader = new ImageLoader();
			loader.data = new ImageData[] { scaled };
			ByteArrayOutputStream bytes = new ByteArrayOutputStream();
			loader.save(bytes, SWT.IMAGE_PNG);
			try {
				Path file = outputPath != null ? Path.of(outputPath)
						: Files.createTempFile("eclipse-screenshot-", ".png"); //$NON-NLS-1$ //$NON-NLS-2$
				Files.write(file, bytes.toByteArray());
				JsonObject result = new JsonObject().put("captured", true) //$NON-NLS-1$
						.put("path", file.toAbsolutePath().toString()) //$NON-NLS-1$
						.put("width", scaled.width) //$NON-NLS-1$
						.put("height", scaled.height) //$NON-NLS-1$
						.put("capturedArea", area.width + "x" + area.height) //$NON-NLS-1$ //$NON-NLS-2$
						.put("bytes", bytes.size()); //$NON-NLS-1$
				if (includeBase64) {
					result.put("base64", Base64.getEncoder().encodeToString(bytes.toByteArray())); //$NON-NLS-1$
				}
				return result;
			} catch (java.io.IOException e) {
				return failure("Could not write the image: " + e.getMessage()); //$NON-NLS-1$
			}
		}

		/** A uniform image is what a silently failing capture produces. */
		private static boolean isBlank(ImageData data) {
			int first = data.getPixel(0, 0);
			for (int y = 0; y < data.height; y += Math.max(1, data.height / 50)) {
				for (int x = 0; x < data.width; x += Math.max(1, data.width / 50)) {
					if (data.getPixel(x, y) != first) {
						return false;
					}
				}
			}
			return true;
		}

		private static Shell findShell(Display display, String title) {
			if (title == null) {
				return display.getActiveShell();
			}
			for (Shell shell : display.getShells()) {
				if (shell.getText() != null && shell.getText().contains(title)) {
					return shell;
				}
			}
			return null;
		}

		private static Control findPart(String partId, boolean activate) {
			IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
			IWorkbenchPage page = window == null ? null : window.getActivePage();
			if (page == null) {
				return null;
			}
			IWorkbenchPartReference reference = null;
			for (IWorkbenchPartReference candidate : ListTargets.allReferences(page)) {
				if (partId.equals(candidate.getId())) {
					reference = candidate;
					break;
				}
			}
			if (reference == null) {
				return null;
			}
			IWorkbenchPart part = reference.getPart(true);
			if (part == null) {
				return null;
			}
			if (!page.isPartVisible(part)) {
				if (!activate) {
					return null;
				}
				page.activate(part);
			}
			Object widget = part.getSite().getService(org.eclipse.e4.ui.model.application.ui.basic.MPart.class);
			if (widget instanceof org.eclipse.e4.ui.model.application.ui.basic.MPart model
					&& model.getWidget() instanceof Composite composite) {
				return composite;
			}
			return null;
		}

		private static JsonObject failure(String reason) {
			return new JsonObject().put("captured", false).put("reason", reason); //$NON-NLS-1$ //$NON-NLS-2$
		}
	}
}
