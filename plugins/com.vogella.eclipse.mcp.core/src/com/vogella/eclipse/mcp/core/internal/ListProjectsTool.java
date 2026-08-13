package com.vogella.eclipse.mcp.core.internal;

import java.util.Map;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IProgressMonitor;

import com.vogella.eclipse.mcp.core.IMcpTool;
import com.vogella.eclipse.mcp.core.McpToolException;
import com.vogella.eclipse.mcp.core.McpToolResult;
import com.vogella.eclipse.mcp.core.json.JsonArray;
import com.vogella.eclipse.mcp.core.json.JsonObject;

/**
 * Reports the projects of the workspace.
 */
public final class ListProjectsTool implements IMcpTool {

	@Override
	public String getName() {
		return "eclipse_list_projects"; //$NON-NLS-1$
	}

	@Override
	public String getDescription() {
		return "Lists the projects in the Eclipse workspace, with their natures and open/closed state."; //$NON-NLS-1$
	}

	@Override
	public String getInputSchema() {
		return """
				{"type":"object","properties":{},"additionalProperties":false}"""; //$NON-NLS-1$
	}

	@Override
	public McpToolResult call(Map<String, Object> arguments, IProgressMonitor monitor) throws McpToolException {
		JsonArray projects = new JsonArray();
		for (IProject project : ResourcesPlugin.getWorkspace().getRoot().getProjects()) {
			JsonObject entry = new JsonObject();
			entry.put("name", project.getName()); //$NON-NLS-1$
			entry.put("open", project.isOpen()); //$NON-NLS-1$
			if (project.isOpen()) {
				JsonArray natures = new JsonArray();
				try {
					for (String nature : project.getDescription().getNatureIds()) {
						natures.add(nature);
					}
				} catch (CoreException e) {
					throw new McpToolException("Could not read the natures of project " + project.getName(), e); //$NON-NLS-1$
				}
				entry.put("natures", natures); //$NON-NLS-1$
			}
			IPath location = project.getLocation();
			entry.put("location", location == null ? null : location.toOSString()); //$NON-NLS-1$
			projects.add(entry);
		}
		return McpToolResult.of(new JsonObject().put("projects", projects).toString()); //$NON-NLS-1$
	}
}
