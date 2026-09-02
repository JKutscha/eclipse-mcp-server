package com.vogella.eclipse.mcp.server.internal;

import org.eclipse.core.runtime.preferences.AbstractPreferenceInitializer;
import org.eclipse.core.runtime.preferences.DefaultScope;
import org.eclipse.core.runtime.preferences.IEclipsePreferences;

import com.vogella.eclipse.mcp.server.McpPreferences;

/**
 * Declares the server's defaults to the preference service.
 * <p>
 * Equinox runs this as the first step of loading the default node and applies
 * the product's {@code plugin_customization.ini} and the command line's
 * {@code -pluginCustomization} file after it, so a value from either wins over
 * what is written here. Writing the same values into the default scope from
 * ordinary code, which is what the preference page used to do when it was
 * opened, runs after that load and overwrites a customized value, which is how
 * an IDE started on one port moved to another as soon as its preference page
 * was shown.
 */
public final class McpPreferenceInitializer extends AbstractPreferenceInitializer {

	@Override
	public void initializeDefaultPreferences() {
		IEclipsePreferences node = DefaultScope.INSTANCE.getNode(McpPreferences.QUALIFIER);
		node.putBoolean(McpPreferences.KEY_ENABLED, McpPreferences.DEFAULT_ENABLED);
		node.putInt(McpPreferences.KEY_PORT, McpPreferences.DEFAULT_PORT);
		node.putInt(McpPreferences.KEY_CALL_TIMEOUT_SECONDS, McpPreferences.DEFAULT_CALL_TIMEOUT_SECONDS);
	}
}
