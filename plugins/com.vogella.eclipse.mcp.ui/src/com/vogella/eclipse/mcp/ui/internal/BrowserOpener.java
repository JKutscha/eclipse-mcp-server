package com.vogella.eclipse.mcp.ui.internal;

import java.net.URL;

import org.eclipse.swt.program.Program;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.browser.IWorkbenchBrowserSupport;

import com.vogella.eclipse.mcp.core.TracePages;

/**
 * Opens a trace page in the machine's browser.
 * <p>
 * Through the workbench's browser support first, because that is what honours the
 * user's choice under General &gt; Web Browser, and through SWT's {@code Program} when
 * that fails, which is the platform's own file association and needs no workbench
 * preference to be set.
 */
final class BrowserOpener implements TracePages.Opener {

	/** An id of our own, so repeated opens reuse one external browser rather than piling up. */
	private static final String BROWSER_ID = "com.vogella.eclipse.mcp.trace"; //$NON-NLS-1$

	static void install() {
		TracePages.setOpener(new BrowserOpener());
	}

	@Override
	public String open(String url) {
		if (!PlatformUI.isWorkbenchRunning()) {
			return "There is no running workbench to open a browser from."; //$NON-NLS-1$
		}
		// asyncExec, never syncExec: the call arrives on a request thread and opening
		// a browser can block on the desktop, which must not take that thread with it
		UiThread.TimedOutcome outcome = UiThread.timed(10, () -> {
			launch(url);
			return null;
		});
		if (outcome.timedOut()) {
			// the launch was queued and may still happen, so this is not a failure
			return "The browser did not open within ten seconds. The URL is in this answer either way."; //$NON-NLS-1$
		}
		return outcome.error();
	}

	private static void launch(String url) {
		try {
			IWorkbenchBrowserSupport support = PlatformUI.getWorkbench().getBrowserSupport();
			support.createBrowser(IWorkbenchBrowserSupport.AS_EXTERNAL, BROWSER_ID, null, null)
					.openURL(new URL(url));
		} catch (Exception e) {
			// no configured browser, or none this platform can start that way
			if (!Program.launch(url)) {
				throw new IllegalStateException("No browser could be started for " + url, e); //$NON-NLS-1$
			}
		}
	}
}
