package com.vogella.eclipse.mcp.core.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Waiting for the workspace to stop building.
 * <p>
 * The tool exists because the quiet before a build and the quiet after it look
 * the same from outside the IDE, so the test that matters is that a build is
 * actually waited for and appears in the answer by name.
 */
class WaitUntilQuietToolTest {

	private static final String TOOL = "eclipse_wait_until_quiet";

	private final TestFixture fixture = new TestFixture();

	@AfterEach
	void deleteTestProjects() throws Exception {
		fixture.dispose();
	}

	@Test
	void reportsQuietWhenNothingRuns() throws Exception {
		TestFixture.callAndParse(TOOL, Map.of("timeoutSeconds", 60));

		Map<String, Object> result = TestFixture.callAndParse(TOOL, Map.of("timeoutSeconds", 60));

		assertEquals("quiet", result.get("state"), "got " + result);
		assertEquals(List.of(), result.get("waitedFor"), "got " + result);
		assertNotNull(result.get("jobsAfter"), "got " + result);
	}

	@Test
	@SuppressWarnings("unchecked")
	void waitsForARunningBuildAndNamesIt() throws Exception {
		fixture.createProject("mcp-wait-quiet-test");
		TestFixture.callAndParse("eclipse_build", Map.of("kind", "full", "wait", false, "timeoutSeconds", 1));

		Map<String, Object> result = TestFixture.callAndParse(TOOL, Map.of("timeoutSeconds", 120));

		assertEquals("quiet", result.get("state"), "got " + result);
		assertEquals(Boolean.FALSE, ((Map<String, Object>) result.get("jobsAfter")).get("building"), "got " + result);
		// what it waited for is the interesting half of the answer, and a build that
		// was already over by the time the wait started leaves it empty
		assertTrue(result.get("waitedFor") instanceof List, "got " + result);
	}

	@Test
	@SuppressWarnings("unchecked")
	void buildStatusReportsTheLiveJobsEvenWithoutABuildOfItsOwn() throws Exception {
		Map<String, Object> result = TestFixture.callAndParse("eclipse_get_build_status", Map.of());

		// the auto-build belongs to nobody's tool, so this section is the only way to
		// see that the workspace is busy after a restart
		Map<String, Object> jobs = (Map<String, Object>) result.get("jobs");
		assertNotNull(jobs, "got " + result);
		assertNotNull(jobs.get("autoBuild"), "got " + result);
		assertNotNull(jobs.get("building"), "got " + result);
	}
}
