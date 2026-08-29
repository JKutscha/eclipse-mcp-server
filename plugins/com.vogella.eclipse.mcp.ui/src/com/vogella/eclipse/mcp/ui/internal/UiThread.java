package com.vogella.eclipse.mcp.ui.internal;

import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

import org.eclipse.ui.PlatformUI;

import com.vogella.eclipse.mcp.core.McpToolResult;
import com.vogella.eclipse.mcp.core.UiDispatch;
import com.vogella.eclipse.mcp.core.json.JsonObject;

/**
 * Runs work on the UI thread on behalf of a tool call.
 * <p>
 * Always {@code asyncExec} and never {@code syncExec}: the call arrives on a
 * Jetty worker thread, and a busy or blocked UI must not take that thread with
 * it. The timeout is what turns a frozen IDE into an answer instead of a
 * request that never returns.
 */
public final class UiThread {

	static final String NO_WORKBENCH = "There is no running workbench."; //$NON-NLS-1$

	private UiThread() {
	}

	/** What the UI thread answered, or why it did not. Exactly one of the two is set. */
	record Outcome(JsonObject value, String error) {
	}

	/**
	 * What a capped wait came to when a timeout is part of the answer rather than
	 * an error. {@code value} and {@code error} are mutually exclusive, and both are
	 * unset when {@code timedOut} is set.
	 */
	record TimedOutcome(JsonObject value, boolean timedOut, String error) {
	}

	/**
	 * Queues the work and hands back the pending answer, or {@code null} when there
	 * is no workbench to queue it on.
	 * <p>
	 * Catches {@link Throwable} and not {@link RuntimeException}: SWT reports an
	 * exhausted handle table as {@code SWTError}, and an {@code Error} that only
	 * escaped into the event loop would leave this future uncompleted, so the caller
	 * would wait out its whole timeout and then blame a frozen UI for what was
	 * really an exception it could have named.
	 */
	private static CompletableFuture<JsonObject> submit(Supplier<JsonObject> work) {
		if (!PlatformUI.isWorkbenchRunning()) {
			return null;
		}
		CompletableFuture<JsonObject> pending = new CompletableFuture<>();
		PlatformUI.getWorkbench().getDisplay().asyncExec(() -> completeFrom(pending, work));
		return pending;
	}

	/**
	 * Runs the work into the future, treating an {@link Error} exactly like an
	 * exception. Separate from {@link #submit} so that it can be exercised without
	 * a workbench, which is the only place the distinction is visible.
	 */
	public static void completeFrom(CompletableFuture<JsonObject> pending, Supplier<JsonObject> work) {
		try {
			pending.complete(work.get());
		} catch (Throwable e) {
			pending.completeExceptionally(e);
		}
	}

	/** The message for a wait that ended in neither an answer nor a timeout. */
	public static String failure(Exception e) {
		if (e instanceof InterruptedException) {
			Thread.currentThread().interrupt();
			return "The request was interrupted."; //$NON-NLS-1$
		}
		Throwable cause = e.getCause() == null ? e : e.getCause();
		return "The request failed: " + cause; //$NON-NLS-1$
	}

	/**
	 * The same, for a tool whose timeout is an answer rather than a failure. The
	 * future is left uncancelled, because the work keeps running and cancelling
	 * would only drop the record of what it went on to do.
	 */
	static TimedOutcome timed(long timeoutSeconds, Supplier<JsonObject> work) {
		CompletableFuture<JsonObject> pending = submit(work);
		if (pending == null) {
			return new TimedOutcome(null, false, NO_WORKBENCH);
		}
		try {
			return new TimedOutcome(pending.get(timeoutSeconds, TimeUnit.SECONDS), false, null);
		} catch (TimeoutException e) {
			return new TimedOutcome(null, true, null);
		} catch (InterruptedException | ExecutionException e) {
			return new TimedOutcome(null, false, failure(e));
		}
	}

	/**
	 * The executor core tools reach the UI thread through. Inline without a
	 * workbench, so a headless run behaves as if nothing were registered.
	 */
	static final UiDispatch.Executor EXECUTOR = new UiDispatch.Executor() {
		@Override
		public <T> T call(Callable<T> work, int timeoutSeconds) throws Exception {
			if (!PlatformUI.isWorkbenchRunning()) {
				return work.call();
			}
			CompletableFuture<T> pending = new CompletableFuture<>();
			PlatformUI.getWorkbench().getDisplay().asyncExec(() -> {
				try {
					pending.complete(work.call());
				} catch (Throwable e) {
					pending.completeExceptionally(e);
				}
			});
			try {
				return pending.get(timeoutSeconds, TimeUnit.SECONDS);
			} catch (ExecutionException e) {
				throw e.getCause() instanceof Exception cause ? cause : e;
			}
		}
	};

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
		CompletableFuture<JsonObject> pending = submit(work);
		if (pending == null) {
			return new Outcome(null, NO_WORKBENCH);
		}
		try {
			return new Outcome(pending.get(timeoutSeconds, TimeUnit.SECONDS), null);
		} catch (TimeoutException e) {
			pending.cancel(false);
			return new Outcome(null, "The Eclipse UI did not process the request within %d seconds." //$NON-NLS-1$
					.formatted(Long.valueOf(timeoutSeconds)));
		} catch (InterruptedException | ExecutionException e) {
			return new Outcome(null, failure(e));
		}
	}
}
