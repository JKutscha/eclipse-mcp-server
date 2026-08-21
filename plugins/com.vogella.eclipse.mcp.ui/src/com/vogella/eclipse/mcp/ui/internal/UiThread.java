package com.vogella.eclipse.mcp.ui.internal;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

import org.eclipse.ui.PlatformUI;

import com.vogella.eclipse.mcp.core.McpToolResult;
import com.vogella.eclipse.mcp.core.json.JsonObject;

/**
 * Runs work on the UI thread on behalf of a tool call.
 * <p>
 * Always {@code asyncExec} and never {@code syncExec}: the call arrives on a
 * Jetty worker thread, and a busy or blocked UI must not take that thread with
 * it. The timeout is what turns a frozen IDE into an answer instead of a
 * request that never returns.
 */
final class UiThread {

	private UiThread() {
	}

	static McpToolResult call(long timeoutSeconds, Supplier<JsonObject> work) {
		if (!PlatformUI.isWorkbenchRunning()) {
			return McpToolResult.error("There is no running workbench."); //$NON-NLS-1$
		}
		CompletableFuture<JsonObject> pending = new CompletableFuture<>();
		PlatformUI.getWorkbench().getDisplay().asyncExec(() -> {
			try {
				pending.complete(work.get());
			} catch (RuntimeException e) {
				pending.completeExceptionally(e);
			}
		});
		try {
			return McpToolResult.of(pending.get(timeoutSeconds, TimeUnit.SECONDS).toString());
		} catch (TimeoutException e) {
			pending.cancel(false);
			return McpToolResult.error("The Eclipse UI did not process the request within %d seconds." //$NON-NLS-1$
					.formatted(Long.valueOf(timeoutSeconds)));
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return McpToolResult.error("The request was interrupted."); //$NON-NLS-1$
		} catch (ExecutionException e) {
			Throwable cause = e.getCause() == null ? e : e.getCause();
			return McpToolResult.error("The request failed: " + cause); //$NON-NLS-1$
		}
	}
}
