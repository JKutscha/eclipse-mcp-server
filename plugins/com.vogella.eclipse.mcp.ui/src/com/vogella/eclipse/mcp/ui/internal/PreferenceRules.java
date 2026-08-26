package com.vogella.eclipse.mcp.ui.internal;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds the {@code IEclipsePreferences} blocks in a CSS snippet.
 * <p>
 * A block such as {@code IEclipsePreferences#org-eclipse-jdt-ui { preferences:
 * 'java_keyword=255,0,0'; }} styles preference nodes rather than widgets and is
 * only styled on the theme activation path, so it is detected before any engine
 * sees the snippet. The id in the selector escapes dots as dashes, which loses
 * dashes that were part of the qualifier; a wrongly guessed qualifier simply
 * matches no rules in the engine, so the verification after applying decides,
 * not this scan.
 */
public final class PreferenceRules {

	/** One block found in a snippet: its selector, its escaped id and its key=value pairs. */
	public record Rule(String selector, String escapedId, String qualifier, Map<String, String> values) {

		/** How many pairs the block declares. */
		public int size() {
			return values.size();
		}
	}

	private static final Pattern SELECTOR = Pattern.compile("(?i)IEclipsePreferences\\s*#\\s*([\\w\\-]+)[^{]*\\{"); //$NON-NLS-1$

	private static final Pattern PAIR = Pattern.compile("'([^'=]+)=([^']*)'"); //$NON-NLS-1$

	private PreferenceRules() {
	}

	/** Every block the snippet declares, in order, empty for one without any. */
	public static List<Rule> scan(String css) {
		List<Rule> found = new ArrayList<>();
		if (css == null) {
			return found;
		}
		Matcher selector = SELECTOR.matcher(css);
		while (selector.find()) {
			String escapedId = selector.group(1);
			int bodyStart = selector.end();
			int bodyEnd = css.indexOf('}', bodyStart);
			String body = bodyEnd < 0 ? css.substring(bodyStart) : css.substring(bodyStart, bodyEnd);
			Map<String, String> values = new LinkedHashMap<>();
			Matcher pair = PAIR.matcher(body);
			while (pair.find()) {
				values.put(pair.group(1).strip(), pair.group(2));
			}
			found.add(new Rule("IEclipsePreferences#" + escapedId, escapedId, escapedId.replace('-', '.'), values)); //$NON-NLS-1$
		}
		return found;
	}
}
