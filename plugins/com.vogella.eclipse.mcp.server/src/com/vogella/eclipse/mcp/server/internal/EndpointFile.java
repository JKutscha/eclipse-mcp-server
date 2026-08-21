package com.vogella.eclipse.mcp.server.internal;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

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

	private EndpointFile() {
	}

	/** The absolute path of the discovery file, whether or not it exists. */
	public static Path location() {
		return Platform.getStateLocation(FrameworkUtil.getBundle(EndpointFile.class)).append(FILE_NAME).toFile()
				.toPath();
	}

	public static void write(McpEndpoint endpoint) {
		Path path = location();
		// startedAt identifies this server process. A client that restarts the IDE
		// cannot tell "reachable" from "restarted", because the reply is sent before
		// the restart begins and the old process answers until it dies; comparing this
		// value across a reconnect is the cheap way to know the new one is up.
		String json = new JsonObject().put("url", endpoint.url()).put("token", endpoint.token()) //$NON-NLS-1$ //$NON-NLS-2$
				.put("startedAt", System.currentTimeMillis()).toString(); //$NON-NLS-1$
		try {
			PrivateFiles.write(path, json);
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
