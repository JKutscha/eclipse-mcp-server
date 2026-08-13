package com.vogella.eclipse.mcp.core.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class ListProjectsToolTest {

	private final TestFixture fixture = new TestFixture();

	@AfterEach
	void deleteTestProjects() throws Exception {
		fixture.dispose();
	}

	@Test
	@SuppressWarnings("unchecked")
	void reportsAProjectCreatedByTheTest() throws Exception {
		fixture.createJavaProject("mcp-list-projects-test");

		Map<String, Object> result = TestFixture.callAndParse("eclipse_list_projects", Map.of());
		List<Map<String, Object>> projects = (List<Map<String, Object>>) result.get("projects");
		Map<String, Object> project = projects.stream()
				.filter(entry -> "mcp-list-projects-test".equals(entry.get("name"))).findFirst()
				.orElseThrow(() -> new AssertionError("Test project not reported, got " + projects));

		assertEquals(Boolean.TRUE, project.get("open"));
		assertTrue(((List<String>) project.get("natures")).contains("org.eclipse.jdt.core.javanature"),
				"Java nature missing in " + project);
	}
}
