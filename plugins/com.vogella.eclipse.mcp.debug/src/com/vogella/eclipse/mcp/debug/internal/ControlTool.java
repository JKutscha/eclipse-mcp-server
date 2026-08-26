package com.vogella.eclipse.mcp.debug.internal;

import java.util.Map;
import java.util.Set;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.debug.core.DebugException;
import org.eclipse.debug.core.model.IDebugTarget;
import org.eclipse.debug.core.model.IThread;

import com.vogella.eclipse.mcp.core.CallBudget;
import com.vogella.eclipse.mcp.core.IMcpTool;
import com.vogella.eclipse.mcp.core.McpToolException;
import com.vogella.eclipse.mcp.core.McpToolResult;
import com.vogella.eclipse.mcp.core.ToolArguments;
import com.vogella.eclipse.mcp.core.json.JsonObject;

/**
 * Resumes, steps, suspends, terminates or disconnects a debug session, and
 * reports where the program ended up.
 */
public final class ControlTool implements IMcpTool {

	private static final Set<String> ACTIONS = Set.of("resume", "stepOver", "stepInto", "stepReturn", "suspend", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
			"terminate", "disconnect"); //$NON-NLS-1$ //$NON-NLS-2$

	@Override
	public String getName() {
		return "eclipse_debug_control"; //$NON-NLS-1$
	}

	@Override
	public String getDescription() {
		return "Drives a suspended or running debug session: resume, stepOver, stepInto, stepReturn, suspend, terminate or disconnect. CHANGES THE STATE OF THE DEBUGGED PROGRAM, and terminate KILLS THE PROCESS. After a step or a resume it waits for the next suspend (waitForSuspendSeconds) and reports the new location in the same answer, so stepping costs one call; timedOut on resume normally just means the program kept running. Stepping needs a suspended thread: name one with 'thread' or leave it to the only suspended thread, which is refused when several are stopped. Use eclipse_debug_launch to start something first."; //$NON-NLS-1$
	}

	@Override
	public String getInputSchema() {
		return """
				{
				  "type": "object",
				  "required": ["action"],
				  "properties": {
				    "sessionId":            {"type":"string","description":"The session to drive. Omitted means the only live one."},
				    "action":               {"type":"string","enum":["resume","stepOver","stepInto","stepReturn","suspend","terminate","disconnect"],"description":"What to do."},
				    "thread":               {"type":"string","description":"Thread to act on. Defaults to the single suspended thread for the stepping actions."},
				    "waitForSuspendSeconds":{"type":"integer","default":20,"minimum":0,"maximum":25,"description":"How long to wait for the next suspend after resume and the steps. 0 answers immediately."}
				  },
				  "additionalProperties": false
				}"""; //$NON-NLS-1$
	}

	@Override
	public McpToolResult call(Map<String, Object> arguments, IProgressMonitor monitor) throws McpToolException {
		ToolArguments args = ToolArguments.of(arguments);
		String action = args.getString("action"); //$NON-NLS-1$
		if (action == null || !ACTIONS.contains(action)) {
			return McpToolResult.error("'action' is one of %s%s.".formatted(String.join(", ", ACTIONS.stream().sorted().toList()), //$NON-NLS-1$ //$NON-NLS-2$
					action == null ? "" : ", not '" + action + "'")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
		}
		boolean waitAfter = action.equals("resume") || action.startsWith("step"); //$NON-NLS-1$ //$NON-NLS-2$
		int waitSeconds = args.getInt("waitForSuspendSeconds", waitAfter ? 20 : 0, 0, 25); //$NON-NLS-1$

		try {
			DebugSessionRegistry.Session session = DebugSupport.requireSession(args.getString("sessionId")); //$NON-NLS-1$
			IDebugTarget target = DebugSupport.target(session);
			IThread thread = null;
			if (!action.equals("terminate") && !action.equals("disconnect")) { //$NON-NLS-1$ //$NON-NLS-2$
				thread = DebugSupport.requireThread(target, args.getString("thread")); //$NON-NLS-1$
			}
			DebugSessionRegistry.SuspendSignal signal = null;
			if (waitSeconds > 0) {
				signal = DebugSessionRegistry.getInstance().onNextSuspend(session);
			}
			perform(session, target, thread, action);

			JsonObject json = DebugSupport.sessionJson(session, 50);
			json.put("action", action); //$NON-NLS-1$
			if (signal != null) {
				boolean arrived = signal.await(CallBudget.boundedWaitSeconds(waitSeconds));
				json = DebugSupport.sessionJson(session, 50);
				json.put("action", action); //$NON-NLS-1$
				boolean nowSuspended = session.suspended();
				if (!arrived && !nowSuspended) {
					json.put("timedOut", Boolean.TRUE).put("waitNote", //$NON-NLS-1$ //$NON-NLS-2$
							action.equals("resume") //$NON-NLS-1$
									? "No suspend within %d seconds; the program most likely kept running.".formatted(Integer.valueOf(waitSeconds)) //$NON-NLS-2$
									: "No suspend within %d seconds.".formatted(Integer.valueOf(waitSeconds))); //$NON-NLS-1$
				} else {
					json.put("timedOut", Boolean.FALSE); //$NON-NLS-1$
					json.put("location", locationOf(session)); //$NON-NLS-1$
				}
			}
			return McpToolResult.of(json.toString());
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new McpToolException("Interrupted while waiting for the suspend.", e);
		} catch (DebugException e) {
			throw new McpToolException("Could not %s: %s".formatted(action, e.getMessage()), e);
		} catch (DebugSupport.Refusal e) {
			return McpToolResult.error(e.getMessage());
		}
	}

	private String locationOf(DebugSessionRegistry.Session session) {
		var target = DebugSupport.target(session);
		for (IThread candidate : DebugSupport.threads(target)) {
			if (DebugSupport.isSuspended(candidate)) {
				String location = DebugSupport.location(candidate);
				if (location != null) {
					return location;
				}
			}
		}
		return null;
	}

	private void perform(DebugSessionRegistry.Session session, IDebugTarget target, IThread thread, String action)
			throws DebugException {
		switch (action) {
		case "resume" -> { //$NON-NLS-1$
			if (thread != null && thread.isSuspended()) {
				thread.resume();
			} else if (!target.isSuspended()) {
				throw new DebugSupport.Refusal("Nothing is suspended here; there is nothing to resume."); //$NON-NLS-1$
			} else {
				target.resume();
			}
		}
		case "suspend" -> { //$NON-NLS-1$
			if (target.isSuspended()) {
				throw new DebugSupport.Refusal("The program is already suspended."); //$NON-NLS-1$
			}
			target.suspend();
		}
		case "stepOver" -> { //$NON-NLS-1$
			if (!thread.canStepOver()) {
				throw cannot("step over"); //$NON-NLS-1$
			}
			thread.stepOver();
		}
		case "stepInto" -> { //$NON-NLS-1$
			if (!thread.canStepInto()) {
				throw cannot("step into"); //$NON-NLS-1$
			}
			thread.stepInto();
		}
		case "stepReturn" -> { //$NON-NLS-1$
			if (!thread.canStepReturn()) {
				throw cannot("step return"); //$NON-NLS-1$
			}
			thread.stepReturn();
		}
		case "terminate" -> terminate(session); //$NON-NLS-1$
		case "disconnect" -> target.disconnect(); //$NON-NLS-1$
		default -> throw new DebugSupport.Refusal("Unknown action '%s'.".formatted(action)); //$NON-NLS-1$
		}
	}

	private static DebugSupport.Refusal cannot(String what) {
		return new DebugSupport.Refusal(
				"Cannot %s from here; the thread has to be suspended at a steppable frame.".formatted(what)); //$NON-NLS-1$
	}

	private static void terminate(DebugSessionRegistry.Session session) throws DebugException {
		var launch = session.launch();
		if (launch == null) {
			throw new DebugSupport.Refusal("This session has nothing running to terminate."); //$NON-NLS-1$
		}
		if (!launch.canTerminate()) {
			throw new DebugSupport.Refusal("This session cannot be terminated; it has probably ended already."); //$NON-NLS-1$
		}
		launch.terminate();
	}
}
