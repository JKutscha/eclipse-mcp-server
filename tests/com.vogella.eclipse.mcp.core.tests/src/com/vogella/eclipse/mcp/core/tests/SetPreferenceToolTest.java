package com.vogella.eclipse.mcp.core.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.IWorkspaceDescription;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.preferences.InstanceScope;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.vogella.eclipse.mcp.core.McpToolResult;

class SetPreferenceToolTest {

	private static final String TOOL = "eclipse_set_preference";

	/** An allowlisted qualifier, with a key nothing else uses. */
	private static final String QUALIFIER = "org.eclipse.core.runtime";

	private static final String KEY = "com.vogella.eclipse.mcp.test.key";

	private final TestFixture fixture = new TestFixture();

	@AfterEach
	void cleanUp() throws Exception {
		InstanceScope.INSTANCE.getNode(QUALIFIER).remove(KEY);
		InstanceScope.INSTANCE.getNode(QUALIFIER).flush();
		fixture.dispose();
	}

	@Test
	void writesAValueAndReportsTheAbsentPrevious() throws Exception {
		Map<String, Object> result = TestFixture.callAndParse(TOOL,
				Map.of("qualifier", QUALIFIER, "key", KEY, "value", "first"));

		assertNull(result.get("previousValue"));
		assertEquals("first", result.get("effective"));
		assertEquals("instance", result.get("effectiveScope"));
		assertEquals("first", InstanceScope.INSTANCE.getNode(QUALIFIER).get(KEY, null));
	}

	@Test
	void returnsThePreviousValueSoTheChangeCanBeUndone() throws Exception {
		TestFixture.callAndParse(TOOL, Map.of("qualifier", QUALIFIER, "key", KEY, "value", "first"));

		Map<String, Object> result = TestFixture.callAndParse(TOOL,
				Map.of("qualifier", QUALIFIER, "key", KEY, "value", "second"));

		assertEquals("first", result.get("previousValue"));
		assertEquals("second", result.get("effective"));
	}

	@Test
	void removesTheKeyWhenNoValueIsGiven() throws Exception {
		TestFixture.callAndParse(TOOL, Map.of("qualifier", QUALIFIER, "key", KEY, "value", "first"));

		Map<String, Object> result = TestFixture.callAndParse(TOOL, Map.of("qualifier", QUALIFIER, "key", KEY));

		assertEquals("first", result.get("previousValue"));
		assertNull(result.get("effective"));
		assertNull(InstanceScope.INSTANCE.getNode(QUALIFIER).get(KEY, null));
	}

	@Test
	void refusesAQualifierThatIsNotOnTheAllowlist() throws Exception {
		McpToolResult result = TestFixture.call(TOOL,
				Map.of("qualifier", "org.eclipse.ui.editors", "key", KEY, "value", "x"));

		assertTrue(result.isError());
		assertTrue(result.text().contains("org.eclipse.ui.editors"), result.text());
		assertTrue(result.text().contains("eclipse_get_preferences"), result.text());
	}

	@Test
	void rejectsAProjectScopeWithoutAProject() throws Exception {
		McpToolResult result = TestFixture.call(TOOL,
				Map.of("qualifier", QUALIFIER, "key", KEY, "value", "x", "scope", "project"));

		assertTrue(result.isError());
		assertTrue(result.text().contains("project"), result.text());
	}

	@Test
	void appliesAutoBuildThroughTheWorkspaceDescription() throws Exception {
		IWorkspace workspace = ResourcesPlugin.getWorkspace();
		boolean before = workspace.isAutoBuilding();
		try {
			Map<String, Object> result = TestFixture.callAndParse(TOOL, Map.of("qualifier",
					"org.eclipse.core.resources", "key", "description.autobuilding", "value", String.valueOf(!before)));

			assertEquals("IWorkspaceDescription.setAutoBuilding", result.get("appliedThrough"));
			assertEquals(String.valueOf(!before), result.get("effective"));
			assertEquals(!before, workspace.isAutoBuilding());
		} finally {
			IWorkspaceDescription description = workspace.getDescription();
			description.setAutoBuilding(before);
			workspace.setDescription(description);
		}
	}

	@Test
	void rejectsANonBooleanAutoBuildValue() throws Exception {
		McpToolResult result = TestFixture.call(TOOL, Map.of("qualifier", "org.eclipse.core.resources", "key",
				"description.autobuilding", "value", "sometimes"));

		assertTrue(result.isError());
		assertTrue(result.text().contains("sometimes"), result.text());
	}

	@Test
	void writesIntoTheProjectScope() throws Exception {
		var project = fixture.createProject("mcp-set-preference-test");

		Map<String, Object> result = TestFixture.callAndParse(TOOL, Map.of("qualifier", QUALIFIER, "key", KEY, "value",
				"project-value", "scope", "project", "project", project.getName()));

		assertEquals("project", result.get("effectiveScope"));
		assertEquals("project-value", result.get("effective"));
	}
}
