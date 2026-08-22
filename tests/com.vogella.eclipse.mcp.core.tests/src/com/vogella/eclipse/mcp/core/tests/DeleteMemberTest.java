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

/**
 * Deleting a single member, which is about half the edits of a real sweep.
 */
class DeleteMemberTest {

	private static final String TOOL = "eclipse_delete";

	private static final String PROJECT = "mcp-delete-member-test";

	private final TestFixture fixture = new TestFixture();

	@AfterEach
	void deleteTestProjects() throws Exception {
		fixture.dispose();
	}

	@Test
	void deletesADeadConstantWithItsJavadoc() throws Exception {
		IJavaProject project = holder("""
				package example;
				public class Holder {
					/** Nothing uses this. */
					private static final int DEAD = 1;
					public int used() { return 2; }
				}
				""");

		Map<String, Object> dry = TestFixture.callAndParse(TOOL,
				Map.of("typeName", "example.Holder", "memberName", "DEAD", "project", PROJECT));
		assertEquals(Boolean.FALSE, dry.get("deleted"));
		assertEquals("field", dry.get("kind"));
		assertEquals(Integer.valueOf(0), dry.get("references"));

		TestFixture.callAndParse(TOOL, Map.of("typeName", "example.Holder", "memberName", "DEAD", //
				"project", PROJECT, "dryRun", Boolean.FALSE));

		String source = TestFixture.read(project.getProject().getFile("src/example/Holder.java"));
		assertFalse(source.contains("DEAD"), source);
		// the javadoc travels with the declaration rather than being left behind
		// describing something that no longer exists
		assertFalse(source.contains("Nothing uses this"), source);
		assertTrue(source.contains("used()"), "the rest of the class must survive: " + source);
	}

	@Test
	@SuppressWarnings("unchecked")
	void deletesSeveralTypesInOneCall() throws Exception {
		IJavaProject project = fixture.createJavaProject(PROJECT);
		TestFixture.addType(project, "example", "One", "package example;\npublic class One { }\n");
		TestFixture.addType(project, "example", "Two", "package example;\npublic class Two { }\n");
		TestFixture.addType(project, "example", "Used", "package example;\npublic class Used { }\n");
		TestFixture.addType(project, "example", "User", "package example;\npublic class User { Used u; }\n");
		TestFixture.build(project.getProject());

		Map<String, Object> result = TestFixture.callAndParse(TOOL, Map.of("project", PROJECT, "dryRun",
				Boolean.FALSE, "typeNames", List.of("example.One", "example.Two", "example.Used")));

		List<Map<String, Object>> results = (List<Map<String, Object>>) result.get("results");
		assertEquals(3, results.size(), "one result per type, got " + results);
		assertEquals(Boolean.TRUE, results.get(0).get("deleted"), "got " + results.get(0));
		assertEquals(Boolean.TRUE, results.get(1).get("deleted"), "got " + results.get(1));
		// the referenced one is refused on its own without stopping the others
		assertEquals(Boolean.FALSE, results.get(2).get("deleted"), "got " + results.get(2));
		assertFalse(project.getProject().getFile("src/example/One.java").exists());
		assertTrue(project.getProject().getFile("src/example/Used.java").exists());
	}

	@Test
	void refusesAMemberThatIsStillUsed() throws Exception {
		holder("""
				package example;
				public class Holder {
					static int USED = 1;
					int read() { return USED; }
				}
				""");

		Map<String, Object> result = TestFixture.callAndParse(TOOL, Map.of("typeName", "example.Holder", //
				"memberName", "USED", "project", PROJECT, "dryRun", Boolean.FALSE));

		assertEquals(Boolean.FALSE, result.get("deleted"));
		assertTrue(String.valueOf(result.get("refusedBecause")).contains("reference"), "got " + result);
	}

	@Test
	void refusesAnOverloadedMethodRatherThanGuessing() throws Exception {
		holder("""
				package example;
				public class Holder {
					void run() { }
					void run(int times) { }
				}
				""");

		McpToolResult result = TestFixture.call(TOOL,
				Map.of("typeName", "example.Holder", "memberName", "run", "project", PROJECT));

		assertTrue(result.isError());
		assertTrue(result.text().contains("by hand"), result.text());
	}

	@Test
	void reportsAMemberThatIsNotThere() throws Exception {
		holder("package example;\npublic class Holder { }\n");

		McpToolResult result = TestFixture.call(TOOL,
				Map.of("typeName", "example.Holder", "memberName", "nope", "project", PROJECT));

		assertTrue(result.isError());
		assertTrue(result.text().contains("no member named"), result.text());
	}

	private IJavaProject holder(String source) throws Exception {
		IJavaProject project = fixture.createJavaProject(PROJECT);
		TestFixture.addType(project, "example", "Holder", source);
		TestFixture.build(project.getProject());
		return project;
	}
}
