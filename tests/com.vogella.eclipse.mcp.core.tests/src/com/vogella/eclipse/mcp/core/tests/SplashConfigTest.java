package com.vogella.eclipse.mcp.core.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.vogella.eclipse.mcp.ui.internal.SplashConfig;

/**
 * The edits made to config.ini, which is p2's file and the one the IDE will not start
 * without.
 * <p>
 * Everything here is text handling with no IDE in it, which is the point: the parts
 * that can corrupt an installation are the escaping and the line rewriting, and both
 * are checkable without one.
 */
class SplashConfigTest {

	/** The real file from an SDK install, escaping and all. */
	private static final List<String> CONFIG = List.of(
			"#This configuration file was written by: org.eclipse.equinox.internal.frameworkadmin.equinox.EquinoxFwConfigFileParser",
			"#Sat Aug 29 06:48:25 CEST 2026",
			"eclipse.application=org.eclipse.ui.ide.workbench",
			"eclipse.product=org.eclipse.sdk.ide",
			"osgi.framework=file\\:plugins/org.eclipse.osgi_3.24.300.v20260721-1251.jar",
			"osgi.splashPath=platform\\:/base/plugins/org.eclipse.sdk");

	@Test
	void aColonIsEscapedTheWayAPropertiesFileWantsIt() {
		// unescaped, the launcher reads the value as truncated at the colon and shows
		// no splash at all, which is a broken install for a cosmetic setting
		assertEquals("/home/x/.eclipse/splash.png", SplashConfig.escape("/home/x/.eclipse/splash.png"));
		assertEquals("platform\\:/base/plugins/x", SplashConfig.escape("platform:/base/plugins/x"));
		assertEquals("C\\:\\\\Users\\\\x", SplashConfig.escape("C:\\Users\\x"));
	}

	@Test
	void escapingRoundTrips() {
		for (String value : List.of("platform:/base/plugins/org.eclipse.sdk", "C:\\Program Files\\eclipse\\s.png",
				"/plain/path.png", "a=b", "with#hash", "with!bang")) {
			assertEquals(value, SplashConfig.unescape(SplashConfig.escape(value)), value);
		}
	}

	@Test
	void readingUnescapesTheValueItFinds() {
		assertEquals("platform:/base/plugins/org.eclipse.sdk", SplashConfig.read(CONFIG, "osgi.splashPath"));
		assertNull(SplashConfig.read(CONFIG, "osgi.splashLocation"));
	}

	@Test
	void aCommentedOutKeyIsNotAValue() {
		List<String> lines = List.of("#osgi.splashLocation=/commented/out.png", "eclipse.product=x");

		assertNull(SplashConfig.read(lines, "osgi.splashLocation"));
		// and setting it has to add a real line rather than revive the comment
		List<String> set = SplashConfig.set(lines, "osgi.splashLocation", "/new.png");
		assertEquals(3, set.size(), "got " + set);
		assertTrue(set.get(0).startsWith("#"), "the comment has to survive untouched, got " + set.get(0));
	}

	@Test
	void settingAKeyLeavesEveryOtherLineByteForByte() {
		List<String> result = SplashConfig.set(CONFIG, "osgi.splashLocation", "/home/x/splash.png");

		assertEquals(CONFIG.size() + 1, result.size());
		for (int i = 0; i < CONFIG.size(); i++) {
			assertEquals(CONFIG.get(i), result.get(i), "line " + i + " was rewritten");
		}
		assertEquals("osgi.splashLocation=/home/x/splash.png", result.get(result.size() - 1));
	}

	@Test
	void settingAnExistingKeyReplacesItInPlace() {
		List<String> once = SplashConfig.set(CONFIG, "osgi.splashPath", "/first.png");
		List<String> twice = SplashConfig.set(once, "osgi.splashPath", "/second.png");

		assertEquals(CONFIG.size(), twice.size(), "replacing must not append a second line, got " + twice);
		assertEquals("osgi.splashPath=/second.png", twice.get(twice.size() - 1));
		assertEquals("/second.png", SplashConfig.read(twice, "osgi.splashPath"));
	}

	@Test
	void removingTakesOnlyThatKey() {
		List<String> result = SplashConfig.remove(CONFIG, "osgi.splashPath");

		assertEquals(CONFIG.size() - 1, result.size());
		assertNull(SplashConfig.read(result, "osgi.splashPath"));
		assertEquals("org.eclipse.sdk.ide", SplashConfig.read(result, "eclipse.product"),
				"an unrelated key was lost, got " + result);
	}

	@Test
	void aKeyThatIsAPrefixOfAnotherIsNotConfused() {
		// osgi.splashPath starts with osgi.splash, and taking the wrong one would
		// remove the platform's own setting while claiming to remove ours
		List<String> both = SplashConfig.set(CONFIG, "osgi.splashLocation", "/ours.png");

		List<String> result = SplashConfig.remove(both, "osgi.splashLocation");
		assertEquals("platform:/base/plugins/org.eclipse.sdk", SplashConfig.read(result, "osgi.splashPath"),
				"the platform's splashPath must survive, got " + result);
	}

	@Test
	void aValueWithSpacesSurvivesAWindowsStylePath() {
		String windows = "C:\\Program Files\\eclipse\\configuration\\splash.png";
		List<String> result = SplashConfig.set(CONFIG, "osgi.splashLocation", windows);

		assertEquals(windows, SplashConfig.read(result, "osgi.splashLocation"));
	}
}
