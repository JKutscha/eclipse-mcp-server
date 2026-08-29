package com.vogella.eclipse.mcp.server.internal;

import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The rendered trace pages the server is currently willing to serve, each behind an
 * unguessable path.
 * <p>
 * A browser cannot put the bearer token on a plain navigation, so these pages cannot
 * sit behind the token filter and are guarded by the URL instead: 128 random bits from
 * a {@link SecureRandom}, which is the same strength as the token itself. The socket is
 * already bound to the loopback interface, so the pair is a capability URL reachable
 * only from this machine.
 * <p>
 * Nothing is written to disk and nothing survives the process. A page holds stack
 * traces of the user's own IDE, so the exposure is bounded by keeping only the last few
 * and by dying with the server.
 */
public final class TracePageStore {

	/** Enough to compare a few runs, few enough that stale profiles do not accumulate. */
	private static final int KEEP = 8;

	private static final SecureRandom RANDOM = new SecureRandom();

	private static final Map<String, Page> PAGES = new LinkedHashMap<>() {
		private static final long serialVersionUID = 1L;

		@Override
		protected boolean removeEldestEntry(Map.Entry<String, Page> eldest) {
			return size() > KEEP;
		}
	};

	/** One rendered page. */
	record Page(String title, String html) {
	}

	private TracePageStore() {
	}

	/** Stores the page and answers with the path segment that reaches it. */
	public static synchronized String add(String title, String html) {
		byte[] bytes = new byte[16];
		RANDOM.nextBytes(bytes);
		String id = HexFormat.of().formatHex(bytes);
		PAGES.put(id, new Page(title, html));
		return id;
	}

	static synchronized Page get(String id) {
		return id == null ? null : PAGES.get(id);
	}

	public static synchronized void clear() {
		PAGES.clear();
	}
}
