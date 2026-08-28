package com.vogella.eclipse.mcp.core.json;

import java.util.List;
import java.util.Map;

/**
 * Writes indented JSON for the small value model of this package.
 */
public final class Json {

	private Json() {
	}

	/**
	 * Serializes {@link JsonObject}, {@link JsonArray}, {@link String}, {@link Number},
	 * {@link Boolean} and {@code null} as indented JSON.
	 */
	public static String write(Object value) {
		StringBuilder out = new StringBuilder();
		writeValue(value, out, 0);
		return out.toString();
	}

	private static void writeValue(Object value, StringBuilder out, int indent) {
		switch (value) {
		case null -> out.append("null"); //$NON-NLS-1$
		case JsonObject object -> writeObject(object.values(), out, indent);
		case JsonArray array -> writeArray(array.values(), out, indent);
		case String string -> writeString(string, out);
		case Boolean bool -> out.append(bool.booleanValue());
		case Number number -> writeNumber(number, out);
		default -> writeString(String.valueOf(value), out);
		}
	}

	private static void writeObject(Map<String, Object> values, StringBuilder out, int indent) {
		if (values.isEmpty()) {
			out.append("{}"); //$NON-NLS-1$
			return;
		}
		out.append("{\n"); //$NON-NLS-1$
		int remaining = values.size();
		for (Map.Entry<String, Object> entry : values.entrySet()) {
			indent(out, indent + 1);
			writeString(entry.getKey(), out);
			out.append(": "); //$NON-NLS-1$
			writeValue(entry.getValue(), out, indent + 1);
			if (--remaining > 0) {
				out.append(',');
			}
			out.append('\n');
		}
		indent(out, indent);
		out.append('}');
	}

	private static void writeArray(List<Object> values, StringBuilder out, int indent) {
		if (values.isEmpty()) {
			out.append("[]"); //$NON-NLS-1$
			return;
		}
		out.append("[\n"); //$NON-NLS-1$
		int remaining = values.size();
		for (Object value : values) {
			indent(out, indent + 1);
			writeValue(value, out, indent + 1);
			if (--remaining > 0) {
				out.append(',');
			}
			out.append('\n');
		}
		indent(out, indent);
		out.append(']');
	}

	/**
	 * JSON has no spelling for NaN or the infinities, so a ratio that divided by
	 * zero would otherwise produce a document the client cannot parse at all. Null
	 * loses that one value and keeps the rest of the answer readable.
	 */
	private static void writeNumber(Number number, StringBuilder out) {
		// only the floating point types, because a BigInteger too large for a double
		// is still an exact number JSON can spell
		boolean broken = (number instanceof Double || number instanceof Float)
				&& (Double.isNaN(number.doubleValue()) || Double.isInfinite(number.doubleValue()));
		out.append(broken ? "null" : number.toString()); //$NON-NLS-1$
	}

	private static void indent(StringBuilder out, int level) {
		out.append("  ".repeat(level)); //$NON-NLS-1$
	}

	private static void writeString(String value, StringBuilder out) {
		out.append('"');
		for (int i = 0; i < value.length(); i++) {
			char c = value.charAt(i);
			switch (c) {
			case '"' -> out.append("\\\""); //$NON-NLS-1$
			case '\\' -> out.append("\\\\"); //$NON-NLS-1$
			case '\b' -> out.append("\\b"); //$NON-NLS-1$
			case '\f' -> out.append("\\f"); //$NON-NLS-1$
			case '\n' -> out.append("\\n"); //$NON-NLS-1$
			case '\r' -> out.append("\\r"); //$NON-NLS-1$
			case '\t' -> out.append("\\t"); //$NON-NLS-1$
			default -> {
				if (c < 0x20) {
					out.append("\\u%04x".formatted((int) c)); //$NON-NLS-1$
				} else {
					out.append(c);
				}
			}
			}
		}
		out.append('"');
	}
}
