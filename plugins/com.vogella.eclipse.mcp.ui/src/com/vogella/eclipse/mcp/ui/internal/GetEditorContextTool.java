package com.vogella.eclipse.mcp.ui.internal;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.runtime.Adapters;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.jface.text.ITextSelection;
import org.eclipse.jface.viewers.ISelectionProvider;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.texteditor.ITextEditor;

import com.vogella.eclipse.mcp.core.IMcpTool;
import com.vogella.eclipse.mcp.core.McpToolException;
import com.vogella.eclipse.mcp.core.McpToolResult;
import com.vogella.eclipse.mcp.core.json.JsonObject;

/**
 * Reports the file in the active editor, the cursor position and the selection.
 */
public final class GetEditorContextTool implements IMcpTool {

	private static final int MAX_SELECTED_TEXT = 2000;

	private static final long UI_TIMEOUT_SECONDS = 5;

	@Override
	public String getName() {
		return "eclipse_get_editor_context"; //$NON-NLS-1$
	}

	@Override
	public String getDescription() {
		return "Returns the file currently open in the active Eclipse editor, the cursor position and the current selection. Use this to resolve vague references such as \"this method\" or \"the file I am looking at\"."; //$NON-NLS-1$
	}

	@Override
	public String getInputSchema() {
		return """
				{"type":"object","properties":{},"additionalProperties":false}"""; //$NON-NLS-1$
	}

	@Override
	public McpToolResult call(Map<String, Object> arguments, IProgressMonitor monitor) throws McpToolException {
		if (!PlatformUI.isWorkbenchRunning()) {
			return McpToolResult.of(noEditor().toString());
		}
		CompletableFuture<JsonObject> pending = new CompletableFuture<>();
		// never syncExec from a request thread, a busy UI would block the HTTP worker
		PlatformUI.getWorkbench().getDisplay().asyncExec(() -> {
			try {
				pending.complete(collect());
			} catch (RuntimeException e) {
				pending.completeExceptionally(e);
			}
		});
		try {
			return McpToolResult.of(pending.get(UI_TIMEOUT_SECONDS, TimeUnit.SECONDS).toString());
		} catch (TimeoutException e) {
			pending.cancel(false);
			return McpToolResult.error("The Eclipse UI is busy, try again"); //$NON-NLS-1$
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return McpToolResult.error("The request was interrupted."); //$NON-NLS-1$
		} catch (ExecutionException e) {
			throw new McpToolException("Could not read the editor context", //$NON-NLS-1$
					e.getCause() == null ? e : e.getCause());
		}
	}

	private static JsonObject collect() {
		IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
		IWorkbenchPage page = window == null ? null : window.getActivePage();
		IEditorPart editor = page == null ? null : page.getActiveEditor();
		if (editor == null) {
			return noEditor();
		}
		IFile file = Adapters.adapt(editor.getEditorInput(), IFile.class);
		JsonObject context = new JsonObject().put("hasActiveEditor", true) //$NON-NLS-1$
				.put("title", editor.getTitle()) //$NON-NLS-1$
				.put("path", file == null ? null : file.getFullPath().toString()) //$NON-NLS-1$
				.put("project", file == null ? null : file.getProject().getName()) //$NON-NLS-1$
				.put("dirty", editor.isDirty()); //$NON-NLS-1$
		addSelection(editor, context);
		return context;
	}

	private static void addSelection(IEditorPart editor, JsonObject context) {
		if (!(editor instanceof ITextEditor textEditor)) {
			return;
		}
		ISelectionProvider provider = textEditor.getSelectionProvider();
		if (provider == null || !(provider.getSelection() instanceof ITextSelection selection)) {
			return;
		}
		context.put("cursorLine", selection.getStartLine() + 1); //$NON-NLS-1$
		context.put("cursorOffset", selection.getOffset()); //$NON-NLS-1$
		context.put("selectionLength", selection.getLength()); //$NON-NLS-1$
		String text = selection.getText();
		if (text == null) {
			return;
		}
		boolean truncated = text.length() > MAX_SELECTED_TEXT;
		context.put("selectedText", truncated ? text.substring(0, MAX_SELECTED_TEXT) : text); //$NON-NLS-1$
		context.put("selectedTextTruncated", truncated); //$NON-NLS-1$
	}

	private static JsonObject noEditor() {
		return new JsonObject().put("hasActiveEditor", false); //$NON-NLS-1$
	}
}
