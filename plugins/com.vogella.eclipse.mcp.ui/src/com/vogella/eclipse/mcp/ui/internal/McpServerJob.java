package com.vogella.eclipse.mcp.ui.internal;

import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;

import com.vogella.eclipse.mcp.server.McpPreferences;
import com.vogella.eclipse.mcp.server.McpServerException;
import com.vogella.eclipse.mcp.server.McpServerService;

/**
 * Brings the running server in line with the preferences, off the UI thread.
 */
final class McpServerJob extends Job {

	private McpServerJob() {
		super("Applying the MCP server preferences"); //$NON-NLS-1$
		setSystem(true);
	}

	/** Schedules the reconciliation and returns the job, so that callers can react when it is done. */
	static Job reconcile() {
		Job job = new McpServerJob();
		job.schedule();
		return job;
	}

	@Override
	protected IStatus run(IProgressMonitor monitor) {
		McpServerService service = McpServerService.getInstance();
		boolean enabled = McpPreferences.isEnabled();
		int port = McpPreferences.getPort();
		try {
			if (!enabled) {
				service.stop();
				return Status.OK_STATUS;
			}
			if (service.isRunning() && service.getPort() == port) {
				return Status.OK_STATUS;
			}
			service.stop();
			service.start();
		} catch (McpServerException e) {
			// failing to open a socket must not pop up a dialog during startup
			ILog.get().error(e.getMessage(), e.getCause() == null ? e : e.getCause());
		}
		return Status.OK_STATUS;
	}
}
