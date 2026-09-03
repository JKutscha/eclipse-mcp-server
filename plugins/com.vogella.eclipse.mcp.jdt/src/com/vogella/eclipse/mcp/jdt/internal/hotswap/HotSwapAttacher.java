package com.vogella.eclipse.mcp.jdt.internal.hotswap;

import java.lang.reflect.InvocationTargetException;

/**
 * Runs in a helper JVM and loads the agent jar into the IDE named by its pid.
 * <p>
 * A JVM refuses to attach to itself unless started with
 * {@code -Djdk.attach.allowAttachSelf}, which no IDE is, so the attach has to
 * come from another process. The attach API is reached reflectively so this
 * bundle compiles without {@code jdk.attach}.
 */
public final class HotSwapAttacher {

	private HotSwapAttacher() {
	}

	public static void main(String[] args) {
		if (args.length != 2) {
			System.err.println("usage: HotSwapAttacher <pid> <agent jar>"); //$NON-NLS-1$
			System.exit(2);
		}
		try {
			Class<?> vmClass = Class.forName("com.sun.tools.attach.VirtualMachine"); //$NON-NLS-1$
			Object vm = vmClass.getMethod("attach", String.class).invoke(null, args[0]); //$NON-NLS-1$
			try {
				vmClass.getMethod("loadAgent", String.class).invoke(vm, args[1]); //$NON-NLS-1$
			} finally {
				vmClass.getMethod("detach").invoke(vm); //$NON-NLS-1$
			}
			System.out.println("attached"); //$NON-NLS-1$
		} catch (InvocationTargetException e) {
			fail(e.getCause() == null ? e : e.getCause());
		} catch (Throwable e) {
			fail(e);
		}
	}

	private static void fail(Throwable e) {
		e.printStackTrace();
		System.exit(1);
	}
}
