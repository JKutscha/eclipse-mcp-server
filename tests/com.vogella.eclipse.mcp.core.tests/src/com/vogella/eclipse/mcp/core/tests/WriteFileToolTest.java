package com.vogella.eclipse.mcp.core.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.vogella.eclipse.mcp.core.McpToolResult;

class WriteFileToolTest {

	private static final String TOOL = "eclipse_write_file";

	private static final String PROJECT = "mcp-write-test";

	private final TestFixture fixture = new TestFixture();

	@AfterEach
	void deleteTestProjects() throws Exception {
		fixture.dispose();
	}

	@Test
	void aFileIsCreatedTogetherWithItsFolders() throws Exception {
		IProject project = fixture.createProject(PROJECT);

		Map<String, Object> result = TestFixture.callAndParse(TOOL,
				Map.of("path", "/" + PROJECT + "/deep/nested/notes.txt", "content", "hello\n"));

		assertEquals(Boolean.TRUE, result.get("created"));
		assertEquals(Boolean.TRUE, result.get("written"));
		assertEquals("[/mcp-write-test/deep, /mcp-write-test/deep/nested]", String.valueOf(result.get("createdFolders")));
		assertEquals("hello\n", TestFixture.read(project.getFile("deep/nested/notes.txt")));
	}

	@Test
	void anExistingFileIsRefusedUntilOverwriteIsAskedFor() throws Exception {
		IFile file = existing("first\n");

		McpToolResult refused = TestFixture.call(TOOL, Map.of("path", file.getFullPath().toString(), "content", "second\n"));

		assertTrue(refused.isError());
		assertTrue(refused.text().contains("overwrite"), refused.text());
		assertEquals("first\n", TestFixture.read(file));

		Map<String, Object> written = TestFixture.callAndParse(TOOL,
				Map.of("path", file.getFullPath().toString(), "content", "second\n", "overwrite", Boolean.TRUE));

		assertEquals(Boolean.FALSE, written.get("created"));
		assertEquals("second\n", TestFixture.read(file));
	}

	@Test
	void appendAddsToTheEndOfTheFile() throws Exception {
		IFile file = existing("first\n");

		Map<String, Object> result = TestFixture.callAndParse(TOOL,
				Map.of("path", file.getFullPath().toString(), "content", "second\n", "append", Boolean.TRUE));

		assertEquals(Boolean.TRUE, result.get("appended"));
		assertEquals("first\nsecond\n", TestFixture.read(file));
	}

	@Test
	void aDryRunReportsTheWriteWithoutPerformingIt() throws Exception {
		IProject project = fixture.createProject(PROJECT);

		Map<String, Object> result = TestFixture.callAndParse(TOOL, Map.of("path", "/" + PROJECT + "/notes.txt",
				"content", "hello\n", "dryRun", Boolean.TRUE));

		assertEquals(Boolean.FALSE, result.get("written"));
		assertEquals(Boolean.TRUE, result.get("created"));
		assertFalse(project.getFile("notes.txt").exists());
	}

	@Test
	void anExplicitCharsetIsWrittenAndRecordedOnTheFile() throws Exception {
		IProject project = fixture.createProject(PROJECT);
		String path = "/" + PROJECT + "/umlaut.txt";

		Map<String, Object> result = TestFixture.callAndParse(TOOL,
				Map.of("path", path, "content", "Grüße\n", "charset", "ISO-8859-1"));

		IFile file = project.getFile("umlaut.txt");
		assertEquals("ISO-8859-1", result.get("charset"));
		assertEquals("ISO-8859-1", file.getCharset());
		assertEquals("Grüße\n", TestFixture.read(file), "read back with the charset the file now carries");
	}

	@Test
	void aMissingProjectIsReportedRatherThanCreated() throws Exception {
		McpToolResult result = TestFixture.call(TOOL, Map.of("path", "/no-such-project/notes.txt", "content", "x"));

		assertTrue(result.isError());
		assertTrue(result.text().contains("no-such-project"), result.text());
	}

	private IFile existing(String content) throws Exception {
		IProject project = fixture.createProject(PROJECT);
		TestFixture.callAndParse(TOOL, Map.of("path", "/" + PROJECT + "/notes.txt", "content", content));
		return project.getFile("notes.txt");
	}
}
