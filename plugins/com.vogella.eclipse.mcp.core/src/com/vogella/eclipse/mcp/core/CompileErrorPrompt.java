package com.vogella.eclipse.mcp.core;

import org.eclipse.core.runtime.preferences.InstanceScope;

/**
 * Pre-answers the platform's "Errors in Workspace" launch prompt for the
 * duration of one launch.
 * <p>
 * Launching with compile errors otherwise raises a modal dialog, which blocks a
 * call nobody is watching and reports as a launch that never started. The value
 * is set around the launch and put back afterwards, so a person's own Run As
 * keeps prompting.
 */
public final class CompileErrorPrompt {

	private static final String DEBUG_UI = "org.eclipse.debug.ui"; //$NON-NLS-1$

	/** The constant is named PREF_CONTINUE_WITH_COMPILE_ERROR; its value is not. */
	private static final String KEY = "org.eclipse.debug.ui.cancel_launch_with_compile_errors"; //$NON-NLS-1$

	/**
	 * The only value that lets a launch through. CompileErrorPromptStatusHandler
	 * proceeds on "always" and opens the dialog for everything else, "never"
	 * included, which is the opposite of what that word suggests.
	 */
	private static final String PROCEED = "always"; //$NON-NLS-1$

	private CompileErrorPrompt() {
	}

	/** The value in effect before this server touched it, or {@code null}. */
	public static String effectiveValue() {
		return InstanceScope.INSTANCE.getNode(DEBUG_UI).get(KEY, null);
	}

	/** Answers the prompt for the next launch, returning what to pass to {@link #restore}. */
	public static String suppress() {
		String previous = effectiveValue();
		InstanceScope.INSTANCE.getNode(DEBUG_UI).put(KEY, PROCEED);
		return previous;
	}

	/**
	 * Puts the preference back. A key that was absent is removed rather than
	 * written back as a value, or this would pin a setting the user never had.
	 */
	public static void restore(String previous) {
		var node = InstanceScope.INSTANCE.getNode(DEBUG_UI);
		if (previous == null) {
			node.remove(KEY);
		} else {
			node.put(KEY, previous);
		}
	}
}
