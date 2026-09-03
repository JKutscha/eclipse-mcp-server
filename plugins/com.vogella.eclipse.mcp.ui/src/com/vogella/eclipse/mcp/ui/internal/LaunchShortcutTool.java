package com.vogella.eclipse.mcp.ui.internal;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IConfigurationElement;
import org.eclipse.core.runtime.IExtensionRegistry;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.Platform;
import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.debug.core.ILaunchConfiguration;
import org.eclipse.debug.ui.ILaunchShortcut;
import org.eclipse.jface.viewers.StructuredSelection;

import com.vogella.eclipse.mcp.core.IMcpTool;
import com.vogella.eclipse.mcp.core.McpToolResult;
import com.vogella.eclipse.mcp.core.ToolArguments;
import com.vogella.eclipse.mcp.core.json.JsonArray;
import com.vogella.eclipse.mcp.core.json.JsonObject;

/**
 * Invokes a Run As / Debug As launch shortcut directly, for the launch
 * configuration types that have one and nothing else: no command id, no
 * saved configuration, and no generic launcher can start them.
 * <p>
 * A shortcut contributed through {@code org.eclipse.debug.ui.launchShortcuts}
 * is a menu action, not a command: the platform registers a command handler
 * for it only per shortcut id and launch mode ({@code <shortcutId>.<mode>}),
 * and even that handler reads the IDE's own ambient selection rather than one
 * a caller supplies, so it cannot be driven from here with a selection built
 * for the occasion. This tool goes around both gaps by reading the extension
 * point directly, the same information the Run As submenu is built from, and
 * calling the shortcut's own {@link ILaunchShortcut#launch(org.eclipse.jface.viewers.ISelection, String)}
 * with a selection resolved from workspace paths, which is what a person
 * clicking the menu item effectively hands it.
 */
public final class LaunchShortcutTool implements IMcpTool {

	private static final String EXTENSION_POINT = "org.eclipse.debug.ui.launchShortcuts"; //$NON-NLS-1$
	private static final String SHORTCUT_ELEMENT = "shortcut"; //$NON-NLS-1$
	private static final String CONFIGURATION_TYPE_ELEMENT = "configurationType"; //$NON-NLS-1$

	@Override
	public String getName() {
		return "eclipse_run_launch_shortcut"; //$NON-NLS-1$
	}

	@Override
	public String getDescription() {
		return "Invokes a Run As / Debug As launch shortcut for a selection, which is the only way to start a launch configuration type whose only entry point is a menu action rather than a command or a saved configuration. RUNS PROJECT CODE, exactly as clicking the equivalent Run As menu item would, and a launch shortcut is free to open a dialog, the way an ambiguous main type or an unsaved editor prompt would. A shortcut is identified by 'shortcutId', by 'label' (a case-insensitive substring of the menu label, e.g. what eclipse_get_context_menu reports under 'Run As'), or by 'launchConfigurationTypeId' (the type id eclipse_list_launch_configurations reports, matched against the shortcut's own declared configuration types); give exactly one of the three. More than one shortcut matching is reported as candidates rather than guessed. 'elements' takes workspace paths or project names, resolved the same way eclipse_set_selection resolves them, and built into the selection the shortcut receives; most shortcuts resolve a Java type, a project or a resource out of it. Runs as a dry run unless dryRun is set to false, reporting the matched shortcut and the resolved selection without invoking anything. THE LAUNCH ITSELF IS ASYNCHRONOUS: invoking the shortcut only starts it, the way clicking the menu item only starts it, so poll eclipse_debug_status for the session and eclipse_list_launch_configurations for a configuration the shortcut created, exactly as after any other launch; results do not flow to eclipse_get_test_results unless the shortcut happens to be a JUnit one, since that tool only tracks runs eclipse_run_tests itself started."; //$NON-NLS-1$
	}

	@Override
	public String getInputSchema() {
		return """
				{
				  "type": "object",
				  "properties": {
				    "elements": {"type":"array","items":{"type":"string"},"description":"Workspace paths ('/my.project/src/Example.java') or project names ('my.project') to build the selection from. At least one must resolve."},
				    "mode": {"type":"string","enum":["run","debug"],"default":"run","description":"Launch mode. Only shortcuts that declare support for this mode are matched."},
				    "shortcutId": {"type":"string","description":"Exact id of the launchShortcuts extension, when known."},
				    "label": {"type":"string","description":"Case-insensitive substring of the shortcut's menu label, e.g. what appears under Run As."},
				    "launchConfigurationTypeId": {"type":"string","description":"Match the shortcut(s) that declare this launch configuration type, e.g. from eclipse_list_launch_configurations."},
				    "timeoutSeconds": {"type":"integer","minimum":1,"maximum":120,"default":20,"description":"How long to wait for the shortcut's own launch(...) call to return. The launched process itself is not waited for."},
				    "dryRun": {"type":"boolean","default":true,"description":"Report the matched shortcut and the resolved selection without invoking it."}
				  },
				  "required": ["elements"],
				  "additionalProperties": false
				}"""; //$NON-NLS-1$
	}

	@Override
	public McpToolResult call(Map<String, Object> arguments, IProgressMonitor monitor) {
		ToolArguments args = ToolArguments.of(arguments);
		if (!(arguments != null && arguments.get("elements") instanceof List<?> requested) || requested.isEmpty()) { //$NON-NLS-1$
			return McpToolResult.error("Give 'elements' as a non-empty array of workspace paths or project names."); //$NON-NLS-1$
		}
		String mode = args.getString("mode", "run"); //$NON-NLS-1$ //$NON-NLS-2$
		if (!"run".equals(mode) && !"debug".equals(mode)) { //$NON-NLS-1$ //$NON-NLS-2$
			return McpToolResult.error("mode must be 'run' or 'debug'."); //$NON-NLS-1$
		}
		String shortcutId = args.getString("shortcutId"); //$NON-NLS-1$
		String typeId = args.getString("launchConfigurationTypeId"); //$NON-NLS-1$
		String label = args.getString("label"); //$NON-NLS-1$
		int given = (shortcutId != null ? 1 : 0) + (typeId != null ? 1 : 0) + (label != null ? 1 : 0);
		if (given != 1) {
			return McpToolResult.error(
					"Give exactly one of 'shortcutId', 'label' or 'launchConfigurationTypeId' to identify the shortcut."); //$NON-NLS-1$
		}
		List<String> specs = new ArrayList<>();
		requested.forEach(value -> specs.add(String.valueOf(value)));
		JsonArray unresolved = new JsonArray();
		List<Object> elements = new ArrayList<>();
		for (String spec : specs) {
			Object resolved = SelectionTools.resolveResource(spec);
			if (resolved == null) {
				unresolved.add(spec);
			} else {
				elements.add(resolved);
			}
		}
		if (elements.isEmpty()) {
			return McpToolResult.error("None of the given elements resolved to a workspace resource."); //$NON-NLS-1$
		}
		List<Candidate> matches = findShortcuts(mode, shortcutId, typeId, label);
		if (matches.isEmpty()) {
			return McpToolResult.error("No launch shortcut for mode '%s' matches %s.".formatted(mode, //$NON-NLS-1$
					criteria(shortcutId, typeId, label)));
		}
		if (matches.size() > 1) {
			JsonArray candidates = new JsonArray();
			matches.forEach(candidate -> candidates.add(candidate.describe()));
			return McpToolResult.of(new JsonObject().put("total", Integer.valueOf(matches.size())) //$NON-NLS-1$
					.put("candidates", candidates) //$NON-NLS-1$
					.put("note", "More than one shortcut matched; narrow with 'shortcutId'.") //$NON-NLS-1$ //$NON-NLS-2$
					.toString());
		}
		Candidate match = matches.get(0);
		boolean dryRun = args.getBoolean("dryRun", true); //$NON-NLS-1$
		JsonArray describedElements = describeElements(elements);
		if (dryRun) {
			return McpToolResult.of(new JsonObject().put("dryRun", Boolean.TRUE) //$NON-NLS-1$
					.put("matched", match.describe()) //$NON-NLS-1$
					.put("elements", describedElements) //$NON-NLS-1$
					.put("mode", mode) //$NON-NLS-1$
					.put("unresolved", unresolved) //$NON-NLS-1$
					.toString());
		}
		List<String> before = configurationNames();
		int timeoutSeconds = args.getInt("timeoutSeconds", 20, 1, 120); //$NON-NLS-1$
		UiThread.Outcome outcome = UiThread.run(timeoutSeconds, () -> {
			ILaunchShortcut shortcut;
			try {
				shortcut = (ILaunchShortcut) match.element.createExecutableExtension("class"); //$NON-NLS-1$
			} catch (CoreException e) {
				throw new IllegalStateException("Could not instantiate the shortcut: " + e.getMessage(), e); //$NON-NLS-1$
			}
			shortcut.launch(new StructuredSelection(elements), mode);
			return new JsonObject().put("invoked", Boolean.TRUE); //$NON-NLS-1$
		});
		if (outcome.error() != null) {
			return McpToolResult.error(outcome.error());
		}
		List<String> created = new ArrayList<>(configurationNames());
		created.removeAll(before);
		JsonArray createdArray = new JsonArray();
		created.forEach(createdArray::add);
		return McpToolResult.of(new JsonObject().put("matched", match.describe()) //$NON-NLS-1$
				.put("elements", describedElements) //$NON-NLS-1$
				.put("mode", mode) //$NON-NLS-1$
				.put("unresolved", unresolved) //$NON-NLS-1$
				.put("createdConfigurations", createdArray) //$NON-NLS-1$
				.put("note", //$NON-NLS-1$
						"The shortcut was invoked; the launch itself runs asynchronously, the way clicking the menu item would. Poll eclipse_debug_status for the session and eclipse_list_launch_configurations for the configuration the shortcut created or reused.") //$NON-NLS-1$
				.toString());
	}

	private static String criteria(String shortcutId, String typeId, String label) {
		if (shortcutId != null) {
			return "shortcutId '%s'".formatted(shortcutId); //$NON-NLS-1$
		}
		if (typeId != null) {
			return "launchConfigurationTypeId '%s'".formatted(typeId); //$NON-NLS-1$
		}
		return "label containing '%s'".formatted(label); //$NON-NLS-1$
	}

	private static JsonArray describeElements(List<Object> elements) {
		JsonArray described = new JsonArray();
		elements.forEach(element -> described.add(SelectionTools.describe(element)));
		return described;
	}

	/** The launch configuration names that exist right now, to diff against afterwards. */
	private static List<String> configurationNames() {
		List<String> names = new ArrayList<>();
		try {
			for (ILaunchConfiguration configuration : DebugPlugin.getDefault().getLaunchManager()
					.getLaunchConfigurations()) {
				names.add(configuration.getName());
			}
		} catch (CoreException e) {
			// reported as no configurations created rather than failing the call
		}
		return names;
	}

	/**
	 * Reads the {@code launchShortcuts} extension point directly with plain
	 * {@link IConfigurationElement} calls, the same information the Run As
	 * submenu is populated from, without touching the internal registry class
	 * that actually backs that menu.
	 */
	private static List<Candidate> findShortcuts(String mode, String shortcutId, String typeId, String label) {
		List<Candidate> result = new ArrayList<>();
		IExtensionRegistry registry = Platform.getExtensionRegistry();
		if (registry == null) {
			return result;
		}
		for (IConfigurationElement element : registry.getConfigurationElementsFor(EXTENSION_POINT)) {
			if (!SHORTCUT_ELEMENT.equals(element.getName())) {
				continue;
			}
			String id = element.getAttribute("id"); //$NON-NLS-1$
			String elementLabel = element.getAttribute("label"); //$NON-NLS-1$
			String modesAttribute = element.getAttribute("modes"); //$NON-NLS-1$
			List<String> modes = modesAttribute == null ? List.of()
					: Arrays.stream(modesAttribute.split(",")).map(String::strip).toList(); //$NON-NLS-1$
			if (!modes.contains(mode)) {
				continue;
			}
			List<String> configurationTypes = new ArrayList<>();
			for (IConfigurationElement child : element.getChildren(CONFIGURATION_TYPE_ELEMENT)) {
				String childId = child.getAttribute("id"); //$NON-NLS-1$
				if (childId != null) {
					configurationTypes.add(childId);
				}
			}
			boolean matches;
			if (shortcutId != null) {
				matches = shortcutId.equals(id);
			} else if (typeId != null) {
				matches = configurationTypes.contains(typeId);
			} else {
				matches = elementLabel != null
						&& elementLabel.toLowerCase(Locale.ROOT).contains(label.toLowerCase(Locale.ROOT));
			}
			if (matches) {
				result.add(new Candidate(element, id, elementLabel, modes, configurationTypes));
			}
		}
		return result;
	}

	/** One matched {@code <shortcut>} extension, kept alive to instantiate on demand. */
	private record Candidate(IConfigurationElement element, String id, String label, List<String> modes,
			List<String> configurationTypes) {

		JsonObject describe() {
			JsonArray modesArray = new JsonArray();
			modes.forEach(modesArray::add);
			JsonArray typesArray = new JsonArray();
			configurationTypes.forEach(typesArray::add);
			return new JsonObject().put("id", id) //$NON-NLS-1$
					.put("label", label) //$NON-NLS-1$
					.put("modes", modesArray) //$NON-NLS-1$
					.put("configurationTypes", typesArray); //$NON-NLS-1$
		}
	}
}
