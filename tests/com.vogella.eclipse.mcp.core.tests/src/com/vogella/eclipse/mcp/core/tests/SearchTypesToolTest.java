package com.vogella.eclipse.mcp.core.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.eclipse.jdt.core.IJavaProject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class SearchTypesToolTest {

	private static final String PROJECT = "mcp-search-types-test";

	private final TestFixture fixture = new TestFixture();

	@AfterEach
	void deleteTestProjects() throws Exception {
		fixture.dispose();
	}

	@Test
	void findsATypeCreatedByTheTest() throws Exception {
		createFixtureProject();

		List<String> names = names(TestFixture.callAndParse("eclipse_search_types",
				Map.of("pattern", "SearchMe*", "project", PROJECT)));

		assertTrue(names.contains("example.SearchMeAlpha"), "Missing type in " + names);
		assertTrue(names.contains("example.SearchMeBeta"), "Missing type in " + names);
	}

	@Test
	void findsTypesFromTheClasspath() throws Exception {
		createFixtureProject();

		List<String> names = names(TestFixture.callAndParse("eclipse_search_types",
				Map.of("pattern", "java.util.ArrayList", "project", PROJECT)));

		assertTrue(names.contains("java.util.ArrayList"), "The JRE type was not found, got " + names);
	}

	@Test
	void matchesCaseInsensitively() throws Exception {
		createFixtureProject();

		List<String> names = names(TestFixture.callAndParse("eclipse_search_types",
				Map.of("pattern", "searchmealpha", "project", PROJECT)));

		assertTrue(names.contains("example.SearchMeAlpha"), "Missing type in " + names);
	}

	@Test
	@SuppressWarnings("unchecked")
	void reportsTruncation() throws Exception {
		createFixtureProject();

		Map<String, Object> result = TestFixture.callAndParse("eclipse_search_types",
				Map.of("pattern", "SearchMe*", "project", PROJECT, "maxResults", Integer.valueOf(1)));

		assertEquals(Integer.valueOf(2), result.get("total"));
		assertEquals(Boolean.TRUE, result.get("truncated"));
		assertEquals(1, ((List<Object>) result.get("types")).size());
	}

	@SuppressWarnings("unchecked")
	private static List<String> names(Map<String, Object> result) {
		return ((List<Map<String, Object>>) result.get("types")).stream()
				.map(type -> String.valueOf(type.get("fullyQualifiedName"))).toList();
	}

	private void createFixtureProject() throws Exception {
		IJavaProject javaProject = fixture.createJavaProject(PROJECT);
		TestFixture.addType(javaProject, "example", "SearchMeAlpha", """
				package example;

				public class SearchMeAlpha {
				}
				""");
		TestFixture.addType(javaProject, "example", "SearchMeBeta", """
				package example;

				public class SearchMeBeta {
				}
				""");
		TestFixture.build(javaProject.getProject());
	}
}
