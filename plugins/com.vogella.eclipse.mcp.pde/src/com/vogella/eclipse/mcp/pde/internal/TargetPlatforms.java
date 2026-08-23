package com.vogella.eclipse.mcp.pde.internal;

import java.io.File;
import java.util.function.Function;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.pde.core.target.ITargetDefinition;
import org.eclipse.pde.core.target.ITargetHandle;
import org.eclipse.pde.core.target.ITargetLocation;
import org.eclipse.pde.core.target.ITargetPlatformService;
import org.eclipse.pde.core.target.TargetBundle;
import org.eclipse.pde.core.target.TargetFeature;
import org.osgi.framework.BundleContext;
import org.osgi.framework.FrameworkUtil;
import org.osgi.framework.ServiceReference;

import com.vogella.eclipse.mcp.core.McpToolResult;
import com.vogella.eclipse.mcp.core.json.JsonArray;
import com.vogella.eclipse.mcp.core.json.JsonObject;

/**
 * Shared plumbing for the target platform tools: the PDE service, the handle a
 * caller names, and the JSON view of a target definition.
 */
final class TargetPlatforms {

	private TargetPlatforms() {
	}

	/** Runs {@code body} with PDE's target platform service, releasing it afterwards. */
	static McpToolResult with(Function<ITargetPlatformService, McpToolResult> body) {
		// the bundle is lazily activated, so its own context only exists once it started
		BundleContext context = FrameworkUtil.getBundle(TargetPlatforms.class).getBundleContext();
		if (context == null) {
			context = FrameworkUtil.getBundle(ITargetPlatformService.class).getBundleContext();
		}
		if (context == null) {
			return McpToolResult
					.error("Neither this bundle nor PDE is active, so the target platform service cannot be reached."); //$NON-NLS-1$
		}
		ServiceReference<ITargetPlatformService> reference = context
				.getServiceReference(ITargetPlatformService.class);
		if (reference == null) {
			return McpToolResult.error("PDE does not offer its target platform service in this IDE."); //$NON-NLS-1$
		}
		try {
			return body.apply(context.getService(reference));
		} finally {
			context.ungetService(reference);
		}
	}

	/**
	 * Resolves a workspace path, a file system path or a memento to a target handle.
	 *
	 * @return {@code null} when nothing of that name exists
	 */
	static ITargetHandle handle(ITargetPlatformService service, String file, String memento) throws CoreException {
		if (memento != null) {
			return service.getTarget(memento);
		}
		IResource resource = ResourcesPlugin.getWorkspace().getRoot().findMember(file);
		if (resource instanceof IFile target) {
			return service.getTarget(target);
		}
		File onDisk = new File(file);
		return onDisk.isFile() ? service.getTarget(onDisk.toURI()) : null;
	}

	/** The full JSON view of a definition, as far as it has been resolved. */
	static JsonObject describe(ITargetDefinition definition, boolean includeLocations, int maxProblems) {
		JsonObject json = new JsonObject().put("name", definition.getName()) //$NON-NLS-1$
				.put("memento", memento(definition.getHandle())) //$NON-NLS-1$
				.put("resolved", definition.isResolved()) //$NON-NLS-1$
				.put("status", status(definition.getStatus())) //$NON-NLS-1$
				.put("jreContainer", //$NON-NLS-1$
						definition.getJREContainer() == null ? null : definition.getJREContainer().toString())
				.put("environment", new JsonObject().put("os", definition.getOS()) //$NON-NLS-1$ //$NON-NLS-2$
						.put("ws", definition.getWS()) //$NON-NLS-1$
						.put("arch", definition.getArch()) //$NON-NLS-1$
						.put("nl", definition.getNL())); //$NON-NLS-1$
		if (!definition.isResolved()) {
			return json;
		}

		TargetBundle[] bundles = definition.getBundles();
		TargetFeature[] features = definition.getAllFeatures();
		json.put("bundleCount", bundles == null ? 0 : bundles.length); //$NON-NLS-1$
		json.put("featureCount", features == null ? 0 : features.length); //$NON-NLS-1$

		JsonArray problems = new JsonArray();
		int broken = 0;
		if (bundles != null) {
			for (TargetBundle bundle : bundles) {
				IStatus bundleStatus = bundle.getStatus();
				if (bundleStatus == null || bundleStatus.isOK()) {
					continue;
				}
				broken++;
				if (problems.size() < maxProblems) {
					// toString rather than getBundleInfo(): PDE returns that from API, but
					// the type itself is internal and the compiler refuses it
					problems.add(new JsonObject().put("bundle", String.valueOf(bundle)) //$NON-NLS-1$
							.put("severity", severity(bundleStatus.getSeverity())) //$NON-NLS-1$
							.put("message", bundleStatus.getMessage())); //$NON-NLS-1$
				}
			}
		}
		json.put("bundleProblems", problems) //$NON-NLS-1$
				.put("bundleProblemCount", broken) //$NON-NLS-1$
				.put("bundleProblemsTruncated", broken > problems.size()); //$NON-NLS-1$

		if (includeLocations) {
			JsonArray locations = new JsonArray();
			for (ITargetLocation location : definition.getTargetLocations() == null ? new ITargetLocation[0]
					: definition.getTargetLocations()) {
				TargetBundle[] fromLocation = location.getBundles();
				locations.add(new JsonObject().put("type", location.getType()) //$NON-NLS-1$
						.put("location", location(location)) //$NON-NLS-1$
						.put("resolved", location.isResolved()) //$NON-NLS-1$
						.put("bundleCount", fromLocation == null ? 0 : fromLocation.length) //$NON-NLS-1$
						.put("status", status(location.getStatus()))); //$NON-NLS-1$
			}
			json.put("locations", locations); //$NON-NLS-1$
		}
		return json;
	}

	/** A status with its children, which is where a failed location says what it could not reach. */
	static JsonObject status(IStatus status) {
		if (status == null) {
			return null;
		}
		JsonObject json = new JsonObject().put("severity", severity(status.getSeverity())) //$NON-NLS-1$
				.put("message", status.getMessage()); //$NON-NLS-1$
		if (status.getException() != null) {
			json.put("exception", String.valueOf(status.getException())); //$NON-NLS-1$
		}
		if (status.isMultiStatus() && status.getChildren().length > 0) {
			JsonArray children = new JsonArray();
			for (IStatus child : status.getChildren()) {
				children.add(status(child));
			}
			json.put("children", children); //$NON-NLS-1$
		}
		return json;
	}

	static String severity(int severity) {
		return switch (severity) {
		case IStatus.OK -> "OK"; //$NON-NLS-1$
		case IStatus.INFO -> "INFO"; //$NON-NLS-1$
		case IStatus.WARNING -> "WARNING"; //$NON-NLS-1$
		case IStatus.ERROR -> "ERROR"; //$NON-NLS-1$
		case IStatus.CANCEL -> "CANCEL"; //$NON-NLS-1$
		default -> String.valueOf(severity);
		};
	}

	static String memento(ITargetHandle handle) {
		try {
			return handle == null ? null : handle.getMemento();
		} catch (CoreException e) {
			return null;
		}
	}

	private static String location(ITargetLocation location) {
		try {
			return location.getLocation(false);
		} catch (CoreException e) {
			return null;
		}
	}
}
