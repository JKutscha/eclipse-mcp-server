package com.vogella.eclipse.mcp.ui.internal;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Widget;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchPartReference;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;

import com.vogella.eclipse.mcp.core.IMcpTool;
import com.vogella.eclipse.mcp.core.McpToolResult;
import com.vogella.eclipse.mcp.core.ToolArguments;
import com.vogella.eclipse.mcp.core.json.JsonArray;
import com.vogella.eclipse.mcp.core.json.JsonObject;

/**
 * Answers what a widget is and how the CSS engine sees it.
 * <p>
 * Addressed by part id and a widget path rather than by screen coordinates. A
 * path survives a window resize and a restart, and a pixel does not, and a
 * client cannot point at anything in the first place.
 */
public final class WidgetTools {

	private WidgetTools() {
	}

	/** The default depth, deep enough for a view's own widgets and not for the whole window. */
	private static final int DEFAULT_DEPTH = 6;

	/** The root control of a part, or of a shell. */
	private static Control rootOf(String partId, String shellTitle, boolean includeToolbar) {
		Display display = PlatformUI.getWorkbench().getDisplay();
		if (partId == null || partId.isBlank()) {
			Shell shell = ScreenshotTools.Capture.findShell(display, shellTitle);
			return shell == null ? null : shell;
		}
		IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
		IWorkbenchPage page = window == null ? null : window.getActivePage();
		if (page == null) {
			return null;
		}
		for (IWorkbenchPartReference reference : ScreenshotTools.ListTargets.allReferences(page)) {
			if (partId.equals(reference.getId())) {
				var part = reference.getPart(true);
				Control control = part == null ? null : ScreenshotTools.Capture.controlOf(part);
				return control != null && includeToolbar ? ScreenshotTools.Capture.stackOf(control) : control;
			}
		}
		return null;
	}

	/**
	 * Walks a slash separated path of child indices from {@code root}.
	 * <p>
	 * Indices rather than names, because most SWT widgets have no stable name at
	 * all, and the tree dump is what a caller reads them from.
	 */
	private static Control resolve(Control root, String path) {
		if (path == null || path.isBlank() || "/".equals(path)) { //$NON-NLS-1$
			return root;
		}
		Control current = root;
		for (String segment : path.split("/")) { //$NON-NLS-1$
			if (segment.isBlank()) {
				continue;
			}
			int index;
			try {
				index = Integer.parseInt(segment.strip());
			} catch (NumberFormatException e) {
				return null;
			}
			if (!(current instanceof Composite composite)) {
				return null;
			}
			Control[] children = composite.getChildren();
			if (index < 0 || index >= children.length) {
				return null;
			}
			current = children[index];
		}
		return current;
	}

	private static String bounds(Control control) {
		Rectangle rectangle = control.getBounds();
		return rectangle.x + "," + rectangle.y + " " + rectangle.width + "x" + rectangle.height; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
	}

	/** The CSS view of a widget, empty when the CSS bundles are not installed. */
	private static JsonObject css(Widget widget) {
		JsonObject result = new JsonObject();
		try {
			result.put("cssId", org.eclipse.e4.ui.css.swt.dom.WidgetElement.getID(widget)) //$NON-NLS-1$
					.put("cssClass", org.eclipse.e4.ui.css.swt.dom.WidgetElement.getCSSClass(widget)); //$NON-NLS-1$
			var engine = org.eclipse.e4.ui.css.swt.dom.WidgetElement.getEngine(widget);
			if (engine == null) {
				return result.put("cssElement", null) //$NON-NLS-1$
						.put("cssNote", "No CSS engine is attached, so this widget is not styled by the theme engine."); //$NON-NLS-1$ //$NON-NLS-2$
			}
			org.w3c.dom.Element element = engine.getElement(widget);
			result.put("cssElement", element == null ? null : element.getLocalName()); //$NON-NLS-1$
		} catch (LinkageError | RuntimeException e) {
			result.put("cssNote", //$NON-NLS-1$
					"The e4 CSS bundles are not available in this IDE, so only the SWT side can be reported."); //$NON-NLS-1$
		}
		return result;
	}

	/** Lists the widgets of a part, so their paths can be read off rather than guessed. */
	public static final class GetWidgetTree implements IMcpTool {

		@Override
		public String getName() {
			return "eclipse_get_widget_tree"; //$NON-NLS-1$
		}

		@Override
		public String getDescription() {
			return "Lists the SWT widget hierarchy of a part or a shell, with each widget's class, bounds, CSS id and CSS class, and the path that addresses it. Changes nothing. This is the answer to 'what am I actually looking at', which otherwise has to be inferred from a screenshot or from reading somebody else's source, and it is where the paths for eclipse_inspect_widget come from. Filter by class to ask a narrow question, such as which Trees a view contains and what their ids are. Paths are slash separated child indices, which is deliberate: most SWT widgets have no stable name, while an index survives a resize and a restart in a way screen coordinates do not."; //$NON-NLS-1$
		}

		@Override
		public String getInputSchema() {
			return """
					{
					  "type": "object",
					  "properties": {
					    "part":       {"type":"string","description":"Part id, from eclipse_list_ui_targets. Omit to walk a shell instead."},
					    "shellTitle": {"type":"string","description":"Shell to walk when no part is given. Omit for the active shell."},
					    "path":       {"type":"string","description":"Start from this widget rather than the root, e.g. '0/2'."},
					    "filter":     {"type":"string","description":"Only report widgets whose simple class name contains this text, case insensitive, e.g. 'Tree' or 'ToolBar'. The walk still descends through everything."},
				    "includeToolbar": {"type":"boolean","default":false,"description":"Start from the surrounding part stack rather than the part. A view's toolbar is built in the stack's CTabFolder, not in the part, so it is in no plain part tree at all; this is how to reach it."},
					    "maxDepth":   {"type":"integer","default":6,"minimum":1,"maximum":30},
					    "maxResults": {"type":"integer","default":200,"minimum":1,"maximum":2000}
					  },
					  "additionalProperties": false
					}"""; //$NON-NLS-1$
		}

		@Override
		public McpToolResult call(Map<String, Object> arguments, IProgressMonitor monitor) {
			ToolArguments args = ToolArguments.of(arguments);
			String partId = args.getString("part"); //$NON-NLS-1$
			String shellTitle = args.getString("shellTitle"); //$NON-NLS-1$
			String path = args.getString("path"); //$NON-NLS-1$
			String filter = args.getString("filter"); //$NON-NLS-1$
			int maxDepth = args.getInt("maxDepth", DEFAULT_DEPTH, 1, 30); //$NON-NLS-1$
			int maxResults = args.getInt("maxResults", 200, 1, 2000); //$NON-NLS-1$
			return UiThread.call(15, () -> collect(partId, shellTitle, path, filter, maxDepth, maxResults,
					args.getBoolean("includeToolbar", false))); //$NON-NLS-1$
		}

		private static JsonObject collect(String partId, String shellTitle, String path, String filter, int maxDepth,
				int maxResults, boolean includeToolbar) {
			Control root = rootOf(partId, shellTitle, includeToolbar);
			if (root == null) {
				return new JsonObject().put("found", Boolean.FALSE) //$NON-NLS-1$
						.put("reason", //$NON-NLS-1$
								"No such part or shell, or the part is not open. Use eclipse_list_ui_targets.");
			}
			Control start = resolve(root, path);
			if (start == null) {
				return new JsonObject().put("found", Boolean.FALSE) //$NON-NLS-1$
						.put("reason", "The path '%s' does not resolve under this part.".formatted(path)); //$NON-NLS-1$ //$NON-NLS-2$
			}
			String needle = filter == null ? null : filter.toLowerCase(Locale.ROOT);
			JsonArray widgets = new JsonArray();
			int[] total = { 0 };
			walk(start, path == null ? "" : path.strip(), 0, maxDepth, needle, maxResults, widgets, total); //$NON-NLS-1$
			return new JsonObject().put("found", Boolean.TRUE) //$NON-NLS-1$
					.put("root", start.getClass().getName()) //$NON-NLS-1$
					.put("total", Integer.valueOf(total[0])) //$NON-NLS-1$
					.put("truncated", Boolean.valueOf(total[0] > widgets.size())) //$NON-NLS-1$
					.put("widgets", widgets); //$NON-NLS-1$
		}

		private static void walk(Control control, String path, int depth, int maxDepth, String needle, int maxResults,
				JsonArray into, int[] total) {
			boolean wanted = needle == null
					|| control.getClass().getSimpleName().toLowerCase(Locale.ROOT).contains(needle);
			if (wanted) {
				total[0]++;
				if (into.size() < maxResults) {
					into.add(css(control).put("path", path.isEmpty() ? "/" : path) //$NON-NLS-1$ //$NON-NLS-2$
							.put("class", control.getClass().getName()) //$NON-NLS-1$
							.put("bounds", bounds(control)) //$NON-NLS-1$
							.put("visible", Boolean.valueOf(control.isVisible()))); //$NON-NLS-1$
				}
			}
			if (depth >= maxDepth || !(control instanceof Composite composite)) {
				return;
			}
			Control[] children = composite.getChildren();
			for (int i = 0; i < children.length; i++) {
				walk(children[i], path.isEmpty() ? String.valueOf(i) : path + "/" + i, depth + 1, maxDepth, needle, //$NON-NLS-1$
						maxResults, into, total);
			}
		}
	}

	/** Reports one widget, its ancestry and what the CSS engine computed for it. */
	public static final class InspectWidget implements IMcpTool {

		private static final List<String> DEFAULT_PROPERTIES = List.of("background-color", "color", //$NON-NLS-1$ //$NON-NLS-2$
				"font-family", "font-size", "font-weight", "border-color", "swt-selected-tab-fill"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$

		@Override
		public String getName() {
			return "eclipse_inspect_widget"; //$NON-NLS-1$
		}

		@Override
		public String getDescription() {
			return "Reports one widget: its SWT class, the CSS element it maps to, its CSS id and classes, its bounds, its ancestor chain, and what the CSS engine resolved for each property asked about. Changes nothing. This is what turns a colour on screen into an explanation, and in particular it shows a '#token' reference that resolved to nothing, which otherwise only shows up as something rendering black or white. Address the widget with a path from eclipse_get_widget_tree. It does NOT report which rules matched or from which stylesheet: the engine does not expose the matched declarations, so that half of a CSS spy is not available here and reading the stylesheets is still the way to find the rule."; //$NON-NLS-1$
		}

		@Override
		public String getInputSchema() {
			return """
					{
					  "type": "object",
					  "properties": {
					    "part":       {"type":"string","description":"Part id, from eclipse_list_ui_targets. Omit to address a shell instead."},
					    "shellTitle": {"type":"string","description":"Shell when no part is given."},
					    "path":       {"type":"string","description":"Slash separated child indices from eclipse_get_widget_tree, e.g. '0/2/1'. Omit for the root."},
					    "properties": {"type":"array","items":{"type":"string"},"description":"CSS properties to resolve. Defaults to the colour and font ones plus swt-selected-tab-fill."},
					    "pseudo":     {"type":"string","description":"Pseudo class to resolve against, e.g. 'hover', 'selected', 'checked'. A property that only differs under a pseudo class reads as unset without this."},
				    "includeToolbar": {"type":"boolean","default":false,"description":"Start from the surrounding part stack rather than the part. A view's toolbar is built in the stack's CTabFolder, not in the part, so it is in no plain part tree at all; this is how to reach it."}
					  },
					  "additionalProperties": false
					}"""; //$NON-NLS-1$
		}

		@Override
		public McpToolResult call(Map<String, Object> arguments, IProgressMonitor monitor) {
			ToolArguments args = ToolArguments.of(arguments);
			String partId = args.getString("part"); //$NON-NLS-1$
			String shellTitle = args.getString("shellTitle"); //$NON-NLS-1$
			String path = args.getString("path"); //$NON-NLS-1$
			String pseudo = args.getString("pseudo"); //$NON-NLS-1$
			List<String> properties = new ArrayList<>();
			if (arguments != null && arguments.get("properties") instanceof List<?> list) { //$NON-NLS-1$
				list.forEach(value -> properties.add(String.valueOf(value)));
			}
			if (properties.isEmpty()) {
				properties.addAll(DEFAULT_PROPERTIES);
			}
			return UiThread.call(15, () -> inspect(partId, shellTitle, path, properties, pseudo,
					args.getBoolean("includeToolbar", false))); //$NON-NLS-1$
		}

		private static JsonObject inspect(String partId, String shellTitle, String path, List<String> properties,
				String pseudo, boolean includeToolbar) {
			Control root = rootOf(partId, shellTitle, includeToolbar);
			if (root == null) {
				return new JsonObject().put("found", Boolean.FALSE) //$NON-NLS-1$
						.put("reason", //$NON-NLS-1$
								"No such part or shell, or the part is not open. Use eclipse_list_ui_targets.");
			}
			Control control = resolve(root, path);
			if (control == null) {
				return new JsonObject().put("found", Boolean.FALSE) //$NON-NLS-1$
						.put("reason", //$NON-NLS-1$
								"The path '%s' does not resolve. Read a current one from eclipse_get_widget_tree."
										.formatted(path));
			}
			JsonArray ancestors = new JsonArray();
			for (Control parent = control.getParent(); parent != null; parent = parent.getParent()) {
				ancestors.add(css(parent).put("class", parent.getClass().getName())); //$NON-NLS-1$
			}
			JsonObject computed = new JsonObject();
			String note = null;
			try {
				var engine = org.eclipse.e4.ui.css.swt.dom.WidgetElement.getEngine(control);
				if (engine == null) {
					note = "No CSS engine is attached to this widget, so nothing was computed for it."; //$NON-NLS-1$
				} else {
					for (String property : properties) {
						computed.put(property, engine.retrieveCSSProperty(control, property, pseudo));
					}
				}
			} catch (LinkageError | RuntimeException e) {
				note = "The e4 CSS bundles are not available in this IDE, so no computed values could be read."; //$NON-NLS-1$
			}
			return css(control).put("found", Boolean.TRUE) //$NON-NLS-1$
					.put("path", path == null || path.isBlank() ? "/" : path.strip()) //$NON-NLS-1$ //$NON-NLS-2$
					.put("class", control.getClass().getName()) //$NON-NLS-1$
					.put("bounds", bounds(control)) //$NON-NLS-1$
					.put("visible", Boolean.valueOf(control.isVisible())) //$NON-NLS-1$
					.put("pseudo", pseudo) //$NON-NLS-1$
					.put("computed", computed) //$NON-NLS-1$
					.put("computedNote", note) //$NON-NLS-1$
					.put("ancestors", ancestors) //$NON-NLS-1$
					.put("matchedRulesNote", //$NON-NLS-1$
							"Which rules matched, and from which stylesheet, is not reported: the engine keeps no matched declaration list to read. A null computed value for a property the theme sets is the signal that a rule did not apply, or that a '#token' reference resolved to nothing.");
		}
	}
}
