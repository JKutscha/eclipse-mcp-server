package com.vogella.eclipse.mcp.core.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.Map;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IMarker;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class GetProblemsToolTest {

	private static final String PROJECT = "mcp-problems-test";

	private final TestFixture fixture = new TestFixture();

	@AfterEach
	void deleteTestProjects() throws Exception {
		fixture.dispose();
	}

	@Test
	void reportsAMarkerCreatedByTheTest() throws Exception {
		IProject project = fixture.createProject(PROJECT);
		createMarker(project, "broken.txt", IMarker.SEVERITY_ERROR, "Something is broken", 42);

		Map<String, Object> result = TestFixture.callAndParse("eclipse_get_problems", Map.of("project", PROJECT));
		Map<String, Object> problem = onlyProblem(result);

		assertEquals("/%s/broken.txt".formatted(PROJECT), problem.get("path"));
		assertEquals(PROJECT, problem.get("project"));
		assertEquals("error", problem.get("severity"));
		assertEquals("Something is broken", problem.get("message"));
		assertEquals(Integer.valueOf(42), problem.get("line"));
	}

	@Test
	void filtersBySeverity() throws Exception {
		IProject project = fixture.createProject(PROJECT);
		createMarker(project, "error.txt", IMarker.SEVERITY_ERROR, "An error", 1);
		createMarker(project, "warning.txt", IMarker.SEVERITY_WARNING, "A warning", 2);

		Map<String, Object> errors = TestFixture.callAndParse("eclipse_get_problems",
				Map.of("project", PROJECT, "severity", "error"));
		assertEquals("An error", onlyProblem(errors).get("message"));

		Map<String, Object> warnings = TestFixture.callAndParse("eclipse_get_problems",
				Map.of("project", PROJECT, "severity", "warning"));
		assertEquals("A warning", onlyProblem(warnings).get("message"));

		Map<String, Object> all = TestFixture.callAndParse("eclipse_get_problems",
				Map.of("project", PROJECT, "severity", "all"));
		assertEquals(Integer.valueOf(2), all.get("total"));
	}

	@Test
	void filtersByProject() throws Exception {
		IProject withMarker = fixture.createProject(PROJECT);
		fixture.createProject(PROJECT + "-other");
		createMarker(withMarker, "error.txt", IMarker.SEVERITY_ERROR, "An error", 1);

		Map<String, Object> other = TestFixture.callAndParse("eclipse_get_problems",
				Map.of("project", PROJECT + "-other"));
		assertEquals(Integer.valueOf(0), other.get("total"));
	}

	@Test
	@SuppressWarnings("unchecked")
	void reportsTruncation() throws Exception {
		IProject project = fixture.createProject(PROJECT);
		for (int i = 0; i < 3; i++) {
			createMarker(project, "broken%d.txt".formatted(i), IMarker.SEVERITY_ERROR, "Error " + i, i + 1);
		}

		Map<String, Object> result = TestFixture.callAndParse("eclipse_get_problems",
				Map.of("project", PROJECT, "maxResults", Integer.valueOf(1)));

		assertEquals(Integer.valueOf(3), result.get("total"));
		assertEquals(Boolean.TRUE, result.get("truncated"));
		assertEquals(1, ((List<Map<String, Object>>) result.get("problems")).size());
	}

	@Test
	void rejectsAnUnknownSeverity() throws Exception {
		assertTrue(TestFixture.call("eclipse_get_problems", Map.of("severity", "critical")).isError());
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> onlyProblem(Map<String, Object> result) {
		List<Map<String, Object>> problems = (List<Map<String, Object>>) result.get("problems");
		assertEquals(1, problems.size(), "Expected exactly one problem, got " + problems);
		return problems.get(0);
	}

	private static void createMarker(IProject project, String fileName, int severity, String message, int line)
			throws Exception {
		IFile file = project.getFile(fileName);
		file.create(new ByteArrayInputStream(new byte[0]), true, new NullProgressMonitor());
		IMarker marker = file.createMarker(IMarker.PROBLEM);
		marker.setAttribute(IMarker.SEVERITY, severity);
		marker.setAttribute(IMarker.MESSAGE, message);
		marker.setAttribute(IMarker.LINE_NUMBER, line);
	}
}
