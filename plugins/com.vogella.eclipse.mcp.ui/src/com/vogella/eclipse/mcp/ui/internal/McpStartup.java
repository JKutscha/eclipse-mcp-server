package com.vogella.eclipse.mcp.ui.internal;

import org.eclipse.ui.IStartup;

/**
 * Starts the server on IDE startup when the user enabled it.
 */
public class McpStartup implements IStartup {

	@Override
	public void earlyStartup() {
		McpServerJob.reconcile();
	}
}
