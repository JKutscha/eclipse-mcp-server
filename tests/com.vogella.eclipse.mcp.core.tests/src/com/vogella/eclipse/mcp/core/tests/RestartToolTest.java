package com.vogella.eclipse.mcp.core.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.Test;

import com.vogella.eclipse.mcp.core.McpToolResult;
import com.vogella.eclipse.mcp.ui.internal.RestartTool;

/**
 * The parts of the restart that can be exercised without restarting anything.
 * <p>
 * The restart itself must never run here: it would take the test IDE with it.
 * What is left is the argument handling, which is where a mistake would be
 * silent, because nothing on this side can observe what the launcher does with
 * the arguments afterwards.
 */
class RestartToolTest {

	@Test
	void refusesWithoutAWorkbench() throws Exception {
		McpToolResult result = TestFixture.call("eclipse_restart", Map.of("splash", Boolean.FALSE));

		assertTrue(result.isError(), result.text());
		assertTrue(result.text().toLowerCase().contains("no running workbench"), result.text());
	}

	@Test
	void anArgumentIsMatchedAsAWholeLine() {
		assertTrue(RestartTool.contains("-nosplash\n", "-nosplash"));
		assertTrue(RestartTool.contains("-data\nfile:/tmp/ws\n-nosplash\n", "-nosplash"));
		assertTrue(RestartTool.contains("  -nosplash  \n", "-nosplash"));
	}

	@Test
	void aLongerArgumentThatStartsTheSameIsNotAMatch() {
		// a substring match would find this one and skip adding the real argument
		assertFalse(RestartTool.contains("-nosplashscreen\n", "-nosplash"));
		assertFalse(RestartTool.contains("-showsplash\nsomething-nosplash\n", "-nosplash"));
		assertFalse(RestartTool.contains("", "-nosplash"));
	}

	@Test
	void theArgumentIsAddedOnceAndKeepsWhatWasThere() {
		String previous = System.getProperty(RestartTool.EXIT_DATA_PROPERTY);
		try {
			System.setProperty(RestartTool.EXIT_DATA_PROPERTY, "-data\nfile:/tmp/ws\n");

			assertTrue(RestartTool.appendNoSplash());
			String after = System.getProperty(RestartTool.EXIT_DATA_PROPERTY);
			assertTrue(after.startsWith("-data\nfile:/tmp/ws\n"), "existing arguments must survive, got " + after);
			assertTrue(RestartTool.contains(after, "-nosplash"), after);

			// a second call must not add it twice: the launcher gets one argument list
			assertTrue(RestartTool.appendNoSplash());
			assertEquals(after, System.getProperty(RestartTool.EXIT_DATA_PROPERTY));
		} finally {
			if (previous == null) {
				System.clearProperty(RestartTool.EXIT_DATA_PROPERTY);
			} else {
				System.setProperty(RestartTool.EXIT_DATA_PROPERTY, previous);
			}
		}
	}
}
