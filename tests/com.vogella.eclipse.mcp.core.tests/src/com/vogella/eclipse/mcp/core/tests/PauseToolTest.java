package com.vogella.eclipse.mcp.core.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.Test;

import com.vogella.eclipse.mcp.core.McpToolResult;

/**
 * {@code eclipse_pause} waits for what it was asked and says when it could not.
 */
class PauseToolTest {

	private static final String TOOL = "eclipse_pause";

	@Test
	void waitsForTheRequestedTime() throws Exception {
		long before = System.currentTimeMillis();
		Map<String, Object> result = TestFixture.callAndParse(TOOL, Map.of("millis", 150));
		long elapsed = System.currentTimeMillis() - before;

		assertTrue(elapsed >= 150, "returned after " + elapsed + " ms");
		assertEquals(Boolean.FALSE, result.get("clamped"));
		assertTrue(((Number) result.get("pausedMillis")).longValue() >= 150, result.toString());
	}

	@Test
	void saysWhenTheCallTimeoutCutTheWaitShort() throws Exception {
		// far above any call timeout, so the cap applies and the answer has to say so
		// rather than sit through it; the wait itself is then the capped length
		McpToolResult result = TestFixture.call(TOOL, Map.of("millis", 600_000));
		assertTrue(!result.isError(), result.text());
		Map<String, Object> parsed = TestFixture.parse(result.text());
		assertEquals(Boolean.TRUE, parsed.get("clamped"), result.text());
		assertTrue(String.valueOf(parsed.get("note")).contains("eclipse_pause"), result.text());
	}

	@Test
	void requiresAPositiveTime() throws Exception {
		McpToolResult result = TestFixture.call(TOOL, Map.of("millis", 0));
		assertTrue(result.isError(), result.text());
	}
}
