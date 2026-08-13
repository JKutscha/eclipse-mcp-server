package com.vogella.eclipse.mcp.core.json;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A JSON object that keeps its members in insertion order.
 */
public final class JsonObject {

	private final Map<String, Object> values = new LinkedHashMap<>();

	/**
	 * Adds a member. Accepted values are {@link JsonObject}, {@link JsonArray},
	 * {@link String}, {@link Number}, {@link Boolean} and {@code null}.
	 */
	public JsonObject put(String name, Object value) {
		values.put(name, value);
		return this;
	}

	public boolean isEmpty() {
		return values.isEmpty();
	}

	Map<String, Object> values() {
		return values;
	}

	@Override
	public String toString() {
		return Json.write(this);
	}
}
