package com.vogella.eclipse.mcp.ui.internal;

import org.eclipse.jface.preference.BooleanFieldEditor;
import org.eclipse.jface.preference.FieldEditorPreferencePage;
import org.eclipse.jface.preference.IntegerFieldEditor;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPreferencePage;

import com.vogella.eclipse.mcp.server.McpPreferences;
import com.vogella.eclipse.mcp.server.McpServerService;

/**
 * Lets the user enable the MCP server and pick the port it listens on.
 */
public class McpPreferencePage extends FieldEditorPreferencePage implements IWorkbenchPreferencePage {

	public McpPreferencePage() {
		super(GRID);
	}

	@Override
	public void init(IWorkbench workbench) {
		setPreferenceStore(McpUiPlugin.getDefault().getServerPreferenceStore());
		setDescription("""
				Exposes read-only information about this IDE to MCP clients over HTTP on the loopback interface.
				Every request has to carry the bearer token from %s.""".formatted(McpServerService.getEndpointFile()));
	}

	@Override
	protected void createFieldEditors() {
		addField(new BooleanFieldEditor(McpPreferences.KEY_ENABLED, "&Enable MCP server", getFieldEditorParent()));
		IntegerFieldEditor port = new IntegerFieldEditor(McpPreferences.KEY_PORT, "&Port:", getFieldEditorParent());
		port.setValidRange(1024, 65535);
		addField(port);
	}

	@Override
	public boolean performOk() {
		boolean result = super.performOk();
		McpServerJob.reconcile();
		return result;
	}
}
