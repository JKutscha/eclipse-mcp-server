package com.vogella.eclipse.mcp.core.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.vogella.eclipse.mcp.core.McpToolResult;

/**
 * Writing an entry into the Error Log.
 * <p>
 * This tool is fully reachable headless, so it is checked from the consuming
 * end like the clear tool is: what matters is that the entry really arrives in
 * the log file with the severity it was written with.
 */
class LogStatusToolTest {

	@Test
	void writingPutsTheEntryInTheLogFile() throws Exception {
		String message = "logged by the test " + System.nanoTime();
		Map<String, Object> written = TestFixture.callAndParse("eclipse_log_status", Map.of("message", message));

		assertEquals(Boolean.TRUE, written.get("written"), "got " + written);
		assertEquals("info", written.get("severity"), "the default severity is info");
		assertEquals(Boolean.TRUE, written.get("verified"), "got " + written);

		Map<String, Object> read = TestFixture.callAndParse("eclipse_get_log_entries",
				Map.of("messageFilter", message));
		List<Map<String, Object>> entries = entriesOf(read);
		assertEquals(1, entries.size(), "got " + read);
		assertEquals(message, entries.get(0).get("message"));
		assertEquals("info", entries.get(0).get("severity"));
	}

	@Test
	void severitiesReachTheLogFile() throws Exception {
		for (String severity : new String[] { "warning", "error" }) {
			String message = "logged as " + severity + " " + System.nanoTime();
			Map<String, Object> written = TestFixture.callAndParse("eclipse_log_status",
					Map.of("message", message, "severity", severity));
			assertEquals(severity, written.get("severity"));

			Map<String, Object> read = TestFixture.callAndParse("eclipse_get_log_entries",
					Map.of("messageFilter", message, "severity", severity));
			List<Map<String, Object>> entries = entriesOf(read);
			assertEquals(1, entries.size(), "got " + read);
			assertEquals(severity, entries.get(0).get("severity"));
		}
	}

	@Test
	void aStackTraceCanBeAttached() throws Exception {
		String message = "logged with a stack trace " + System.nanoTime();
		Map<String, Object> written = TestFixture.callAndParse("eclipse_log_status",
				Map.of("message", message, "includeStackTrace", Boolean.TRUE));

		Map<String, Object> read = TestFixture.callAndParse("eclipse_get_log_entries",
				Map.of("messageFilter", message, "includeStackTraces", Boolean.TRUE));
		assertNotNull(entriesOf(read).get(0).get("stackTrace"), "got " + read);
		assertEquals(Boolean.TRUE, written.get("stackTrace"));
	}

	@Test
	void anUnknownSeverityIsRefused() throws Exception {
		McpToolResult result = TestFixture.call("eclipse_log_status",
				Map.of("message", "never written", "severity", "fatal"));

		assertTrue(result.isError());
		assertTrue(result.text().contains("severity"), result.text());
	}

	@Test
	void theMessageIsRequired() throws Exception {
		McpToolResult result = TestFixture.call("eclipse_log_status", Map.of());

		assertTrue(result.isError());
		assertTrue(result.text().contains("'message' is required"), result.text());
	}

	private static List<Map<String, Object>> entriesOf(Map<String, Object> read) {
		@SuppressWarnings("unchecked")
		List<Map<String, Object>> entries = (List<Map<String, Object>>) read.get("entries");
		return entries;
	}
}
