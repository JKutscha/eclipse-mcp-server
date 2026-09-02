package com.vogella.eclipse.mcp.core.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import org.junit.jupiter.api.Test;

import com.vogella.eclipse.mcp.core.json.JsonObject;
import com.vogella.eclipse.mcp.ui.internal.UiThread;

/**
 * How work handed to the UI thread reports what went wrong with it.
 * <p>
 * The distinction that matters is between an answer that never arrived and one
 * that failed: a tool call which waits out its whole timeout tells the caller
 * the IDE is frozen, and that is the wrong thing to say about work that threw
 * on the first line. These tests drive the capture directly, because the run
 * has no workbench to queue anything on.
 */
class UiThreadTest {

	@Test
	void anAnswerCompletesTheFuture() {
		CompletableFuture<JsonObject> pending = new CompletableFuture<>();
		JsonObject answer = new JsonObject().put("ok", Boolean.TRUE);

		UiThread.completeFrom(pending, () -> answer);

		assertTrue(pending.isDone());
		assertSame(answer, pending.getNow(null));
	}

	@Test
	void anExceptionIsReportedRatherThanSwallowed() {
		CompletableFuture<JsonObject> pending = new CompletableFuture<>();
		IllegalStateException thrown = new IllegalStateException("the part was disposed");

		UiThread.completeFrom(pending, () -> {
			throw thrown;
		});

		assertTrue(pending.isCompletedExceptionally());
		assertSame(thrown, assertThrows(ExecutionException.class, pending::get).getCause());
	}

	@Test
	void anErrorIsReportedTooAndDoesNotLeaveTheCallWaiting() {
		// SWT reports an exhausted handle table as SWTError, which is an Error and not
		// a RuntimeException. Catching only the latter left this future uncompleted,
		// so the caller waited out its timeout and then blamed a frozen UI for it
		CompletableFuture<JsonObject> pending = new CompletableFuture<>();
		Error thrown = new Error("no more handles");

		UiThread.completeFrom(pending, () -> {
			throw thrown;
		});

		assertTrue(pending.isCompletedExceptionally(), "an Error has to complete the future, not escape it");
		assertSame(thrown, assertThrows(ExecutionException.class, pending::get).getCause());
	}

	@Test
	void workWhoseWaitGaveUpIsNotRunLater() {
		// three eclipse_close_editor calls once answered with a timeout and then
		// closed the editors minutes later, when the UI thread got round to the
		// queued runnables; the wait cancels the future, and that has to be enough
		CompletableFuture<JsonObject> pending = new CompletableFuture<>();
		pending.cancel(false);
		boolean[] ran = { false };

		UiThread.completeFrom(pending, () -> {
			ran[0] = true;
			return new JsonObject();
		});

		assertFalse(ran[0], "withdrawn work must not run");
	}

	@Test
	void theTimeoutAnswerSaysTheWorkWasWithdrawn() {
		String answer = UiThread.TIMED_OUT.formatted(Long.valueOf(15));

		assertTrue(answer.contains("will not run later"), "got " + answer);
	}

	@Test
	void aCapturedFailureIsDescribedByItsCause() {
		String described = UiThread.failure(new ExecutionException(new Error("no more handles")));

		assertTrue(described.contains("no more handles"), "got " + described);
		assertFalse(described.contains("did not process"), "a failure must not read as a timeout, got " + described);
	}

	@Test
	void anInterruptIsNamedAsOneAndRestoresTheFlag() {
		try {
			String described = UiThread.failure(new InterruptedException());

			assertEquals("The request was interrupted.", described);
			assertTrue(Thread.currentThread().isInterrupted(), "the interrupt has to be handed back on");
		} finally {
			// this test is the one that sets it, so it is the one that clears it again
			Thread.interrupted();
		}
	}
}
