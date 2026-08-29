package com.vogella.eclipse.mcp.server;

import java.time.Duration;

import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.preferences.ConfigurationScope;

/**
 * The preferences that control the embedded MCP server.
 */
public final class McpPreferences {

	/** Preference node of the server bundle. */
	public static final String QUALIFIER = "com.vogella.eclipse.mcp.server"; //$NON-NLS-1$

	public static final String KEY_ENABLED = "enabled"; //$NON-NLS-1$

	public static final String KEY_PORT = "port"; //$NON-NLS-1$

	public static final String KEY_CALL_TIMEOUT_SECONDS = "callTimeoutSeconds"; //$NON-NLS-1$

	/**
	 * Directories a client may run commands in, one per line.
	 * <p>
	 * Empty by default, which switches the command tools off. Running a command is
	 * the one thing this server does that is not the IDE acting on itself, and it
	 * can do anything the user can.
	 */
	public static final String KEY_COMMAND_ROOTS = "commandRoots"; //$NON-NLS-1$

	/**
	 * Whether this plug-in replaces the IDE's splash screen.
	 * <p>
	 * Held in the CONFIGURATION scope and not the instance scope, unlike everything
	 * else here: the splash is written into the installation's config.ini and is
	 * therefore a property of the installation. An instance scoped flag would let two
	 * workspaces of one installation disagree, with the last one started silently
	 * winning and the other's preference page showing a state that is not in force.
	 */
	public static final String KEY_REPLACE_SPLASH = "replaceSplash"; //$NON-NLS-1$

	/**
	 * Off, because repainting the branding of an IDE somebody else installed is a
	 * bigger surprise than opening a socket, and harder to trace back to its cause.
	 */
	public static final boolean DEFAULT_REPLACE_SPLASH = false;

	/** A process that listens on a socket and answers questions about the user's code must be opt-in. */
	public static final boolean DEFAULT_ENABLED = false;

	public static final int DEFAULT_PORT = 8642;

	/** Enough for a search over a large workspace, short enough that a wedged tool does not hang a client. */
	public static final int DEFAULT_CALL_TIMEOUT_SECONDS = 30;

	public static final int MIN_CALL_TIMEOUT_SECONDS = 5;

	public static final int MAX_CALL_TIMEOUT_SECONDS = 3600;

	private McpPreferences() {
	}

	/** Read from the configuration scope, which is the installation this splash belongs to. */
	public static boolean isReplaceSplash() {
		return ConfigurationScope.INSTANCE.getNode(QUALIFIER).getBoolean(KEY_REPLACE_SPLASH,
				DEFAULT_REPLACE_SPLASH);
	}

	public static boolean isEnabled() {
		return Platform.getPreferencesService().getBoolean(QUALIFIER, KEY_ENABLED, DEFAULT_ENABLED, null);
	}

	public static int getPort() {
		return Platform.getPreferencesService().getInt(QUALIFIER, KEY_PORT, DEFAULT_PORT, null);
	}

	/** How long a single tool call may run before the server abandons it. */
	public static Duration getCallTimeout() {
		int seconds = Platform.getPreferencesService().getInt(QUALIFIER, KEY_CALL_TIMEOUT_SECONDS,
				DEFAULT_CALL_TIMEOUT_SECONDS, null);
		return Duration.ofSeconds(Math.clamp(seconds, MIN_CALL_TIMEOUT_SECONDS, MAX_CALL_TIMEOUT_SECONDS));
	}
}
