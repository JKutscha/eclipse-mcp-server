package com.vogella.eclipse.mcp.ui.internal;

import org.eclipse.core.runtime.preferences.DefaultScope;
import org.eclipse.core.runtime.preferences.InstanceScope;
import org.eclipse.jface.preference.IPreferenceStore;
import org.eclipse.ui.plugin.AbstractUIPlugin;
import org.eclipse.ui.preferences.ScopedPreferenceStore;
import org.osgi.framework.BundleContext;

import com.vogella.eclipse.mcp.server.McpPreferences;

/**
 * Bundle activator, and owner of the preference store the preference page edits.
 */
public class McpUiPlugin extends AbstractUIPlugin {

	public static final String PLUGIN_ID = "com.vogella.eclipse.mcp.ui"; //$NON-NLS-1$

	private static McpUiPlugin plugin;

	private ScopedPreferenceStore serverPreferences;

	@Override
	public void start(BundleContext context) throws Exception {
		super.start(context);
		plugin = this;
	}

	@Override
	public void stop(BundleContext context) throws Exception {
		plugin = null;
		serverPreferences = null;
		super.stop(context);
	}

	public static McpUiPlugin getDefault() {
		return plugin;
	}

	/** The store for the server bundle's preference node, with the defaults applied. */
	public synchronized IPreferenceStore getServerPreferenceStore() {
		if (serverPreferences == null) {
			DefaultScope.INSTANCE.getNode(McpPreferences.QUALIFIER).putBoolean(McpPreferences.KEY_ENABLED,
					McpPreferences.DEFAULT_ENABLED);
			DefaultScope.INSTANCE.getNode(McpPreferences.QUALIFIER).putInt(McpPreferences.KEY_PORT,
					McpPreferences.DEFAULT_PORT);
			serverPreferences = new ScopedPreferenceStore(InstanceScope.INSTANCE, McpPreferences.QUALIFIER);
		}
		return serverPreferences;
	}
}
