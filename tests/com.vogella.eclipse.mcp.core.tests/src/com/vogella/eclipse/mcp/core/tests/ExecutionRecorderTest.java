package com.vogella.eclipse.mcp.core.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.Map;

import org.junit.jupiter.api.Test;

import com.vogella.eclipse.mcp.core.json.JsonObject;
import com.vogella.eclipse.mcp.ui.internal.ExecutionRecorder;

/**
 * The verdicts eclipse_run_workbench_command hears from the command framework.
 * <p>
 * This is the headless part of the handler verdict: which of the framework's
 * callbacks count as the handler having run, and how that reads in an answer.
 * The timeout answer is exactly what a caller reads once
 * eclipse_dismiss_dialog has done its job, so the mapping is what matters and
 * it needs no workbench to exercise.
 */
class ExecutionRecorderTest {

	@Test
	void nothingFiredReadsAsUnfinishedWithNoVerdict() throws Exception {
		Map<String, Object> described = describedBy(new ExecutionRecorder());

		assertEquals(Boolean.FALSE, described.get("handlerFinished"), "got " + described);
		assertNull(described.get("outcome"));
	}

	@Test
	void successAndFailureCountAsTheHandlerHavingRun() throws Exception {
		for (String verb : new String[] { "success", "failure" }) {
			ExecutionRecorder recorder = new ExecutionRecorder();
			if ("success".equals(verb)) {
				recorder.postExecuteSuccess("some.command", null);
			} else {
				// the exception itself is never read, only the fact of the callback
				recorder.postExecuteFailure("some.command", null);
			}

			Map<String, Object> described = describedBy(recorder);
			assertEquals(Boolean.TRUE, described.get("handlerFinished"), verb + " is a verdict, got " + described);
			assertEquals(verb, described.get("outcome"));
		}
	}

	@Test
	void notHandledIsAVerdictButNotARun() throws Exception {
		ExecutionRecorder recorder = new ExecutionRecorder();
		recorder.notHandled("some.command", null);

		Map<String, Object> described = describedBy(recorder);
		assertEquals(Boolean.FALSE, described.get("handlerFinished"), "got " + described);
		assertEquals("notHandled", described.get("outcome"));
	}

	@Test
	void aSecondCallbackOverwritesTheFirst() throws Exception {
		ExecutionRecorder recorder = new ExecutionRecorder();
		recorder.notHandled("some.command", null);
		recorder.postExecuteSuccess("some.command", null);

		assertEquals("success", TestFixture.parse(recorder.reportInto(new JsonObject()).toString()).get("outcome"));
	}

	private static Map<String, Object> describedBy(ExecutionRecorder recorder) throws Exception {
		return TestFixture.parse(recorder.reportInto(new JsonObject()).toString());
	}
}
