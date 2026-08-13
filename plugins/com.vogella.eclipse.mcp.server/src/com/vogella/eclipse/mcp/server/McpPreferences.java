package com.vogella.eclipse.mcp.server;

import org.eclipse.core.runtime.Platform;

/**
 * The preferences that control the embedded MCP server.
 */
public final class McpPreferences {

	/** Preference node of the server bundle. */
	public static final String QUALIFIER = "com.vogella.eclipse.mcp.server"; //$NON-NLS-1$

	public static final String KEY_ENABLED = "enabled"; //$NON-NLS-1$

	public static final String KEY_PORT = "port"; //$NON-NLS-1$

	/** A process that listens on a socket and answers questions about the user's code must be opt-in. */
	public static final boolean DEFAULT_ENABLED = false;

	public static final int DEFAULT_PORT = 8642;

	private McpPreferences() {
	}

	public static boolean isEnabled() {
		return Platform.getPreferencesService().getBoolean(QUALIFIER, KEY_ENABLED, DEFAULT_ENABLED, null);
	}

	public static int getPort() {
		return Platform.getPreferencesService().getInt(QUALIFIER, KEY_PORT, DEFAULT_PORT, null);
	}
}
