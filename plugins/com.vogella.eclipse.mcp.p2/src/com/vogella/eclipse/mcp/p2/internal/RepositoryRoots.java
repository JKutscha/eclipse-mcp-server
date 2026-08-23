package com.vogella.eclipse.mcp.p2.internal;

import java.net.URI;
import java.util.List;

import org.eclipse.core.runtime.Platform;

/**
 * The URL prefixes the person at the IDE has allowed a client to add as a p2
 * repository.
 * <p>
 * Read from the server bundle's own preferences by qualifier rather than through
 * its API, so that this bundle keeps depending on nothing but core and p2. Empty
 * by default: adding a repository fetches and runs code from a new source, and
 * that decision stays with the person at the IDE. Configuring a prefix makes it
 * once for a class of URLs instead of once per install.
 */
final class RepositoryRoots {

	private static final String QUALIFIER = "com.vogella.eclipse.mcp.server"; //$NON-NLS-1$

	private static final String KEY = "repositoryRoots"; //$NON-NLS-1$

	private RepositoryRoots() {
	}

	static List<String> configured() {
		String value = Platform.getPreferencesService().getString(QUALIFIER, KEY, "", null); //$NON-NLS-1$
		return value.lines().map(String::strip).filter(line -> !line.isEmpty()).toList();
	}

	/**
	 * Whether {@code uri} starts with one of the configured prefixes.
	 * <p>
	 * Compared on the normalised URI, so that {@code ../} cannot walk out of an
	 * allowed root and a trailing slash does not decide the answer.
	 */
	static boolean allows(URI uri) {
		String candidate = normalise(uri.normalize().toString());
		for (String root : configured()) {
			String prefix = normalise(URI.create(root).normalize().toString());
			if (candidate.equals(prefix) || candidate.startsWith(prefix + "/")) { //$NON-NLS-1$
				return true;
			}
		}
		return false;
	}

	private static String normalise(String value) {
		return value.endsWith("/") ? value.substring(0, value.length() - 1) : value; //$NON-NLS-1$
	}

	static String refusal(URI uri) {
		List<String> roots = configured();
		if (roots.isEmpty()) {
			return "Refused: this IDE allows no repository roots, so a client may not add '%s'. Adding a repository fetches and runs code from a new source, which is a decision for the person at the IDE. Configure the URL prefixes that are acceptable under Preferences > General > MCP Server, or add the site by hand under Preferences > Install/Update > Available Software Sites." //$NON-NLS-1$
					.formatted(uri);
		}
		return "Refused: '%s' is not under any repository root this IDE allows. Allowed roots: %s. Add another under Preferences > General > MCP Server." //$NON-NLS-1$
				.formatted(uri, String.join(", ", roots)); //$NON-NLS-1$
	}
}
