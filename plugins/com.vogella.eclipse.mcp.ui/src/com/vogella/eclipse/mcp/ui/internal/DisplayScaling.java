package com.vogella.eclipse.mcp.ui.internal;

import java.lang.reflect.Method;

/**
 * Every reference to SWT's internal scaling and window system classes.
 * <p>
 * {@code org.eclipse.swt.internal.DPIUtil} carries the only answer to what
 * scaling actually took effect, and {@code org.eclipse.swt.internal.gtk.GTK}
 * the GTK version, but both are internal: DPIUtil has changed shape across
 * releases, and the GTK class exists only on one window system, so a direct
 * call would turn this bundle into a GTK-only one. They are reached by name
 * here, the way {@link CssStyling} reaches the CSS engine, so an IDE without
 * them costs a null in one field rather than a failing tool.
 */
final class DisplayScaling {

	private static final String DPI_UTIL = "org.eclipse.swt.internal.DPIUtil"; //$NON-NLS-1$

	private static final String GTK = "org.eclipse.swt.internal.gtk.GTK"; //$NON-NLS-1$

	private DisplayScaling() {
	}

	/**
	 * What the whole process scales at. This is the number that changes when
	 * {@code swt.autoScale} or {@code GDK_SCALE} takes effect, and the one a
	 * capture's own zoom cannot show.
	 */
	static Integer deviceZoom() {
		return intOf(DPI_UTIL, "getDeviceZoom"); //$NON-NLS-1$
	}

	/** What the window system reported before any autoScale override was applied. */
	static Integer nativeDeviceZoom() {
		return intOf(DPI_UTIL, "getNativeDeviceZoom"); //$NON-NLS-1$
	}

	/** The autoScale value in force, whether it came from a property or a default. */
	static String effectiveAutoScaleValue() {
		Object value = invoke(DPI_UTIL, "getEffectiveAutoScaleValue"); //$NON-NLS-1$
		return value == null ? null : String.valueOf(value);
	}

	/**
	 * Whether {@code swt.autoScale} was set at all.
	 * <p>
	 * This is what separates "the flag was ignored" from "the flag was never
	 * there", which the effective value alone cannot say: a default and an
	 * explicit setting of the same number read identically.
	 */
	static Boolean customAutoScale() {
		Object value = invoke(DPI_UTIL, "isCustomAutoScale"); //$NON-NLS-1$
		return value instanceof Boolean flag ? flag : null;
	}

	/** The GTK version as major.minor.micro, or {@code null} off GTK. */
	static String gtkVersion() {
		Integer major = intOf(GTK, "gtk_get_major_version"); //$NON-NLS-1$
		Integer minor = intOf(GTK, "gtk_get_minor_version"); //$NON-NLS-1$
		Integer micro = intOf(GTK, "gtk_get_micro_version"); //$NON-NLS-1$
		return major == null || minor == null || micro == null ? null
				: "%d.%d.%d".formatted(major, minor, micro); //$NON-NLS-1$
	}

	private static Integer intOf(String className, String method) {
		Object value = invoke(className, method);
		return value instanceof Integer number ? number : null;
	}

	/**
	 * Calls a no-argument static method by name.
	 *
	 * @return its result, or {@code null} when the class, the method or the call
	 *         is not available here
	 */
	private static Object invoke(String className, String methodName) {
		try {
			Class<?> type = Class.forName(className);
			Method method = type.getMethod(methodName);
			return method.invoke(null);
		} catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
			// a missing internal class or a renamed method is the expected case off
			// GTK or on another SWT, and the field is simply absent from the answer
			return null;
		}
	}
}
