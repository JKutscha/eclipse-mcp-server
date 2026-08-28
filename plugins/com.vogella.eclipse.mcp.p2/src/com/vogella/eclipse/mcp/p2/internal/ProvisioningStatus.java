package com.vogella.eclipse.mcp.p2.internal;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.core.runtime.IStatus;

/**
 * Turns the status a provisioning job ends with into what the client is told.
 */
public final class ProvisioningStatus {

	private ProvisioningStatus() {
	}

	/**
	 * The reported state for a finished job.
	 * <p>
	 * Only ERROR is a failure. p2 reports an operation that completed but had
	 * something to say as INFO or WARNING, so treating every non-OK status as
	 * failed reports a finished install as a failure.
	 */
	public static String stateOf(IStatus status) {
		int severity = status == null ? IStatus.OK : status.getSeverity();
		if (severity == IStatus.CANCEL) {
			return "cancelled"; //$NON-NLS-1$
		}
		return severity >= IStatus.ERROR ? "failed" : "done"; //$NON-NLS-1$ //$NON-NLS-2$
	}

	/**
	 * The text of a status, taking the children when it has none of its own.
	 * <p>
	 * A p2 operation answers with a MultiStatus whose own message is empty, so
	 * reporting {@code getMessage()} alone says nothing about what happened.
	 *
	 * @return the text, or null when there is none
	 */
	public static String describe(IStatus status) {
		if (status == null) {
			return null;
		}
		List<String> lines = new ArrayList<>();
		collect(status, lines);
		return lines.isEmpty() ? null : String.join("\n", lines); //$NON-NLS-1$
	}

	private static void collect(IStatus status, List<String> lines) {
		String message = status.getMessage();
		if (message != null && !message.isBlank() && !lines.contains(message)) {
			lines.add(message);
		}
		for (IStatus child : status.getChildren()) {
			collect(child, lines);
		}
	}
}
