package com.vogella.eclipse.mcp.core;

/**
 * The text a tool produced, plus the flag that tells the model whether the call failed.
 */
public record McpToolResult(String text, boolean isError) {

	public static McpToolResult of(String text) {
		return new McpToolResult(text, false);
	}

	public static McpToolResult error(String message) {
		return new McpToolResult(message, true);
	}
}
