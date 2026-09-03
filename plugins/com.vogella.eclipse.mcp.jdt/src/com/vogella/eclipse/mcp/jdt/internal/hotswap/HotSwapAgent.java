package com.vogella.eclipse.mcp.jdt.internal.hotswap;

import java.lang.instrument.Instrumentation;

/**
 * The Java agent that eclipse_hot_code_replace loads into the running IDE.
 * <p>
 * Packed into a jar of its own and loaded through the attach API, so the copy
 * that holds the {@link Instrumentation} lives in the system class loader and
 * outlives this bundle. It must stay free of any dependency, and its one method
 * is the whole of its contract.
 */
public final class HotSwapAgent {

	private static volatile Instrumentation instrumentation;

	private HotSwapAgent() {
	}

	public static void premain(String args, Instrumentation inst) {
		instrumentation = inst;
	}

	public static void agentmain(String args, Instrumentation inst) {
		instrumentation = inst;
	}

	public static Instrumentation instrumentation() {
		return instrumentation;
	}
}
