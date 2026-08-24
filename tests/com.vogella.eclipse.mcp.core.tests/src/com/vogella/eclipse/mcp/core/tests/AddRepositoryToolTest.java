package com.vogella.eclipse.mcp.core.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.Test;

import com.vogella.eclipse.mcp.core.McpToolResult;

class AddRepositoryToolTest {

	@Test
	void rejectsAUrlWithNoScheme() throws Exception {
		McpToolResult result = TestFixture.call("eclipse_add_repository", Map.of("url", "/home/me/repository"));

		assertTrue(result.isError(), "got " + result.text());
		assertTrue(result.text().contains("no scheme"), "got " + result.text());
	}

	@Test
	void reportsAUrlThatIsNotARepository() throws Exception {
		McpToolResult result = TestFixture.call("eclipse_add_repository",
				Map.of("url", "file:/home/me/not-a-p2-repository-at-all"));

		assertTrue(result.isError(), "got " + result.text());
		assertTrue(result.text().contains("Could not read a p2 repository"), "got " + result.text());
	}

	@Test
	@SuppressWarnings("unchecked")
	void removingAUrlThatWasNeverConfiguredChangesNothing() throws Exception {
		Map<String, Object> result = TestFixture.callAndParse("eclipse_remove_repository",
				Map.of("url", "file:/home/me/never-configured", "dryRun", Boolean.FALSE));

		assertEquals(Boolean.FALSE, result.get("wasConfigured"), "got " + result);
		assertEquals(Boolean.FALSE, result.get("removed"), "got " + result);
	}
}
