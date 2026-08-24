package com.vogella.eclipse.mcp.core;

import org.eclipse.core.runtime.Platform;

/**
 * How long a tool may block before the server aborts the call.
 * <p>
 * A tool that waits longer than the server's own call timeout is killed, and the
 * caller gets an error instead of the handle it could have polled with. That made
 * the maximum several tools advertise unreachable: asking eclipse_build to wait
 * five minutes never returned a buildId, only "did not finish within 30 seconds",
 * while the id it needed already existed. Waits are bounded here instead, and the
 * tool answers with what it has.
 */
public final class CallBudget {

	private static final String QUALIFIER = "com.vogella.eclipse.mcp.server"; //$NON-NLS-1$

	private static final String KEY = "callTimeoutSeconds"; //$NON-NLS-1$

	private static final int DEFAULT_CALL_TIMEOUT_SECONDS = 30;

	/** Left for the answer to be written and sent after the wait gives up. */
	private static final int MARGIN_SECONDS = 3;

	private CallBudget() {
	}

	/** The configured tool call timeout in seconds. */
	public static int callTimeoutSeconds() {
		return Platform.getPreferencesService().getInt(QUALIFIER, KEY, DEFAULT_CALL_TIMEOUT_SECONDS, null);
	}

	/** The longest a tool should wait, which is the call timeout less a margin. */
	public static int maxWaitSeconds() {
		return Math.max(1, callTimeoutSeconds() - MARGIN_SECONDS);
	}

	/** {@code requested} capped at what fits inside the call timeout. */
	public static int boundedWaitSeconds(int requested) {
		return Math.min(requested, maxWaitSeconds());
	}

	/**
	 * A sentence for the answer when the wait was cut short, or {@code null} when it
	 * was not, so that an early return is never mistaken for a finished job.
	 */
	public static String clampNote(int requested, String handleName) {
		int bounded = boundedWaitSeconds(requested);
		if (bounded >= requested) {
			return null;
		}
		return "Waited %d of the %d seconds asked for, because the server aborts any call that outlasts its %d second tool call timeout, which is set in Preferences > General > MCP Server. This is not a failure: poll %s, which is still running." //$NON-NLS-1$
				.formatted(Integer.valueOf(bounded), Integer.valueOf(requested),
						Integer.valueOf(callTimeoutSeconds()), handleName);
	}
}
