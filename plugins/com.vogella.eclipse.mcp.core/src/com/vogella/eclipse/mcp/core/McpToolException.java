package com.vogella.eclipse.mcp.core;

/**
 * Thrown when a tool cannot complete for a reason the client cannot fix by sending
 * different arguments. Invalid arguments should be reported with
 * {@link McpToolResult#error(String)} instead.
 */
public class McpToolException extends Exception {

	private static final long serialVersionUID = 1L;

	public McpToolException(String message) {
		super(message);
	}

	public McpToolException(String message, Throwable cause) {
		super(message, cause);
	}
}
