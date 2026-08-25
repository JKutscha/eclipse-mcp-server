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

	/** What the UI thread answered, or why it did not. Exactly one of the two is set. */
	record Outcome(JsonObject value, String error) {
	}

	static McpToolResult call(long timeoutSeconds, Supplier<JsonObject> work) {
		Outcome outcome = run(timeoutSeconds, work);
		return outcome.error() == null ? McpToolResult.of(outcome.value().toString())
				: McpToolResult.error(outcome.error());
	}

	/**
	 * The same, for a caller that is not answering a tool call of its own and has
	 * to fold the failure into an answer somebody else is writing.
	 */
	static Outcome run(long timeoutSeconds, Supplier<JsonObject> work) {
		if (!PlatformUI.isWorkbenchRunning()) {
			return new Outcome(null, "There is no running workbench."); //$NON-NLS-1$
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
			return new Outcome(pending.get(timeoutSeconds, TimeUnit.SECONDS), null);
		} catch (TimeoutException e) {
			pending.cancel(false);
			return new Outcome(null, "The Eclipse UI did not process the request within %d seconds." //$NON-NLS-1$
					.formatted(Long.valueOf(timeoutSeconds)));
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return new Outcome(null, "The request was interrupted."); //$NON-NLS-1$
		} catch (ExecutionException e) {
			Throwable cause = e.getCause() == null ? e : e.getCause();
			return new Outcome(null, "The request failed: " + cause); //$NON-NLS-1$
		}
	}
}
