package com.vogella.eclipse.mcp.debug.internal;

import org.osgi.framework.BundleContext;
import org.osgi.framework.BundleActivator;

/**
 * Bundle activator, and owner of the shutdown that terminates the debug sessions
 * this server started.
 */
public final class McpDebugPlugin implements BundleActivator {

	@Override
	public void start(BundleContext context) throws Exception {
		// the registry listens lazily, so a workspace that never debugs pays nothing
	}

	@Override
	public void stop(BundleContext context) throws Exception {
		// a suspended JVM nobody can see is the same class of mess as a hidden window
		DebugSessionRegistry.getInstance().shutdown();
	}
}
