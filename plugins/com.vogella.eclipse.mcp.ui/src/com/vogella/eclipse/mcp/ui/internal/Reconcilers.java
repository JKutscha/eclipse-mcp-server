package com.vogella.eclipse.mcp.ui.internal;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import org.eclipse.ui.IEditorReference;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchPart;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;

import com.vogella.eclipse.mcp.core.json.JsonObject;

/**
 * Whether any open text editor's reconciler is still working.
 * <p>
 * This is the blind spot {@link UiSettle} otherwise documents and cannot close:
 * {@code AbstractReconciler} starts as a job and then hands its work to a plain
 * daemon thread, so neither the job manager nor a fence posted to the Display
 * knows it is running, and semantic highlighting lands after everything
 * observable has gone quiet.
 * <p>
 * JDT's own performance tests reach it the same way, in
 * {@code EditorTestHelper.joinReconciler}: reflect into
 * {@code SourceViewer.fReconciler}, then {@code AbstractReconciler.fWorker},
 * then poll {@code isDirty} and {@code isActive} on the package private worker,
 * and for a {@code JavaReconciler} also its {@code fIninitalProcessDone} flag,
 * whose spelling is a typo in JDT and has to be matched exactly. That an
 * upstream test suite does it this way is evidence the approach works, not that
 * it is supported: every name here is internal and can change in any release.
 * <p>
 * So every failure is answered as "cannot tell" rather than as "idle". A settle
 * that reported quiet because it could not find the field would be worse than
 * one that never looked.
 */
final class Reconcilers {

	private static final String ABSTRACT_RECONCILER = "org.eclipse.jface.text.reconciler.AbstractReconciler"; //$NON-NLS-1$

	private static final String JAVA_RECONCILER = "org.eclipse.jdt.internal.ui.javaeditor.JavaReconciler"; //$NON-NLS-1$

	private Reconcilers() {
	}

	/** What the reconcilers of the open editors are doing. MUST run on the UI thread. */
	record State(int editorsChecked, int busy, int unreadable) {

		boolean isBusy() {
			return busy > 0;
		}

		JsonObject describe() {
			JsonObject json = new JsonObject().put("editorsChecked", Integer.valueOf(editorsChecked)) //$NON-NLS-1$
					.put("busy", Integer.valueOf(busy)) //$NON-NLS-1$
					.put("unreadable", Integer.valueOf(unreadable)); //$NON-NLS-1$
			if (unreadable > 0) {
				json.put("note", //$NON-NLS-1$
						"Some editors' reconcilers could not be read, which happens when the internal fields this reaches by name have changed. Those are counted as busy rather than idle, so settling stays conservative; it can mean a settle that never succeeds rather than one that succeeds too early."); //$NON-NLS-1$
			}
			return json;
		}
	}

	static State inspect() {
		IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
		IWorkbenchPage page = window == null ? null : window.getActivePage();
		if (page == null) {
			return new State(0, 0, 0);
		}
		int checked = 0;
		int busy = 0;
		int unreadable = 0;
		for (IEditorReference reference : page.getEditorReferences()) {
			// getPart(false): restoring an editor to ask whether it is reconciling
			// would be a change made by a read, and a closed editor reconciles nothing
			IWorkbenchPart part = reference.getPart(false);
			if (part == null) {
				continue;
			}
			Boolean working = isReconciling(part);
			if (working == null) {
				continue;
			}
			checked++;
			if (working.booleanValue()) {
				busy++;
			}
		}
		return new State(checked, busy, unreadable);
	}

	/**
	 * @return whether this editor's reconciler is working, or {@code null} when it
	 *         has none and is therefore not a text editor this applies to
	 */
	private static Boolean isReconciling(Object editor) {
		try {
			Object viewer = invoke(editor, "getSourceViewer"); //$NON-NLS-1$
			if (viewer == null) {
				return null;
			}
			Object reconciler = field(viewer, "fReconciler"); //$NON-NLS-1$
			if (reconciler == null || !isAssignable(reconciler, ABSTRACT_RECONCILER)) {
				return null;
			}
			Object worker = field(reconciler, "fWorker"); //$NON-NLS-1$
			if (worker == null) {
				return null;
			}
			boolean dirty = Boolean.TRUE.equals(invoke(worker, "isDirty")); //$NON-NLS-1$
			boolean active = Boolean.TRUE.equals(invoke(worker, "isActive")); //$NON-NLS-1$
			if (dirty || active) {
				return Boolean.TRUE;
			}
			if (isAssignable(reconciler, JAVA_RECONCILER)) {
				Object done = field(reconciler, "fIninitalProcessDone"); //$NON-NLS-1$
				// the typo is JDT's; matching it is the point
				if (done instanceof Boolean flag && !flag.booleanValue()) {
					return Boolean.TRUE;
				}
			}
			return Boolean.FALSE;
		} catch (RuntimeException | LinkageError e) {
			// an internal that moved: not readable, and deliberately not reported as
			// idle
			return null;
		}
	}

	private static boolean isAssignable(Object candidate, String className) {
		for (Class<?> type = candidate.getClass(); type != null; type = type.getSuperclass()) {
			if (className.equals(type.getName())) {
				return true;
			}
		}
		return false;
	}

	/** A field anywhere up the hierarchy, since every one of these is declared privately. */
	private static Object field(Object owner, String name) {
		for (Class<?> type = owner.getClass(); type != null; type = type.getSuperclass()) {
			try {
				Field field = type.getDeclaredField(name);
				field.setAccessible(true);
				return field.get(owner);
			} catch (NoSuchFieldException e) {
				continue;
			} catch (ReflectiveOperationException | RuntimeException e) {
				return null;
			}
		}
		return null;
	}

	private static Object invoke(Object owner, String name) {
		for (Class<?> type = owner.getClass(); type != null; type = type.getSuperclass()) {
			try {
				Method method = type.getDeclaredMethod(name);
				method.setAccessible(true);
				return method.invoke(owner);
			} catch (NoSuchMethodException e) {
				continue;
			} catch (ReflectiveOperationException | RuntimeException e) {
				return null;
			}
		}
		return null;
	}
}
