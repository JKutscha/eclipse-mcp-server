package com.vogella.eclipse.mcp.core.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.util.List;
import java.util.Map;

import org.eclipse.core.resources.IFile;
import org.eclipse.jdt.core.IJavaProject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.vogella.eclipse.mcp.core.McpToolResult;

/**
 * The two tools that modify source files.
 */
class SourceEditingToolsTest {

	private static final String PROJECT = "mcp-editing-test";

	private final TestFixture fixture = new TestFixture();

	@AfterEach
	void deleteTestProjects() throws Exception {
		fixture.dispose();
	}

	@Test
	void formatsAFileWithTheProjectSettings() throws Exception {
		IJavaProject javaProject = fixture.createJavaProject(PROJECT);
		IFile file = TestFixture.addType(javaProject, "example", "Ugly", """
				package example;
				public class Ugly {
				public    int value(  ) {
				return   1 ;
				}
				}
				""");
		TestFixture.build(javaProject.getProject());

		Map<String, Object> result = TestFixture.callAndParse("eclipse_format",
				Map.of("path", file.getFullPath().toString()));

		assertEquals(Boolean.TRUE, result.get("changed"));
		String formatted = TestFixture.read(file);
		assertTrue(formatted.contains("public int value() {"), "Not formatted: " + formatted);
		assertFalse(formatted.contains("return   1 ;"), "Not formatted: " + formatted);
	}

	@Test
	void formattingAnAlreadyFormattedFileChangesNothing() throws Exception {
		IJavaProject javaProject = fixture.createJavaProject(PROJECT);
		IFile file = TestFixture.addType(javaProject, "example", "Tidy", """
				package example;

				public class Tidy {
				}
				""");
		TestFixture.build(javaProject.getProject());
		TestFixture.callAndParse("eclipse_format", Map.of("path", file.getFullPath().toString()));

		Map<String, Object> result = TestFixture.callAndParse("eclipse_format",
				Map.of("path", file.getFullPath().toString()));

		assertEquals(Boolean.FALSE, result.get("changed"));
	}

	@Test
	void organizeImportsAddsAndRemovesImports() throws Exception {
		IJavaProject javaProject = fixture.createJavaProject(PROJECT);
		IFile file = TestFixture.addType(javaProject, "example", "Imports", """
				package example;

				import java.io.File;

				public class Imports {
					public ArrayList<String> create() {
						return new ArrayList<>();
					}
				}
				""");
		TestFixture.build(javaProject.getProject());

		Map<String, Object> result = TestFixture.callAndParse("eclipse_organize_imports",
				Map.of("path", file.getFullPath().toString()));

		assertEquals(Boolean.TRUE, result.get("changed"));
		assertEquals(Integer.valueOf(1), result.get("importsRemoved"), "The unused java.io.File import should go");
		assertEquals(Integer.valueOf(1), result.get("importsAdded"), "java.util.ArrayList should be imported");
		String source = TestFixture.read(file);
		assertFalse(source.contains("import java.io.File;"), "Unused import still there: " + source);
		assertTrue(source.contains("import java.util.ArrayList;"), "Import not added: " + source);
	}

	@Test
	@SuppressWarnings("unchecked")
	void organizeImportsReportsAmbiguousNamesInsteadOfGuessing() throws Exception {
		IJavaProject javaProject = fixture.createJavaProject(PROJECT);
		TestFixture.addType(javaProject, "one", "Duplicated", """
				package one;

				public class Duplicated {
				}
				""");
		TestFixture.addType(javaProject, "two", "Duplicated", """
				package two;

				public class Duplicated {
				}
				""");
		IFile file = TestFixture.addType(javaProject, "example", "Ambiguous", """
				package example;

				public class Ambiguous {
					public Duplicated create() {
						return null;
					}
				}
				""");
		TestFixture.build(javaProject.getProject());
		String before = TestFixture.read(file);

		McpToolResult aborted = TestFixture.call("eclipse_organize_imports",
				Map.of("path", file.getFullPath().toString()));

		assertTrue(aborted.isError(), "Expected an error result, got " + aborted.text());
		assertTrue(aborted.text().contains("Duplicated"), "The message should name the type: " + aborted.text());
		assertEquals(before, TestFixture.read(file), "The file must not be touched when the import is ambiguous");

		Map<String, Object> resolved = TestFixture.callAndParse("eclipse_organize_imports",
				Map.of("path", file.getFullPath().toString(), "resolveAmbiguous", Boolean.TRUE));

		assertTrue(((List<String>) resolved.get("ambiguous")).contains("Duplicated"),
				"The ambiguity should still be reported: " + resolved);
		assertTrue(TestFixture.read(file).contains("import one.Duplicated;")
				|| TestFixture.read(file).contains("import two.Duplicated;"),
				"One of the candidates should have been imported: " + TestFixture.read(file));
	}

	@Test
	void formatsAFileChangedOnDiskBehindTheIde() throws Exception {
		IJavaProject javaProject = fixture.createJavaProject(PROJECT);
		IFile file = TestFixture.addType(javaProject, "example", "External", """
				package example;

				public class External {
				}
				""");
		TestFixture.build(javaProject.getProject());
		Files.writeString(file.getLocation().toFile().toPath(), """
				package example;
				public class External {
				public   int value(){return 2;}
				}
				""");

		Map<String, Object> result = TestFixture.callAndParse("eclipse_format",
				Map.of("path", file.getFullPath().toString()));

		assertEquals(Boolean.TRUE, result.get("changed"), "The tool must refresh before it formats");
		assertTrue(TestFixture.read(file).contains("public int value() {"), "Not formatted: " + TestFixture.read(file));
	}

	@Test
	void leavesAFileThatDoesNotParseUnchanged() throws Exception {
		IJavaProject javaProject = fixture.createJavaProject(PROJECT);
		IFile file = TestFixture.addType(javaProject, "example", "Unparsable", """
				package example;

				public class Unparsable {
				""");
		TestFixture.build(javaProject.getProject());
		String before = TestFixture.read(file);

		Map<String, Object> result = TestFixture.callAndParse("eclipse_format",
				Map.of("path", file.getFullPath().toString()));

		// the formatter produces no edits rather than failing, so the file is reported as unchanged
		assertEquals(Boolean.FALSE, result.get("changed"));
		assertEquals(before, TestFixture.read(file));
	}

	@Test
	void rejectsAPathThatIsNotAJavaFile() throws Exception {
		fixture.createJavaProject(PROJECT);

		assertTrue(TestFixture.call("eclipse_format", Map.of("path", "/%s/does/not/exist.java".formatted(PROJECT)))
				.isError(), "Expected an error result");
	}
}
