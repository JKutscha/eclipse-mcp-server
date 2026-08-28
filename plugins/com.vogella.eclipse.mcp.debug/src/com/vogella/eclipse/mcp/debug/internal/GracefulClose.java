package com.vogella.eclipse.mcp.debug.internal;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.eclipse.debug.core.DebugException;
import org.eclipse.debug.core.ILaunch;
import org.eclipse.debug.core.model.IDebugTarget;
import org.eclipse.debug.core.model.IThread;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.debug.core.IJavaDebugTarget;
import org.eclipse.jdt.debug.core.IJavaStackFrame;
import org.eclipse.jdt.debug.core.IJavaThread;
import org.eclipse.jdt.debug.eval.EvaluationManager;
import org.eclipse.jdt.debug.eval.IAstEvaluationEngine;
import org.eclipse.jdt.debug.eval.IEvaluationListener;
import org.eclipse.jdt.debug.eval.IEvaluationResult;

import com.vogella.eclipse.mcp.core.json.JsonObject;

/**
 * Asks a debugged application to shut itself down, instead of killing it.
 * <p>
 * Terminating a launch calls {@code Process.destroy}, which on Linux is SIGTERM
 * and happens to reach Equinox's shutdown hook, and on Windows is an immediate
 * TerminateProcess. Neither runs the workbench's own shutdown, so the workspace
 * is not saved and the next start of the same instance finds whatever the last
 * one left behind.
 * <p>
 * The debugger can do better without anything being configured in the target:
 * suspend a thread, evaluate a call that queues the close on the target's UI
 * thread, and resume so the UI thread runs it. The queueing is the point.
 * {@code IWorkbench.close} must run on the UI thread, while an evaluation runs
 * on the thread that is suspended, so calling it directly would be wrong; an
 * asyncExec is only put in the queue and is worked off once the program runs
 * again.
 */
final class GracefulClose {

	/** Queued rather than called: the evaluation is not on the target's UI thread. */
	private static final String CLOSE_WORKBENCH = "org.eclipse.swt.widgets.Display.getDefault().asyncExec(() -> org.eclipse.ui.PlatformUI.getWorkbench().close())"; //$NON-NLS-1$

	private GracefulClose() {
	}

	/**
	 * Suspends, asks the application to close, resumes and waits for it to go.
	 *
	 * @param fallback terminate when the application is still there at the end
	 */
	static JsonObject close(DebugSessionRegistry.Session session, int waitSeconds, boolean fallback)
			throws DebugException, InterruptedException {
		JsonObject json = new JsonObject().put("sessionId", session.id()); //$NON-NLS-1$
		ILaunch launchValue = session.launch();
		IDebugTarget target = DebugSupport.target(session);
		if (!(target instanceof IJavaDebugTarget)) {
			return refuse(json, "This is not a Java debug target, so nothing can be evaluated in it."); //$NON-NLS-1$
		}

		IJavaThread thread = suspendedThread(target);
		if (thread == null) {
			return refuse(json,
					"No thread of this session could be suspended with Java frames, so there is nowhere to evaluate the close from. Terminate it instead, and expect an unsaved workspace."); //$NON-NLS-1$
		}
		if (!thread.isSuspended()) {
			return refuse(json,
					"No thread of this session is suspended at a breakpoint. A method cannot be invoked in a thread that was merely suspended: JDI allows it only for a thread stopped BY A BREAKPOINT OR A STEP, and eclipse_debug_control action suspend does not qualify. Set a breakpoint with eclipse_set_breakpoint in a NAMED class of org.eclipse.ui that the UI thread reaches regularly, wait for it with eclipse_debug_status, then call close."); //$NON-NLS-1$
		}
		if (thread.getBreakpoints().length == 0 && !thread.isPerformingEvaluation()) {
			// the same JDI rule, seen from the other side: a thread suspended by the VM
			// carries no breakpoint, and the invocation would fail with "Thread must be
			// suspended by step or breakpoint to perform method invocation"
			json.put("suspendedByBreakpoint", Boolean.FALSE); //$NON-NLS-1$
		} else {
			json.put("suspendedByBreakpoint", Boolean.TRUE); //$NON-NLS-1$
		}
		json.put("suspendedThread", DebugSupport.name(thread)); //$NON-NLS-1$

		try {
			IJavaStackFrame frame = workbenchFrame(thread);
			if (frame == null) {
				return refuse(json,
						"No frame of this thread belongs to a named class that can see org.eclipse.ui. The top frame of an idle workbench is org.eclipse.swt.internal.gtk.OS.Call, whose class loader does not see the workbench at all, and an anonymous class such as Workbench$1 cannot compile the lambda. Suspend at a breakpoint in a named org.eclipse.ui class and call close again."); //$NON-NLS-1$
			}
			json.put("frame", DebugSupport.location(frame)); //$NON-NLS-1$
			IJavaProject project = EvaluateTool.projectOf(frame, session);
			if (project == null) {
				return refuse(json,
						"No workspace project supplies the types of the suspended frame, so the expression cannot be compiled against it. That is the same limit eclipse_debug_evaluate has."); //$NON-NLS-1$
			}
			IEvaluationResult result = evaluate(project, (IJavaDebugTarget) target, frame);
			if (result != null && result.hasErrors()) {
				return refuse(json.put("evaluationErrors", String.join("; ", result.getErrorMessages())), //$NON-NLS-1$ //$NON-NLS-2$
						"The close call did not compile in the target. A program without a workbench cannot be closed this way; stop its system bundle instead."); //$NON-NLS-1$
			}
			json.put("closeQueued", Boolean.TRUE); //$NON-NLS-1$
		} finally {
			// resume whatever the state was: the queued close only runs once the UI
			// thread is free again, so leaving it suspended would hang the shutdown
			if (thread.isSuspended()) {
				thread.resume();
			}
		}

		boolean gone = waitForExit(launchValue, waitSeconds);
		json.put("closedGracefully", Boolean.valueOf(gone)) //$NON-NLS-1$
				.put("waitedSeconds", Integer.valueOf(waitSeconds)); //$NON-NLS-1$
		if (gone) {
			return json.put("terminatedInstead", Boolean.FALSE) //$NON-NLS-1$
					.put("note", //$NON-NLS-1$
							"The application ran its own shutdown, so the workspace was saved and the framework stopped in order. A recording written on exit is complete."); //$NON-NLS-1$
		}
		if (!fallback) {
			return json.put("terminatedInstead", Boolean.FALSE) //$NON-NLS-1$
					.put("note", //$NON-NLS-1$
							"It was still running when the wait ran out and nothing was killed, so it may yet close on its own, or it may be waiting on a dialog of its own that nothing here can answer."); //$NON-NLS-1$
		}
		DebugSessionRegistry.terminateAndWait(session, 10);
		return json.put("terminatedInstead", Boolean.TRUE) //$NON-NLS-1$
				.put("note", //$NON-NLS-1$
						"It did not close in time and was terminated, which is Process.destroy: SIGTERM on Linux, where Equinox's shutdown hook still runs, and an immediate kill on Windows. Either way the workbench shutdown did not happen, so treat the state it left as unsaved."); //$NON-NLS-1$
	}

	private static JsonObject refuse(JsonObject json, String why) {
		return json.put("closedGracefully", Boolean.FALSE).put("refusedBecause", why); //$NON-NLS-1$ //$NON-NLS-2$
	}

	private static IEvaluationResult evaluate(IJavaProject project, IJavaDebugTarget target, IJavaStackFrame frame)
			throws InterruptedException, DebugException {
		IAstEvaluationEngine engine = EvaluationManager.newAstEvaluationEngine(project, target);
		try {
			CountDownLatch done = new CountDownLatch(1);
			IEvaluationResult[] box = new IEvaluationResult[1];
			IEvaluationListener listener = result -> {
				box[0] = result;
				done.countDown();
			};
			engine.evaluate(CLOSE_WORKBENCH, frame, listener, org.eclipse.debug.core.DebugEvent.EVALUATION, false);
			done.await(15, TimeUnit.SECONDS);
			return box[0];
		} finally {
			engine.dispose();
		}
	}

	/** A suspended thread if there is one, otherwise any thread that could be suspended. */
	private static IJavaThread suspendedThread(IDebugTarget target) {
		IJavaThread candidate = null;
		for (IThread thread : DebugSupport.threads(target)) {
			if (thread instanceof IJavaThread java) {
				if (java.isSuspended()) {
					return java;
				}
				if (candidate == null && java.canSuspend()) {
					candidate = java;
				}
			}
		}
		return candidate;
	}

	/**
	 * A frame the close expression can actually be compiled and run against.
	 * <p>
	 * Two things rule most frames out, and both were measured rather than guessed.
	 * The type of the frame decides which class loader the expression is compiled
	 * against, so a frame in org.eclipse.swt cannot see org.eclipse.ui at all and
	 * reports it as an unresolved type, which reads like a missing workbench and is
	 * not one. And JDT refuses a lambda inside a local or anonymous class, so
	 * Workbench$1, where the idle event loop lives, is unusable even though it is
	 * the frame a breakpoint most easily reaches.
	 */
	private static IJavaStackFrame workbenchFrame(IJavaThread thread) throws DebugException {
		IJavaStackFrame fallback = null;
		for (var candidate : thread.getStackFrames()) {
			if (!(candidate instanceof IJavaStackFrame frame)) {
				continue;
			}
			String type = frame.getDeclaringTypeName();
			if (type == null || anonymous(type)) {
				continue;
			}
			if (type.startsWith("org.eclipse.ui.")) { //$NON-NLS-1$
				return frame;
			}
			if (fallback == null && (type.startsWith("org.eclipse.e4.ui.") //$NON-NLS-1$
					|| type.startsWith("org.eclipse.equinox.launcher."))) { //$NON-NLS-1$
				fallback = frame;
			}
		}
		return fallback;
	}

	/** {@code Workbench$1} and friends, where a lambda cannot be compiled. */
	private static boolean anonymous(String typeName) {
		int dollar = typeName.lastIndexOf('$');
		return dollar >= 0 && dollar + 1 < typeName.length() && Character.isDigit(typeName.charAt(dollar + 1));
	}

	private static boolean waitForSuspend(IJavaThread thread, int seconds) throws InterruptedException {
		long deadline = System.currentTimeMillis() + seconds * 1000L;
		while (System.currentTimeMillis() < deadline) {
			if (thread.isSuspended()) {
				return true;
			}
			Thread.sleep(50);
		}
		return thread.isSuspended();
	}

	private static boolean waitForExit(ILaunch launchValue, int seconds) throws InterruptedException {
		if (launchValue == null) {
			return false;
		}
		long deadline = System.currentTimeMillis() + seconds * 1000L;
		while (System.currentTimeMillis() < deadline) {
			if (launchValue.isTerminated()) {
				return true;
			}
			Thread.sleep(200);
		}
		return launchValue.isTerminated();
	}
}
