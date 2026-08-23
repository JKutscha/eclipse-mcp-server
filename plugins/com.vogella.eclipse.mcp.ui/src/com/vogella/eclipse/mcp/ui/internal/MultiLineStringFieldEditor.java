package com.vogella.eclipse.mcp.ui.internal;

import org.eclipse.jface.preference.StringFieldEditor;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Text;

/**
 * A string preference edited over several lines, for values that are a list.
 */
final class MultiLineStringFieldEditor extends StringFieldEditor {

	private static final int LINES = 4;

	MultiLineStringFieldEditor(String name, String labelText, Composite parent) {
		super(name, labelText, UNLIMITED, parent);
	}

	@Override
	protected Text createTextWidget(Composite parent) {
		return new Text(parent, SWT.MULTI | SWT.BORDER | SWT.V_SCROLL | SWT.H_SCROLL);
	}

	@Override
	protected void doFillIntoGrid(Composite parent, int numColumns) {
		super.doFillIntoGrid(parent, numColumns);
		Text text = getTextControl();
		GridData data = (GridData) text.getLayoutData();
		data.heightHint = text.getLineHeight() * LINES;
		data.verticalAlignment = SWT.FILL;
		data.grabExcessVerticalSpace = false;
	}
}
