package com.vogella.eclipse.mcp.core;

import java.util.Map;

/**
 * Type-safe access to the loosely typed argument map an MCP client sends.
 */
public final class ToolArguments {

	private final Map<String, Object> arguments;

	private ToolArguments(Map<String, Object> arguments) {
		this.arguments = arguments == null ? Map.of() : arguments;
	}

	public static ToolArguments of(Map<String, Object> arguments) {
		return new ToolArguments(arguments);
	}

	/** Returns the trimmed value, or {@code null} when absent or blank. */
	public String getString(String name) {
		Object value = arguments.get(name);
		if (value == null) {
			return null;
		}
		String text = String.valueOf(value).trim();
		return text.isEmpty() ? null : text;
	}

	public String getString(String name, String fallback) {
		String value = getString(name);
		return value == null ? fallback : value;
	}

	/** Returns the value clamped into {@code [min, max]}, or {@code fallback} when absent or unparsable. */
	public int getInt(String name, int fallback, int min, int max) {
		Object value = arguments.get(name);
		int result = fallback;
		if (value instanceof Number number) {
			result = number.intValue();
		} else if (value != null) {
			try {
				result = Integer.parseInt(String.valueOf(value).trim());
			} catch (NumberFormatException e) {
				result = fallback;
			}
		}
		return Math.clamp(result, min, max);
	}
}
