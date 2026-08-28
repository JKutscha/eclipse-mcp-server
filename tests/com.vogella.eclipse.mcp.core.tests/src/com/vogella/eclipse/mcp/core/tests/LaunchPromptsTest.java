package com.vogella.eclipse.mcp.core.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.eclipse.core.runtime.preferences.InstanceScope;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.vogella.eclipse.mcp.core.LaunchPrompts;

/**
 * Silencing the settings that stop an unattended launch, and putting them back.
 * <p>
 * The counting is the part worth testing: these are global preferences, so two
 * launches that overlap would otherwise restore each other's values while the
 * other is still running, and the second launch would suspend after all.
 */
class LaunchPromptsTest {

	private static final String JDT_DEBUG_UI = "org.eclipse.jdt.debug.ui";

	private static final String SUSPEND_ON_UNCAUGHT = "org.eclipse.jdt.debug.ui.javaDebug.SuspendOnUncaughtExceptions";

	@AfterEach
	void releaseWhatTheTestHeld() throws Exception {
		while (LaunchPrompts.held() > 0) {
			LaunchPrompts.release();
		}
		InstanceScope.INSTANCE.getNode(JDT_DEBUG_UI).remove(SUSPEND_ON_UNCAUGHT);
		InstanceScope.INSTANCE.getNode(JDT_DEBUG_UI).flush();
	}

	@Test
	void suspendOnUncaughtExceptionsIsOffWhileALaunchHoldsIt() {
		LaunchPrompts.quiet();

		assertEquals("false", InstanceScope.INSTANCE.getNode(JDT_DEBUG_UI).get(SUSPEND_ON_UNCAUGHT, null));
		assertEquals(1, LaunchPrompts.held());
	}

	@Test
	void aKeyThatWasNotSetIsRemovedAgainRatherThanPinned() {
		InstanceScope.INSTANCE.getNode(JDT_DEBUG_UI).remove(SUSPEND_ON_UNCAUGHT);

		LaunchPrompts.quiet();
		LaunchPrompts.release();

		assertNull(InstanceScope.INSTANCE.getNode(JDT_DEBUG_UI).get(SUSPEND_ON_UNCAUGHT, null),
				"restoring must not write a value the user never had");
	}

	@Test
	void aValueTheUserSetComesBackExactly() throws Exception {
		InstanceScope.INSTANCE.getNode(JDT_DEBUG_UI).put(SUSPEND_ON_UNCAUGHT, "true");
		InstanceScope.INSTANCE.getNode(JDT_DEBUG_UI).flush();

		LaunchPrompts.quiet();
		LaunchPrompts.release();

		assertEquals("true", InstanceScope.INSTANCE.getNode(JDT_DEBUG_UI).get(SUSPEND_ON_UNCAUGHT, null));
	}

	@Test
	void theSecondLaunchDoesNotRestoreWhileTheFirstIsStillRunning() {
		LaunchPrompts.quiet();
		LaunchPrompts.quiet();

		LaunchPrompts.release();

		assertEquals("false", InstanceScope.INSTANCE.getNode(JDT_DEBUG_UI).get(SUSPEND_ON_UNCAUGHT, null),
				"one launch has ended, the other has not, so the setting has to stay silenced");
		assertEquals(1, LaunchPrompts.held());

		LaunchPrompts.release();

		assertEquals(0, LaunchPrompts.held());
	}

	@Test
	void releasingWhatNobodyHeldDoesNothing() {
		LaunchPrompts.release();

		assertEquals(0, LaunchPrompts.held());
	}
}
