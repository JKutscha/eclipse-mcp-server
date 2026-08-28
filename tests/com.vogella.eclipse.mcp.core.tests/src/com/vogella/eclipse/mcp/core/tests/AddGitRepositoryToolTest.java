package com.vogella.eclipse.mcp.core.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.vogella.eclipse.mcp.core.McpToolResult;

/**
 * Registering a repository that is already on disk with EGit.
 * <p>
 * The registration itself is a change to the IDE's own configuration and is
 * left to the dry run here, because a test that really registered one would
 * write into the preferences of whatever Eclipse ran the suite. What is checked
 * is the part a caller depends on: that a path which is no repository is
 * refused before anything is written, that both the working tree and the .git
 * directory are accepted as the same repository, and that the dry run reports
 * what it would do without doing it.
 */
class AddGitRepositoryToolTest {

	private static final String NAME = "eclipse_add_git_repository";

	private Path directory;

	@BeforeEach
	void createRepository() throws Exception {
		directory = Files.createTempDirectory("mcp-git-add");
		try (Git git = Git.init().setDirectory(directory.toFile()).setInitialBranch("main").call()) {
			Files.writeString(directory.resolve("tracked.txt"), "first\n");
			git.add().addFilepattern("tracked.txt").call();
			git.commit().setMessage("first").setAuthor("Test", "test@example.com")
					.setCommitter("Test", "test@example.com").call();
		}
	}

	@AfterEach
	void deleteRepository() throws Exception {
		if (directory == null || !Files.exists(directory)) {
			return;
		}
		try (var walk = Files.walk(directory)) {
			for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
				Files.deleteIfExists(path);
			}
		}
	}

	@Test
	void theDryRunResolvesTheGitDirWithoutRegisteringAnything() throws Exception {
		Map<String, Object> answer = TestFixture.callAndParse(NAME, Map.of("directory", directory.toString()));

		assertEquals(Boolean.TRUE, answer.get("dryRun"), "got " + answer);
		assertEquals(Boolean.FALSE, answer.get("registered"), "a dry run must not register, got " + answer);
		assertNotNull(answer.get("gitDir"));
		assertTrue(String.valueOf(answer.get("gitDir")).endsWith(".git"),
				"the working tree has to resolve to its .git directory, got " + answer.get("gitDir"));
	}

	@Test
	void theGitDirectoryItselfResolvesToTheSameRepository() throws Exception {
		Map<String, Object> fromWorkTree = TestFixture.callAndParse(NAME, Map.of("directory", directory.toString()));
		Map<String, Object> fromGitDir = TestFixture.callAndParse(NAME,
				Map.of("directory", directory.resolve(".git").toString()));

		assertEquals(fromWorkTree.get("gitDir"), fromGitDir.get("gitDir"), "got " + fromGitDir);
	}

	@Test
	void aDirectoryThatIsNoRepositoryIsRefusedBeforeAnythingIsWritten() throws Exception {
		Path empty = Files.createTempDirectory("mcp-git-add-none");
		try {
			McpToolResult result = TestFixture.call(NAME, Map.of("directory", empty.toString()));

			assertTrue(result.isError(), "got " + result.text());
			assertTrue(result.text().contains("not a git repository"), "got " + result.text());
		} finally {
			Files.deleteIfExists(empty);
		}
	}

	@Test
	void addAndRemoveBothNeedADirectory() throws Exception {
		for (String action : new String[] { "add", "remove" }) {
			McpToolResult result = TestFixture.call(NAME, Map.of("action", action));

			assertTrue(result.isError(), action + " without a directory, got " + result.text());
			assertTrue(result.text().contains("directory"), "got " + result.text());
		}
	}

	@Test
	void anUnknownActionIsRefusedByName() throws Exception {
		McpToolResult result = TestFixture.call(NAME, Map.of("action", "clone"));

		assertTrue(result.isError(), "got " + result.text());
		assertTrue(result.text().contains("clone"), "the refusal has to quote what was passed, got " + result.text());
	}

	@Test
	void listReportsTheConfiguredRepositoriesWithoutArguments() throws Exception {
		Map<String, Object> answer = TestFixture.callAndParse(NAME, Map.of("action", "list"));

		assertTrue(answer.get("registered") instanceof List, "got " + answer);
	}

	@Test
	void connectProjectsNamesWhatItWouldConnect() throws Exception {
		// no workspace project lives in this throw-away repository, so the list is
		// empty; what matters is that the field is reported rather than omitted, since
		// that is how a caller sees the difference between nothing to do and not asked
		Map<String, Object> answer = TestFixture.callAndParse(NAME,
				Map.of("directory", directory.toString(), "connectProjects", Boolean.TRUE));

		assertEquals(List.of(), answer.get("projectsToConnect"), "got " + answer);
	}
}
