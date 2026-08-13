package com.vogella.eclipse.mcp.server.internal;

import org.osgi.framework.BundleActivator;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceRegistration;

import com.vogella.eclipse.mcp.server.McpServerService;

/**
 * Publishes the {@link McpServerService} as an OSGi service and shuts the server down
 * when the bundle stops.
 */
public class McpServerActivator implements BundleActivator {

	private ServiceRegistration<McpServerService> registration;

	@Override
	public void start(BundleContext context) {
		registration = context.registerService(McpServerService.class, McpServerService.getInstance(), null);
	}

	@Override
	public void stop(BundleContext context) {
		if (registration != null) {
			registration.unregister();
			registration = null;
		}
		McpServerService.getInstance().stop();
	}
}
