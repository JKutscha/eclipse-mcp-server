package com.vogella.eclipse.mcp.core.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.Test;

import com.vogella.eclipse.mcp.core.internal.SubstituteBundleTool;

/**
 * What the framework has actually loaded, which is the field a caller is meant
 * to trust over bundles.info.
 * <p>
 * It used to be unreadable: the answer came from this bundle's own
 * BundleContext, and this bundle declares no activator and no lazy activation,
 * so it never leaves RESOLVED and the context is null for the life of the IDE.
 * Every caller was told the server was still starting and to ask again.
 */
class SubstituteBundleToolTest {

	@Test
	void reportsWhatTheFrameworkHasLoadedRatherThanRefusingToLook() throws Exception {
		Map<String, Object> running = TestFixture
				.parse(SubstituteBundleTool.running("org.eclipse.core.runtime").toString());

		assertEquals(Boolean.TRUE, running.get("known"),
				"a bundle that is certainly there has to be readable, got " + running);
		assertNotNull(running.get("version"), "got " + running);
		assertNotNull(running.get("state"), "the state is what tells a resolved bundle from a broken one");
		assertEquals(Boolean.FALSE, running.get("isSubstitutedJar"),
				"nothing is substituted in the test IDE, got " + running);
	}

	@Test
	void aBundleThatIsNotThereIsSaidToBeAbsentRatherThanUnreadable() throws Exception {
		Map<String, Object> running = TestFixture
				.parse(SubstituteBundleTool.running("com.example.no.such.bundle").toString());

		assertEquals(Boolean.FALSE, running.get("known"));
		assertTrue(String.valueOf(running.get("reason")).contains("no bundle called"),
				"got " + running.get("reason"));
	}
}
