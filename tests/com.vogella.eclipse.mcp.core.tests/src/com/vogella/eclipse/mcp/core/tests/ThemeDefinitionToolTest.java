package com.vogella.eclipse.mcp.core.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.Test;

import com.vogella.eclipse.mcp.core.IMcpTool;
import com.vogella.eclipse.mcp.core.McpToolResult;

/**
 * The colour and font definition listing.
 * <p>
 * The reading itself needs the workbench colour and font registries, which this
 * headless run does not have, so what is checked here is everything that decides
 * whether a call is worth making at all: that the tool is contributed under the
 * name a client is told to use, that its schema is the one the client validates
 * against, and that bad arguments are refused with a reason rather than reaching
 * the UI thread and coming back as a timeout.
 */
class ThemeDefinitionToolTest {

	private static final String NAME = "eclipse_list_theme_definitions";

	@Test
	void isContributedUnderItsName() {
		IMcpTool tool = TestFixture.tool(NAME);

		assertEquals(NAME, tool.getName());
		assertNotNull(tool.getDescription());
		assertTrue(tool.getDescription().contains("READ ONLY"), "a read-only tool has to say so");
	}

	@Test
	void theSchemaOffersTheFieldsTheDescriptionPromises() throws Exception {
		Map<String, Object> schema = TestFixture.parse(TestFixture.tool(NAME).getInputSchema());

		@SuppressWarnings("unchecked")
		Map<String, Object> properties = (Map<String, Object>) schema.get("properties");
		assertEquals(Boolean.FALSE, schema.get("additionalProperties"), "got " + schema);
		for (String field : new String[] { "kind", "idPattern", "categoryId", "onlyOverridden", "countOnly",
				"maxResults" }) {
			assertTrue(properties.containsKey(field), field + " is missing, got " + properties.keySet());
		}
	}

	@Test
	void anUnknownKindIsRefusedByName() throws Exception {
		McpToolResult result = TestFixture.call(NAME, Map.of("kind", "colours"));

		assertTrue(result.isError(), "got " + result.text());
		assertTrue(result.text().contains("colours"), "the refusal has to quote what was passed, got " + result.text());
	}

	@Test
	void aBrokenIdPatternIsRefusedRatherThanThrown() throws Exception {
		// an unbalanced group reaches Pattern.compile, and a PatternSyntaxException
		// escaping the call would reach the client as a failed tool rather than as a
		// fixable argument
		McpToolResult result = TestFixture.call(NAME, Map.of("idPattern", "tag("));

		assertTrue(result.isError(), "got " + result.text());
		assertTrue(result.text().contains("idPattern"), "got " + result.text());
	}

	@Test
	void aValidPatternGetsPastTheArgumentChecks() throws Exception {
		// no workbench here, so this can only reach the UI hand-off and be told so;
		// what matters is that it is not refused as a bad argument
		McpToolResult result = TestFixture.call(NAME, Map.of("kind", "colors", "idPattern", "tag|comment|string"));

		assertFalse(result.text().contains("idPattern"), "the pattern was valid, got " + result.text());
		assertFalse(result.text().contains("has to be colors"), "the kind was valid, got " + result.text());
	}
}
