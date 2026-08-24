package com.vogella.eclipse.mcp.server.internal;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Rejects every request that does not carry the session bearer token.
 */
public final class BearerTokenFilter implements Filter {

	private static final String PREFIX = "Bearer "; //$NON-NLS-1$

	private final byte[] expected;

	public BearerTokenFilter(String token) {
		this.expected = (PREFIX + token).getBytes(StandardCharsets.UTF_8);
	}

	@Override
	public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
			throws IOException, ServletException {
		if (!(request instanceof HttpServletRequest httpRequest)
				|| !(response instanceof HttpServletResponse httpResponse)) {
			chain.doFilter(request, response);
			return;
		}
		String authorization = httpRequest.getHeader("Authorization"); //$NON-NLS-1$
		if (authorization == null
				|| !MessageDigest.isEqual(authorization.getBytes(StandardCharsets.UTF_8), expected)) {
			httpResponse.setHeader("WWW-Authenticate", "Bearer"); //$NON-NLS-1$ //$NON-NLS-2$
			// a client turns this into "requires re-authorization" and disconnects, so
			// the body has to say what to do rather than only that something is wrong:
			// the token is stale, and here are the two files it can be re-read from
			httpResponse.sendError(HttpServletResponse.SC_UNAUTHORIZED,
					"The bearer token sent is not the one this server is using, which usually means a client is configured with a token from before it last changed. Nothing needs re-authorizing: re-read the current token from %s, or from the 'token' field of %s, and update the client configuration. This server is serving the workspace %s." //$NON-NLS-1$
							.formatted(TokenStore.location(), EndpointFile.location(), EndpointFile.workspace()));
			return;
		}
		// counted here because the SDK's transport does not expose its sessions, and
		// tools that answer about "the most recent" anything need to know whether
		// there is more than one client to be most recent for
		String session = httpRequest.getHeader("Mcp-Session-Id"); //$NON-NLS-1$
		if ("DELETE".equals(httpRequest.getMethod())) { //$NON-NLS-1$
			ActiveSessions.ended(session);
		} else {
			ActiveSessions.seen(session);
		}
		chain.doFilter(request, response);
	}
}
