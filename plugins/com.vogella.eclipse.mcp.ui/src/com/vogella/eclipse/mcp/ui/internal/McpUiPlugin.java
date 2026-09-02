package com.vogella.eclipse.mcp.ui.internal;

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

	/**
	 * The store for the server bundle's preference node.
	 * <p>
	 * The defaults are not written here. They are declared by the server bundle's
	 * preference initializer, which the preference service runs before it applies a
	 * {@code -pluginCustomization} file; a write into the default scope from here
	 * would run after that file and replace the port it set, so that opening the
	 * page moved the server off the port the IDE was started with.
	 */
	public synchronized IPreferenceStore getServerPreferenceStore() {
		if (serverPreferences == null) {
			serverPreferences = new ScopedPreferenceStore(InstanceScope.INSTANCE, McpPreferences.QUALIFIER);
		}
		return serverPreferences;
	}
}
