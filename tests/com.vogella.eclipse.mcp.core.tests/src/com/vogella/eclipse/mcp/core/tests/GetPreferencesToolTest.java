package com.vogella.eclipse.mcp.core.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ProjectScope;
import org.eclipse.core.runtime.preferences.DefaultScope;
import org.eclipse.core.runtime.preferences.IEclipsePreferences;
import org.eclipse.core.runtime.preferences.InstanceScope;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.vogella.eclipse.mcp.core.McpToolResult;

class GetPreferencesToolTest {

	private static final String TOOL = "eclipse_get_preferences";

	private static final String QUALIFIER = "com.vogella.eclipse.mcp.preference.test";

	private final TestFixture fixture = new TestFixture();

	@AfterEach
	void cleanUp() throws Exception {
		InstanceScope.INSTANCE.getNode(QUALIFIER).removeNode();
		DefaultScope.INSTANCE.getNode(QUALIFIER).removeNode();
		fixture.dispose();
	}

	@Test
	void reportsAnInstanceValueAndItsScope() throws Exception {
		set(InstanceScope.INSTANCE.getNode(QUALIFIER), "colour", "blue");

		Map<String, Object> preference = only(TestFixture.callAndParse(TOOL, Map.of("qualifier", QUALIFIER)));

		assertEquals("colour", preference.get("key"));
		assertEquals("blue", preference.get("effective"));
		assertEquals("instance", preference.get("effectiveScope"));
		assertEquals(Map.of("instance", "blue"), preference.get("values"));
	}

	@Test
	void showsTheDefaultAlongsideTheOverride() throws Exception {
		set(DefaultScope.INSTANCE.getNode(QUALIFIER), "colour", "green");
		set(InstanceScope.INSTANCE.getNode(QUALIFIER), "colour", "blue");

		Map<String, Object> preference = only(TestFixture.callAndParse(TOOL, Map.of("qualifier", QUALIFIER)));

		assertEquals("blue", preference.get("effective"));
		assertEquals("instance", preference.get("effectiveScope"));
		assertEquals(Map.of("instance", "blue", "default", "green"), preference.get("values"));
	}

	@Test
	void aProjectValueWinsOverTheInstanceValue() throws Exception {
		IProject project = fixture.createProject("mcp-preferences-test");
		set(InstanceScope.INSTANCE.getNode(QUALIFIER), "colour", "blue");
		set(new ProjectScope(project).getNode(QUALIFIER), "colour", "red");

		Map<String, Object> preference = only(TestFixture.callAndParse(TOOL,
				Map.of("qualifier", QUALIFIER, "scope", "project", "project", project.getName())));

		assertEquals("red", preference.get("effective"));
		assertEquals("project", preference.get("effectiveScope"));
		assertEquals(Map.of("project", "red", "instance", "blue"), preference.get("values"));
	}

	@Test
	void hidesDefaultOnlyKeysUnlessAsked() throws Exception {
		set(DefaultScope.INSTANCE.getNode(QUALIFIER), "untouched", "yes");

		Map<String, Object> hidden = TestFixture.callAndParse(TOOL, Map.of("qualifier", QUALIFIER));
		assertEquals(Integer.valueOf(0), hidden.get("total"));

		Map<String, Object> shown = TestFixture.callAndParse(TOOL,
				Map.of("qualifier", QUALIFIER, "includeDefaults", Boolean.TRUE));
		Map<String, Object> preference = only(shown);
		assertEquals("untouched", preference.get("key"));
		assertEquals("default", preference.get("effectiveScope"));
	}

	@Test
	void selectsKeysByGlob() throws Exception {
		IEclipsePreferences node = InstanceScope.INSTANCE.getNode(QUALIFIER);
		set(node, "compiler.source", "25");
		set(node, "compiler.target", "25");
		set(node, "formatter.tabulation", "4");

		Map<String, Object> result = TestFixture.callAndParse(TOOL,
				Map.of("qualifier", QUALIFIER, "keyPattern", "compiler.*"));

		assertEquals(Integer.valueOf(2), result.get("total"));
		assertEquals(List.of("compiler.source", "compiler.target"), keys(result));
	}

	@Test
	void readsTheAutoBuildSettingOfThisWorkspace() throws Exception {
		Map<String, Object> result = TestFixture.callAndParse(TOOL, Map.of("qualifier", "org.eclipse.core.resources",
				"key", "description.autobuilding", "scope", "all", "includeDefaults", Boolean.TRUE));

		// the value depends on the test workspace, but the key must resolve to something
		Map<String, Object> preference = only(result);
		assertEquals("description.autobuilding", preference.get("key"));
		assertTrue(List.of("true", "false").contains(preference.get("effective")),
				String.valueOf(preference.get("effective")));
	}

	@Test
	void reportsAKeyThatIsSetNowhere() throws Exception {
		Map<String, Object> result = TestFixture.callAndParse(TOOL,
				Map.of("qualifier", QUALIFIER, "key", "never.set", "scope", "all"));

		assertEquals(Integer.valueOf(0), result.get("total"));
		assertNull(((List<?>) result.get("preferences")).stream().findFirst().orElse(null));
	}

	@Test
	void rejectsAProjectScopeWithoutAProject() throws Exception {
		McpToolResult result = TestFixture.call(TOOL, Map.of("qualifier", QUALIFIER, "scope", "project"));

		assertTrue(result.isError());
		assertTrue(result.text().contains("project"), result.text());
	}

	@Test
	void rejectsAnUnknownProject() throws Exception {
		McpToolResult result = TestFixture.call(TOOL,
				Map.of("qualifier", QUALIFIER, "scope", "project", "project", "no-such-project"));

		assertTrue(result.isError());
		assertTrue(result.text().contains("no-such-project"), result.text());
	}

	@Test
	void rejectsAMissingQualifier() throws Exception {
		McpToolResult result = TestFixture.call(TOOL, Map.of());

		assertTrue(result.isError());
		assertTrue(result.text().contains("qualifier"), result.text());
	}

	private static void set(IEclipsePreferences node, String key, String value) throws Exception {
		node.put(key, value);
		node.flush();
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> only(Map<String, Object> result) {
		List<Map<String, Object>> preferences = (List<Map<String, Object>>) result.get("preferences");
		assertEquals(1, preferences.size(), "expected exactly one preference, got " + preferences);
		return preferences.get(0);
	}

	@SuppressWarnings("unchecked")
	private static List<String> keys(Map<String, Object> result) {
		return ((List<Map<String, Object>>) result.get("preferences")).stream().map(p -> (String) p.get("key")).toList();
	}
}
