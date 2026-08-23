package com.vogella.eclipse.mcp.server.internal;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.preferences.UserScope;
import org.osgi.framework.FrameworkUtil;

/**
 * The bearer token, kept in the user area so that it survives IDE restarts and is the
 * same for every workspace this user opens.
 */
public final class TokenStore {

	private static final String FILE_NAME = "token"; //$NON-NLS-1$

	private static final String BUNDLE_NAME = "com.vogella.eclipse.mcp.server"; //$NON-NLS-1$

	private TokenStore() {
	}

	/**
	 * The token file under the user scope location, {@code ~/.eclipse}.
	 * <p>
	 * Not the bundle state location, which lives inside the workspace: the port is
	 * one preference with the same default everywhere, so a workspace scoped token
	 * gives every workspace a different secret behind one address, and a client
	 * configured against one of them is rejected by the next with nothing to tell
	 * that apart from the server being gone. User scope is the narrowest one that
	 * matches how a client is registered, which is once per user.
	 * <p>
	 * The file is written directly rather than as a preference, because a
	 * preference file is world readable and this is a secret.
	 */
	static Path location() {
		IPath area = UserScope.INSTANCE.getLocation();
		Path root = area == null ? Path.of(System.getProperty("user.home"), ".eclipse") //$NON-NLS-1$ //$NON-NLS-2$
				: Path.of(area.toOSString());
		return root.resolve(BUNDLE_NAME).resolve(FILE_NAME);
	}

	/** Where the token used to live, one per workspace. */
	private static Path workspaceLocation() {
		return Platform.getStateLocation(FrameworkUtil.getBundle(TokenStore.class)).append(FILE_NAME).toFile().toPath();
	}

	/** The stored token, generating and storing one on first use. */
	public static synchronized String get() {
		Path path = location();
		String token = read(path);
		if (token != null) {
			return token;
		}
		// an IDE that already had a workspace token keeps it, so that a client
		// registered before this moved to user scope is not silently orphaned
		Path workspaceToken = workspaceLocation();
		String inherited = read(workspaceToken);
		if (inherited != null) {
			String adopted = store(inherited, path);
			retire(workspaceToken);
			return adopted;
		}
		return regenerate();
	}

	/** Replaces the stored token and returns the new one. */
	public static synchronized String regenerate() {
		return store(UUID.randomUUID().toString(), location());
	}

	/**
	 * Renames the adopted workspace token out of the way.
	 * <p>
	 * A file still called {@code token}, owner-only, sitting beside the live
	 * endpoint.json and holding a value the server no longer uses is a trap for
	 * whoever reads it while diagnosing: it looks exactly like current state. It is
	 * renamed rather than deleted so the previous value can still be recovered.
	 */
	private static void retire(Path path) {
		try {
			Files.move(path, path.resolveSibling(FILE_NAME + ".migrated"), //$NON-NLS-1$
					java.nio.file.StandardCopyOption.REPLACE_EXISTING);
		} catch (IOException e) {
			ILog.get().warn("Could not rename the migrated workspace token %s".formatted(path), e); //$NON-NLS-1$
		}
	}

	private static String read(Path path) {
		try {
			if (Files.exists(path)) {
				String token = Files.readString(path, StandardCharsets.UTF_8).strip();
				if (!token.isEmpty()) {
					return token;
				}
			}
		} catch (IOException e) {
			ILog.get().warn("Could not read the MCP token from %s, generating a new one".formatted(path), e); //$NON-NLS-1$
		}
		return null;
	}

	private static String store(String token, Path path) {
		try {
			PrivateFiles.write(path, token);
		} catch (IOException e) {
			ILog.get().error(
					"Could not store the MCP token in %s, it will change again on the next restart".formatted(path), e); //$NON-NLS-1$
		}
		return token;
	}
}
