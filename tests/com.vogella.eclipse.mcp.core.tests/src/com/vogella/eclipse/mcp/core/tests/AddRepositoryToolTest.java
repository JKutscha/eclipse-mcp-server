package com.vogella.eclipse.mcp.core.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.eclipse.core.runtime.preferences.InstanceScope;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.osgi.service.prefs.BackingStoreException;

import com.vogella.eclipse.mcp.core.McpToolResult;

class AddRepositoryToolTest {

	private static final String QUALIFIER = "com.vogella.eclipse.mcp.server";

	private static final String KEY = "repositoryRoots";

	@AfterEach
	void clearRoots() throws BackingStoreException {
		InstanceScope.INSTANCE.getNode(QUALIFIER).remove(KEY);
		InstanceScope.INSTANCE.getNode(QUALIFIER).flush();
	}

	private static void allow(String roots) throws BackingStoreException {
		InstanceScope.INSTANCE.getNode(QUALIFIER).put(KEY, roots);
		InstanceScope.INSTANCE.getNode(QUALIFIER).flush();
	}

	@Test
	void refusesWhenNoRootIsConfigured() throws Exception {
		McpToolResult result = TestFixture.call("eclipse_add_repository",
				Map.of("url", "file:/tmp/whatever/repository"));

		assertTrue(result.isError(), "got " + result.text());
		assertTrue(result.text().contains("allows no repository roots"), "got " + result.text());
	}

	@Test
	void refusesAUrlOutsideTheConfiguredRoots() throws Exception {
		allow("file:/home/me/git");

		McpToolResult result = TestFixture.call("eclipse_add_repository", Map.of("url", "file:/etc/repository"));

		assertTrue(result.isError(), "got " + result.text());
		assertTrue(result.text().contains("not under any repository root"), "got " + result.text());
	}

	@Test
	void refusesATraversalOutOfAnAllowedRoot() throws Exception {
		allow("file:/home/me/git");

		McpToolResult result = TestFixture.call("eclipse_add_repository",
				Map.of("url", "file:/home/me/git/../../etc/repository"));

		assertTrue(result.isError(), "a normalised path decides, got " + result.text());
	}

	@Test
	void reportsAnAllowedUrlThatIsNotARepository() throws Exception {
		allow("file:/home/me/git");

		McpToolResult result = TestFixture.call("eclipse_add_repository",
				Map.of("url", "file:/home/me/git/nothing-here"));

		// past the allowlist, so the failure is about the content rather than the URL
		assertTrue(result.isError(), "got " + result.text());
		assertTrue(result.text().contains("Could not read a p2 repository"), "got " + result.text());
	}

	@Test
	void removalIsBoundedByTheSameAllowlist() throws Exception {
		McpToolResult result = TestFixture.call("eclipse_remove_repository",
				Map.of("url", "https://download.eclipse.org/releases/2026-06"));

		assertTrue(result.isError(), "a site the user configured by hand is theirs, got " + result.text());
	}

	@Test
	void allowsAnExactRootMatch() throws Exception {
		allow("file:/home/me/git/project/target/repository");

		McpToolResult result = TestFixture.call("eclipse_add_repository",
				Map.of("url", "file:/home/me/git/project/target/repository"));

		assertEquals(false, result.text().contains("repository root"), "got " + result.text());
	}
}
