package com.vogella.eclipse.mcp.server.internal;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.Platform;
import org.osgi.framework.FrameworkUtil;

import com.vogella.eclipse.mcp.core.json.JsonObject;
import com.vogella.eclipse.mcp.server.McpEndpoint;

/**
 * The discovery file under the bundle state location, so that clients do not have to be
 * configured with the port and the token by hand.
 */
public final class EndpointFile {

	private static final String FILE_NAME = "endpoint.json"; //$NON-NLS-1$

	private static final String OWNER_ONLY = "rw-------"; //$NON-NLS-1$

	private EndpointFile() {
	}

	/** The absolute path of the discovery file, whether or not it exists. */
	public static Path location() {
		return Platform.getStateLocation(FrameworkUtil.getBundle(EndpointFile.class)).append(FILE_NAME).toFile()
				.toPath();
	}

	public static void write(McpEndpoint endpoint) {
		Path path = location();
		String json = new JsonObject().put("url", endpoint.url()).put("token", endpoint.token()).toString(); //$NON-NLS-1$ //$NON-NLS-2$
		try {
			Files.deleteIfExists(path);
			try {
				Files.createFile(path,
						PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString(OWNER_ONLY)));
			} catch (UnsupportedOperationException e) {
				// no POSIX permissions on this platform, the file inherits the state location's access rights
				Files.createFile(path);
			}
			Files.writeString(path, json, StandardCharsets.UTF_8);
		} catch (IOException e) {
			ILog.get().error("Could not write the MCP endpoint file " + path, e); //$NON-NLS-1$
		}
	}

	public static void delete() {
		Path path = location();
		try {
			Files.deleteIfExists(path);
		} catch (IOException e) {
			ILog.get().warn("Could not delete the MCP endpoint file " + path, e); //$NON-NLS-1$
		}
	}
}
