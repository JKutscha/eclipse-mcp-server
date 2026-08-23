package com.vogella.eclipse.mcp.core.internal;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import org.eclipse.core.runtime.Platform;

/**
 * The directories the person at the IDE has allowed a client to run commands in.
 * <p>
 * Empty by default, which means the command tools are off. Running a command is
 * the one thing this server does that is not the IDE acting on itself, and it can
 * do anything the user can, so it is switched on deliberately and scoped to the
 * trees where a build is expected to happen.
 */
public final class CommandRoots {

	private static final String QUALIFIER = "com.vogella.eclipse.mcp.server"; //$NON-NLS-1$

	private static final String KEY = "commandRoots"; //$NON-NLS-1$

	private CommandRoots() {
	}

	public static List<String> configured() {
		String value = Platform.getPreferencesService().getString(QUALIFIER, KEY, "", null); //$NON-NLS-1$
		return value.lines().map(String::strip).filter(line -> !line.isEmpty()).toList();
	}

	/**
	 * Whether commands may run in {@code directory}.
	 * <p>
	 * Compared on the real path, so that neither {@code ../} nor a symbolic link
	 * pointing out of an allowed root gets past this.
	 */
	public static boolean allows(Path directory) {
		Path candidate = real(directory);
		for (String root : configured()) {
			Path allowed = real(Path.of(root));
			if (candidate.startsWith(allowed)) {
				return true;
			}
		}
		return false;
	}

	private static Path real(Path path) {
		try {
			return path.toRealPath();
		} catch (IOException e) {
			// a path that does not exist yet cannot be resolved, and an absolute
			// comparison is still better than none
			return path.toAbsolutePath().normalize();
		}
	}

	public static String refusal(Path directory) {
		List<String> roots = configured();
		if (roots.isEmpty()) {
			return "Refused: running commands is switched off. It is the one thing this server does that is not the IDE acting on itself, so the person at the IDE turns it on deliberately: add the directories commands may run in under Preferences > General > MCP Server."; //$NON-NLS-1$
		}
		return "Refused: '%s' is not under any directory this IDE allows commands in. Allowed: %s. Add another under Preferences > General > MCP Server." //$NON-NLS-1$
				.formatted(directory, String.join(", ", roots)); //$NON-NLS-1$
	}
}
