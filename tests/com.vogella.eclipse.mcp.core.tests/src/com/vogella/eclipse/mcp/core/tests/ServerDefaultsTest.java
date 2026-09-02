package com.vogella.eclipse.mcp.core.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.eclipse.core.runtime.preferences.DefaultScope;
import org.eclipse.core.runtime.preferences.IEclipsePreferences;
import org.eclipse.core.runtime.preferences.InstanceScope;
import org.eclipse.jface.preference.IPreferenceStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.osgi.service.prefs.BackingStoreException;

import com.vogella.eclipse.mcp.server.McpPreferences;
import com.vogella.eclipse.mcp.ui.internal.McpUiPlugin;

/**
 * The server's defaults come from a preference initializer, and a value a
 * {@code -pluginCustomization} file puts over them has to survive the UI
 * bundle building its preference store.
 * <p>
 * The customization file is applied by writing into the default node after
 * the initializer ran, which is what {@link #customize} does here; the
 * preference page used to write the code's defaults into that same node when it
 * was opened, and that moved an IDE started on a customized port back to 8642.
 */
class ServerDefaultsTest {

	private static final int CUSTOMIZED_PORT = 8645;

	private IEclipsePreferences defaults;

	private IEclipsePreferences instance;

	private String previousInstancePort;

	@BeforeEach
	void setUp() throws BackingStoreException {
		defaults = DefaultScope.INSTANCE.getNode(McpPreferences.QUALIFIER);
		instance = InstanceScope.INSTANCE.getNode(McpPreferences.QUALIFIER);
		previousInstancePort = instance.get(McpPreferences.KEY_PORT, null);
		instance.remove(McpPreferences.KEY_PORT);
		instance.flush();
	}

	@AfterEach
	void tearDown() throws BackingStoreException {
		defaults.putInt(McpPreferences.KEY_PORT, McpPreferences.DEFAULT_PORT);
		defaults.putBoolean(McpPreferences.KEY_ENABLED, McpPreferences.DEFAULT_ENABLED);
		if (previousInstancePort != null) {
			instance.put(McpPreferences.KEY_PORT, previousInstancePort);
		}
		instance.flush();
	}

	@Test
	void theInitializerDeclaresTheDefaults() {
		assertEquals(McpPreferences.DEFAULT_PORT, defaults.getInt(McpPreferences.KEY_PORT, -1));
		assertEquals(McpPreferences.DEFAULT_ENABLED, defaults.getBoolean(McpPreferences.KEY_ENABLED, true));
		assertEquals(McpPreferences.DEFAULT_CALL_TIMEOUT_SECONDS,
				defaults.getInt(McpPreferences.KEY_CALL_TIMEOUT_SECONDS, -1));
	}

	@Test
	void aCustomizedPortIsWhatTheServerReads() {
		customize();

		assertEquals(CUSTOMIZED_PORT, McpPreferences.getPort());
		assertTrue(McpPreferences.isEnabled());
	}

	@Test
	void aCustomizedPortSurvivesThePreferenceStore() {
		customize();

		IPreferenceStore store = McpUiPlugin.getDefault().getServerPreferenceStore();

		assertEquals(CUSTOMIZED_PORT, store.getInt(McpPreferences.KEY_PORT));
		assertEquals(CUSTOMIZED_PORT, store.getDefaultInt(McpPreferences.KEY_PORT));
		assertTrue(store.getBoolean(McpPreferences.KEY_ENABLED));
		assertEquals(CUSTOMIZED_PORT, McpPreferences.getPort(), "the page must not move the server");
	}

	@Test
	void aPortSetInTheWorkspaceStillWins() throws BackingStoreException {
		customize();
		instance.putInt(McpPreferences.KEY_PORT, 8700);
		instance.flush();

		assertEquals(8700, McpPreferences.getPort());
		assertFalse(McpUiPlugin.getDefault().getServerPreferenceStore().isDefault(McpPreferences.KEY_PORT));
	}

	/** What Equinox does with a {@code -pluginCustomization} line for this bundle. */
	private void customize() {
		defaults.putInt(McpPreferences.KEY_PORT, CUSTOMIZED_PORT);
		defaults.putBoolean(McpPreferences.KEY_ENABLED, true);
	}
}
