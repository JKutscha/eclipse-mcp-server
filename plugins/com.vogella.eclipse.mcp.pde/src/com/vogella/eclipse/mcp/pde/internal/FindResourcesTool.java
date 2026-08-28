package com.vogella.eclipse.mcp.pde.internal;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IResourceProxy;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.pde.core.target.ITargetDefinition;
import org.eclipse.pde.core.target.TargetBundle;
import org.osgi.framework.Bundle;
import org.osgi.framework.FrameworkUtil;

import com.vogella.eclipse.mcp.core.Globs;
import com.vogella.eclipse.mcp.core.IMcpTool;
import com.vogella.eclipse.mcp.core.McpToolResult;
import com.vogella.eclipse.mcp.core.ToolArguments;
import com.vogella.eclipse.mcp.core.json.JsonArray;
import com.vogella.eclipse.mcp.core.json.JsonObject;

/**
 * Finds files by name across the workspace, the target platform and the running
 * installation.
 */
public final class FindResourcesTool implements IMcpTool {

	@Override
	public String getName() {
		return "eclipse_find_resources"; //$NON-NLS-1$
	}

	@Override
	public String getDescription() {
		return "Finds files by NAME across three places that no other tool here covers together: the workspace, the bundles of the active target platform, and the bundles of the running installation. Changes nothing. eclipse_search_text searches the CONTENT of workspace files, so an icon inside a jarred bundle is invisible to it, and that is the gap this closes: 'does an SVG of this name already exist anywhere in Eclipse' is answerable only by looking inside jars. Each hit says which of the three it came from, the bundle and version that holds it, the path inside that bundle, and a location the bytes can be read from; copyTo extracts the hits to a directory, which is what makes a found icon usable rather than only known about. A NAME MATCH IS NOT A MATCH IN MEANING: an icon called remove upstream may be a red cross where yours is a minus, so look at what you found before replacing anything with it."; //$NON-NLS-1$
	}

	@Override
	public String getInputSchema() {
		return """
				{
				  "type": "object",
				  "required": ["namePattern"],
				  "properties": {
				    "namePattern":  {"type":"string","description":"Glob over the FILE NAME only, not the path: '*question*', 'refresh.svg', 'overlay-*'. Case insensitive."},
				    "extensions":   {"type":"array","items":{"type":"string"},"description":"Only these file extensions, without the dot, e.g. ['svg','png','gif']."},
				    "scope":        {"type":"string","enum":["workspace","target","installation","all"],"default":"all","description":"Where to look. 'target' needs an active target platform, 'installation' looks inside the bundles this IDE is running, jars included."},
				    "bundleFilter": {"type":"string","description":"Only bundles whose symbolic name contains this text. Narrows a search over a few thousand icons to one component."},
				    "copyTo":       {"type":"string","description":"Absolute directory to copy each hit into, named bundle_file so two hits of the same name do not collide. This is how the bytes leave a jar; without it the hit is only a location."},
				    "maxResults":   {"type":"integer","default":100,"minimum":1,"maximum":1000,"description":"An SDK holds thousands of icons, so this is a small number on purpose; narrow the pattern rather than raising it."}
				  },
				  "additionalProperties": false
				}"""; //$NON-NLS-1$
	}

	@Override
	public McpToolResult call(Map<String, Object> arguments, IProgressMonitor monitor) {
		ToolArguments args = ToolArguments.of(arguments);
		String namePattern = args.getString("namePattern"); //$NON-NLS-1$
		if (namePattern == null) {
			return McpToolResult.error("The argument 'namePattern' is required, for instance '*question*'."); //$NON-NLS-1$
		}
		Pattern name = Globs.compile(namePattern);
		List<String> extensions = strings(arguments, "extensions"); //$NON-NLS-1$
		String scope = args.getString("scope", "all"); //$NON-NLS-1$ //$NON-NLS-2$
		String bundleFilter = args.getString("bundleFilter"); //$NON-NLS-1$
		int maxResults = args.getInt("maxResults", 100, 1, 1000); //$NON-NLS-1$
		Path copyTo = null;
		if (args.getString("copyTo") != null) { //$NON-NLS-1$
			copyTo = Path.of(args.getString("copyTo")); //$NON-NLS-1$
			try {
				Files.createDirectories(copyTo);
			} catch (IOException e) {
				return McpToolResult.error("Could not create '%s': %s".formatted(copyTo, e.getMessage())); //$NON-NLS-1$
			}
		}

		JsonArray hits = new JsonArray();
		int[] total = { 0 };
		if (scope.equals("workspace") || scope.equals("all")) { //$NON-NLS-1$ //$NON-NLS-2$
			searchWorkspace(name, extensions, hits, total, maxResults, copyTo);
		}
		if (scope.equals("installation") || scope.equals("all")) { //$NON-NLS-1$ //$NON-NLS-2$
			searchInstallation(name, extensions, bundleFilter, hits, total, maxResults, copyTo);
		}
		if (scope.equals("target") || scope.equals("all")) { //$NON-NLS-1$ //$NON-NLS-2$
			searchTarget(name, extensions, bundleFilter, hits, total, maxResults, copyTo);
		}

		JsonObject result = new JsonObject().put("hits", hits) //$NON-NLS-1$
				.put("total", Integer.valueOf(total[0])) //$NON-NLS-1$
				.put("truncated", Boolean.valueOf(total[0] > hits.size())) //$NON-NLS-1$
				.put("scope", scope); //$NON-NLS-1$
		if (copyTo != null) {
			result.put("copiedTo", copyTo.toString()); //$NON-NLS-1$
		}
		return McpToolResult.of(result.put("note", //$NON-NLS-1$
				hits.size() == 0
						? "Nothing matched. The pattern is over the file name alone, so a path fragment such as icons/full does not belong in it, and 'target' finds nothing when no target platform is active." //$NON-NLS-1$
						: "A name match says nothing about the picture. Copy the hits with copyTo and look at them before treating one as a replacement for another.") //$NON-NLS-1$
				.toString());
	}

	private static void searchWorkspace(Pattern name, List<String> extensions, JsonArray hits, int[] total,
			int maxResults, Path copyTo) {
		try {
			ResourcesPlugin.getWorkspace().getRoot().accept((IResourceProxy proxy) -> {
				if (proxy.getType() != IResource.FILE) {
					return true;
				}
				if (!matches(proxy.getName(), name, extensions)) {
					return false;
				}
				total[0]++;
				if (hits.size() < maxResults) {
					IResource resource = proxy.requestResource();
					java.net.URI location = resource.getLocationURI();
					JsonObject hit = new JsonObject().put("scope", "workspace") //$NON-NLS-1$ //$NON-NLS-2$
							.put("bundle", resource.getProject().getName()) //$NON-NLS-1$
							.put("path", resource.getProjectRelativePath().toString()) //$NON-NLS-1$
							.put("workspacePath", resource.getFullPath().toString()) //$NON-NLS-1$
							.put("location", location == null ? null : location.getPath()); //$NON-NLS-1$
					if (resource instanceof IFile file) {
						java.io.File onDisk = resource.getLocation() == null ? null
								: resource.getLocation().toFile();
						if (onDisk != null && onDisk.isFile()) {
							hit.put("bytes", Long.valueOf(onDisk.length())); //$NON-NLS-1$
						}
						copy(copyTo, resource.getProject().getName(), proxy.getName(), () -> file.getContents(true),
								hit);
					}
					hits.add(hit);
				}
				return false;
			}, IResource.NONE);
		} catch (CoreException e) {
			// a workspace that cannot be walked still leaves the other scopes usable
		}
	}

	private static void searchInstallation(Pattern name, List<String> extensions, String bundleFilter, JsonArray hits,
			int[] total, int maxResults, Path copyTo) {
		Bundle self = FrameworkUtil.getBundle(FindResourcesTool.class);
		if (self == null || self.getBundleContext() == null) {
			return;
		}
		for (Bundle bundle : self.getBundleContext().getBundles()) {
			if (bundleFilter != null && !bundle.getSymbolicName().contains(bundleFilter)) {
				continue;
			}
			// findEntries reads the bundle whether it is a directory or a jar, which is
			// the whole reason this reaches an icon a content search cannot see
			var entries = bundle.findEntries("/", "*", true); //$NON-NLS-1$ //$NON-NLS-2$
			while (entries != null && entries.hasMoreElements()) {
				java.net.URL url = entries.nextElement();
				String path = url.getPath();
				String fileName = path.substring(path.lastIndexOf('/') + 1);
				if (fileName.isEmpty() || !matches(fileName, name, extensions)) {
					continue;
				}
				total[0]++;
				if (hits.size() >= maxResults) {
					continue;
				}
				JsonObject hit = new JsonObject().put("scope", "installation") //$NON-NLS-1$ //$NON-NLS-2$
						.put("bundle", bundle.getSymbolicName()) //$NON-NLS-1$
						.put("version", String.valueOf(bundle.getVersion())) //$NON-NLS-1$
						.put("path", path.startsWith("/") ? path.substring(1) : path) //$NON-NLS-1$ //$NON-NLS-2$
						.put("location", url.toExternalForm()); //$NON-NLS-1$
				copy(copyTo, bundle.getSymbolicName(), fileName, url::openStream, hit);
				hits.add(hit);
			}
		}
	}

	private static void searchTarget(Pattern name, List<String> extensions, String bundleFilter, JsonArray hits,
			int[] total, int maxResults, Path copyTo) {
		TargetPlatforms.with(service -> {
			try {
				ITargetDefinition definition = service.getWorkspaceTargetDefinition();
				if (definition == null || definition.getBundles() == null) {
					return McpToolResult.of("{}"); //$NON-NLS-1$
				}
				for (TargetBundle bundle : definition.getBundles()) {
					// PDE returns the bundle info from API while the type itself is
					// internal to frameworkadmin, so the compiler refuses to name it and
					// the three values have to be asked for reflectively
					Object info = bundle.getBundleInfo();
					String symbolicName = string(info, "getSymbolicName"); //$NON-NLS-1$
					String version = string(info, "getVersion"); //$NON-NLS-1$
					String location = string(info, "getLocation"); //$NON-NLS-1$
					if (location == null || (bundleFilter != null
							&& (symbolicName == null || !symbolicName.contains(bundleFilter)))) {
						continue;
					}
					File file = new File(java.net.URI.create(location).getPath());
					if (!file.isFile()) {
						file = new File(location);
					}
					if (file.isFile()) {
						searchJar(file, symbolicName, version, name, extensions, hits, total, maxResults, copyTo);
					}
				}
			} catch (CoreException | RuntimeException e) {
				// no resolved target platform, which the note already explains
			}
			return McpToolResult.of("{}"); //$NON-NLS-1$
		});
	}

	private static void searchJar(File jar, String symbolicName, String version, Pattern name,
			List<String> extensions, JsonArray hits, int[] total, int maxResults, Path copyTo) {
		try (ZipFile zip = new ZipFile(jar)) {
			var entries = zip.entries();
			while (entries.hasMoreElements()) {
				ZipEntry entry = entries.nextElement();
				if (entry.isDirectory()) {
					continue;
				}
				String path = entry.getName();
				String fileName = path.substring(path.lastIndexOf('/') + 1);
				if (!matches(fileName, name, extensions)) {
					continue;
				}
				total[0]++;
				if (hits.size() >= maxResults) {
					continue;
				}
				JsonObject hit = new JsonObject().put("scope", "target") //$NON-NLS-1$ //$NON-NLS-2$
						.put("bundle", symbolicName) //$NON-NLS-1$
						.put("version", version) //$NON-NLS-1$
						.put("path", path) //$NON-NLS-1$
						.put("bytes", Long.valueOf(entry.getSize())) //$NON-NLS-1$
						.put("location", "jar:%s!/%s".formatted(jar.toURI(), path)); //$NON-NLS-1$ //$NON-NLS-2$
				copy(copyTo, symbolicName, fileName, () -> zip.getInputStream(entry), hit);
				hits.add(hit);
			}
		} catch (IOException e) {
			// an unreadable jar is not a reason to abandon the rest of the target
		}
	}

	/** One string property of an object whose type cannot be named here. */
	private static String string(Object target, String method) {
		if (target == null) {
			return null;
		}
		try {
			Object value = target.getClass().getMethod(method).invoke(target);
			return value == null ? null : String.valueOf(value);
		} catch (ReflectiveOperationException | RuntimeException e) {
			return null;
		}
	}

	/** Opens the bytes of a hit, wherever they live. */
	private interface Bytes {
		InputStream open() throws IOException, CoreException;
	}

	private static void copy(Path copyTo, String bundle, String fileName, Bytes bytes, JsonObject hit) {
		if (copyTo == null) {
			return;
		}
		Path target = copyTo.resolve(bundle + "_" + fileName); //$NON-NLS-1$
		try (InputStream in = bytes.open()) {
			Files.copy(in, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
			hit.put("copiedTo", target.toString()); //$NON-NLS-1$
		} catch (IOException | CoreException | RuntimeException e) {
			hit.put("copyFailed", String.valueOf(e.getMessage())); //$NON-NLS-1$
		}
	}

	private static boolean matches(String fileName, Pattern name, List<String> extensions) {
		if (!name.matcher(fileName).matches()) {
			return false;
		}
		if (extensions.isEmpty()) {
			return true;
		}
		int dot = fileName.lastIndexOf('.');
		String extension = dot < 0 ? "" : fileName.substring(dot + 1); //$NON-NLS-1$
		for (String candidate : extensions) {
			if (extension.equalsIgnoreCase(candidate)) {
				return true;
			}
		}
		return false;
	}

	private static List<String> strings(Map<String, Object> arguments, String key) {
		Object raw = arguments == null ? null : arguments.get(key);
		if (!(raw instanceof List<?> list)) {
			return List.of();
		}
		List<String> values = new ArrayList<>();
		for (Object value : list) {
			if (value != null) {
				values.add(String.valueOf(value).strip());
			}
		}
		return values;
	}
}
