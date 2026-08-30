package com.vogella.eclipse.mcp.core.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.vogella.eclipse.mcp.core.McpToolResult;

/**
 * Running several tools as one call, and the expectations that judge them.
 * <p>
 * The steps here use tools that need no workbench, since what is being tested
 * is the sequencing and the checking rather than any one tool: an expectation
 * that passes when it should fail is what would make a scripted test worthless.
 */
class RunScriptToolTest {

	private static final String TOOL = "eclipse_run_script";

	private static final String PROJECT = "RunScriptFixture";

	private final TestFixture fixture = new TestFixture();

	@org.junit.jupiter.api.AfterEach
	void dispose() throws Exception {
		fixture.dispose();
	}

	@org.junit.jupiter.api.BeforeEach
	void createProject() throws Exception {
		fixture.createProject(PROJECT);
	}

	@Test
	@SuppressWarnings("unchecked")
	void stepsRunInOrderAndTheAnswersAreKept() throws Exception {
		Map<String, Object> result = TestFixture.callAndParse(TOOL, Map.of("steps", List.of(
				Map.of("tool", "eclipse_list_projects", "label", "first"),
				Map.of("tool", "eclipse_list_projects", "label", "second"))));

		assertEquals(Integer.valueOf(2), result.get("total"));
		assertEquals(Integer.valueOf(2), result.get("passed"));
		assertEquals(Integer.valueOf(0), result.get("failed"));
		List<Map<String, Object>> steps = (List<Map<String, Object>>) result.get("steps");
		assertEquals("first", steps.get(0).get("label"));
		assertEquals("second", steps.get(1).get("label"));
		// the answer stays a document rather than a string escaped into one
		assertTrue(steps.get(0).get("answer") instanceof Map, "got " + steps.get(0).get("answer"));
	}

	@Test
	@SuppressWarnings("unchecked")
	void anExpectationThatDoesNotHoldFailsTheStepAndStopsTheRest() throws Exception {
		Map<String, Object> result = TestFixture.callAndParse(TOOL, Map.of("steps", List.of(
				Map.of("tool", "eclipse_list_projects", "label", "impossible",
						"expect", Map.of("total", Integer.valueOf(-1))),
				Map.of("tool", "eclipse_list_projects", "label", "never runs"))));

		assertEquals(Integer.valueOf(1), result.get("failed"));
		assertEquals(Boolean.TRUE, result.get("stoppedEarly"));
		List<Map<String, Object>> steps = (List<Map<String, Object>>) result.get("steps");
		assertEquals(Boolean.FALSE, steps.get(0).get("ok"));
		List<Map<String, Object>> failures = (List<Map<String, Object>>) steps.get(0).get("expectationsFailed");
		assertEquals("total", failures.get(0).get("path"), "the failing path has to be named, got " + failures);
		assertNotNull(failures.get(0).get("found"), "and what was there instead, got " + failures);
		assertEquals(Boolean.FALSE, steps.get(1).get("ran"), "a later step must not run after a failure");
	}

	@Test
	@SuppressWarnings("unchecked")
	void continueOnErrorRunsTheRestAnyway() throws Exception {
		Map<String, Object> result = TestFixture.callAndParse(TOOL, Map.of("steps", List.of(
				Map.of("tool", "eclipse_list_projects", "expect", Map.of("total", Integer.valueOf(-1)),
						"continueOnError", Boolean.TRUE),
				Map.of("tool", "eclipse_list_projects", "label", "runs anyway"))));

		assertEquals(Boolean.FALSE, result.get("stoppedEarly"));
		List<Map<String, Object>> steps = (List<Map<String, Object>>) result.get("steps");
		assertEquals(Boolean.TRUE, steps.get(1).get("ran"));
	}

	@Test
	@SuppressWarnings("unchecked")
	void theMatchersJudgeWhatTheyClaimTo() throws Exception {
		Map<String, Object> result = TestFixture.callAndParse(TOOL, Map.of("steps", List.of(
				Map.of("tool", "eclipse_list_projects", "label", "matchers", "expect", Map.of(
						"total", Map.of("exists", Boolean.TRUE),
						"projects", Map.of("exists", Boolean.TRUE),
						"nothingIsCalledThis", Map.of("exists", Boolean.FALSE))))));

		List<Map<String, Object>> steps = (List<Map<String, Object>>) result.get("steps");
		assertEquals(Boolean.TRUE, steps.get(0).get("ok"), "got " + steps.get(0).get("expectationsFailed"));
	}

	@Test
	@SuppressWarnings("unchecked")
	void anEntryOfAListIsPickedByAFieldRatherThanByItsPosition() throws Exception {
		// a position is a statement about the order of a list, and the question is
		// almost always about the entry with a particular value in it
		Map<String, Object> result = TestFixture.callAndParse(TOOL, Map.of("steps", List.of(
				Map.of("tool", "eclipse_list_projects", "label", "by field", "expect", Map.of(
						"projects[name=" + PROJECT + "].name", PROJECT,
						"projects[name=no.such.project].name", Map.of("exists", Boolean.FALSE))))));

		List<Map<String, Object>> steps = (List<Map<String, Object>>) result.get("steps");
		assertEquals(Boolean.TRUE, steps.get(0).get("ok"), "got " + steps.get(0).get("expectationsFailed"));
	}

	@Test
	@SuppressWarnings("unchecked")
	void aSelectorValueMayContainDots() throws Exception {
		// a command id is the usual selector value, and splitting the path on every
		// dot tore it into pieces that matched nothing while looking well formed
		Map<String, Object> result = TestFixture.callAndParse(TOOL, Map.of("steps", List.of(
				Map.of("tool", "eclipse_list_projects", "label", "dotted value", "expect", Map.of(
						"projects[name=" + PROJECT + "].open", Boolean.TRUE)))));

		List<Map<String, Object>> steps = (List<Map<String, Object>>) result.get("steps");
		assertEquals(Boolean.TRUE, steps.get(0).get("ok"), "got " + steps.get(0).get("expectationsFailed"));
	}

	@Test
	void anUnknownToolIsRefusedBeforeAnythingRuns() throws Exception {
		McpToolResult result = TestFixture.call(TOOL,
				Map.of("steps", List.of(Map.of("tool", "eclipse_no_such_tool"))));

		assertTrue(result.isError(), result.text());
		assertTrue(result.text().contains("eclipse_no_such_tool"), result.text());
	}

	@Test
	void aScriptCannotContainItself() throws Exception {
		McpToolResult result = TestFixture.call(TOOL, Map.of("steps", List.of(Map.of("tool", TOOL))));

		assertTrue(result.isError(), result.text());
		assertTrue(result.text().contains("itself"), result.text());
	}

	@Test
	void anEmptyScriptIsRefusedRatherThanReportedAsAllPassing() throws Exception {
		McpToolResult result = TestFixture.call(TOOL, Map.of("steps", List.of()));

		assertTrue(result.isError(), result.text());
	}
}
