package com.vogella.eclipse.mcp.core;

/**
 * Launch configuration attributes shared by the tools that launch something.
 * <p>
 * They are plain strings on purpose. The debug perspective keys belong to
 * {@code org.eclipse.debug.ui}, and a launch that must not raise a dialog is
 * needed in bundles that have no business depending on a UI bundle, so the
 * value is set by key rather than through the constant.
 */
public final class LaunchAttributes {

	/**
	 * Marks a configuration this server created, so that a session adopted from
	 * the launch manager can be told from one a person started by hand. Set it in
	 * every tool that launches, or the session is reported as somebody else's and
	 * is never cleaned up automatically.
	 */
	public static final String STARTED_BY_MCP = "com.vogella.eclipse.mcp.startedByMcp"; //$NON-NLS-1$

	/** {@code IDebugUIConstants.ATTR_TARGET_DEBUG_PERSPECTIVE}. */
	public static final String TARGET_DEBUG_PERSPECTIVE = "org.eclipse.debug.ui.target_debug_perspective"; //$NON-NLS-1$

	/** {@code IDebugUIConstants.ATTR_TARGET_RUN_PERSPECTIVE}. */
	public static final String TARGET_RUN_PERSPECTIVE = "org.eclipse.debug.ui.target_run_perspective"; //$NON-NLS-1$

	/**
	 * {@code IDebugUIConstants.PERSPECTIVE_NONE}. Without it a suspend raises the
	 * modal "Confirm Perspective Switch" prompt, which blocks the IDE of somebody
	 * who never asked for the launch.
	 */
	public static final String PERSPECTIVE_NONE = "perspective_none"; //$NON-NLS-1$

	private LaunchAttributes() {
	}
}
