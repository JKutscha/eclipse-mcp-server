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
			// error and error_description as RFC 6750 defines them, because a client
			// that discards the body may still read the challenge. Claude Code reads
			// neither: it renders any 401 as "requires re-authorization" and drops the
			// payload, so a stale token reaches the model as an auth problem it cannot
			// act on. Answering 200 with a JSON-RPC error would reach it, and is not
			// worth it: a rejected credential has to be a 401, or every other client's
			// auth handling is wrong instead.
			httpResponse.setHeader("WWW-Authenticate", //$NON-NLS-1$
					"Bearer error=\"invalid_token\", error_description=\"The bearer token is not the one this server is using. Re-read it from the token file or from endpoint.json in the workspace, and update the client configuration.\""); //$NON-NLS-1$
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
