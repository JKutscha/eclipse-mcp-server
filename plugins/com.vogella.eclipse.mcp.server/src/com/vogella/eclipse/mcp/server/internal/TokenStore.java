package com.vogella.eclipse.mcp.server.internal;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.Platform;
import org.osgi.framework.FrameworkUtil;

/**
 * The bearer token, kept in the bundle state location so that it survives IDE restarts
 * and client configurations stay valid.
 */
public final class TokenStore {

	private static final String FILE_NAME = "token"; //$NON-NLS-1$

	private TokenStore() {
	}

	static Path location() {
		return Platform.getStateLocation(FrameworkUtil.getBundle(TokenStore.class)).append(FILE_NAME).toFile().toPath();
	}

	/** The stored token, generating and storing one on first use. */
	public static synchronized String get() {
		Path path = location();
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
		return regenerate();
	}

	/** Replaces the stored token and returns the new one. */
	public static synchronized String regenerate() {
		String token = UUID.randomUUID().toString();
		Path path = location();
		try {
			PrivateFiles.write(path, token);
		} catch (IOException e) {
			ILog.get().error(
					"Could not store the MCP token in %s, it will change again on the next restart".formatted(path), e); //$NON-NLS-1$
		}
		return token;
	}
}
