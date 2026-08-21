package com.vogella.eclipse.mcp.server.internal;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Counts the MCP sessions that have been heard from recently.
 * <p>
 * The SDK's transport does not expose its sessions, so they are counted from the
 * {@code Mcp-Session-Id} header on the way through the token filter. A client
 * that has gone away stops refreshing its entry and drops out of the count, which
 * is what keeps a stale disconnect from making the defaults refuse forever.
 */
public final class ActiveSessions {

	// short, because a client that reconnects looks like a second client until its
	// old session ages out, and reconnecting is exactly what a client does after
	// eclipse_restart. Ending a session removes it at once; this only bounds the
	// case where a client goes away without saying so
	private static final long WINDOW_MILLIS = 60 * 1000L;

	private static final Map<String, Long> lastSeen = new ConcurrentHashMap<>();

	private ActiveSessions() {
	}

	static void seen(String sessionId) {
		if (sessionId != null && !sessionId.isBlank()) {
			lastSeen.put(sessionId, Long.valueOf(System.currentTimeMillis()));
		}
	}

	/** A client ending its session stops being a client immediately. */
	static void ended(String sessionId) {
		if (sessionId != null) {
			lastSeen.remove(sessionId);
		}
	}

	/** How many distinct clients have been heard from inside the window. */
	public static int count() {
		long cutoff = System.currentTimeMillis() - WINDOW_MILLIS;
		lastSeen.values().removeIf(seen -> seen.longValue() < cutoff);
		return lastSeen.size();
	}

	public static void clear() {
		lastSeen.clear();
	}
}
