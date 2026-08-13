package com.vogella.eclipse.mcp.jdt.internal;

/**
 * Signals input the client can correct, for example a type name that does not resolve.
 * The message is handed back to the model as an error result.
 */
final class ToolInputException extends Exception {

	private static final long serialVersionUID = 1L;

	ToolInputException(String message) {
		super(message);
	}
}
