package com.vogella.eclipse.mcp.server;

/**
 * Thrown when the embedded server cannot be started or stopped.
 */
public class McpServerException extends Exception {

	private static final long serialVersionUID = 1L;

	public McpServerException(String message) {
		super(message);
	}

	public McpServerException(String message, Throwable cause) {
		super(message, cause);
	}
}
