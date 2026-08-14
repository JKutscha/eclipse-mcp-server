package com.vogella.eclipse.mcp.core.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.eclipse.jdt.core.IJavaProject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The read/write split on fields, which is the one thing a text search cannot approximate.
 */
class FieldAccessSplitTest {

	private static final String PROJECT = "mcp-access-split-test";

	private final TestFixture fixture = new TestFixture();

	@BeforeEach
	void createFixtureProject() throws Exception {
		IJavaProject javaProject = fixture.createJavaProject(PROJECT);
		TestFixture.addType(javaProject, "example", "Accessed", """
				package example;

				public class Accessed {
					public String writtenNeverRead;
					public String readNeverWritten = "constant";
					public int both;

					public void assignAll(String value) {
						writtenNeverRead = value;
						readNeverWritten.length();
						both = 1;
					}
				}
				""");
		TestFixture.addType(javaProject, "example", "Writer", """
				package example;

				public class Writer {
					public void write(Accessed target) {
						target.writtenNeverRead = "a";
						target.writtenNeverRead = "b";
					}

					public String read(Accessed target) {
						return target.readNeverWritten;
					}

					public void update(Accessed target) {
						target.both += 1;
					}
				}
				""");
		TestFixture.build(javaProject.getProject());
	}

	@AfterEach
	void deleteTestProjects() throws Exception {
		fixture.dispose();
	}

	@Test
	void reportsAFieldThatIsOnlyEverWritten() throws Exception {
		Map<String, Object> result = find("writtenNeverRead", null);

		assertEquals(Integer.valueOf(0), byKind(result).get("read"),
				"The field is never read, so it is dead: " + result);
		assertEquals(Integer.valueOf(3), byKind(result).get("write"));
		assertTrue(((Number) result.get("total")).intValue() >= 3, "Every occurrence is still a reference: " + result);
	}

	@Test
	void countsAFieldInitializerAsAWriteButNotAsAReference() throws Exception {
		Map<String, Object> result = find("readNeverWritten", null);

		assertEquals(Integer.valueOf(2), byKind(result).get("read"));
		// the declaration's initializer is a write access, and a declaration is not a reference,
		// so the write is counted in byKind while being absent from total and from matches
		assertEquals(Integer.valueOf(1), byKind(result).get("write"));
		assertEquals(Integer.valueOf(2), result.get("total"));
		assertTrue(matches(result).stream().allMatch(match -> "read".equals(match.get("kind"))), "" + matches(result));
	}

	@Test
	void countsACompoundAssignmentAsBothReadAndWrite() throws Exception {
		Map<String, Object> result = find("both", null);

		assertTrue(((Number) byKind(result).get("read")).intValue() >= 1, "'both += 1' reads: " + result);
		assertTrue(((Number) byKind(result).get("write")).intValue() >= 2, "'both = 1' and 'both += 1' write: " + result);
		assertTrue(matches(result).stream().anyMatch(match -> "readWrite".equals(match.get("kind"))),
				"The compound assignment should be tagged readWrite: " + matches(result));
	}

	@Test
	void restrictsTheSearchToWriteAccesses() throws Exception {
		Map<String, Object> result = find("writtenNeverRead", "write");

		assertEquals("write", result.get("accessKind"));
		assertEquals(Integer.valueOf(3), result.get("total"));
		assertTrue(matches(result).stream().allMatch(match -> "write".equals(match.get("kind"))), "" + matches(result));
	}

	@Test
	void restrictsTheSearchToReadAccesses() throws Exception {
		Map<String, Object> result = find("writtenNeverRead", "read");

		assertEquals(Integer.valueOf(0), result.get("total"), "The field is never read: " + result);
	}

	@Test
	void reportsNoKindForANonField() throws Exception {
		Map<String, Object> result = find("assignAll", null);

		assertNull(result.get("byKind"), "Methods are neither read nor written: " + result);
		assertTrue(matches(result).stream().allMatch(match -> match.get("kind") == null), "" + matches(result));
	}

	@Test
	void rejectsReadAccessesOnAMethod() throws Exception {
		assertTrue(TestFixture.call("eclipse_find_references",
				Map.of("typeName", "example.Accessed", "memberName", "assignAll", "project", PROJECT, "accessKind",
						"read")).isError(),
				"Read accesses are meaningless for a method");
	}

	@Test
	void rejectsAnUnknownAccessKind() throws Exception {
		assertTrue(TestFixture.call("eclipse_find_references", Map.of("typeName", "example.Accessed", "memberName",
				"both", "project", PROJECT, "accessKind", "sideways")).isError());
	}

	private static Map<String, Object> find(String memberName, String accessKind) throws Exception {
		Map<String, Object> arguments = accessKind == null
				? Map.of("typeName", "example.Accessed", "memberName", memberName, "project", PROJECT)
				: Map.of("typeName", "example.Accessed", "memberName", memberName, "project", PROJECT, "accessKind",
						accessKind);
		return TestFixture.callAndParse("eclipse_find_references", arguments);
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> byKind(Map<String, Object> result) {
		return (Map<String, Object>) result.get("byKind");
	}

	@SuppressWarnings("unchecked")
	private static List<Map<String, Object>> matches(Map<String, Object> result) {
		return (List<Map<String, Object>>) result.get("matches");
	}
}
