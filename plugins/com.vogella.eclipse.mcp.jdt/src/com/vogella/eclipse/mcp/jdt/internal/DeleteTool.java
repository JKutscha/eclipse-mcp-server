package com.vogella.eclipse.mcp.jdt.internal;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.search.IJavaSearchConstants;
import org.eclipse.jdt.core.search.IJavaSearchScope;
import org.eclipse.jdt.core.search.SearchEngine;
import org.eclipse.jdt.core.search.SearchMatch;
import org.eclipse.jdt.core.search.SearchParticipant;
import org.eclipse.jdt.core.search.SearchPattern;
import org.eclipse.jdt.core.search.SearchRequestor;
import org.eclipse.ltk.core.refactoring.Change;
import org.eclipse.ltk.core.refactoring.PerformChangeOperation;
import org.eclipse.ltk.core.refactoring.Refactoring;
import org.eclipse.ltk.core.refactoring.RefactoringStatus;
import org.eclipse.ltk.core.refactoring.resource.DeleteResourcesDescriptor;

import com.vogella.eclipse.mcp.core.IMcpTool;
import com.vogella.eclipse.mcp.core.McpToolException;
import com.vogella.eclipse.mcp.core.McpToolResult;
import com.vogella.eclipse.mcp.core.ToolArguments;
import com.vogella.eclipse.mcp.core.json.JsonArray;
import com.vogella.eclipse.mcp.core.json.JsonObject;

/**
 * Deletes the compilation unit of a Java type, and says what it does not clean up.
 */
public final class DeleteTool implements IMcpTool {

	@Override
	public String getName() {
		return "eclipse_delete"; //$NON-NLS-1$
	}

	@Override
	public String getDescription() {
		return "Deletes the source file declaring a Java type, as the last step of a dead code sweep. DELETES A FILE FROM THE WORKSPACE, and runs as a dry run unless dryRun is set to false. It refuses, unless force is passed, when anything still references the type, when a registry position names it, or when its package is exported as public API, and it reports all three either way. It counts an e4 application model as a registry position: a class named by a bundleclass:// URI in a .e4xmi is instantiated by the workbench at every start and referenced from no Java, so it looks dead and is not. READ THIS LIMITATION: the deletion goes through LTK as a resource delete, and PDE's manifest participants are enabled on IType rather than on IResource, so plugin.xml class attributes and Export-Package are NOT updated. Whatever registryEvidence the answer reports is what will be left dangling and has to be fixed by hand. Use eclipse_list_declarations to find candidates and eclipse_find_references to confirm them."; //$NON-NLS-1$
	}

	@Override
	public String getInputSchema() {
		return """
				{
				  "type": "object",
				  "required": ["typeName"],
				  "properties": {
				    "typeName": {"type":"string","description":"Fully qualified name of the type whose file to delete."},
				    "project":  {"type":"string","description":"Project to resolve the name in. Omit to search every Java project."},
				    "dryRun":   {"type":"boolean","default":true,"description":"Report what would happen and change nothing."},
				    "force":    {"type":"boolean","default":false,"description":"Delete despite references, a registry position, or a public API package."}
				  },
				  "additionalProperties": false
				}"""; //$NON-NLS-1$
	}

	@Override
	public McpToolResult call(Map<String, Object> arguments, IProgressMonitor monitor) throws McpToolException {
		ToolArguments args = ToolArguments.of(arguments);
		String typeName = args.getString("typeName"); //$NON-NLS-1$
		if (typeName == null) {
			return McpToolResult.error("The argument 'typeName' is required."); //$NON-NLS-1$
		}
		boolean dryRun = args.getBoolean("dryRun", true); //$NON-NLS-1$
		boolean force = args.getBoolean("force", false); //$NON-NLS-1$
		IProgressMonitor progress = monitor == null ? new NullProgressMonitor() : monitor;

		IType type;
		List<IJavaProject> projects;
		try {
			projects = JavaModelSupport.javaProjects(args.getString("project")); //$NON-NLS-1$
			type = JavaModelSupport.findType(typeName, projects, monitor);
		} catch (ToolInputException e) {
			return McpToolResult.error(e.getMessage());
		}
		if (type.isBinary() || type.getCompilationUnit() == null) {
			return McpToolResult.error(
					"'%s' is a binary type, so there is no source file here to delete.".formatted(typeName)); //$NON-NLS-1$
		}
		ICompilationUnit unit = type.getCompilationUnit();
		IResource resource = unit.getResource();
		if (!(resource instanceof IFile file) || !file.exists()) {
			return McpToolResult.error("'%s' has no file in this workspace.".formatted(typeName)); //$NON-NLS-1$
		}

		JsonObject result = new JsonObject().put("type", type.getFullyQualifiedName()) //$NON-NLS-1$
				.put("file", file.getFullPath().toString()) //$NON-NLS-1$
				.put("dryRun", Boolean.valueOf(dryRun)); //$NON-NLS-1$

		// deleting the file deletes every type in it, which for a unit declaring more
		// than one is not the deletion that was asked for
		List<String> alsoDeclared = new ArrayList<>();
		try {
			for (IType other : unit.getTypes()) {
				if (!other.getElementName().equals(type.getElementName())) {
					alsoDeclared.add(other.getFullyQualifiedName());
				}
			}
		} catch (CoreException e) {
			throw new McpToolException("Could not read " + file.getFullPath(), e); //$NON-NLS-1$
		}
		if (!alsoDeclared.isEmpty()) {
			JsonArray others = new JsonArray();
			alsoDeclared.forEach(others::add);
			return McpToolResult.of(result.put("deleted", Boolean.FALSE).put("alsoDeclaredInThisFile", others) //$NON-NLS-1$ //$NON-NLS-2$
					.put("refusedBecause", //$NON-NLS-1$
							"The file declares other top level types, and deleting it would delete them too. This tool deletes files, not declarations.") //$NON-NLS-1$
					.toString());
		}

		RegistryIndex index = RegistryIndex.build(progress);
		List<RegistryIndex.Evidence> evidence = index.evidenceFor(type.getFullyQualifiedName());
		PackageExports.Export export = new PackageExports().of(type.getJavaProject().getProject(),
				type.getPackageFragment().getElementName());
		int references = countReferences(type, progress);

		JsonArray registry = new JsonArray();
		for (RegistryIndex.Evidence one : evidence) {
			registry.add(new JsonObject().put("kind", one.kind()).put("file", one.file()) //$NON-NLS-1$ //$NON-NLS-2$
					.put("xpathOrHeader", one.position())); //$NON-NLS-1$
		}
		// deleting the last type of a package leaves an Export-Package naming a
		// package that no longer exists, which the next build reports against
		// MANIFEST.MF. Saying so lets the caller follow up with eclipse_edit_manifest
		boolean lastInPackage = false;
		try {
			lastInPackage = unit.getParent() instanceof org.eclipse.jdt.core.IPackageFragment fragment
					&& fragment.getCompilationUnits().length <= 1;
		} catch (CoreException e) {
			// a package that cannot be read is simply not reported as emptied
		}
		result.put("references", Integer.valueOf(references)) //$NON-NLS-1$
				.put("registryEvidence", registry) //$NON-NLS-1$
				.put("apiTier", export.tier()) //$NON-NLS-1$
				.put("lastTypeInPackage", Boolean.valueOf(lastInPackage)); //$NON-NLS-1$
		if (lastInPackage && !PackageExports.NOT_EXPORTED.equals(export.tier())
				&& !PackageExports.NOT_A_BUNDLE.equals(export.tier())) {
			result.put("packageBecomesEmpty", //$NON-NLS-1$
					"This is the last type in '%s', which the manifest still exports as %s. After deleting it, remove the export with eclipse_edit_manifest removeExportPackage or the next build reports 'Package does not exist in this plug-in'." //$NON-NLS-1$
							.formatted(type.getPackageFragment().getElementName(), export.tier()));
		}

		List<String> blockers = new ArrayList<>();
		if (references > 0) {
			blockers.add("%d reference(s) to it remain, so deleting it is a compile break rather than a cleanup"
					.formatted(Integer.valueOf(references)));
		}
		if (registry.size() > 0) {
			blockers.add(
					"%d registry position(s) name it, which this tool cannot update and which fail at runtime rather than at compile time"
							.formatted(Integer.valueOf(registry.size())));
		}
		if (PackageExports.PUBLIC.equals(export.tier())) {
			blockers.add(
					"its package is exported as public API, so consumers can exist outside this workspace and no search here can prove otherwise");
		}
		if (!blockers.isEmpty() && !force) {
			return McpToolResult.of(result.put("deleted", Boolean.FALSE) //$NON-NLS-1$
					.put("refusedBecause", String.join("; ", blockers) + ". Pass force to delete it anyway.") //$NON-NLS-1$ //$NON-NLS-2$
					.toString());
		}
		if (registry.size() > 0) {
			result.put("danglingAfterDelete", //$NON-NLS-1$
					"The registry positions above are NOT updated by this tool and will name a class that no longer exists. Fix them by hand."); //$NON-NLS-1$
		}

		if (dryRun) {
			return McpToolResult.of(result.put("deleted", Boolean.FALSE) //$NON-NLS-1$
					.put("note", "Nothing was changed. Pass dryRun false to delete the file.").toString()); //$NON-NLS-1$ //$NON-NLS-2$
		}
		try {
			DeleteResourcesDescriptor descriptor = new DeleteResourcesDescriptor();
			descriptor.setResources(new IResource[] { file });
			RefactoringStatus status = new RefactoringStatus();
			Refactoring refactoring = descriptor.createRefactoring(status);
			if (refactoring == null) {
				return McpToolResult.error("The delete refactoring could not be created: " + status.getMessageMatchingSeverity(RefactoringStatus.ERROR)); //$NON-NLS-1$
			}
			status.merge(refactoring.checkAllConditions(progress));
			if (status.hasFatalError() || status.hasError()) {
				return McpToolResult
						.error("Refused: " + status.getMessageMatchingSeverity(RefactoringStatus.ERROR)); //$NON-NLS-1$
			}
			Change change = refactoring.createChange(progress);
			Set<String> files = new LinkedHashSet<>();
			files.add(file.getFullPath().toString());
			change.initializeValidationData(progress);
			ResourcesPlugin.getWorkspace().run(new PerformChangeOperation(change), progress);
			JsonArray affected = new JsonArray();
			files.forEach(affected::add);
			return McpToolResult.of(result.put("deleted", Boolean.TRUE).put("affectedFiles", affected).toString()); //$NON-NLS-1$ //$NON-NLS-2$
		} catch (CoreException e) {
			throw new McpToolException("The delete failed", e); //$NON-NLS-1$
		}
	}

	/** References to the type anywhere in the workspace, excluding its own file. */
	private static int countReferences(IType type, IProgressMonitor monitor) throws McpToolException {
		SearchPattern pattern = SearchPattern.createPattern(type, IJavaSearchConstants.REFERENCES);
		if (pattern == null) {
			return 0;
		}
		IJavaSearchScope scope = SearchEngine.createWorkspaceScope();
		IResource own = type.getResource();
		int[] count = { 0 };
		try {
			new SearchEngine().search(pattern, new SearchParticipant[] { SearchEngine.getDefaultSearchParticipant() },
					scope, new SearchRequestor() {
						@Override
						public void acceptSearchMatch(SearchMatch match) {
							if (own == null || !own.equals(match.getResource())) {
								count[0]++;
							}
						}
					}, monitor);
		} catch (CoreException e) {
			throw new McpToolException("Could not search for references to " + type.getFullyQualifiedName(), e); //$NON-NLS-1$
		}
		return count[0];
	}
}
