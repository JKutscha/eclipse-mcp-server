package com.vogella.eclipse.mcp.server.internal;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Serves one rendered trace page, addressed by the random id it was stored under.
 */
public final class TraceServlet extends HttpServlet {

	private static final long serialVersionUID = 1L;

	@Override
	protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
		String path = request.getPathInfo();
		String id = path == null ? null : path.replace("/", "").strip(); //$NON-NLS-1$ //$NON-NLS-2$
		TracePageStore.Page page = TracePageStore.get(id);
		if (page == null) {
			// the same answer for a malformed id and an expired one, so that the
			// response cannot be used to tell a wrong guess from an old page
			response.setStatus(HttpServletResponse.SC_NOT_FOUND);
			response.setContentType("text/plain; charset=utf-8"); //$NON-NLS-1$
			response.getWriter().write(
					"No trace page here. Pages are kept in memory only, the last few at a time, and are gone when Eclipse restarts. Record again to get a fresh one."); //$NON-NLS-1$
			return;
		}
		response.setStatus(HttpServletResponse.SC_OK);
		response.setContentType("text/html; charset=utf-8"); //$NON-NLS-1$
		// it is a profile of a running IDE, not something to keep or to share onward
		response.setHeader("Cache-Control", "no-store"); //$NON-NLS-1$ //$NON-NLS-2$
		response.setHeader("Referrer-Policy", "no-referrer"); //$NON-NLS-1$ //$NON-NLS-2$
		// the page is entirely self contained, so nothing it needs comes from anywhere
		response.setHeader("Content-Security-Policy", //$NON-NLS-1$
				"default-src 'none'; style-src 'unsafe-inline'; script-src 'unsafe-inline'; img-src data:"); //$NON-NLS-1$
		response.setHeader("X-Content-Type-Options", "nosniff"); //$NON-NLS-1$ //$NON-NLS-2$
		byte[] bytes = page.html().getBytes(StandardCharsets.UTF_8);
		response.setContentLength(bytes.length);
		response.getOutputStream().write(bytes);
	}
}
