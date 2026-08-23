package com.vogella.eclipse.mcp.core.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.eclipse.core.runtime.preferences.InstanceScope;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.osgi.service.prefs.BackingStoreException;

import com.vogella.eclipse.mcp.core.McpToolResult;

class RunCommandToolTest {

	private static final String QUALIFIER = "com.vogella.eclipse.mcp.server";

	private static final String KEY = "commandRoots";

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
	void refusesWhenNoDirectoryIsAllowed() throws Exception {
		McpToolResult result = TestFixture.call("eclipse_run_command",
				Map.of("command", "echo hello", "directory", System.getProperty("java.io.tmpdir")));

		assertTrue(result.isError(), "got " + result.text());
		assertTrue(result.text().contains("switched off"), "got " + result.text());
	}

	@Test
	void refusesADirectoryOutsideTheAllowedRoots() throws Exception {
		Path allowed = Files.createTempDirectory("mcp-command-allowed");
		allow(allowed.toString());

		McpToolResult result = TestFixture.call("eclipse_run_command",
				Map.of("command", "echo hello", "directory", System.getProperty("user.dir")));

		assertTrue(result.isError(), "got " + result.text());
		assertTrue(result.text().contains("not under any directory"), "got " + result.text());
	}

	@Test
	@SuppressWarnings("unchecked")
	void runsAnAllowedCommandAndCapturesItsOutput() throws Exception {
		Path directory = Files.createTempDirectory("mcp-command-run");
		allow(directory.toString());

		Map<String, Object> result = TestFixture.callAndParse("eclipse_run_command",
				Map.of("args", java.util.List.of("echo", "from the command"), "directory", directory.toString(),
						"wait", Boolean.TRUE, "timeoutSeconds", Integer.valueOf(20)));

		assertEquals("done", result.get("state"), "got " + result);
		assertEquals(Integer.valueOf(0), result.get("exitCode"), "got " + result);
		assertTrue(String.valueOf(result.get("output")).contains("from the command"), "got " + result);

		// the same handle answers again, which is what a long build polls
		Map<String, Object> polled = TestFixture.callAndParse("eclipse_get_command_output",
				Map.of("commandId", result.get("commandId")));
		assertEquals("done", polled.get("state"), "got " + polled);
	}

	@Test
	void reportsAFailingCommandAsFailedWithItsExitCode() throws Exception {
		Path directory = Files.createTempDirectory("mcp-command-fail");
		allow(directory.toString());

		Map<String, Object> result = TestFixture.callAndParse("eclipse_run_command",
				Map.of("args", java.util.List.of("sh", "-c", "echo broken >&2; exit 3"), "directory",
						directory.toString(), "wait", Boolean.TRUE, "timeoutSeconds", Integer.valueOf(20)));

		assertEquals("failed", result.get("state"), "got " + result);
		assertEquals(Integer.valueOf(3), result.get("exitCode"), "got " + result);
		// stderr is merged, so the reason sits next to the step that produced it
		assertTrue(String.valueOf(result.get("output")).contains("broken"), "got " + result);
	}

	@Test
	void refusesADirectoryThatDoesNotExist() throws Exception {
		McpToolResult result = TestFixture.call("eclipse_run_command",
				Map.of("command", "echo hello", "directory", "/no/such/directory/here"));

		assertTrue(result.isError(), "got " + result.text());
		assertTrue(result.text().contains("no directory"), "got " + result.text());
	}
}
