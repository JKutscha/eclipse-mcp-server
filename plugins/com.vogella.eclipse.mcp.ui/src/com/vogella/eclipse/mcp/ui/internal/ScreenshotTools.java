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
			return "Captures the IDE as a PNG and writes it to a file, returning the path. Targets are a workbench part by id, a shell by title, or the whole display; passing part or shellTitle selects the target on its own. Omitting both shellTitle and part captures the active workbench window's shell, which is also what a target of shell without a title does; the display target must be asked for explicitly. The answer reports which method worked: rootCapture reads the real screen pixels, widgetPrint paints the widget hierarchy instead, which is the fallback on a compositing window manager where reading the X11 root yields nothing. A shell capture is sized to the shell's client area and composes every visible child of the shell into one image, so the trim bars are in it; the window decorations, meaning the title bar and the frame, are drawn by the window manager and are present only when rootCapture succeeded. requestedArea names the bounds of what was asked for, and when the capture covers less than that, requestedAreaNote says what was excluded and why. For method widgetPrint the areas between parts that no print paints are replaced with the widget background colour before the image is written, and the answer counts them in unpaintedPixels, so a whole shell capture does not arrive outlined in filler colour. Use it for UI work such as layout, theming and dialog rendering; for anything textual the other tools answer better and shorter. A part that is not visible is refused rather than captured blank, unless activate is set. On a HiDPI monitor widgetPrint captures at the monitor's zoom, so the image is larger than the widget's size in points: capturedArea is the pixels returned, areaInPoints is the widget, and zoom is the percentage between them. Set includeToolbar to capture a part together with its surrounding stack, but note that the stack's topRight children, the view toolbar among them, are not painted by any widget print rooted inside the window; capture the shell and crop to the bounds from eclipse_get_widget_tree for those."; //$NON-NLS-1$
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
				    "includeToolbar": {"type":"boolean","default":false,"description":"For a part, capture the whole part stack instead: the tabs and any sibling view sharing the stack. KNOWN GAP: it does NOT paint the CTabFolder's topRight children, which are the view toolbar, the view menu and the min and max buttons, and nothing in the answer says they are missing. To see those, capture target=shell and crop to the bounds eclipse_get_widget_tree reports for them."}
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
			// the bounds of what the caller named, which the answer reports even when
			// the capture covers less
			Rectangle requested;
			// what is outside the capture although the caller named it, if anything
			String exclusion = null;
			Rectangle clientArea = null;
			List<Paintable> pieces = null;
			// the control to paint if reading the root drawable comes back empty
			Control printable = null;
			if ("display".equals(target)) { //$NON-NLS-1$
				area = display.getBounds();
				requested = new Rectangle(area.x, area.y, area.width, area.height);
			} else if ("shell".equals(target)) { //$NON-NLS-1$
				Shell shell = findShell(display, shellTitle);
				if (shell == null) {
					return failure(shellTitle == null ? "This IDE has no window to capture." //$NON-NLS-1$
							: "No shell matching '%s'.".formatted(shellTitle)); //$NON-NLS-1$
				}
				area = shell.getBounds();
				requested = new Rectangle(area.x, area.y, area.width, area.height);
				exclusion = DECORATIONS_EXCLUDED;
				printable = shell;
			} else {
				Control control = findPart(partId, activate);
				if (control == null) {
					return failure(
							"No part '%s', or it is not visible. A part behind another tab is not rendered at all; pass activate to bring it forward." //$NON-NLS-1$
									.formatted(partId));
				}
				if (includeToolbar) {
					control = stackOf(control);
				}
				// map to display coordinates and capture the real pixels, rather than
				// Control.print(), which has GTK gaps the widget on screen does not
				area = display.map(control.getParent(), null, control.getBounds());
				requested = new Rectangle(area.x, area.y, area.width, area.height);
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
					// Composite.print does not, so paint the shell's content instead:
					// every visible child at its own bounds, trim bars included, rather
					// than one of them alone. The window decorations are no child of
					// anything SWT can print; requestedArea and its note tell the caller.
					clientArea = shell.getClientArea();
					pieces = paintablesOf(shell);
				}
				if (isBlank(image.getImageData()) && printable != null) {
					final Control painted = printable;
					final List<Paintable> composed = pieces;
					// A compositing window manager redirects window contents into an
					// offscreen pixmap, so reading the X11 root drawable yields nothing.
					// Painting the widget hierarchy ourselves does work there. It has
					// known GTK gaps, which is why it is the fallback and not the
					// primary path, but a slightly wrong image beats no image at all.
					image.dispose();
					Rectangle own = clientArea != null ? clientArea : painted.getBounds();
					Size canvas = compositionSize(own.width, own.height);
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
						if (composed == null) {
							painted.print(drawer);
						} else {
							for (Paintable piece : composed) {
								piece.print(drawer);
							}
						}
					}, canvas.width(), canvas.height());
					area = new Rectangle(area.x, area.y, canvas.width(), canvas.height());
					method = "widgetPrint"; //$NON-NLS-1$
				}
				ImageData data = image.getImageData(zoom);
				if (isBlank(data)) {
					return failure(printable == null
							? "The capture came back uniform, so this display cannot be captured through the X11 root drawable. A compositing window manager redirects window contents into an offscreen pixmap, so reading the root yields nothing. There is no fallback for the whole display; capture a part or a shell instead, which can be painted directly." //$NON-NLS-1$
							: "The capture came back uniform through both the X11 root drawable and by painting the widget, so this display cannot be captured at all. Nothing was written; do not trust screenshots here."); //$NON-NLS-1$
				}
				// before the filler is replaced, because replacing it is what makes this
				// invisible: a paint that landed at half scale fills the top left quarter
				// and leaves the rest filler, which afterwards reads as a plain
				// background and counts as zero unpainted pixels
				String scaleWarning = "widgetPrint".equals(method) ? paintCoverageWarning(data) : null; //$NON-NLS-1$
				// after the blank check: a fully unpainted capture must still read as
				// uniform here, and swapping the filler first would hide exactly that
				Unpainted unpainted = "widgetPrint".equals(method) && printable != null && scaleWarning == null //$NON-NLS-1$
						? replaceFiller(data, backgroundSource(printable, pieces))
						: null;
				JsonObject written = write(display, image, data, area, maxWidth, outputPath, includeBase64)
						.put("method", method) //$NON-NLS-1$
						.put("zoom", Integer.valueOf(zoom)) //$NON-NLS-1$
						.put("requestedArea", describe(requested)); //$NON-NLS-1$
				if (!requested.equals(area)) {
					written.put("requestedAreaNote", exclusion != null ? exclusion //$NON-NLS-1$
							: "The capture covers %dx%d points while requestedArea names %dx%d." //$NON-NLS-1$
									.formatted(Integer.valueOf(area.width), Integer.valueOf(area.height),
											Integer.valueOf(requested.width), Integer.valueOf(requested.height)));
				}
				if (scaleWarning != null) {
					written.put("paintScaleMismatch", scaleWarning); //$NON-NLS-1$
				}
				if (unpainted != null) {
					written.put("unpaintedPixels", Integer.valueOf(unpainted.pixels())) //$NON-NLS-1$
							.put("unpaintedFraction", Double.valueOf(unpainted.fraction())) //$NON-NLS-1$
							.put("unpaintedFilledWith", unpainted.fillDescription()); //$NON-NLS-1$
				}
				if ("widgetPrint".equals(method)) { //$NON-NLS-1$
					written.put("printNote", "The widget print does not paint the sash and margin areas between parts; those were filled before printing and replaced with the widget background colour afterwards, which is what unpaintedPixels counts."); //$NON-NLS-1$ //$NON-NLS-2$
				}
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

		/** What a shell capture leaves out, and why nothing can paint it. */
		private static final String DECORATIONS_EXCLUDED = "The window decorations, meaning the title bar and the frame around the shell, are drawn by the window manager and are not part of the client area this capture paints, so they are not in the image."; //$NON-NLS-1$

		/** The size of a composed shell capture's canvas, as plain values. */
		public record Size(int width, int height) {
		}

		/**
		 * The canvas a composed shell capture paints into: the shell's client area,
		 * never empty, so that a degenerate shell still yields an image the
		 * uniform-pixel check can judge.
		 */
		public static Size compositionSize(int clientWidth, int clientHeight) {
			return new Size(Math.max(1, clientWidth), Math.max(1, clientHeight));
		}

		/** Where a child goes in a composed shell capture, as plain values. */
		public record Placement(int x, int y, int width, int height) {
		}

		/**
		 * Where a child is painted in a composed shell capture, or {@code null} when
		 * it is left out because it is invisible or has an empty bounds.
		 */
		public static Placement placementOf(int x, int y, int width, int height, boolean visible) {
			if (!visible || width <= 0 || height <= 0) {
				return null;
			}
			return new Placement(x, y, width, height);
		}

		/** One control of a composed shell capture, with its placement. */
		private record Paintable(Control control, Rectangle at) {

			/**
			 * Prints the control into an image of its own and draws that image at its
			 * place, so every piece goes through the same print a single child would.
			 */
			void print(GC target) {
				Image piece = new Image(target.getDevice(), (gc, w, h) -> {
					// a fresh SWT image starts white, and what the print leaves untouched
					// inside a child, its sashes and part margins, then survives as white
					// lines drawn over the canvas filler. Filling each piece the same way
					// the canvas is filled is what lets those areas be found at all
					gc.setBackground(gc.getDevice().getSystemColor(SWT.COLOR_MAGENTA));
					gc.fillRectangle(0, 0, w, h);
					control.print(gc);
				}, at.width, at.height);
				try {
					target.drawImage(piece, at.x, at.y);
				} finally {
					piece.dispose();
				}
			}
		}

		private static List<Paintable> paintablesOf(Shell shell) {
			List<Paintable> paintables = new ArrayList<>();
			for (Control child : shell.getChildren()) {
				Rectangle bounds = child.getBounds();
				Placement at = placementOf(bounds.x, bounds.y, bounds.width, bounds.height, child.isVisible());
				if (at != null) {
					paintables.add(new Paintable(child, new Rectangle(at.x(), at.y(), at.width(), at.height())));
				}
			}
			return paintables;
		}

		/**
		 * The widget whose background replaces unpainted pixels: the first child for a
		 * composed shell, which is what the one-child path used before composing.
		 */
		private static Control backgroundSource(Control printable, List<Paintable> pieces) {
			return pieces != null && !pieces.isEmpty() ? pieces.get(0).control() : printable;
		}

		/** Bounds in the {@code x,y widthxheight} form the other tools report. */
		private static String describe(Rectangle rectangle) {
			return rectangle.x + "," + rectangle.y + " " + rectangle.width + "x" + rectangle.height; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
		}

		/**
		 * The width to scale to, snapped to a whole number ratio when that is close to
		 * what was asked for.
		 * <p>
		 * Resampling already rendered text by a fraction makes the glyphs mushy, and a
		 * caller asking for 1920 from a 5370 wide capture wants a picture that reads
		 * rather than exactly 1920 pixels. Only snaps within a fifth of the request, so
		 * a deliberate width is still honoured. A tenth was too tight to be useful: a
		 * 5370 wide capture asked for 2000 lands on 1790, which missed by half a
		 * percent and left the picture mushy for nothing.
		 */
		private static int crispWidth(int actual, int maxWidth) {
			if (actual <= maxWidth) {
				return maxWidth;
			}
			int divisor = Math.max(1, Math.round(actual / (float) maxWidth));
			int candidate = actual / divisor;
			return candidate <= maxWidth && candidate >= maxWidth * 4 / 5 ? candidate : maxWidth;
		}

		private static JsonObject write(Display display, Image image, ImageData data, Rectangle area, int maxWidth,
				String outputPath, boolean includeBase64) {
			ImageData scaled = data;
			int snapped = crispWidth(data.width, maxWidth);
			if (data.width > snapped) {
				int height = Math.max(1, data.height * snapped / data.width);
				scaled = data.scaledTo(snapped, height);
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
						// every number the scaling depends on, from its own source: the
						// image as SWT sizes it, the data as it came back, and the factor
						// actually applied. A capture that looks right in every derived
						// field and wrong on screen is a disagreement between these, and
						// naming them is cheaper than inferring them from the picture
						.put("imageBounds", image.getBounds().width + "x" + image.getBounds().height) //$NON-NLS-1$ //$NON-NLS-2$
						.put("scaleFactor", Math.round(scaled.width * 1000.0 / data.width) / 1000.0) //$NON-NLS-1$
						.put("maxWidthSnappedTo", snapped == maxWidth ? null : Integer.valueOf(snapped)) //$NON-NLS-1$
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

		/** The pre-print fill and what it was replaced with. */
		private record Unpainted(int pixels, int width, int height, org.eclipse.swt.graphics.RGB fill) {

			double fraction() {
				return width * height == 0 ? 0.0 : Math.round(pixels * 1000.0 / (width * height)) / 10.0;
			}

			String fillDescription() {
				return "%d,%d,%d (the widget's background colour)".formatted(Integer.valueOf(fill().red), //$NON-NLS-1$
						Integer.valueOf(fill().green), Integer.valueOf(fill().blue));
			}
		}

		/**
		 * Whether the paint covered the image it was given, or only a corner of it.
		 * <p>
		 * A print that lands at half the scale of its buffer fills the top left
		 * quarter and leaves the rest as it was filled, which the metadata cannot
		 * show: the sizes agree with each other and only the pixels disagree. Sampling
		 * the far corner catches it, and it has to happen before the filler is
		 * replaced, or the evidence is gone.
		 *
		 * @return the warning, or {@code null} when the far corner holds real content
		 */
		private static String paintCoverageWarning(ImageData data) {
			int fillerPixel = pixelOf(data.palette, FILLER);
			if (fillerPixel < 0 || data.width < 8 || data.height < 8) {
				return null;
			}
			int sampled = 0;
			int filler = 0;
			// the bottom right eighth: far enough from the middle that a real widget
			// tree covers it, small enough to stay cheap on a 5000 pixel wide capture
			for (int y = data.height * 7 / 8; y < data.height; y += 4) {
				for (int x = data.width * 7 / 8; x < data.width; x += 4) {
					sampled++;
					if (data.getPixel(x, y) == fillerPixel) {
						filler++;
					}
				}
			}
			if (sampled == 0 || filler < sampled) {
				return null;
			}
			return "The far corner of this capture is entirely unpainted while the answer claims the whole area, which means the widget print landed at a different scale than the image it was given: the picture holds the top left part of the widget, enlarged, and the rest is filler. The filler was NOT replaced here, so the image shows the problem rather than hiding it. Known to happen after the window has been resized; a restart of the IDE clears it."; //$NON-NLS-1$
		}

		private static final org.eclipse.swt.graphics.RGB FILLER = new org.eclipse.swt.graphics.RGB(255, 0, 255);

		/**
		 * Replaces the pixels the print left untouched with the widget's background
		 * colour, in the data already fetched at the capture zoom.
		 * <p>
		 * The magenta stays in the image while it is being judged: a uniform one means
		 * nothing painted at all. Only afterwards is it swapped out, so the picture a
		 * human reads is not screaming pink between the parts while the count still
		 * says exactly how much of it was never painted.
		 *
		 * @return {@code null} when either colour cannot be named in this palette,
		 *         which leaves the image as printed
		 */
		private static Unpainted replaceFiller(ImageData data, Control painted) {
			org.eclipse.swt.graphics.PaletteData palette = data.palette;
			int fillerPixel = pixelOf(palette, FILLER);
			if (fillerPixel < 0) {
				return null;
			}
			org.eclipse.swt.graphics.RGB background = painted.getBackground() == null ? null
					: painted.getBackground().getRGB();
			if (background == null) {
				background = painted.getDisplay().getSystemColor(SWT.COLOR_WIDGET_BACKGROUND).getRGB();
			}
			int backgroundPixel = pixelOf(palette, background);
			if (backgroundPixel < 0 || backgroundPixel == fillerPixel) {
				return null;
			}
			int count = 0;
			int[] row = new int[data.width];
			for (int y = 0; y < data.height; y++) {
				data.getPixels(0, y, data.width, row, 0);
				boolean touched = false;
				for (int x = 0; x < data.width; x++) {
					if (row[x] == fillerPixel) {
						row[x] = backgroundPixel;
						count++;
						touched = true;
					}
				}
				if (touched) {
					data.setPixels(0, y, data.width, row, 0);
				}
			}
			return new Unpainted(count, data.width, data.height, background);
		}

		/** The palette value for a colour, or -1 when the palette cannot name it. */
		private static int pixelOf(org.eclipse.swt.graphics.PaletteData palette, org.eclipse.swt.graphics.RGB rgb) {
			if (palette.isDirect) {
				return palette.getPixel(rgb);
			}
			org.eclipse.swt.graphics.RGB[] colors = palette.colors;
			if (colors == null) {
				return -1;
			}
			for (int i = 0; i < colors.length; i++) {
				if (rgb.equals(colors[i])) {
					return i;
				}
			}
			return -1;
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
				Shell active = display.getActiveShell();
				if (active != null) {
					return active;
				}
				// getActiveShell is null whenever the IDE does not have focus, which is
				// the normal state when the call arrives over HTTP
				IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
				return window == null ? null : window.getShell();
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

		private static JsonObject failure(String reason) {
			return new JsonObject().put("captured", false).put("reason", reason); //$NON-NLS-1$ //$NON-NLS-2$
		}
	}
}
