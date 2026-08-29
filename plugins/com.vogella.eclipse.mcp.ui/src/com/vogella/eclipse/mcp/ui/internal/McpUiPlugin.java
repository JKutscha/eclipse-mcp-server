package com.vogella.eclipse.mcp.ui.internal;

import org.eclipse.core.runtime.preferences.DefaultScope;
import org.eclipse.core.runtime.preferences.InstanceScope;
import org.eclipse.jface.preference.IPreferenceStore;
import org.eclipse.ui.plugin.AbstractUIPlugin;
import org.eclipse.ui.preferences.ScopedPreferenceStore;
import org.osgi.framework.BundleContext;

import com.vogella.eclipse.mcp.core.LogClearedHandlers;
import com.vogella.eclipse.mcp.core.UiDispatch;
import com.vogella.eclipse.mcp.server.McpPreferences;

/**
 * Bundle activator, and owner of the preference store the preference page edits.
 */
public class McpUiPlugin extends AbstractUIPlugin {

	public static final String PLUGIN_ID = "com.vogella.eclipse.mcp.ui"; //$NON-NLS-1$

	private static McpUiPlugin plugin;

	private ScopedPreferenceStore serverPreferences;

	private final ErrorLogRefresh errorLogRefresh = new ErrorLogRefresh();

	@Override
	public void start(BundleContext context) throws Exception {
		super.start(context);
		plugin = this;
		// the core bundle clears the log file and must not know about any view, so
		// the part that empties the view is registered from this side
		LogClearedHandlers.set(errorLogRefresh);
		// a preference write from a worker thread reaches editor listeners that
		// assume the UI thread, so core writes through this
		UiDispatch.set(UiThread.EXECUTOR);
	}

	@Override
	public void stop(BundleContext context) throws Exception {
		LogClearedHandlers.unset(errorLogRefresh);
		UiDispatch.unset(UiThread.EXECUTOR);
		// a hidden window has no menu to bring it back, so the plug-in going away
		// must not be the moment the IDE becomes unrecoverable
		VisibilityTool.restoreIfHidden();
		// the same reasoning for an ad-hoc stylesheet: it can leave the IDE unreadable
		CssStyling.dropIfApplied();
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
			DefaultScope.INSTANCE.getNode(McpPreferences.QUALIFIER).putInt(McpPreferences.KEY_CALL_TIMEOUT_SECONDS,
					McpPreferences.DEFAULT_CALL_TIMEOUT_SECONDS);
			serverPreferences = new ScopedPreferenceStore(InstanceScope.INSTANCE, McpPreferences.QUALIFIER);
		}
		return serverPreferences;
	}
}
