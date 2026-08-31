package com.vogella.eclipse.mcp.ui.internal;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import org.eclipse.swt.widgets.Shell;

import com.vogella.eclipse.mcp.core.FileLocations;

/**
 * Every reference to SWT's win32 internals, which is what makes a raise stick
 * on Windows.
 * <p>
 * {@code Shell.forceActive} ends in {@code SetForegroundWindow}, and Windows
 * refuses that to a process that does not already own the foreground: the call
 * returns, the taskbar button flashes, and nothing comes forward. So a raise
 * driven from here reported success while the window in front stayed in front,
 * and a screen read then photographed that instead.
 * <p>
 * The way round it is the one Windows itself documents as the exception:
 * attaching this thread's input queue to the queue of the thread that owns the
 * foreground makes the two count as one input context for the length of the
 * attachment, so the raise is no longer coming from a stranger. Attach, raise,
 * detach. While attached, input-state calls on either thread synchronize on
 * the shared queue, so a foreground thread that is not pumping messages would
 * block this one; a hung window is therefore never attached to, and the cost
 * is the taskbar flash the plain call always produced.
 * <p>
 * {@code org.eclipse.swt.internal.win32.OS} exists on one window system only,
 * so it is reached by name here, the way {@link DisplayScaling} reaches
 * DPIUtil. Anywhere else this costs a reason string rather than a link error.
 */
final class NativeForeground {

	private static final String OS = "org.eclipse.swt.internal.win32.OS"; //$NON-NLS-1$

	private NativeForeground() {
	}

	/** Whether this window system has a native raise worth trying. */
	static boolean isSupported() {
		return FileLocations.isWindows();
	}

	/**
	 * Raises the shell past the foreground lock.
	 *
	 * @return {@code null} when the native raise ran, otherwise why it could not,
	 *         which is not the same as the window having come forward: Windows
	 *         can still refuse, and only the caller re-reading the active shell
	 *         knows that
	 */
	static String raise(Shell shell) {
		if (!isSupported()) {
			return "this is not Windows, so there is no foreground lock to work around"; //$NON-NLS-1$
		}
		try {
			Class<?> os = Class.forName(OS, true, Shell.class.getClassLoader());
			long window = handleOf(shell);
			if (window == 0) {
				return "the shell has no native window handle"; //$NON-NLS-1$
			}
			Method setForeground = os.getMethod("SetForegroundWindow", long.class); //$NON-NLS-1$
			long owner = (Long) os.getMethod("GetForegroundWindow").invoke(null); //$NON-NLS-1$
			if (owner == 0 || owner == window) {
				// nobody holds the foreground, or we already do, and attaching to our
				// own input queue is the one call that is documented to fail
				setForeground.invoke(null, Long.valueOf(window));
				return null;
			}
			if (Boolean.TRUE.equals(os.getMethod("IsHungAppWindow", long.class) //$NON-NLS-1$
					.invoke(null, Long.valueOf(owner)))) {
				setForeground.invoke(null, Long.valueOf(window));
				return "the window in front is not responding, and attaching to its input queue would hang this IDE with it"; //$NON-NLS-1$
			}
			int ownerThread = (Integer) os
					.getMethod("GetWindowThreadProcessId", long.class, int[].class) //$NON-NLS-1$
					.invoke(null, Long.valueOf(owner), null);
			int ourThread = (Integer) os.getMethod("GetCurrentThreadId").invoke(null); //$NON-NLS-1$
			Method attachThreadInput = os.getMethod("AttachThreadInput", int.class, int.class, //$NON-NLS-1$
					boolean.class);
			boolean attached = ownerThread != 0 && ownerThread != ourThread && Boolean.TRUE
					.equals(attachThreadInput.invoke(null, Integer.valueOf(ourThread),
							Integer.valueOf(ownerThread), Boolean.TRUE));
			try {
				setForeground.invoke(null, Long.valueOf(window));
			} finally {
				// a shared input queue left behind would make this IDE's keyboard state
				// follow another process for as long as it lives
				if (attached) {
					attachThreadInput.invoke(null, Integer.valueOf(ourThread),
							Integer.valueOf(ownerThread), Boolean.FALSE);
				}
			}
			return null;
		} catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
			return "SWT's win32 internals could not be reached: " + e; //$NON-NLS-1$
		}
	}

	private static long handleOf(Shell shell) throws ReflectiveOperationException {
		Field handle = shell.getClass().getField("handle"); //$NON-NLS-1$
		return handle.getLong(shell);
	}
}
