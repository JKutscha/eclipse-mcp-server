package com.vogella.eclipse.mcp.ui.internal;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.core.runtime.jobs.IJobChangeEvent;
import org.eclipse.core.runtime.jobs.JobChangeAdapter;
import org.eclipse.jface.preference.BooleanFieldEditor;
import org.eclipse.jface.preference.FieldEditorPreferencePage;
import org.eclipse.jface.preference.IntegerFieldEditor;
import org.eclipse.swt.SWT;
import org.eclipse.swt.dnd.Clipboard;
import org.eclipse.swt.dnd.TextTransfer;
import org.eclipse.swt.dnd.Transfer;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Group;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Text;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPreferencePage;
import org.eclipse.ui.PlatformUI;

import com.vogella.eclipse.mcp.server.McpEndpoint;
import com.vogella.eclipse.mcp.server.McpPreferences;
import com.vogella.eclipse.mcp.server.McpServerService;

/**
 * Lets the user enable the MCP server, pick the port, and copy the endpoint a client
 * has to be configured with.
 */
public class McpPreferencePage extends FieldEditorPreferencePage implements IWorkbenchPreferencePage {

	private static final int FIELD_WIDTH_HINT = 320;

	private final List<Button> copyButtons = new ArrayList<>();

	private Label status;

	private Text url;

	private Text token;

	public McpPreferencePage() {
		super(GRID);
	}

	@Override
	public void init(IWorkbench workbench) {
		setPreferenceStore(McpUiPlugin.getDefault().getServerPreferenceStore());
		setDescription(
				"Exposes read-only information about this IDE to MCP clients over HTTP on the loopback interface.");
	}

	@Override
	protected void createFieldEditors() {
		addField(new BooleanFieldEditor(McpPreferences.KEY_ENABLED, "&Enable MCP server", getFieldEditorParent()));
		IntegerFieldEditor port = new IntegerFieldEditor(McpPreferences.KEY_PORT, "&Port:", getFieldEditorParent());
		port.setValidRange(1024, 65535);
		addField(port);
	}

	@Override
	protected Control createContents(Composite parent) {
		Composite page = new Composite(parent, SWT.NONE);
		GridLayout layout = new GridLayout();
		layout.marginWidth = 0;
		layout.marginHeight = 0;
		page.setLayout(layout);

		Control fields = super.createContents(page);
		fields.setLayoutData(new GridData(SWT.FILL, SWT.BEGINNING, true, false));

		createEndpointGroup(page);
		refresh();
		return page;
	}

	private void createEndpointGroup(Composite parent) {
		Group group = new Group(parent, SWT.NONE);
		group.setText("Endpoint");
		group.setLayout(new GridLayout(3, false));
		group.setLayoutData(new GridData(SWT.FILL, SWT.BEGINNING, true, false));

		status = new Label(group, SWT.NONE);
		status.setLayoutData(new GridData(SWT.FILL, SWT.BEGINNING, true, false, 3, 1));

		url = addCopyableField(group, "&URL:", true);
		token = addCopyableField(group, "&Token:", true);
		addCopyableField(group, "&File:", false).setText(McpServerService.getEndpointFile().toString());

		Label hint = new Label(group, SWT.WRAP);
		hint.setText("Send the token as an Authorization: Bearer header. It changes with every IDE restart.");
		GridData hintLayout = new GridData(SWT.FILL, SWT.BEGINNING, true, false, 3, 1);
		hintLayout.widthHint = FIELD_WIDTH_HINT;
		hint.setLayoutData(hintLayout);
	}

	/**
	 * @param onlyWhenRunning whether the copy button is greyed out while the server is stopped
	 */
	private Text addCopyableField(Composite parent, String label, boolean onlyWhenRunning) {
		new Label(parent, SWT.NONE).setText(label);

		Text text = new Text(parent, SWT.READ_ONLY | SWT.BORDER);
		GridData textLayout = new GridData(SWT.FILL, SWT.CENTER, true, false);
		textLayout.widthHint = FIELD_WIDTH_HINT;
		text.setLayoutData(textLayout);

		Button copy = new Button(parent, SWT.PUSH);
		copy.setText("Cop&y");
		copy.addListener(SWT.Selection, event -> copyToClipboard(text.getText()));
		if (onlyWhenRunning) {
			copyButtons.add(copy);
		}
		return text;
	}

	private static void copyToClipboard(String value) {
		Clipboard clipboard = new Clipboard(PlatformUI.getWorkbench().getDisplay());
		try {
			clipboard.setContents(new Object[] { value }, new Transfer[] { TextTransfer.getInstance() });
		} finally {
			clipboard.dispose();
		}
	}

	@Override
	public boolean performOk() {
		boolean result = super.performOk();
		McpServerJob.reconcile().addJobChangeListener(new JobChangeAdapter() {
			@Override
			public void done(IJobChangeEvent event) {
				refreshLater();
			}
		});
		return result;
	}

	private void refreshLater() {
		PlatformUI.getWorkbench().getDisplay().asyncExec(() -> {
			if (url != null && !url.isDisposed()) {
				refresh();
			}
		});
	}

	private void refresh() {
		McpEndpoint endpoint = McpServerService.getInstance().getEndpoint();
		boolean running = endpoint != null;
		status.setText(running ? "The server is listening."
				: "The server is not running. Enable it above and press Apply.");
		url.setText(running ? endpoint.url() : "");
		token.setText(running ? endpoint.token() : "");
		copyButtons.forEach(button -> button.setEnabled(running));
	}
}
