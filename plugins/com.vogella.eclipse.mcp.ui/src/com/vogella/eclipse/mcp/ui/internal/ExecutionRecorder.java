package com.vogella.eclipse.mcp.ui.internal;

import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.core.commands.IExecutionListener;
import org.eclipse.core.commands.NotHandledException;

import com.vogella.eclipse.mcp.core.json.JsonObject;

/**
 * Hears the framework's execution callbacks for one command run and remembers
 * which verdict it reached.
 * <p>
 * The return of {@code IHandlerService.executeCommand} and the handler's own
 * outcome are not the same thing: when a dialog holds the UI thread, neither
 * has arrived. The callbacks are what separate a handler still inside
 * {@code execute} from one that already finished, so they are recorded as they
 * fire rather than read off the eventual return.
 */
public final class ExecutionRecorder implements IExecutionListener {

	private volatile String outcome;

	/** The verdict the framework reported: success, failure or notHandled, null while none has fired. */
	public String outcome() {
		return outcome;
	}

	/** Whether the handler reached a verdict, which success and failure alone say. */
	public boolean finished() {
		return "success".equals(outcome) || "failure".equals(outcome); //$NON-NLS-1$ //$NON-NLS-2$
	}

	/** Adds the verdict to an answer being written, in every case including a timeout. */
	public JsonObject reportInto(JsonObject answer) {
		return answer.put("handlerFinished", Boolean.valueOf(finished())).put("outcome", outcome); //$NON-NLS-1$ //$NON-NLS-2$
	}

	@Override
	public void preExecute(String commandId, ExecutionEvent event) {
		// dispatched is not a verdict; only the three outcomes below are reported
	}

	@Override
	public void notHandled(String commandId, NotHandledException exception) {
		outcome = "notHandled"; //$NON-NLS-1$
	}

	@Override
	public void postExecuteFailure(String commandId, ExecutionException exception) {
		outcome = "failure"; //$NON-NLS-1$
	}

	@Override
	public void postExecuteSuccess(String commandId, Object returnValue) {
		outcome = "success"; //$NON-NLS-1$
	}
}
