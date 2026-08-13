package com.vogella.eclipse.mcp.core;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IConfigurationElement;
import org.eclipse.core.runtime.IExtensionRegistry;
import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.Platform;

/**
 * The tools contributed through the {@code com.vogella.eclipse.mcp.core.tools} extension
 * point, instantiated on first access.
 */
public final class McpToolRegistry {

	public static final String EXTENSION_POINT_ID = "com.vogella.eclipse.mcp.core.tools"; //$NON-NLS-1$

	private static final String ATTRIBUTE_CLASS = "class"; //$NON-NLS-1$

	private static final McpToolRegistry INSTANCE = new McpToolRegistry();

	private Map<String, IMcpTool> tools;

	private McpToolRegistry() {
	}

	public static McpToolRegistry getInstance() {
		return INSTANCE;
	}

	/** All contributed tools, ordered by name. */
	public synchronized Collection<IMcpTool> getTools() {
		return List.copyOf(load().values());
	}

	public synchronized Optional<IMcpTool> findTool(String name) {
		return Optional.ofNullable(load().get(name));
	}

	/** Drops the cached tools so that the next access reads the extension registry again. */
	public synchronized void reset() {
		tools = null;
	}

	private Map<String, IMcpTool> load() {
		if (tools != null) {
			return tools;
		}
		Map<String, IMcpTool> discovered = new TreeMap<>();
		IExtensionRegistry registry = Platform.getExtensionRegistry();
		if (registry == null) {
			ILog.get().warn("No extension registry available, no MCP tools contributed"); //$NON-NLS-1$
			tools = new LinkedHashMap<>();
			return tools;
		}
		for (IConfigurationElement element : registry.getConfigurationElementsFor(EXTENSION_POINT_ID)) {
			String contributor = element.getContributor().getName();
			try {
				Object extension = element.createExecutableExtension(ATTRIBUTE_CLASS);
				if (!(extension instanceof IMcpTool tool)) {
					ILog.get().error("Tool contributed by %s does not implement IMcpTool".formatted(contributor)); //$NON-NLS-1$
					continue;
				}
				String name = tool.getName();
				if (name == null || name.isBlank()) {
					ILog.get().error("Tool contributed by %s has no name".formatted(contributor)); //$NON-NLS-1$
					continue;
				}
				IMcpTool previous = discovered.putIfAbsent(name, tool);
				if (previous != null) {
					ILog.get().error("Duplicate MCP tool name '%s' contributed by %s, ignoring it".formatted(name, //$NON-NLS-1$
							contributor));
				}
			} catch (CoreException e) {
				ILog.get().error("Could not create MCP tool contributed by %s".formatted(contributor), e); //$NON-NLS-1$
			}
		}
		tools = discovered;
		return tools;
	}
}
