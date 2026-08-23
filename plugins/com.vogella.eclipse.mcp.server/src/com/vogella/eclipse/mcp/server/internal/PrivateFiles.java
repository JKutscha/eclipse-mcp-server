package com.vogella.eclipse.mcp.server.internal;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;

/**
 * Writes the files that carry the bearer token, readable by their owner only where the
 * platform supports it.
 */
final class PrivateFiles {

	private static final String OWNER_ONLY = "rw-------"; //$NON-NLS-1$

	private PrivateFiles() {
	}

	/**
	 * Writes through a temporary file and moves it into place.
	 * <p>
	 * Truncating in place leaves a window in which the file does not exist, and a
	 * second IDE starting at that moment reads no token and mints a new one, which
	 * silently invalidates every client of the first. A move is atomic, so a reader
	 * sees either the old content or the new one.
	 */
	static void write(Path path, String content) throws IOException {
		Path parent = path.getParent();
		if (parent != null) {
			Files.createDirectories(parent);
		}
		Path temporary = path.resolveSibling(path.getFileName() + ".tmp"); //$NON-NLS-1$
		Files.deleteIfExists(temporary);
		try {
			Files.createFile(temporary,
					PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString(OWNER_ONLY)));
		} catch (UnsupportedOperationException e) {
			// no POSIX permissions on this platform, the file inherits the directory's access rights
			Files.createFile(temporary);
		}
		Files.writeString(temporary, content, StandardCharsets.UTF_8);
		try {
			Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
		} catch (AtomicMoveNotSupportedException e) {
			Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
		}
	}
}
