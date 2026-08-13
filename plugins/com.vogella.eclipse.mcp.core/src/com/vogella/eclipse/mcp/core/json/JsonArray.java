package com.vogella.eclipse.mcp.core.json;

import java.util.ArrayList;
import java.util.List;

/**
 * A JSON array.
 */
public final class JsonArray {

	private final List<Object> values = new ArrayList<>();

	/**
	 * Appends an element. Accepted values are {@link JsonObject}, {@link JsonArray},
	 * {@link String}, {@link Number}, {@link Boolean} and {@code null}.
	 */
	public JsonArray add(Object value) {
		values.add(value);
		return this;
	}

	public int size() {
		return values.size();
	}

	List<Object> values() {
		return values;
	}

	@Override
	public String toString() {
		return Json.write(this);
	}
}
