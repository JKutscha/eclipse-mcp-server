package com.vogella.eclipse.mcp.pde.internal;

import java.util.Map;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.pde.core.target.ITargetDefinition;
import org.eclipse.pde.core.target.ITargetHandle;
import org.eclipse.pde.core.target.ITargetPlatformService;

import com.vogella.eclipse.mcp.core.IMcpTool;
import com.vogella.eclipse.mcp.core.McpToolResult;
import com.vogella.eclipse.mcp.core.ToolArguments;
import com.vogella.eclipse.mcp.core.json.JsonArray;
import com.vogella.eclipse.mcp.core.json.JsonObject;

/**
 * Reports the active target platform and the target definitions the IDE knows.
 */
public final class GetTargetPlatformTool implements IMcpTool {

	@Override
	public String getName() {
		return "eclipse_get_target_platform"; //$NON-NLS-1$
	}

	@Override
	public String getDescription() {
		return "Reports the active target platform: which target definition is loaded, whether it resolved, its locations with the status of each, how many bundles and features it contributes, and the bundles that failed to resolve. Reports a load started by eclipse_set_target_platform as lastLoad, which is how a resolve that outlived its call is followed. Can also list the target definitions the IDE knows, so one of them can be handed to eclipse_set_target_platform. Read-only. This is the answer to 'why does every plug-in in this workspace have unresolved dependencies', which no manifest shows."; //$NON-NLS-1$
	}

	@Override
	public String getInputSchema() {
		return """
				{
				  "type": "object",
				  "properties": {
				    "includeLocations": {"type":"boolean","default":true,"description":"List the locations of the active definition with the resolution status of each."},
				    "includeKnown":     {"type":"boolean","default":false,"description":"Also list the target definitions the IDE knows, workspace .target files included, with the memento that names each one."},
				    "maxProblems":      {"type":"integer","default":50,"minimum":1,"maximum":1000,"description":"Cap on the reported bundles that failed to resolve."}
				  },
				  "additionalProperties": false
				}"""; //$NON-NLS-1$
	}

	@Override
	public McpToolResult call(Map<String, Object> arguments, IProgressMonitor monitor) {
		ToolArguments args = ToolArguments.of(arguments);
		boolean includeLocations = args.getBoolean("includeLocations", true); //$NON-NLS-1$
		boolean includeKnown = args.getBoolean("includeKnown", false); //$NON-NLS-1$
		int maxProblems = args.getInt("maxProblems", 50, 1, 1000); //$NON-NLS-1$

		return TargetPlatforms.with(service -> {
			JsonObject result = new JsonObject();
			ITargetHandle activeHandle;
			ITargetDefinition active;
			try {
				// the handle, not the definition: PDE answers getWorkspaceTargetDefinition
				// with an empty default when nothing is set, and only the handle is null then
				activeHandle = service.getWorkspaceTargetHandle();
				active = service.getWorkspaceTargetDefinition();
			} catch (CoreException e) {
				return McpToolResult.error("Could not read the active target platform: " + e.getMessage()); //$NON-NLS-1$
			}
			result.put("targetSet", activeHandle != null); //$NON-NLS-1$
			if (activeHandle == null) {
				result.put("note", //$NON-NLS-1$
						"No target definition is set, so PDE compiles against the IDE's own installation."); //$NON-NLS-1$
			}
			result.put("active", active == null ? null : TargetPlatforms.describe(active, includeLocations, maxProblems)); //$NON-NLS-1$
			if (active != null && !active.isResolved()) {
				result.put("resolveNote", //$NON-NLS-1$
						"This definition is not resolved in this session, so bundle counts and per bundle problems are missing. eclipse_set_target_platform resolves and loads it."); //$NON-NLS-1$
			}
			TargetLoad load = TargetLoad.current();
			if (load != null) {
				result.put("lastLoad", load.toJson(includeLocations, maxProblems)); //$NON-NLS-1$
			}
			if (includeKnown) {
				result.put("known", known(service, monitor)); //$NON-NLS-1$
			}
			return McpToolResult.of(result.toString());
		});
	}

	private static JsonArray known(ITargetPlatformService service, IProgressMonitor monitor) {
		JsonArray targets = new JsonArray();
		String activeMemento = null;
		try {
			activeMemento = TargetPlatforms.memento(service.getWorkspaceTargetHandle());
		} catch (CoreException e) {
			// leave the active flag off rather than failing the whole listing
		}
		ITargetHandle[] handles = service.getTargets(monitor);
		for (ITargetHandle handle : handles == null ? new ITargetHandle[0] : handles) {
			String memento = TargetPlatforms.memento(handle);
			JsonObject entry = new JsonObject().put("memento", memento) //$NON-NLS-1$
					.put("active", memento != null && memento.equals(activeMemento)); //$NON-NLS-1$
			try {
				entry.put("name", handle.getTargetDefinition().getName()); //$NON-NLS-1$
			} catch (CoreException e) {
				// a .target file that does not parse still deserves its memento in the list
				entry.put("name", null).put("unreadable", e.getMessage()); //$NON-NLS-1$ //$NON-NLS-2$
			}
			targets.add(entry);
		}
		return targets;
	}
}
