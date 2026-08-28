package com.vogella.eclipse.mcp.core.internal;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IProjectDescription;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.Path;

import com.vogella.eclipse.mcp.core.IMcpTool;
import com.vogella.eclipse.mcp.core.McpToolResult;
import com.vogella.eclipse.mcp.core.ToolArguments;
import com.vogella.eclipse.mcp.core.json.JsonArray;
import com.vogella.eclipse.mcp.core.json.JsonObject;

/**
 * Imports projects that already exist on disk into the workspace.
 */
public final class ImportProjectTool implements IMcpTool {

	/** How deep to look for .project files below the named directory. */
	private static final int MAX_DEPTH = 6;

	@Override
	public String getName() {
		return "eclipse_import_project"; //$NON-NLS-1$
	}

	@Override
	public String getDescription() {
		return "Imports projects that already exist on disk into the workspace, which is what File > Import > Existing Projects into Workspace does. MODIFIES THE WORKSPACE, and runs as a dry run unless dryRun is set to false; nothing on disk is written or moved, the project stays where it is and the workspace gains an entry pointing at it. It works from the .project file: a directory that has one is imported, and with search it also looks below the directory for more. THAT IS THE LIMIT AND IT MATTERS HERE: a Maven or pomless Tycho module has no .project until m2e has imported it once, so this cannot bring one back, and eclipse_remove_project reports hasProjectFile so that is visible before removing rather than after. A name already taken in the workspace is reported and skipped rather than silently doing nothing, since two projects cannot share a name even when they are different directories."; //$NON-NLS-1$
	}

	@Override
	public String getInputSchema() {
		return """
				{
				  "type": "object",
				  "required": ["location"],
				  "properties": {
				    "location":   {"type":"string","description":"Absolute path of the project directory, or of a directory to look below when 'search' is set."},
				    "search":     {"type":"boolean","default":false,"description":"Look below the directory for .project files instead of expecting one directly in it. Nested projects are found, six levels deep at most."},
				    "open":       {"type":"boolean","default":true,"description":"Open each imported project. A closed project takes part in no build and shows no problems."},
				    "dryRun":     {"type":"boolean","default":true,"description":"Report what would be imported, including names already taken, and change nothing."},
				    "maxResults": {"type":"integer","default":100,"minimum":1,"maximum":1000}
				  },
				  "additionalProperties": false
				}"""; //$NON-NLS-1$
	}

	@Override
	public McpToolResult call(Map<String, Object> arguments, IProgressMonitor monitor) {
		ToolArguments args = ToolArguments.of(arguments);
		String location = args.getString("location"); //$NON-NLS-1$
		if (location == null) {
			return McpToolResult.error("The argument 'location' is required: the absolute path of the project directory."); //$NON-NLS-1$
		}
		File directory = new File(location);
		if (!directory.isDirectory()) {
			return McpToolResult.error("'%s' is not a directory on this machine.".formatted(location)); //$NON-NLS-1$
		}
		boolean search = args.getBoolean("search", false); //$NON-NLS-1$
		boolean open = args.getBoolean("open", true); //$NON-NLS-1$
		boolean dryRun = args.getBoolean("dryRun", true); //$NON-NLS-1$
		int maxResults = args.getInt("maxResults", 100, 1, 1000); //$NON-NLS-1$

		List<File> found = new ArrayList<>();
		if (search) {
			collect(directory, 0, found, maxResults);
		} else if (new File(directory, IProjectDescription.DESCRIPTION_FILE_NAME).isFile()) {
			found.add(directory);
		}
		if (found.isEmpty()) {
			return McpToolResult.error(search
					? "No .project file below '%s'. A Maven or pomless Tycho module has none until m2e imports it, and this tool cannot create one." //$NON-NLS-1$
							.formatted(location)
					: "'%s' has no .project file. Pass search true to look below it, or import it as a Maven project through the IDE if it has none at all." //$NON-NLS-1$
							.formatted(location));
		}

		IWorkspace workspace = ResourcesPlugin.getWorkspace();
		JsonArray results = new JsonArray();
		int imported = 0;
		for (File candidate : found) {
			JsonObject entry = new JsonObject().put("location", candidate.getAbsolutePath()); //$NON-NLS-1$
			IProjectDescription description;
			try {
				IPath descriptionFile = new Path(candidate.getAbsolutePath())
						.append(IProjectDescription.DESCRIPTION_FILE_NAME);
				description = workspace.loadProjectDescription(descriptionFile);
			} catch (CoreException e) {
				results.add(entry.put("imported", Boolean.FALSE).put("error", //$NON-NLS-1$ //$NON-NLS-2$
						"Its .project file could not be read: " + e.getMessage())); //$NON-NLS-1$
				continue;
			}
			entry.put("name", description.getName()); //$NON-NLS-1$
			IProject project = workspace.getRoot().getProject(description.getName());
			if (project.exists()) {
				results.add(entry.put("imported", Boolean.FALSE) //$NON-NLS-1$
						.put("alreadyInWorkspace", Boolean.TRUE) //$NON-NLS-1$
						.put("existingLocation", project.getLocation() == null ? null //$NON-NLS-1$
								: project.getLocation().toOSString())
						.put("note", //$NON-NLS-1$
								"A project of that name is already in the workspace. Names are unique, so this one cannot be imported until the other is removed with eclipse_remove_project.")); //$NON-NLS-1$
				continue;
			}
			if (dryRun) {
				results.add(entry.put("imported", Boolean.FALSE)); //$NON-NLS-1$
				continue;
			}
			try {
				// the location has to be in the description, or the platform creates the
				// project inside the workspace directory instead of pointing at this one
				description.setLocation(new Path(candidate.getAbsolutePath()));
				project.create(description, monitor);
				if (open) {
					project.open(monitor);
				}
				imported++;
				results.add(entry.put("imported", Boolean.TRUE).put("open", Boolean.valueOf(open))); //$NON-NLS-1$ //$NON-NLS-2$
			} catch (CoreException e) {
				results.add(entry.put("imported", Boolean.FALSE).put("error", e.getMessage())); //$NON-NLS-1$ //$NON-NLS-2$
			}
		}

		return McpToolResult.of(new JsonObject().put("dryRun", Boolean.valueOf(dryRun)) //$NON-NLS-1$
				.put("found", Integer.valueOf(found.size())) //$NON-NLS-1$
				.put("imported", Integer.valueOf(imported)) //$NON-NLS-1$
				.put("projects", results) //$NON-NLS-1$
				.put("note", dryRun //$NON-NLS-1$
						? "Nothing was imported. Pass dryRun false to carry it out." //$NON-NLS-1$
						: "The projects stay where they are on disk; the workspace only gained entries pointing at them. Build them with eclipse_build before reading problems, since an imported project has never been built here.") //$NON-NLS-1$
				.toString());
	}

	/** Directories holding a .project, not descending into one that does. */
	private static void collect(File directory, int depth, List<File> found, int max) {
		if (depth > MAX_DEPTH || found.size() >= max) {
			return;
		}
		if (new File(directory, IProjectDescription.DESCRIPTION_FILE_NAME).isFile()) {
			found.add(directory);
			return;
		}
		File[] children = directory.listFiles(File::isDirectory);
		if (children == null) {
			return;
		}
		for (File child : children) {
			if (!child.getName().startsWith(".")) { //$NON-NLS-1$
				collect(child, depth + 1, found, max);
			}
		}
	}
}
