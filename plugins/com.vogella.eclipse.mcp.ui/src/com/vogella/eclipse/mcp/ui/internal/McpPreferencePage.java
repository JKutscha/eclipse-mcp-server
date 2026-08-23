package com.vogella.eclipse.mcp.ui.internal;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.IJobChangeEvent;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.core.runtime.jobs.JobChangeAdapter;
import org.eclipse.jface.dialogs.MessageDialog;
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
import com.vogella.eclipse.mcp.server.McpServerException;
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

	private Button tokenCopy;

	private Button lastCopyButton;

	public McpPreferencePage() {
		super(GRID);
	}

	@Override
	public void init(IWorkbench workbench) {
		setPreferenceStore(McpUiPlugin.getDefault().getServerPreferenceStore());
		setDescription(
				"Exposes information about this IDE to MCP clients over HTTP on the loopback interface. Most tools only read; a few format a file, run a build, open and close projects or change plug-in and IDE settings.");
	}

	@Override
	protected void createFieldEditors() {
		addField(new BooleanFieldEditor(McpPreferences.KEY_ENABLED, "&Enable MCP server", getFieldEditorParent()));
		IntegerFieldEditor port = new IntegerFieldEditor(McpPreferences.KEY_PORT, "&Port:", getFieldEditorParent());
		port.setValidRange(1024, 65535);
		addField(port);
		IntegerFieldEditor timeout = new IntegerFieldEditor(McpPreferences.KEY_CALL_TIMEOUT_SECONDS,
				"&Tool call timeout (seconds):", getFieldEditorParent());
		timeout.setValidRange(McpPreferences.MIN_CALL_TIMEOUT_SECONDS, McpPreferences.MAX_CALL_TIMEOUT_SECONDS);
		addField(timeout);
		addField(new MultiLineStringFieldEditor(McpPreferences.KEY_REPOSITORY_ROOTS,
				"Allowed p2 &repository roots (one URL prefix per line):", getFieldEditorParent()));
		addField(new MultiLineStringFieldEditor(McpPreferences.KEY_COMMAND_ROOTS,
				"Directories &commands may run in (one path per line, empty disables):", getFieldEditorParent()));
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
		token = addCopyableField(group, "&Token:", false);
		tokenCopy = lastCopyButton;
		addCopyableField(group, "&File:", false).setText(McpServerService.getEndpointFile().toString());

		Button regenerate = new Button(group, SWT.PUSH);
		regenerate.setText("&Regenerate token");
		regenerate.setLayoutData(new GridData(SWT.END, SWT.BEGINNING, true, false, 3, 1));
		regenerate.addListener(SWT.Selection, event -> regenerateToken());

		Label hint = new Label(group, SWT.WRAP);
		hint.setText("Send the token as an Authorization: Bearer header. It is kept across IDE restarts, so a client has to be configured only once. Regenerating it invalidates every configured client.");
		GridData hintLayout = new GridData(SWT.FILL, SWT.BEGINNING, true, false, 3, 1);
		hintLayout.widthHint = FIELD_WIDTH_HINT;
		hint.setLayoutData(hintLayout);
	}

	private void regenerateToken() {
		if (!MessageDialog.openConfirm(getShell(), "Regenerate token",
				"Every MCP client configured with the current token will be rejected until it is updated. Continue?")) {
			return;
		}
		Job job = Job.create("Regenerating the MCP token", monitor -> {
			try {
				McpServerService.getInstance().regenerateToken();
			} catch (McpServerException e) {
				ILog.get().error(e.getMessage(), e.getCause() == null ? e : e.getCause());
			}
			return Status.OK_STATUS;
		});
		job.addJobChangeListener(new JobChangeAdapter() {
			@Override
			public void done(IJobChangeEvent event) {
				refreshLater();
			}
		});
		job.schedule();
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
		lastCopyButton = copy;
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
		String error = McpServerService.getInstance().getLastError();
		// the token is persisted, so it exists and is worth showing whether or not the
		// server is up. Blanking it while stopped made "Regenerate token" look like a
		// button that does nothing, because the only visible result was hidden
		String persisted = running ? endpoint.token() : McpServerService.getToken();
		status.setText(running ? "The server is listening."
				: error != null ? error
						: persisted == null ? "The server is not running. Enable it above and press Apply."
								: "The server is not running. Enable it above and press Apply. The token below is already generated and will be used when it starts.");
		url.setText(running ? endpoint.url() : "");
		token.setText(persisted == null ? "" : persisted);
		copyButtons.forEach(button -> button.setEnabled(running));
		if (tokenCopy != null) {
			tokenCopy.setEnabled(persisted != null);
		}
	}
}
