package com.vogella.eclipse.mcp.core.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.eclipse.jdt.core.IJavaProject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class GetSourceToolTest {

	private static final String PROJECT = "mcp-source-test";

	private final TestFixture fixture = new TestFixture();

	@AfterEach
	void deleteTestProjects() throws Exception {
		fixture.dispose();
	}

	@Test
	void returnsTheSourceOfAType() throws Exception {
		createFixtureProject();

		Map<String, Object> result = TestFixture.callAndParse("eclipse_get_source",
				Map.of("typeName", "example.Documented", "project", PROJECT));

		assertEquals("example.Documented", result.get("type"));
		assertEquals(Boolean.FALSE, result.get("binary"));
		assertEquals(Boolean.TRUE, result.get("sourceAvailable"));
		String source = onlyElement(result).get("source").toString();
		assertTrue(source.contains("class Documented"), "Unexpected source: " + source);
		assertTrue(source.contains("public String greet"), "The member should be part of the type source");
	}

	@Test
	void returnsTheSourceOfAMemberIncludingItsJavadoc() throws Exception {
		createFixtureProject();

		Map<String, Object> result = TestFixture.callAndParse("eclipse_get_source",
				Map.of("typeName", "example.Documented", "memberName", "greet", "project", PROJECT));

		Map<String, Object> element = onlyElement(result);
		String source = element.get("source").toString();
		assertTrue(source.startsWith("/**"), "The Javadoc should be included: " + source);
		assertTrue(source.contains("Greets somebody."), "The Javadoc text is missing: " + source);
		assertTrue(source.contains("return \"Hello \" + name;"), "The body is missing: " + source);
		assertEquals("example.Documented.greet(String)", element.get("element"));
		assertTrue(((Number) element.get("line")).intValue() > 1, "A line number was expected");
	}

	@Test
	@SuppressWarnings("unchecked")
	void returnsEveryOverload() throws Exception {
		createFixtureProject();

		Map<String, Object> result = TestFixture.callAndParse("eclipse_get_source",
				Map.of("typeName", "example.Documented", "memberName", "overloaded", "project", PROJECT));

		List<Map<String, Object>> elements = (List<Map<String, Object>>) result.get("elements");
		assertEquals(2, elements.size(), "Both overloads were expected, got " + elements);
	}

	@Test
	void readsATypeFromTheClasspath() throws Exception {
		createFixtureProject();

		Map<String, Object> result = TestFixture.callAndParse("eclipse_get_source",
				Map.of("typeName", "java.lang.String", "memberName", "trim", "project", PROJECT));

		assertEquals(Boolean.TRUE, result.get("binary"));
		// the JRE in the test runtime may or may not have sources attached, both are valid answers
		assertTrue(result.containsKey("sourceAvailable"), "sourceAvailable should always be reported");
	}

	@Test
	void truncatesLongSource() throws Exception {
		createFixtureProject();

		Map<String, Object> result = TestFixture.callAndParse("eclipse_get_source",
				Map.of("typeName", "example.Documented", "project", PROJECT, "maxLength", Integer.valueOf(100)));

		Map<String, Object> element = onlyElement(result);
		assertEquals(Boolean.TRUE, element.get("truncated"));
		assertEquals(100, element.get("source").toString().length());
	}

	@Test
	void reportsAnUnresolvableTypeAsAnErrorResult() throws Exception {
		createFixtureProject();

		assertTrue(TestFixture.call("eclipse_get_source", Map.of("typeName", "does.not.Exist", "project", PROJECT))
				.isError(), "Expected an error result");
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> onlyElement(Map<String, Object> result) {
		List<Map<String, Object>> elements = (List<Map<String, Object>>) result.get("elements");
		assertEquals(1, elements.size(), "Expected exactly one element, got " + elements);
		return elements.get(0);
	}

	private void createFixtureProject() throws Exception {
		IJavaProject javaProject = fixture.createJavaProject(PROJECT);
		TestFixture.addType(javaProject, "example", "Documented", """
				package example;

				public class Documented {
					/**
					 * Greets somebody.
					 */
					public String greet(String name) {
						return "Hello " + name;
					}

					public void overloaded(String value) {
					}

					public void overloaded(int value) {
					}
				}
				""");
		TestFixture.build(javaProject.getProject());
	}
}
