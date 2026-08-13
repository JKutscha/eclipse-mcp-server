package com.vogella.eclipse.mcp.core.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.eclipse.jdt.core.IJavaProject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.vogella.eclipse.mcp.core.McpToolResult;

class FindReferencesToolTest {

	private static final String PROJECT = "mcp-references-test";

	private final TestFixture fixture = new TestFixture();

	@AfterEach
	void deleteTestProjects() throws Exception {
		fixture.dispose();
	}

	@Test
	@SuppressWarnings("unchecked")
	void findsAReferenceBetweenTwoTypes() throws Exception {
		createFixtureProject();

		Map<String, Object> result = TestFixture.callAndParse("eclipse_find_references",
				Map.of("typeName", "example.Greeter", "project", PROJECT));

		assertEquals("example.Greeter", result.get("resolved"));
		List<Map<String, Object>> matches = (List<Map<String, Object>>) result.get("matches");
		assertTrue(matches.stream().anyMatch(match -> "/%s/src/example/Caller.java".formatted(PROJECT)
				.equals(match.get("path"))), "No reference from Caller.java, got " + matches);
	}

	@Test
	@SuppressWarnings("unchecked")
	void findsReferencesToAMethod() throws Exception {
		createFixtureProject();

		Map<String, Object> result = TestFixture.callAndParse("eclipse_find_references",
				Map.of("typeName", "example.Greeter", "memberName", "greet", "project", PROJECT));

		assertEquals("example.Greeter#greet", result.get("resolved"));
		List<Map<String, Object>> matches = (List<Map<String, Object>>) result.get("matches");
		assertTrue(matches.size() >= 1, "No reference to greet(), got " + matches);
		assertTrue(matches.get(0).get("enclosingElement").toString().startsWith("example.Caller."),
				"Unexpected enclosing element in " + matches.get(0));
	}

	@Test
	void reportsTruncation() throws Exception {
		createFixtureProject();

		Map<String, Object> result = TestFixture.callAndParse("eclipse_find_references",
				Map.of("typeName", "example.Greeter", "project", PROJECT, "maxResults", Integer.valueOf(1)));

		assertTrue(((Number) result.get("total")).intValue() > 1, "Fixture produced too few matches: " + result);
		assertEquals(Boolean.TRUE, result.get("truncated"));
	}

	@Test
	void reportsAnUnresolvableTypeAsAnErrorResult() throws Exception {
		createFixtureProject();

		McpToolResult result = TestFixture.call("eclipse_find_references",
				Map.of("typeName", "does.not.Exist", "project", PROJECT));

		assertTrue(result.isError(), "Expected an error result");
		assertTrue(result.text().contains("does.not.Exist"), "The message should name the type: " + result.text());
	}

	private void createFixtureProject() throws Exception {
		IJavaProject javaProject = fixture.createJavaProject(PROJECT);
		TestFixture.addType(javaProject, "example", "Greeter", """
				package example;

				public class Greeter {
					public String greet(String name) {
						return "Hello " + name;
					}
				}
				""");
		TestFixture.addType(javaProject, "example", "Caller", """
				package example;

				public class Caller {
					public String callOnce(Greeter greeter) {
						return greeter.greet("world");
					}

					public String callTwice(Greeter greeter) {
						return greeter.greet("again");
					}
				}
				""");
		TestFixture.build(javaProject.getProject());
	}
}
