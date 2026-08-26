package com.vogella.eclipse.mcp.core.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.MultiStatus;
import org.eclipse.core.runtime.Status;
import org.junit.jupiter.api.Test;

import com.vogella.eclipse.mcp.p2.internal.ResolutionStatuses;

/**
 * The flattening of a p2 resolution status into readable reasons.
 * <p>
 * p2 answers a failed resolution with the top level message "Operation details"
 * and hides the actual conflicts in the children, so this is the logic that
 * decides whether a caller learns why. It is pure logic over {@link IStatus},
 * which is why it can be tested directly with hand built statuses.
 */
class ResolutionStatusesTest {

	private static final String PLUGIN = "com.vogella.eclipse.mcp.p2.tests.fixture"; //$NON-NLS-1$

	@Test
	void collectsChildMessagesInOrderThroughNestedMultiStatuses() {
		MultiStatus nested = new MultiStatus(PLUGIN, IStatus.ERROR, new IStatus[] {
				new Status(IStatus.WARNING, PLUGIN, "inner one"), //$NON-NLS-1$
				new Status(IStatus.WARNING, PLUGIN, "inner two") }, "nested", null); //$NON-NLS-1$ //$NON-NLS-2$
		MultiStatus root = new MultiStatus(PLUGIN, IStatus.ERROR, "Operation details", null); //$NON-NLS-1$
		root.add(new Status(IStatus.WARNING, PLUGIN, "first conflict")); //$NON-NLS-1$
		root.add(nested);

		// the message of the nested status is collected too, not only its leaves: p2
		// puts the readable sentence on the intermediate level, "cannot complete the
		// install because of a conflicting dependency", and the specifics under it
		assertEquals(List.of("first conflict", "nested", "inner one", "inner two"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
				ResolutionStatuses.explanations(root));
	}

	@Test
	void duplicatesCollapseAndBlankMessagesAreSkipped() {
		MultiStatus root = new MultiStatus(PLUGIN, IStatus.ERROR, "Operation details", null); //$NON-NLS-1$
		root.add(new Status(IStatus.WARNING, PLUGIN, "same conflict")); //$NON-NLS-1$
		root.add(new Status(IStatus.WARNING, PLUGIN, "")); //$NON-NLS-1$
		root.add(new Status(IStatus.WARNING, PLUGIN, "   ")); //$NON-NLS-1$
		root.add(new Status(IStatus.WARNING, PLUGIN, "same conflict")); //$NON-NLS-1$

		assertEquals(List.of("same conflict"), ResolutionStatuses.explanations(root)); //$NON-NLS-1$
	}

	@Test
	void aLeafStatusHasNoExplanations() {
		Status leaf = new Status(IStatus.ERROR, PLUGIN, "just the message"); //$NON-NLS-1$

		assertTrue(ResolutionStatuses.explanations(leaf).isEmpty());
	}

	@Test
	void failureKeepsTheTopLevelMessageAndTheHeadline() {
		MultiStatus root = new MultiStatus(PLUGIN, IStatus.ERROR, "Operation details", null); //$NON-NLS-1$
		root.add(new Status(IStatus.WARNING, PLUGIN, "the real reason")); //$NON-NLS-1$

		String text = ResolutionStatuses.failure("The install could not be resolved", root); //$NON-NLS-1$

		assertTrue(text.startsWith("The install could not be resolved: Operation details"), "got " + text); //$NON-NLS-1$ //$NON-NLS-2$
		assertTrue(text.contains("- the real reason"), "got " + text); //$NON-NLS-1$ //$NON-NLS-2$
	}

	@Test
	void failureCapsTheReasonsAndSaysSo() {
		MultiStatus root = new MultiStatus(PLUGIN, IStatus.ERROR, "Operation details", null); //$NON-NLS-1$
		for (int i = 0; i < 25; i++) {
			root.add(new Status(IStatus.WARNING, PLUGIN, "conflict %d".formatted(i))); //$NON-NLS-1$
		}

		String text = ResolutionStatuses.failure("The uninstall could not be resolved", root); //$NON-NLS-1$

		assertEquals(ResolutionStatuses.MAX_EXPLANATIONS,
				text.split("\n- ", -1).length - 1, "got " + text); //$NON-NLS-1$ //$NON-NLS-2$
		assertTrue(text.contains("showing 20 of 25"), "got " + text); //$NON-NLS-1$ //$NON-NLS-2$
		assertTrue(text.contains("logged as a warning"), "got " + text); //$NON-NLS-1$ //$NON-NLS-2$
	}
}
