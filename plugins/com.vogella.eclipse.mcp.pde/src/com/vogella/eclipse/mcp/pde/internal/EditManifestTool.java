package com.vogella.eclipse.mcp.pde.internal;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.osgi.service.resolver.BundleDescription;
import org.eclipse.osgi.service.resolver.ExportPackageDescription;
import org.eclipse.osgi.service.resolver.ImportPackageSpecification;
import org.eclipse.pde.core.plugin.IPluginModelBase;
import org.eclipse.pde.core.plugin.PluginRegistry;
import org.eclipse.pde.core.project.IBundleProjectDescription;
import org.eclipse.pde.core.project.IBundleProjectService;
import org.eclipse.pde.core.project.IPackageExportDescription;
import org.eclipse.pde.core.project.IPackageImportDescription;
import org.eclipse.pde.core.project.IRequiredBundleDescription;
import org.osgi.framework.BundleContext;
import org.osgi.framework.FrameworkUtil;
import org.osgi.framework.ServiceReference;
import org.osgi.framework.Version;
import org.osgi.framework.VersionRange;

import com.vogella.eclipse.mcp.core.IMcpTool;
import com.vogella.eclipse.mcp.core.McpToolException;
import com.vogella.eclipse.mcp.core.McpToolResult;
import com.vogella.eclipse.mcp.core.ToolArguments;
import com.vogella.eclipse.mcp.core.json.JsonArray;
import com.vogella.eclipse.mcp.core.json.JsonObject;

/**
 * Edits the OSGi headers of a plug-in project.
 */
public final class EditManifestTool implements IMcpTool {

	@Override
	public String getName() {
		return "eclipse_edit_manifest"; //$NON-NLS-1$
	}

	@Override
	public String getDescription() {
		return "Adds and removes Require-Bundle, Import-Package and Export-Package entries of a plug-in project, with their directives: version ranges, optional, reexport, x-internal and x-friends. MODIFIES MANIFEST.MF, and runs as a dry run unless dryRun is set to false. It goes through PDE's own project model rather than editing text, so the header is written the way PDE writes it, with the continuation lines and the byte-counted line folding correct. Removing an export or a required bundle that other workspace bundles still consume is REFUSED unless force is passed, and the consumers are reported either way: an export removed under a consumer still compiles here and fails to resolve at runtime, which is the failure this check exists to prevent."; //$NON-NLS-1$
	}

	@Override
	public String getInputSchema() {
		return """
				{
				  "type": "object",
				  "required": ["project"],
				  "properties": {
				    "project": {"type":"string","description":"Plug-in project whose manifest to edit."},
				    "addExportPackage": {"type":"array","items":{"type":"object","properties":{
				        "package":{"type":"string"},"version":{"type":"string"},
				        "internal":{"type":"boolean","description":"Write x-internal:=true."},
				        "friends":{"type":"array","items":{"type":"string"},"description":"Write x-friends."}},
				        "required":["package"],"additionalProperties":false}},
				    "removeExportPackage": {"type":"array","items":{"type":"string"}},
				    "addRequireBundle": {"type":"array","items":{"type":"object","properties":{
				        "bundle":{"type":"string"},"versionRange":{"type":"string"},
				        "optional":{"type":"boolean"},"reexport":{"type":"boolean"}},
				        "required":["bundle"],"additionalProperties":false}},
				    "removeRequireBundle": {"type":"array","items":{"type":"string"}},
				    "addImportPackage": {"type":"array","items":{"type":"object","properties":{
				        "package":{"type":"string"},"versionRange":{"type":"string"},"optional":{"type":"boolean"}},
				        "required":["package"],"additionalProperties":false}},
				    "removeImportPackage": {"type":"array","items":{"type":"string"}},
				    "dryRun": {"type":"boolean","default":true},
				    "force":  {"type":"boolean","default":false,"description":"Remove an export or a required bundle that workspace bundles still consume."}
				  },
				  "additionalProperties": false
				}"""; //$NON-NLS-1$
	}

	@Override
	public McpToolResult call(Map<String, Object> arguments, IProgressMonitor monitor) throws McpToolException {
		ToolArguments args = ToolArguments.of(arguments);
		String projectName = args.getString("project"); //$NON-NLS-1$
		if (projectName == null) {
			return McpToolResult.error("The argument 'project' is required."); //$NON-NLS-1$
		}
		IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
		if (!project.isAccessible()) {
			return McpToolResult.error("No open project named '%s' in this workspace.".formatted(projectName)); //$NON-NLS-1$
		}
		boolean dryRun = args.getBoolean("dryRun", true); //$NON-NLS-1$
		boolean force = args.getBoolean("force", false); //$NON-NLS-1$

		// the bundle is lazily activated, so its own context only exists once it started
		BundleContext context = FrameworkUtil.getBundle(EditManifestTool.class).getBundleContext();
		if (context == null) {
			context = FrameworkUtil.getBundle(IBundleProjectService.class).getBundleContext();
		}
		if (context == null) {
			return McpToolResult
					.error("Neither this bundle nor PDE is active, so the bundle project service cannot be reached."); //$NON-NLS-1$
		}
		ServiceReference<IBundleProjectService> reference = context.getServiceReference(IBundleProjectService.class);
		if (reference == null) {
			return McpToolResult.error("PDE does not offer its bundle project service in this IDE."); //$NON-NLS-1$
		}
		IBundleProjectService service = context.getService(reference);
		try {
			return edit(service, project, arguments, dryRun, force, monitor);
		} catch (CoreException e) {
			throw new McpToolException("Could not edit the manifest of " + projectName, e); //$NON-NLS-1$
		} finally {
			context.ungetService(reference);
		}
	}

	private McpToolResult edit(IBundleProjectService service, IProject project, Map<String, Object> arguments,
			boolean dryRun, boolean force, IProgressMonitor monitor) throws CoreException {
		IBundleProjectDescription description = service.getDescription(project);
		if (description.getSymbolicName() == null) {
			return McpToolResult.error("'%s' is not a plug-in project; it has no bundle manifest." //$NON-NLS-1$
					.formatted(project.getName()));
		}
		List<String> removeExports = strings(arguments, "removeExportPackage"); //$NON-NLS-1$
		List<String> removeRequires = strings(arguments, "removeRequireBundle"); //$NON-NLS-1$
		List<String> removeImports = strings(arguments, "removeImportPackage"); //$NON-NLS-1$

		JsonObject result = new JsonObject().put("project", project.getName()) //$NON-NLS-1$
				.put("bundle", description.getSymbolicName()) //$NON-NLS-1$
				.put("dryRun", Boolean.valueOf(dryRun)); //$NON-NLS-1$

		// who would break, computed before anything is changed
		JsonArray blocked = new JsonArray();
		for (String exported : removeExports) {
			List<String> consumers = importersOf(exported, description.getSymbolicName());
			if (!consumers.isEmpty()) {
				blocked.add(new JsonObject().put("removing", "Export-Package " + exported) //$NON-NLS-1$ //$NON-NLS-2$
						.put("consumedBy", array(consumers))); //$NON-NLS-1$
			}
		}
		// removing what THIS bundle requires cannot break anyone else, so only
		// exports are checked for consumers
		if (blocked.size() > 0) {
			result.put("wouldBreak", blocked); //$NON-NLS-1$
			if (!force) {
				return McpToolResult.of(result.put("applied", Boolean.FALSE) //$NON-NLS-1$
						.put("refusedBecause", //$NON-NLS-1$
								"Workspace bundles still consume what you are removing. They keep compiling and fail to resolve at runtime, which is why this is refused rather than warned about. Pass force to do it anyway.") //$NON-NLS-1$
						.toString());
			}
		}

		List<IPackageExportDescription> exports = new ArrayList<>(
				List.of(description.getPackageExports() == null ? new IPackageExportDescription[0]
						: description.getPackageExports()));
		exports.removeIf(export -> removeExports.contains(export.getName()));
		for (Map<String, Object> entry : objects(arguments, "addExportPackage")) { //$NON-NLS-1$
			String name = string(entry, "package"); //$NON-NLS-1$
			String version = string(entry, "version"); //$NON-NLS-1$
			List<String> friends = strings(entry, "friends"); //$NON-NLS-1$
			boolean internal = Boolean.TRUE.equals(entry.get("internal")); //$NON-NLS-1$
			exports.removeIf(export -> export.getName().equals(name));
			// api false is what PDE writes as x-internal, and a friend list is written
			// as x-friends; the two are alternatives rather than independent flags
			// an empty list rather than null: PDE dereferences the collection, and its
			// javadoc says a version may be null but is silent about this one
			exports.add(service.newPackageExport(name, version == null ? null : Version.parseVersion(version),
					!internal, friends));
		}

		List<IRequiredBundleDescription> requires = new ArrayList<>(
				List.of(description.getRequiredBundles() == null ? new IRequiredBundleDescription[0]
						: description.getRequiredBundles()));
		requires.removeIf(required -> removeRequires.contains(required.getName()));
		for (Map<String, Object> entry : objects(arguments, "addRequireBundle")) { //$NON-NLS-1$
			String name = string(entry, "bundle"); //$NON-NLS-1$
			String range = string(entry, "versionRange"); //$NON-NLS-1$
			requires.removeIf(required -> required.getName().equals(name));
			requires.add(service.newRequiredBundle(name, range == null ? null : new VersionRange(range),
					Boolean.TRUE.equals(entry.get("optional")), Boolean.TRUE.equals(entry.get("reexport")))); //$NON-NLS-1$ //$NON-NLS-2$
		}

		List<IPackageImportDescription> imports = new ArrayList<>(
				List.of(description.getPackageImports() == null ? new IPackageImportDescription[0]
						: description.getPackageImports()));
		imports.removeIf(imported -> removeImports.contains(imported.getName()));
		for (Map<String, Object> entry : objects(arguments, "addImportPackage")) { //$NON-NLS-1$
			String name = string(entry, "package"); //$NON-NLS-1$
			String range = string(entry, "versionRange"); //$NON-NLS-1$
			imports.removeIf(imported -> imported.getName().equals(name));
			imports.add(service.newPackageImport(name, range == null ? null : new VersionRange(range),
					Boolean.TRUE.equals(entry.get("optional")))); //$NON-NLS-1$
		}

		result.put("exportPackage", exportsJson(exports)) //$NON-NLS-1$
				.put("requireBundle", requiresJson(requires)) //$NON-NLS-1$
				.put("importPackage", importsJson(imports)); //$NON-NLS-1$
		if (dryRun) {
			return McpToolResult.of(result.put("applied", Boolean.FALSE) //$NON-NLS-1$
					.put("note", "This is the manifest as it would be. Pass dryRun false to write it.").toString()); //$NON-NLS-1$ //$NON-NLS-2$
		}
		description.setPackageExports(exports.toArray(IPackageExportDescription[]::new));
		description.setRequiredBundles(requires.toArray(IRequiredBundleDescription[]::new));
		description.setPackageImports(imports.isEmpty() ? null : imports.toArray(IPackageImportDescription[]::new));
		description.apply(monitor);
		return McpToolResult.of(result.put("applied", Boolean.TRUE).toString()); //$NON-NLS-1$
	}

	/** The workspace bundles that import a package, which is what an export removal breaks. */
	private static List<String> importersOf(String packageName, String exporter) {
		Set<String> consumers = new LinkedHashSet<>();
		for (IPluginModelBase model : PluginRegistry.getWorkspaceModels()) {
			BundleDescription description = model.getBundleDescription();
			if (description == null || description.getSymbolicName() == null
					|| description.getSymbolicName().equals(exporter)) {
				continue;
			}
			for (ImportPackageSpecification specification : description.getImportPackages()) {
				if (packageName.equals(specification.getName())) {
					consumers.add(description.getSymbolicName());
				}
			}
			// Require-Bundle consumers see every exported package of the required
			// bundle, so they consume this one without naming it
			for (org.eclipse.osgi.service.resolver.BundleSpecification specification : description
					.getRequiredBundles()) {
				if (exporter.equals(specification.getName())) {
					consumers.add(description.getSymbolicName());
				}
			}
		}
		return List.copyOf(consumers);
	}

	private static JsonArray exportsJson(List<IPackageExportDescription> exports) {
		JsonArray array = new JsonArray();
		for (IPackageExportDescription export : exports) {
			JsonObject entry = new JsonObject().put("package", export.getName()) //$NON-NLS-1$
					.put("version", export.getVersion() == null ? null : export.getVersion().toString()) //$NON-NLS-1$
					.put("visibility", export.isApi() ? "public" : "x-internal"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
			if (export.getFriends() != null && export.getFriends().length > 0) {
				entry.put("visibility", "x-friends").put("friends", array(List.of(export.getFriends()))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
			}
			array.add(entry);
		}
		return array;
	}

	private static JsonArray requiresJson(List<IRequiredBundleDescription> requires) {
		JsonArray array = new JsonArray();
		for (IRequiredBundleDescription required : requires) {
			array.add(new JsonObject().put("bundle", required.getName()) //$NON-NLS-1$
					.put("versionRange", required.getVersionRange() == null ? null //$NON-NLS-1$
							: required.getVersionRange().toString())
					.put("optional", Boolean.valueOf(required.isOptional())) //$NON-NLS-1$
					.put("reexport", Boolean.valueOf(required.isExported()))); //$NON-NLS-1$
		}
		return array;
	}

	private static JsonArray importsJson(List<IPackageImportDescription> imports) {
		JsonArray array = new JsonArray();
		for (IPackageImportDescription imported : imports) {
			array.add(new JsonObject().put("package", imported.getName()) //$NON-NLS-1$
					.put("versionRange", imported.getVersionRange() == null ? null //$NON-NLS-1$
							: imported.getVersionRange().toString())
					.put("optional", Boolean.valueOf(imported.isOptional()))); //$NON-NLS-1$
		}
		return array;
	}

	private static JsonArray array(List<String> values) {
		JsonArray array = new JsonArray();
		values.forEach(array::add);
		return array;
	}

	private static String string(Map<String, Object> entry, String key) {
		Object value = entry.get(key);
		return value == null || String.valueOf(value).isBlank() ? null : String.valueOf(value).trim();
	}

	private static List<String> strings(Map<String, Object> arguments, String name) {
		List<String> values = new ArrayList<>();
		if (arguments != null && arguments.get(name) instanceof List<?> list) {
			for (Object value : list) {
				if (value != null && !String.valueOf(value).isBlank()) {
					values.add(String.valueOf(value).trim());
				}
			}
		}
		return values;
	}

	@SuppressWarnings("unchecked")
	private static List<Map<String, Object>> objects(Map<String, Object> arguments, String name) {
		List<Map<String, Object>> values = new ArrayList<>();
		if (arguments != null && arguments.get(name) instanceof List<?> list) {
			for (Object value : list) {
				if (value instanceof Map<?, ?> map) {
					values.add((Map<String, Object>) map);
				}
			}
		}
		return values;
	}
}
