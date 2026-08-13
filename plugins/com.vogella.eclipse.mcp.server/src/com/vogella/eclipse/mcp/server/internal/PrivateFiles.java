package com.vogella.eclipse.mcp.server.internal;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

/**
 * Writes the files that carry the bearer token, readable by their owner only where the
 * platform supports it.
 */
final class PrivateFiles {

	private static final String OWNER_ONLY = "rw-------"; //$NON-NLS-1$

	private PrivateFiles() {
	}

	static void write(Path path, String content) throws IOException {
		Files.deleteIfExists(path);
		try {
			Files.createFile(path, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString(OWNER_ONLY)));
		} catch (UnsupportedOperationException e) {
			// no POSIX permissions on this platform, the file inherits the directory's access rights
			Files.createFile(path);
		}
		Files.writeString(path, content, StandardCharsets.UTF_8);
	}
}
