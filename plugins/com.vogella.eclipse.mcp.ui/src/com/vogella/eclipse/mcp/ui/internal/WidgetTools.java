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
	 * Walks a slash separated path from {@code root}.
	 * <p>
	 * A plain index is a child control; an {@code i} prefixed one is an item, as in
	 * {@code 2/i0} for the first button of the ToolBar at {@code 2}. Indices rather
	 * than names, because most SWT widgets have no stable name at all, and the tree
	 * dump is what a caller reads them from.
	 */
	private static Widget resolve(Control root, String path) {
		if (path == null || path.isBlank() || "/".equals(path)) { //$NON-NLS-1$
			return root;
		}
		Widget current = root;
		for (String segment : path.split("/")) { //$NON-NLS-1$
			String step = segment.strip();
			if (step.isEmpty()) {
				continue;
			}
			boolean item = step.startsWith("i"); //$NON-NLS-1$
			int index;
			try {
				index = Integer.parseInt(item ? step.substring(1) : step);
			} catch (NumberFormatException e) {
				return null;
			}
			Widget[] candidates = item ? itemsOf(current)
					: current instanceof Composite composite ? composite.getChildren() : new Widget[0];
			if (index < 0 || index >= candidates.length) {
				return null;
			}
			current = candidates[index];
		}
		return current;
	}

	/**
	 * The items of a widget, which are not children and are therefore invisible to a
	 * walk over controls.
	 * <p>
	 * A ToolItem is an Item and not a Control, so the buttons of a view toolbar have
	 * no place in a control hierarchy at all, while the CSS engine models each of
	 * them as its own stylable element. Enumerating them is what lets the inspector
	 * address one, pseudo classes included.
	 */
	private static Widget[] itemsOf(Widget widget) {
		return switch (widget) {
		case org.eclipse.swt.widgets.ToolBar bar -> bar.getItems();
		case org.eclipse.swt.custom.CTabFolder folder -> folder.getItems();
		case org.eclipse.swt.widgets.TabFolder folder -> folder.getItems();
		case org.eclipse.swt.widgets.CoolBar bar -> bar.getItems();
		case org.eclipse.swt.widgets.ExpandBar bar -> bar.getItems();
		case org.eclipse.swt.widgets.Menu menu -> menu.getItems();
		// the columns rather than the rows: a table's rows are data, its columns are
		// what a stylesheet talks about
		case org.eclipse.swt.widgets.Table table -> table.getColumns();
		case org.eclipse.swt.widgets.Tree tree -> tree.getColumns();
		default -> new Widget[0];
		};
	}

	/** The bounds of a control or of an item, {@code null} when the widget has none. */
	private static String bounds(Widget widget) {
		Rectangle rectangle = switch (widget) {
		case Control control -> control.getBounds();
		case org.eclipse.swt.widgets.ToolItem item -> item.getBounds();
		case org.eclipse.swt.custom.CTabItem item -> item.getBounds();
		case org.eclipse.swt.widgets.TabItem item -> item.getBounds();
		case org.eclipse.swt.widgets.CoolItem item -> item.getBounds();
		default -> null;
		};
		if (rectangle == null) {
			return null;
		}
		return rectangle.x + "," + rectangle.y + " " + rectangle.width + "x" + rectangle.height; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
	}

	/** The control a widget hangs under, which for an item is the widget that owns it. */
	private static Control parentOf(Widget widget) {
		return switch (widget) {
		case Control control -> control.getParent();
		case org.eclipse.swt.widgets.ToolItem item -> item.getParent();
		case org.eclipse.swt.custom.CTabItem item -> item.getParent();
		case org.eclipse.swt.widgets.TabItem item -> item.getParent();
		case org.eclipse.swt.widgets.CoolItem item -> item.getParent();
		case org.eclipse.swt.widgets.TableColumn column -> column.getParent();
		case org.eclipse.swt.widgets.TreeColumn column -> column.getParent();
		default -> null;
		};
	}

	/** Whether a widget is a control or an item, because the two are not interchangeable. */
	private static String kindOf(Widget widget) {
		return widget instanceof Control ? "control" : "item"; //$NON-NLS-1$ //$NON-NLS-2$
	}

	/** Lists the widgets of a part, so their paths can be read off rather than guessed. */
	public static final class GetWidgetTree implements IMcpTool {

		@Override
		public String getName() {
			return "eclipse_get_widget_tree"; //$NON-NLS-1$
		}

		@Override
		public String getDescription() {
			return "Lists the SWT widget hierarchy of a part or a shell, with each widget's class, bounds, CSS id and CSS class, and the path that addresses it. Changes nothing. This is the answer to 'what am I actually looking at', which otherwise has to be inferred from a screenshot or from reading somebody else's source, and it is where the paths for eclipse_inspect_widget come from. Filter by class to ask a narrow question, such as which Trees a view contains and what their ids are. Paths are slash separated indices, which is deliberate: most SWT widgets have no stable name, while an index survives a resize and a restart in a way screen coordinates do not. Set includeItems to enumerate Items as well, the buttons of a toolbar above all: a ToolItem is not a Control, so it appears in no walk over the control hierarchy, while the CSS engine styles each one as its own element."; //$NON-NLS-1$
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
					    "includeItems": {"type":"boolean","default":false,"description":"Also enumerate Items, which are not Controls and are therefore in no plain walk: ToolItems, CTabItems, TabItems, CoolItems, MenuItems and the columns of a Table or Tree. Their paths carry an i prefix, as in 2/i0, and that is the only way eclipse_inspect_widget can address one. Off by default because a Menu can be large."},
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
					args.getBoolean("includeToolbar", false), args.getBoolean("includeItems", false))); //$NON-NLS-1$ //$NON-NLS-2$
		}

		private static JsonObject collect(String partId, String shellTitle, String path, String filter, int maxDepth,
				int maxResults, boolean includeToolbar, boolean includeItems) {
			Control root = rootOf(partId, shellTitle, includeToolbar);
			if (root == null) {
				return new JsonObject().put("found", Boolean.FALSE) //$NON-NLS-1$
						.put("reason", //$NON-NLS-1$
								"No such part or shell, or the part is not open. Use eclipse_list_ui_targets.");
			}
			Widget start = resolve(root, path);
			if (start == null) {
				return new JsonObject().put("found", Boolean.FALSE) //$NON-NLS-1$
						.put("reason", "The path '%s' does not resolve under this part.".formatted(path)); //$NON-NLS-1$ //$NON-NLS-2$
			}
			String needle = filter == null ? null : filter.toLowerCase(Locale.ROOT);
			JsonArray widgets = new JsonArray();
			int[] total = { 0 };
			walk(start, path == null ? "" : path.strip(), 0, maxDepth, needle, maxResults, includeItems, widgets, //$NON-NLS-1$
					total);
			return new JsonObject().put("found", Boolean.TRUE) //$NON-NLS-1$
					.put("root", start.getClass().getName()) //$NON-NLS-1$
					.put("total", Integer.valueOf(total[0])) //$NON-NLS-1$
					.put("truncated", Boolean.valueOf(total[0] > widgets.size())) //$NON-NLS-1$
					.put("widgets", widgets); //$NON-NLS-1$
		}

		private static void walk(Widget widget, String path, int depth, int maxDepth, String needle, int maxResults,
				boolean includeItems, JsonArray into, int[] total) {
			boolean wanted = needle == null
					|| widget.getClass().getSimpleName().toLowerCase(Locale.ROOT).contains(needle);
			if (wanted) {
				total[0]++;
				if (into.size() < maxResults) {
					into.add(CssStyling.describe(widget).put("path", path.isEmpty() ? "/" : path) //$NON-NLS-1$ //$NON-NLS-2$
							.put("kind", kindOf(widget)) //$NON-NLS-1$
							.put("class", widget.getClass().getName()) //$NON-NLS-1$
							.put("text", textOf(widget)) //$NON-NLS-1$
							.put("bounds", bounds(widget)) //$NON-NLS-1$
							.put("visible", widget instanceof Control control ? Boolean.valueOf(control.isVisible()) //$NON-NLS-1$
									: null));
				}
			}
			if (depth >= maxDepth) {
				return;
			}
			if (includeItems) {
				Widget[] items = itemsOf(widget);
				for (int i = 0; i < items.length; i++) {
					walk(items[i], path.isEmpty() ? "i" + i : path + "/i" + i, depth + 1, maxDepth, needle, //$NON-NLS-1$ //$NON-NLS-2$
							maxResults, includeItems, into, total);
				}
			}
			if (!(widget instanceof Composite composite)) {
				return;
			}
			Control[] children = composite.getChildren();
			for (int i = 0; i < children.length; i++) {
				walk(children[i], path.isEmpty() ? String.valueOf(i) : path + "/" + i, depth + 1, maxDepth, needle, //$NON-NLS-1$
						maxResults, includeItems, into, total);
			}
		}

		/** An item's label, which is usually the only readable thing about it. */
		private static String textOf(Widget widget) {
			return widget instanceof org.eclipse.swt.widgets.Item item
					&& item.getText() != null && !item.getText().isEmpty() ? item.getText() : null;
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
			return "Reports one widget: its SWT class, the CSS element it maps to, its CSS id and classes, its bounds, its ancestor chain, and what the CSS engine resolved for each property asked about. Changes nothing. This is what turns a colour on screen into an explanation, and in particular it shows a '#token' reference that resolved to nothing, which otherwise only shows up as something rendering black or white. Address the widget with a path from eclipse_get_widget_tree, an i prefixed segment for an item. A ToolItem is reachable this way and only this way, which is what makes the pseudo parameter usable for a question like whether a toggle computes differently when checked. Read 'computed' together with 'declared': computed is the widget's live value, which the engine reads back from SWT and which therefore always answers something, so a colour there is no evidence that a rule set it; declared is what the matching rules set for this element, cssDeclaration is that whole merged declaration, and origin says 'css' or 'widget' per property. That is how a themed colour is told apart from the window system's default, and how an 'inherit' that resolved to the wrong parent is spotted. Which rule and which stylesheet it came from is still not reported: the engine keeps the merged declaration and not its source. A control that paints a background image reports backgroundImage, and on such a control nothing reported here is what is on screen: a CTabFolder handing its children a gradient clears their background first, so the computed colour is the window system's default rather than the theme's, and reading it as the colour under the image is the mistake this flag exists to prevent. Use eclipse_apply_css to test a rule against the running IDE and inspect again."; //$NON-NLS-1$
		}

		@Override
		public String getInputSchema() {
			return """
					{
					  "type": "object",
					  "properties": {
					    "part":       {"type":"string","description":"Part id, from eclipse_list_ui_targets. Omit to address a shell instead."},
					    "shellTitle": {"type":"string","description":"Shell when no part is given."},
					    "path":       {"type":"string","description":"Slash separated indices from eclipse_get_widget_tree, e.g. '0/2/1'. An i prefixed segment is an item rather than a child control, as in '2/i0' for the first button of a toolbar. Omit for the root."},
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
			// items included unconditionally here: a path that names one is the caller
			// saying it wants that item, and refusing it for want of a flag is noise
			Control root = rootOf(partId, shellTitle, includeToolbar);
			if (root == null) {
				return new JsonObject().put("found", Boolean.FALSE) //$NON-NLS-1$
						.put("reason", //$NON-NLS-1$
								"No such part or shell, or the part is not open. Use eclipse_list_ui_targets.");
			}
			Widget control = resolve(root, path);
			if (control == null) {
				return new JsonObject().put("found", Boolean.FALSE) //$NON-NLS-1$
						.put("reason", //$NON-NLS-1$
								"The path '%s' does not resolve. Read a current one from eclipse_get_widget_tree."
										.formatted(path));
			}
			JsonArray ancestors = new JsonArray();
			for (Control parent = parentOf(control); parent != null; parent = parent.getParent()) {
				ancestors.add(CssStyling.describe(parent).put("class", parent.getClass().getName())); //$NON-NLS-1$
			}
			JsonObject result = CssStyling.describe(control).put("found", Boolean.TRUE) //$NON-NLS-1$
					.put("path", path == null || path.isBlank() ? "/" : path.strip()) //$NON-NLS-1$ //$NON-NLS-2$
					.put("kind", kindOf(control)) //$NON-NLS-1$
					.put("class", control.getClass().getName()) //$NON-NLS-1$
					.put("bounds", bounds(control)) //$NON-NLS-1$
					.put("visible", control instanceof Control visible ? Boolean.valueOf(visible.isVisible()) : null) //$NON-NLS-1$
					.put("pseudo", pseudo); //$NON-NLS-1$
			CssStyling.styles(control, properties, pseudo, result);
			if (control instanceof Control painted && painted.getBackgroundImage() != null) {
				// the image is on screen and the reported colour is not, and it is not the
				// colour under the image either: CTabFolder.updateBkImages clears it
				result.put("backgroundImage", Boolean.TRUE) //$NON-NLS-1$
						.put("backgroundImageNote", //$NON-NLS-1$
								"This control paints a background image, so nothing reported here is what is on screen. Do not read the computed background-color as the colour under the image either: a CTabFolder handing its children a gradient calls setBackground(null) on each of them first, so the value read back is the window system's default for that widget and has no relationship to the theme. Three different colours are then in play for one widget, and the computed one is the least relevant.");
			}
			return result.put("ancestors", ancestors) //$NON-NLS-1$
					.put("matchedRulesNote", //$NON-NLS-1$
							"Which rules matched, and from which stylesheet, is not reported: the engine keeps the merged declaration and not the rules it came from. 'declared' is that declaration, so it says whether a rule decided the property; finding the rule itself still means reading the stylesheets.");
		}
	}
}
