package com.vogella.eclipse.mcp.ui.internal;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.IEditorReference;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;

import com.vogella.eclipse.mcp.core.IMcpTool;
import com.vogella.eclipse.mcp.core.McpToolResult;
import com.vogella.eclipse.mcp.core.ToolArguments;
import com.vogella.eclipse.mcp.core.json.JsonArray;
import com.vogella.eclipse.mcp.core.json.JsonObject;

/**
 * Restarts the IDE, after answering.
 */
public final class RestartTool implements IMcpTool {

	private static final long UI_TIMEOUT_SECONDS = 10;

	/** Long enough for the HTTP response to be on the wire before the server dies with the IDE. */
	private static final int RESTART_DELAY_MILLIS = 2000;

	@Override
	public String getName() {
		return "eclipse_restart"; //$NON-NLS-1$
	}

	@Override
	public String getDescription() {
		return "Restarts the Eclipse IDE into the same workspace, which is what makes an installed or updated feature active. The answer names the workspace it will return to. THE CONNECTION WILL DROP BY DESIGN: this tool answers first and restarts a couple of seconds later, so a dropped connection right after a successful result is the expected outcome and not a failure. Reconnect with the same bearer token, which survives restarts and updates. Refuses when editors have unsaved changes or a modal dialog is open, unless save or force is passed. It works independently of eclipse_update, so a half applied update can still be recovered by restarting."; //$NON-NLS-1$
	}

	@Override
	public String getInputSchema() {
		return """
				{
				  "type": "object",
				  "properties": {
				    "save":  {"type":"boolean","default":false,"description":"Save dirty editors first, then restart."},
				    "force": {"type":"boolean","default":false,"description":"Restart even with unsaved changes or an open modal dialog. Discards that work."}
				  },
				  "additionalProperties": false
				}"""; //$NON-NLS-1$
	}

	@Override
	public McpToolResult call(Map<String, Object> arguments, IProgressMonitor monitor) {
		if (!PlatformUI.isWorkbenchRunning()) {
			return McpToolResult.error("There is no running workbench to restart."); //$NON-NLS-1$
		}
		ToolArguments args = ToolArguments.of(arguments);
		boolean save = args.getBoolean("save", false); //$NON-NLS-1$
		boolean force = args.getBoolean("force", false); //$NON-NLS-1$

		CompletableFuture<JsonObject> pending = new CompletableFuture<>();
		PlatformUI.getWorkbench().getDisplay().asyncExec(() -> {
			try {
				pending.complete(prepare(save, force));
			} catch (RuntimeException e) {
				pending.completeExceptionally(e);
			}
		});
		try {
			JsonObject result = pending.get(UI_TIMEOUT_SECONDS, TimeUnit.SECONDS);
			return Boolean.TRUE.equals(result.remove("restarting")) //$NON-NLS-1$
					? McpToolResult.of(result.toString())
					: McpToolResult.error(result.toString());
		} catch (TimeoutException e) {
			pending.cancel(false);
			return McpToolResult.error("The Eclipse UI is busy, try again."); //$NON-NLS-1$
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return McpToolResult.error("The request was interrupted."); //$NON-NLS-1$
		} catch (ExecutionException e) {
			return McpToolResult.error("Could not restart: " + (e.getCause() == null ? e : e.getCause()));
		}
	}

	private static JsonObject prepare(boolean save, boolean force) {
		IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
		IWorkbenchPage page = window == null ? null : window.getActivePage();
		JsonArray dirty = new JsonArray();
		if (page != null) {
			for (IEditorReference reference : page.getEditorReferences()) {
				if (reference.isDirty()) {
					dirty.add(reference.getTitle());
				}
			}
		}
		JsonArray modal = new JsonArray();
		for (Shell shell : PlatformUI.getWorkbench().getDisplay().getShells()) {
			boolean isModal = (shell.getStyle()
					& (SWT.APPLICATION_MODAL | SWT.PRIMARY_MODAL | SWT.SYSTEM_MODAL)) != 0;
			if (isModal && shell.isVisible()) {
				modal.add(shell.getText());
			}
		}
		if (dirty.size() > 0 && save && page != null) {
			page.saveAllEditors(false);
			dirty = new JsonArray();
		}
		if (!force && (dirty.size() > 0 || modal.size() > 0)) {
			return new JsonObject().put("restarting", Boolean.FALSE) //$NON-NLS-1$
					.put("dirtyEditors", dirty) //$NON-NLS-1$
					.put("openModalDialogs", modal) //$NON-NLS-1$
					.put("reason", //$NON-NLS-1$
							"Refused: restarting now would lose unsaved work. Pass save to save the editors first, or force to discard."); //$NON-NLS-1$
		}
		// answer first, restart after: the server dies with the IDE, so restarting
		// inside the call gives the caller a dropped connection instead of a result
		Display display = PlatformUI.getWorkbench().getDisplay();
		// restart(true), not restart(): the no argument form relaunches without -data,
		// so the IDE comes back up asking for a workspace and waits for a human
		display.timerExec(RESTART_DELAY_MILLIS, () -> PlatformUI.getWorkbench().restart(true));
		return new JsonObject().put("restarting", Boolean.TRUE) //$NON-NLS-1$
				.put("inMillis", RESTART_DELAY_MILLIS) //$NON-NLS-1$
				.put("workspace", workspaceLocation()) //$NON-NLS-1$
				.put("savedEditors", save) //$NON-NLS-1$
				.put("note", //$NON-NLS-1$
						"The connection will drop when the IDE goes down. Reconnect with the same bearer token, which is kept in the bundle state location and survives restarts and updates. The IDE is relaunched into the workspace named above; if it comes back asking which workspace to use, the relaunch lost its arguments and a human has to answer the chooser."); //$NON-NLS-1$
	}

	/** The workspace the IDE is expected to come back into. */
	private static String workspaceLocation() {
		var location = org.eclipse.core.runtime.Platform.getInstanceLocation();
		return location == null || location.getURL() == null ? null : location.getURL().toString();
	}
}
