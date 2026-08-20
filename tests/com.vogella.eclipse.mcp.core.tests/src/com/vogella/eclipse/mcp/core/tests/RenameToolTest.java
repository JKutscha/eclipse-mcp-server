package com.vogella.eclipse.mcp.core.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.eclipse.jdt.core.IJavaProject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.vogella.eclipse.mcp.core.McpToolResult;

class RenameToolTest {

	private static final String TOOL = "eclipse_rename";

	private static final String PROJECT = "mcp-rename-test";

	private final TestFixture fixture = new TestFixture();

	@AfterEach
	void deleteTestProjects() throws Exception {
		fixture.dispose();
	}

	private IJavaProject withTypes() throws Exception {
		IJavaProject project = fixture.createJavaProject(PROJECT);
		TestFixture.addType(project, "sample", "Target", """
				package sample;
				public class Target {
					public int counter;
					public void greet() {
					}
					public void overloaded() {
					}
					public void overloaded(int times) {
					}
				}
				""");
		TestFixture.addType(project, "sample", "User", """
				package sample;
				public class User {
					void use() {
						Target target = new Target();
						target.greet();
						target.counter = 1;
					}
				}
				""");
		TestFixture.build(project.getProject());
		return project;
	}

	@Test
	void aDryRunListsTheAffectedFilesAndWritesNothing() throws Exception {
		IJavaProject project = withTypes();

		Map<String, Object> result = TestFixture.callAndParse(TOOL,
				Map.of("typeName", "sample.Target", "newName", "Renamed", "project", PROJECT));

		assertEquals(Boolean.TRUE, result.get("dryRun"));
		assertEquals(Boolean.FALSE, result.get("applied"));
		@SuppressWarnings("unchecked")
		List<String> files = (List<String>) result.get("affectedFiles");
		assertTrue(files.stream().anyMatch(f -> f.endsWith("Target.java")), files.toString());
		assertTrue(files.stream().anyMatch(f -> f.endsWith("User.java")),
				"the referencing file must be listed: " + files);
		assertTrue(project.getProject().getFile("src/sample/Target.java").exists(),
				"a dry run must not rename the file");
	}

	@Test
	void renamesATypeAndItsReferences() throws Exception {
		IJavaProject project = withTypes();

		Map<String, Object> result = TestFixture.callAndParse(TOOL, Map.of("typeName", "sample.Target", "newName",
				"Renamed", "project", PROJECT, "dryRun", Boolean.FALSE));

		assertEquals(Boolean.TRUE, result.get("applied"));
		assertTrue(project.getProject().getFile("src/sample/Renamed.java").exists(), "the file should have moved");
		assertFalse(project.getProject().getFile("src/sample/Target.java").exists());
		String user = TestFixture.read(project.getProject().getFile("src/sample/User.java"));
		assertTrue(user.contains("Renamed target = new Renamed()"), user);
	}

	@Test
	void renamesAMethodAndItsCallers() throws Exception {
		IJavaProject project = withTypes();

		TestFixture.callAndParse(TOOL, Map.of("typeName", "sample.Target", "memberName", "greet", "newName", "welcome",
				"project", PROJECT, "dryRun", Boolean.FALSE));

		String user = TestFixture.read(project.getProject().getFile("src/sample/User.java"));
		assertTrue(user.contains("target.welcome()"), user);
	}

	@Test
	void renamesAFieldAndItsAccesses() throws Exception {
		IJavaProject project = withTypes();

		TestFixture.callAndParse(TOOL, Map.of("typeName", "sample.Target", "memberName", "counter", "newName", "count",
				"project", PROJECT, "dryRun", Boolean.FALSE));

		String user = TestFixture.read(project.getProject().getFile("src/sample/User.java"));
		assertTrue(user.contains("target.count = 1"), user);
	}

	@Test
	void refusesAnOverloadedMethodRatherThanGuessing() throws Exception {
		withTypes();

		McpToolResult result = TestFixture.call(TOOL, Map.of("typeName", "sample.Target", "memberName", "overloaded",
				"newName", "renamed", "project", PROJECT));

		assertTrue(result.isError());
		assertTrue(result.text().contains("ambiguous"), result.text());
	}

	@Test
	void refusesARenameThatWouldCollide() throws Exception {
		withTypes();

		McpToolResult result = TestFixture.call(TOOL,
				Map.of("typeName", "sample.Target", "newName", "User", "project", PROJECT, "dryRun", Boolean.FALSE));

		assertTrue(result.isError(), result.text());
		assertTrue(result.text().startsWith("Refused:"), result.text());
	}

	@Test
	void refusesAnInvalidJavaName() throws Exception {
		withTypes();

		McpToolResult result = TestFixture.call(TOOL,
				Map.of("typeName", "sample.Target", "newName", "not a name", "project", PROJECT));

		assertTrue(result.isError(), result.text());
	}

	@Test
	void rejectsAnUnknownType() throws Exception {
		withTypes();

		McpToolResult result = TestFixture.call(TOOL,
				Map.of("typeName", "sample.NoSuchType", "newName", "X", "project", PROJECT));

		assertTrue(result.isError());
		assertTrue(result.text().contains("NoSuchType"), result.text());
	}
}
