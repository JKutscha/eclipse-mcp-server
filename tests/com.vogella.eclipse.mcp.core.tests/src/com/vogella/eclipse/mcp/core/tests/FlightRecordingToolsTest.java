package com.vogella.eclipse.mcp.core.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.vogella.eclipse.mcp.core.McpToolResult;

/**
 * Recording this JVM and reading the recording back.
 * <p>
 * This is one of the few tools that can be exercised end to end headlessly,
 * because the thing it profiles is the test JVM itself. That is worth using: an
 * aggregation that never saw a real recording proves nothing.
 */
class FlightRecordingToolsTest {

	private static final String START = "eclipse_start_flight_recording";

	private static final String STOP = "eclipse_stop_flight_recording";

	@Test
	void refusesSettingsItDoesNotKnow() throws Exception {
		McpToolResult result = TestFixture.call(START, Map.of("settings", "everything"));

		assertTrue(result.isError(), result.text());
		assertTrue(result.text().contains("everything"), result.text());
	}

	@Test
	void recordsThisJvmAndFindsWhatTheTestAllocated() throws Exception {
		Map<String, Object> started = TestFixture.callAndParse(START,
				Map.of("settings", "profile", "durationSeconds", Integer.valueOf(0), "maxSizeMegabytes",
						Integer.valueOf(16)));
		String id = String.valueOf(started.get("recordingId"));
		assumeTrue(started.get("recordingId") != null, "this JVM does not expose jdk.jfr, got " + started);
		assertEquals(Boolean.TRUE, started.get("running"), "got " + started);

		try {
			allocate();

			Map<String, Object> stopped = TestFixture.callAndParse(STOP,
					Map.of("recordingId", id, "topClasses", Integer.valueOf(30)));

			assertEquals(Boolean.FALSE, stopped.get("stillRunning"), "got " + stopped);
			assertTrue(((Number) stopped.get("eventsRead")).intValue() > 0, "the recording should hold events");
			assertNotNull(stopped.get("allocationByClass"));
			assertNotNull(stopped.get("gc"));
			// the point of the whole tool: the caller of the allocation has to be named,
			// which is what a class on its own never says
			assertTrue(names(stopped, "allocationByStack", "stack").stream()
					.anyMatch(stack -> stack.contains("FlightRecordingToolsTest.allocate")),
					"the test's own allocating frame should be among the stacks, got " + stopped);
			assertTrue(names(stopped, "allocationByClass", "class").size() > 0, "got " + stopped);
		} finally {
			// the recording is closed by the stop above; this only matters when an
			// assertion failed before it ran
			TestFixture.call(STOP, Map.of("recordingId", id));
		}
	}

	@Test
	void readingWithoutARecordingSaysWhichToolStartsOne() throws Exception {
		// the id cannot exist: the registry hands out jfr-<counter>
		McpToolResult result = TestFixture.call(STOP, Map.of("recordingId", "jfr-does-not-exist"));

		assertTrue(result.isError(), result.text());
		assertTrue(result.text().contains("jfr-does-not-exist"), result.text());
	}

	/** Enough short lived objects that the sampler is certain to see some. */
	private static void allocate() {
		List<byte[]> keep = new ArrayList<>();
		for (int i = 0; i < 20_000; i++) {
			byte[] block = new byte[4096];
			block[0] = (byte) i;
			if (i % 500 == 0) {
				keep.add(block);
			}
		}
		assertTrue(keep.size() > 0);
	}

	@SuppressWarnings("unchecked")
	private static List<String> names(Map<String, Object> result, String field, String key) {
		List<String> names = new ArrayList<>();
		for (Map<String, Object> entry : (List<Map<String, Object>>) result.get(field)) {
			names.add(String.valueOf(entry.get(key)));
		}
		return names;
	}
}
