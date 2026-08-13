package com.vogella.eclipse.mcp.server;

/**
 * Where a client reaches the server, and the bearer token it has to send.
 */
public record McpEndpoint(String url, String token) {
}
