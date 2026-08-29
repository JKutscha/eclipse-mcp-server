package com.vogella.eclipse.mcp.core;

/**
 * Publishes a rendered trace page, hands back the URL a browser can open, and opens it
 * when asked.
 * <p>
 * The page is served by the embedded HTTP server, which lives in the server bundle, and
 * opening a browser needs the workbench, which lives in the UI bundle, while the tools
 * that produce traces live here and there. Neither of those may be a dependency of this
 * bundle, so both sides register their implementation on startup the same way the
 * {@link ClientSessions} provider is registered, and everything here degrades to a
 * plain "not available" when they have not.
 */
public final class TracePages {

	/** Stores one page and answers with its absolute URL. */
	public interface Publisher {

		/**
		 * @param title what the page is called, for the browser tab
		 * @param html  the complete, self contained document
		 * @return the URL to open, never {@code null}
		 */
		String publish(String title, String html);
	}

	/** Opens a URL in the machine's browser. */
	public interface Opener {

		/** @return {@code null} when the browser was launched, or why it was not */
		String open(String url);
	}

	private static volatile Publisher publisher;

	private static volatile Opener opener;

	private TracePages() {
	}

	/** Set by the server when it starts, and cleared when it stops. */
	public static void setPublisher(Publisher newPublisher) {
		publisher = newPublisher;
	}

	/** Set by the UI bundle, which is the only one that can reach a browser. */
	public static void setOpener(Opener newOpener) {
		opener = newOpener;
	}

	/** Whether a page can be served at all, which is false while the server is stopped. */
	public static boolean isAvailable() {
		return publisher != null;
	}

	/** The URL of the published page, or {@code null} when no server is running to serve it. */
	public static String publish(String title, String html) {
		Publisher current = publisher;
		return current == null ? null : current.publish(title, html);
	}

	/**
	 * Opens the page in the machine's browser.
	 *
	 * @return {@code null} when it was opened, or why it was not, which is never a
	 *         reason to fail the call: the URL is in the answer either way
	 */
	public static String open(String url) {
		Opener current = opener;
		if (current == null) {
			return "There is no workbench to open a browser from, so the URL was not opened. Open it by hand."; //$NON-NLS-1$
		}
		return current.open(url);
	}

	/** Why a caller asking for a page did not get one, for an answer that has to say so. */
	public static String unavailable() {
		return "The trace page could not be published because the MCP server is not running, which is also how you are reading this. Nothing was rendered."; //$NON-NLS-1$
	}
}
