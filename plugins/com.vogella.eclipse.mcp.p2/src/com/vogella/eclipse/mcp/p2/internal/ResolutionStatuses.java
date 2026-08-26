package com.vogella.eclipse.mcp.p2.internal;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.MultiStatus;
import org.eclipse.core.runtime.Status;
import org.osgi.framework.FrameworkUtil;

/**
 * Turns a failed p2 resolution status into something readable.
 * <p>
 * p2 reports a failed resolution with the top level message "Operation details"
 * and keeps every actual conflict in the children of the multi status, so the
 * tree has to be walked for an answer to say more than that.
 */
public final class ResolutionStatuses {

	/** Cap on the explanations reported inline, like every other list here. */
	public static final int MAX_EXPLANATIONS = 20;

	private ResolutionStatuses() {
	}

	/**
	 * Collects the messages below {@code status}, outermost first, recursing into
	 * nested multi statuses and collapsing duplicates.
	 */
	public static List<String> explanations(IStatus status) {
		Set<String> collected = new LinkedHashSet<>();
		collect(status, collected);
		return new ArrayList<>(collected);
	}

	private static void collect(IStatus status, Set<String> collected) {
		if (status == null || !status.isMultiStatus()) {
			return;
		}
		for (IStatus child : status.getChildren()) {
			if (child != null && child.getMessage() != null && !child.getMessage().isBlank()) {
				collected.add(child.getMessage());
			}
			collect(child, collected);
		}
	}

	/**
	 * Builds the error answer for a failed resolution: the caller's headline and
	 * the top level message, then up to twenty of p2's own explanations, and logs
	 * the whole status once so the detail survives even when only the short answer
	 * was seen.
	 */
	public static String failure(String headline, IStatus resolution) {
		log(resolution);
		StringBuilder text = new StringBuilder(headline).append(": ").append(resolution.getMessage()); //$NON-NLS-1$
		List<String> reasons = explanations(resolution);
		if (reasons.isEmpty()) {
			return text.toString();
		}
		text.append("\np2 gave these reasons, most specific last:"); //$NON-NLS-1$
		int shown = Math.min(reasons.size(), MAX_EXPLANATIONS);
		for (int i = 0; i < shown; i++) {
			text.append("\n- ").append(reasons.get(i)); //$NON-NLS-1$
		}
		if (shown < reasons.size()) {
			text.append("\n(showing %d of %d; the rest were capped, and the full status was logged as a warning.)" //$NON-NLS-1$
					.formatted(shown, reasons.size()));
		}
		return text.toString();
	}

	/**
	 * Logs a failed resolution at warning level, flattened so every explanation is
	 * a sub entry of one warning rather than an error severity inherited from p2.
	 */
	private static void log(IStatus resolution) {
		try {
			String pluginId = FrameworkUtil.getBundle(ResolutionStatuses.class).getSymbolicName();
			ILog log = ILog.of(ResolutionStatuses.class);
			MultiStatus entry = new MultiStatus(pluginId, IStatus.WARNING, resolution.getMessage(),
					resolution.getException());
			for (String reason : explanations(resolution)) {
				entry.add(new Status(IStatus.WARNING, pluginId, reason));
			}
			log.log(entry);
		} catch (RuntimeException e) {
			// logging must never turn a reported failure into another failure
		}
	}
}
