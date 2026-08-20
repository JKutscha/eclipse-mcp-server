package com.vogella.eclipse.mcp.core.internal;

import java.util.regex.Pattern;

/**
 * Translates the {@code *} and {@code ?} globs a client is likely to type into
 * regular expressions.
 */
public final class Globs {

	private Globs() {
	}

	/** Returns {@code null} for {@code null}, so that an absent filter stays absent. */
	public static Pattern compile(String glob) {
		if (glob == null) {
			return null;
		}
		StringBuilder regex = new StringBuilder();
		for (int i = 0; i < glob.length(); i++) {
			char c = glob.charAt(i);
			switch (c) {
			case '*' -> regex.append(".*"); //$NON-NLS-1$
			case '?' -> regex.append('.');
			default -> regex.append(Pattern.quote(String.valueOf(c)));
			}
		}
		return Pattern.compile(regex.toString(), Pattern.CASE_INSENSITIVE);
	}
}
