package com.vogella.eclipse.mcp.core.tests;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.Test;

import com.vogella.eclipse.mcp.core.McpToolResult;

/**
 * The theme tools, as far as they can be reached without a workbench.
 * <p>
 * The registry tests already check name, description and schema; what is worth
 * its own test here is the argument handling that happens before the UI thread
 * is involved, and the clean refusal of an IDE whose workbench is not running.
 */
class ThemeToolsTest {

	@Test
	void setThemeNamesItsRequiredArgument() throws Exception {
		McpToolResult result = TestFixture.call("eclipse_set_theme", Map.of());

		assertTrue(result.isError(), result.text());
		assertTrue(result.text().contains("'theme' is required"), result.text());
	}

	@Test
	void bothRefuseCleanlyWithoutAWorkbench() throws Exception {
		assertRefused("eclipse_list_themes", Map.of());
		assertRefused("eclipse_set_theme", Map.of("theme", "org.eclipse.e4.ui.css.theme.e4_dark"));
		assertRefused("eclipse_set_theme", Map.of("theme", "Dark", "persist", Boolean.FALSE));
	}

	private static void assertRefused(String tool, Map<String, Object> arguments) throws Exception {
		McpToolResult result = TestFixture.call(tool, arguments);
		assertTrue(result.isError(), "expected an error, got " + result.text());
		assertTrue(result.text().toLowerCase().contains("no running workbench"),
				"expected a refusal without a workbench, got " + result.text());
	}
}
