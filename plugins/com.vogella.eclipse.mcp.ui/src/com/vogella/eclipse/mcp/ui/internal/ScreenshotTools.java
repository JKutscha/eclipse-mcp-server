package com.vogella.eclipse.mcp.ui.internal;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
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
import org.eclipse.ui.views.IViewDescriptor;

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
			return "Lists what the UI tools can address: every open shell with its title, whether it is modal and its bounds, and every workbench part with its id, title and whether it is currently visible. With includeAvailableViews it also lists the views that are registered but not open, which is where eclipse_show_view gets its ids. Also the answer to 'which dialog is open right now', which nothing else here can tell you."; //$NON-NLS-1$
		}

		@Override
		public String getInputSchema() {
			return """
					{
					  "type": "object",
					  "properties": {
					    "includeAvailableViews": {"type":"boolean","default":false,"description":"Also list every view registered in this IDE, open or not. There are hundreds, so pass a filter with it."},
					    "filter":                {"type":"string","description":"Substring of the id or the label, case insensitive, applied to availableViews only."},
					    "maxResults":            {"type":"integer","default":100,"minimum":1,"maximum":1000}
					  },
					  "additionalProperties": false
					}"""; //$NON-NLS-1$
		}

		@Override
		public McpToolResult call(Map<String, Object> arguments, IProgressMonitor monitor) {
			ToolArguments args = ToolArguments.of(arguments);
			boolean includeAvailableViews = args.getBoolean("includeAvailableViews", false); //$NON-NLS-1$
			String filter = args.getString("filter"); //$NON-NLS-1$
			int maxResults = args.getInt("maxResults", 100, 1, 1000); //$NON-NLS-1$
			return onUi(() -> collect(includeAvailableViews, filter, maxResults), JsonObject::toString);
		}

		private static JsonObject collect(boolean includeAvailableViews, String filter, int maxResults) {
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
			if (includeAvailableViews) {
				addAvailableViews(result, filter, maxResults);
			}
			String unsupported = unsupportedReason();
			if (unsupported != null) {
				result.put("captureUnsupported", unsupported); //$NON-NLS-1$
			}
			return result;
		}

		/** Every registered view, which is the set eclipse_show_view can open. */
		private static void addAvailableViews(JsonObject result, String filter, int maxResults) {
			String needle = filter == null ? null : filter.toLowerCase(Locale.ROOT);
			List<IViewDescriptor> matching = new ArrayList<>();
			for (IViewDescriptor descriptor : PlatformUI.getWorkbench().getViewRegistry().getViews()) {
				if (needle == null || descriptor.getId().toLowerCase(Locale.ROOT).contains(needle)
						|| descriptor.getLabel().toLowerCase(Locale.ROOT).contains(needle)) {
					matching.add(descriptor);
				}
			}
			matching.sort((a, b) -> a.getLabel().compareToIgnoreCase(b.getLabel()));
			result.put("availableViews", ViewTools.describe(matching, maxResults)) //$NON-NLS-1$
					.put("availableViewsTotal", Integer.valueOf(matching.size())) //$NON-NLS-1$
					.put("availableViewsTruncated", Boolean.valueOf(matching.size() > maxResults)); //$NON-NLS-1$
		}

		static java.util.List<IWorkbenchPartReference> allReferences(IWorkbenchPage page) {
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
			return "Captures the IDE as a PNG and writes it to a file, returning the path. Targets are a workbench part by id, a shell by title, or the whole display; passing part or shellTitle selects the target on its own. The answer reports which method worked: rootCapture reads the real screen pixels, widgetPrint paints the widget hierarchy instead, which is the fallback on a compositing window manager where reading the X11 root yields nothing. Use it for UI work such as layout, theming and dialog rendering; for anything textual the other tools answer better and shorter. The display target captures whatever else is on the screen, so it is not the default. A part that is not visible is refused rather than captured blank, unless activate is set. On a HiDPI monitor widgetPrint captures at the monitor's zoom, so the image is larger than the widget's size in points: capturedArea is the pixels returned, areaInPoints is the widget, and zoom is the percentage between them. Set includeToolbar to capture a view together with its toolbar, which lives in the surrounding part stack and is in no plain part capture."; //$NON-NLS-1$
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
					    "includeBase64": {"type":"boolean","default":false,"description":"Also return the image inline. Large; prefer reading the file."},
				    "includeToolbar": {"type":"boolean","default":false,"description":"For a part, capture the whole part stack instead. A view's toolbar is rendered by the surrounding CTabFolder rather than by the part, so it appears in no plain part capture; this is how to see one. The image then also contains the tabs and any sibling view sharing the stack."}
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
			String part = args.getString("part"); //$NON-NLS-1$
			String explicitTarget = args.getString("target"); //$NON-NLS-1$
			// infer from whichever selector was given, so that passing shellTitle alone
			// does not fail with "the target 'part' needs a 'part' id"
			String target = explicitTarget != null ? explicitTarget
					: args.getString("shellTitle") != null ? "shell" : "part"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
			if ("part".equals(target) && part == null) { //$NON-NLS-1$
				return McpToolResult.error("The target 'part' needs a 'part' id. Use eclipse_list_ui_targets."); //$NON-NLS-1$
			}
			String shellTitle = args.getString("shellTitle"); //$NON-NLS-1$
			boolean activate = args.getBoolean("activate", false); //$NON-NLS-1$
			int maxWidth = args.getInt("maxWidth", 1200, 100, 4000); //$NON-NLS-1$
			String outputPath = args.getString("outputPath"); //$NON-NLS-1$
			boolean includeBase64 = args.getBoolean("includeBase64", false); //$NON-NLS-1$

			return onUi(() -> capture(target, part, shellTitle, activate, maxWidth, outputPath, includeBase64,
					args.getBoolean("includeToolbar", false)), JsonObject::toString); //$NON-NLS-1$
		}

		private static JsonObject capture(String target, String partId, String shellTitle, boolean activate,
				int maxWidth, String outputPath, boolean includeBase64, boolean includeToolbar) {
			Display display = PlatformUI.getWorkbench().getDisplay();
			Rectangle area;
			// the control to paint if reading the root drawable comes back empty
			Control printable = null;
			// the region of the painted control to keep, when what was asked about is
			// smaller than what has to be painted to get it
			Rectangle clip = null;
			if ("display".equals(target)) { //$NON-NLS-1$
				area = display.getBounds();
			} else if ("shell".equals(target)) { //$NON-NLS-1$
				Shell shell = findShell(display, shellTitle);
				if (shell == null) {
					return failure("No shell matching '%s'.".formatted(shellTitle)); //$NON-NLS-1$
				}
				area = shell.getBounds();
				printable = shell;
			} else {
				Control control = findPart(partId, activate);
				if (control == null) {
					return failure(
							"No part '%s', or it is not visible. A part behind another tab is not rendered at all; pass activate to bring it forward." //$NON-NLS-1$
									.formatted(partId));
				}
				if (includeToolbar) {
					Control stack = stackOf(control);
					// print rooted at the CTabFolder does not paint its topRight control,
					// which is the view toolbar, the view menu and the min and max
					// buttons: the one thing this flag is named after. Nor does rooting
					// one level up. A print of the window's content composite does paint
					// them, so that is what is printed, and the result is cropped back to
					// the stack so the answer is still the stack and not its neighbours.
					Composite content = contentOf(stack);
					if (stack != control && content != null && content != stack) {
						clip = display.map(stack.getParent(), content, stack.getBounds());
						control = content;
					} else {
						control = stack;
					}
				}
				// map to display coordinates and capture the real pixels, rather than
				// Control.print(), which has GTK gaps the widget on screen does not
				area = display.map(control.getParent(), null, control.getBounds());
				printable = control;
			}
			if (area.width <= 0 || area.height <= 0) {
				return failure("The capture area is empty."); //$NON-NLS-1$
			}

			Image image = new Image(display, area.width, area.height);
			String method = "rootCapture"; //$NON-NLS-1$
			int zoom = 100;
			try {
				GC gc = new GC(display);
				try {
					gc.copyArea(image, area.x, area.y);
				} finally {
					gc.dispose();
				}
				if (isBlank(image.getImageData()) && printable instanceof Shell shell
						&& shell.getChildren().length > 0) {
					// Shell.print returns blank under a compositing window manager while
					// Composite.print does not, so paint the shell's content instead.
					// Trim and decorations are lost, which does not matter for reading a
					// dialog, and it is the only path that produces pixels here.
					printable = shell.getChildren()[0];
					Rectangle inner = printable.getBounds();
					if (inner.width > 0 && inner.height > 0) {
						area = new Rectangle(area.x, area.y, inner.width, inner.height);
					}
				}
				if (isBlank(image.getImageData()) && printable != null) {
					Control painted = printable;
					// A compositing window manager redirects window contents into an
					// offscreen pixmap, so reading the X11 root drawable yields nothing.
					// Painting the widget hierarchy ourselves does work there. It has
					// known GTK gaps, which is why it is the fallback and not the
					// primary path, but a slightly wrong image beats no image at all.
					image.dispose();
					Rectangle own = printable.getBounds();
					int width = Math.max(1, own.width);
					int height = Math.max(1, own.height);
					// print paints at the monitor's device scale, so drawing into an
					// image sized in points wrote a 2x picture into a 1x canvas and kept
					// the top left quarter, silently. An ImageGcDrawer is given a GC that
					// SWT has set up for the target zoom, and asking that image for its
					// data AT that zoom returns the full resolution picture. Correcting
					// with a GC transform instead does not work: it shrinks the paint to
					// a quarter of a canvas that is still device sized.
					zoom = zoomOf(painted);
					image = new Image(display, (drawer, drawnWidth, drawnHeight) -> {
						// anything print leaves untouched stays this colour. White would
						// be indistinguishable from the unstyled widgets a dark theme bug
						// produces, which is most of what these captures are used for
						drawer.setBackground(display.getSystemColor(SWT.COLOR_MAGENTA));
						drawer.fillRectangle(0, 0, drawnWidth, drawnHeight);
						painted.print(drawer);
					}, width, height);
					area = new Rectangle(area.x, area.y, width, height);
					method = "widgetPrint"; //$NON-NLS-1$
				}
				ImageData data = image.getImageData(zoom);
				if (clip != null && "widgetPrint".equals(method)) { //$NON-NLS-1$
					ImageData cropped = crop(display, data, clip, zoom);
					if (cropped != null) {
						data = cropped;
						area = new Rectangle(area.x, area.y, clip.width, clip.height);
					}
				}
				if (isBlank(data)) {
					return failure(printable == null
							? "The capture came back uniform, so this display cannot be captured through the X11 root drawable. A compositing window manager redirects window contents into an offscreen pixmap, so reading the root yields nothing. There is no fallback for the whole display; capture a part or a shell instead, which can be painted directly." //$NON-NLS-1$
							: "The capture came back uniform through both the X11 root drawable and by painting the widget, so this display cannot be captured at all. Nothing was written; do not trust screenshots here."); //$NON-NLS-1$
				}
				JsonObject written = write(display, image, data, area, maxWidth, outputPath, includeBase64)
						.put("method", method) //$NON-NLS-1$
						.put("zoom", Integer.valueOf(zoom)); //$NON-NLS-1$
				// zoom, the pixels and the points have to agree, and when they do not the
				// paint landed at the wrong scale and part of the image is whatever the
				// canvas was filled with. Saying so beats returning a picture that is
				// three quarters filler and looks like a rendering bug in the IDE
				if (data.width != area.width * zoom / 100 || data.height != area.height * zoom / 100) {
					written.put("scaleMismatch", //$NON-NLS-1$
							"The capture is %dx%d pixels for a widget of %dx%d points at zoom %d, which does not agree. Part of the image is the magenta fill rather than the widget, so treat it as unreliable." //$NON-NLS-1$
									.formatted(Integer.valueOf(data.width), Integer.valueOf(data.height),
											Integer.valueOf(area.width), Integer.valueOf(area.height),
											Integer.valueOf(zoom)));
				}
				return written;
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
						// the pixels that were actually captured, not the widget's size in
						// points. Reporting the two as if they were the same is what let a
						// capture that kept a quarter of the window look complete
						.put("capturedArea", data.width + "x" + data.height) //$NON-NLS-1$ //$NON-NLS-2$
						.put("areaInPoints", area.width + "x" + area.height) //$NON-NLS-1$ //$NON-NLS-2$
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

		static Shell findShell(Display display, String title) {
			if (title == null) {
				// the modal one is what is blocking, otherwise whatever is active
				for (Shell candidate : display.getShells()) {
					if (candidate.isVisible() && (candidate.getStyle()
							& (SWT.APPLICATION_MODAL | SWT.PRIMARY_MODAL | SWT.SYSTEM_MODAL)) != 0) {
						return candidate;
					}
				}
				return display.getActiveShell();
			}
			for (Shell shell : display.getShells()) {
				if (shell.getText() != null && shell.getText().contains(title)) {
					return shell;
				}
			}
			return null;
		}

		/** The monitor's zoom in percent, which is the resolution a widget paints at. */
		private static int zoomOf(Control control) {
			try {
				org.eclipse.swt.widgets.Monitor monitor = control.getMonitor();
				int zoom = monitor == null ? 100 : monitor.getZoom();
				return zoom <= 0 ? 100 : zoom;
			} catch (RuntimeException e) {
				return 100;
			}
		}

		/**
		 * The part stack a control sits in, or the control itself when it is not in one.
		 * <p>
		 * A view's toolbar is rendered by the surrounding {@code CTabFolder} rather than
		 * by the part, so it appears in no part capture at all. Walking up to the folder
		 * is the only way to ask for a view together with its toolbar.
		 */
		static Control stackOf(Control control) {
			for (Control candidate = control; candidate != null; candidate = candidate.getParent()) {
				if (candidate instanceof org.eclipse.swt.custom.CTabFolder) {
					return candidate;
				}
			}
			return control;
		}

		/** The composite an e4 part renders into, or {@code null} when it has none yet. */
		static Control controlOf(IWorkbenchPart part) {
			Object widget = part.getSite().getService(org.eclipse.e4.ui.model.application.ui.basic.MPart.class);
			if (widget instanceof org.eclipse.e4.ui.model.application.ui.basic.MPart model
					&& model.getWidget() instanceof Composite composite) {
				return composite;
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
			return controlOf(part);
		}

		/** The window's top level content composite, which is the highest print that works. */
		private static Composite contentOf(Control control) {
			Shell shell = control.getShell();
			if (shell == null || shell.getChildren().length == 0) {
				return null;
			}
			return shell.getChildren()[0] instanceof Composite composite ? composite : null;
		}

		/** Cuts {@code region}, given in points, out of device pixel data. */
		private static ImageData crop(Display display, ImageData data, Rectangle region, int zoom) {
			int x = region.x * zoom / 100;
			int y = region.y * zoom / 100;
			int width = Math.min(region.width * zoom / 100, data.width - x);
			int height = Math.min(region.height * zoom / 100, data.height - y);
			if (x < 0 || y < 0 || width <= 0 || height <= 0) {
				return null;
			}
			// both images are built from ImageData and are therefore 1:1, so the device
			// pixels above stay device pixels and nothing is rescaled on the way through
			Image full = new Image(display, data);
			Image cut = new Image(display, width, height);
			GC gc = new GC(cut);
			try {
				gc.drawImage(full, x, y, width, height, 0, 0, width, height);
			} finally {
				gc.dispose();
				full.dispose();
			}
			try {
				return cut.getImageData();
			} finally {
				cut.dispose();
			}
		}

		private static JsonObject failure(String reason) {
			return new JsonObject().put("captured", false).put("reason", reason); //$NON-NLS-1$ //$NON-NLS-2$
		}
	}
}
