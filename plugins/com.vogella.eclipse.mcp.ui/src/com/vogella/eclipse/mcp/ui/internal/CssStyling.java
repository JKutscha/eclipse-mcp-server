package com.vogella.eclipse.mcp.ui.internal;

import java.io.Reader;
import java.io.StringReader;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.e4.core.contexts.IEclipseContext;
import org.eclipse.e4.ui.css.core.engine.CSSEngine;
import org.eclipse.e4.ui.css.core.engine.CSSErrorHandler;
import org.eclipse.e4.ui.css.swt.dom.WidgetElement;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Widget;
import org.eclipse.ui.PlatformUI;
import org.w3c.dom.Element;
import org.w3c.dom.css.CSSStyleDeclaration;
import org.w3c.dom.css.CSSStyleSheet;

import com.vogella.eclipse.mcp.core.json.JsonArray;
import com.vogella.eclipse.mcp.core.json.JsonObject;

/**
 * Everything that talks to the e4 CSS engine.
 * <p>
 * The engine bundles are optional, so every reference to them lives in this one
 * class and callers catch {@link LinkageError}, the way {@code GitContent}
 * isolates jgit. The theme engine itself is reached reflectively: the two
 * methods a snippet needs, {@code resetCurrentTheme} and {@code getCSSEngines},
 * are on the internal implementation and not on {@code IThemeEngine}, which is
 * the same gap PDE's CSS scratch pad works around with a cast.
 */
final class CssStyling {

	/** The ad-hoc stylesheet applied on top of the theme, {@code null} when none is. */
	private static volatile String snippet;

	private CssStyling() {
	}

	/** The CSS view of a widget: what the engine calls it, and how a rule can name it. */
	static JsonObject describe(Widget widget) {
		JsonObject result = new JsonObject();
		try {
			result.put("cssId", WidgetElement.getID(widget)) //$NON-NLS-1$
					.put("cssClass", WidgetElement.getCSSClass(widget)); //$NON-NLS-1$
			CSSEngine engine = WidgetElement.getEngine(widget);
			if (engine == null) {
				return result.put("cssElement", null) //$NON-NLS-1$
						.put("cssNote", "No CSS engine is attached, so this widget is not styled by the theme engine."); //$NON-NLS-1$ //$NON-NLS-2$
			}
			Element element = engine.getElement(widget);
			result.put("cssElement", element == null ? null : element.getLocalName()); //$NON-NLS-1$
		} catch (LinkageError | RuntimeException e) {
			result.put("cssNote", //$NON-NLS-1$
					"The e4 CSS bundles are not available in this IDE, so only the SWT side can be reported."); //$NON-NLS-1$
		}
		return result;
	}

	/**
	 * What the engine resolved for a widget, and which of it a rule decided.
	 * <p>
	 * {@code computed} is the widget's live value, which a property handler reads
	 * back from SWT and which therefore always answers something; {@code declared}
	 * is what the matching rules set, and is the only way to tell a themed colour
	 * from the window system's default.
	 */
	static void styles(Widget widget, List<String> properties, String pseudo, JsonObject into) {
		CSSEngine engine;
		try {
			engine = WidgetElement.getEngine(widget);
		} catch (LinkageError | RuntimeException e) {
			into.put("computedNote", //$NON-NLS-1$
					"The e4 CSS bundles are not available in this IDE, so no computed values could be read."); //$NON-NLS-1$
			return;
		}
		if (engine == null) {
			into.put("computedNote", //$NON-NLS-1$
					"No CSS engine is attached to this widget, so nothing was computed for it."); //$NON-NLS-1$
			return;
		}
		Map<String, String> cascade = cascade(engine, widget, pseudo);
		JsonObject computed = new JsonObject();
		JsonObject declared = new JsonObject();
		JsonObject origin = new JsonObject();
		for (String property : properties) {
			String value = engine.retrieveCSSProperty(widget, property, pseudo);
			String rule = cascade.get(property);
			computed.put(property, value);
			declared.put(property, rule);
			origin.put(property, rule != null ? "css" : value != null ? "widget" : "unset"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
		}
		into.put("computed", computed) //$NON-NLS-1$
				.put("declared", declared) //$NON-NLS-1$
				.put("origin", origin) //$NON-NLS-1$
				.put("cssDeclaration", cascade.isEmpty() ? null : text(cascade)); //$NON-NLS-1$
	}

	/** The merged declaration the matching rules produce for this element. */
	private static Map<String, String> cascade(CSSEngine engine, Widget widget, String pseudo) {
		try {
			Element element = engine.getElement(widget);
			// getViewCSS is the cascade the engine keeps; a newer platform deprecates it
			// in favour of computeStyle, and answers through it either way
			CSSStyleDeclaration declaration = element == null ? null
					: engine.getViewCSS().getComputedStyle(element, pseudo);
			if (declaration == null) {
				return Map.of();
			}
			Map<String, String> values = new LinkedHashMap<>();
			for (int i = 0; i < declaration.getLength(); i++) {
				String name = declaration.item(i);
				values.put(name, declaration.getPropertyValue(name));
			}
			return values;
		} catch (LinkageError | RuntimeException e) {
			return Map.of();
		}
	}

	private static String text(Map<String, String> cascade) {
		StringBuilder builder = new StringBuilder();
		cascade.forEach((name, value) -> builder.append(name).append(": ").append(value).append("; ")); //$NON-NLS-1$ //$NON-NLS-2$
		return builder.toString().strip();
	}

	/**
	 * Puts an ad-hoc stylesheet on top of the current theme, or takes it away.
	 * <p>
	 * The theme is re-applied first, so a snippet replaces the one before it rather
	 * than piling on top of it, and so dropping one needs nothing but that step.
	 */
	static JsonObject apply(String css, boolean drop) {
		long start = System.nanoTime();
		JsonObject result = new JsonObject();
		String previous = snippet;
		Object themeEngine = themeEngine();
		JsonArray errors = new JsonArray();
		boolean themeReset = false;
		if (themeEngine != null) {
			try {
				themeEngine.getClass().getMethod("resetCurrentTheme").invoke(themeEngine); //$NON-NLS-1$
				themeReset = true;
				snippet = null;
			} catch (ReflectiveOperationException | RuntimeException e) {
				errors.add("Resetting the theme failed: " + e); //$NON-NLS-1$
			}
		}
		if (drop && !themeReset) {
			return result.put("applied", Boolean.FALSE) //$NON-NLS-1$
					.put("reason", //$NON-NLS-1$
							"The theme engine could not be reached, so a snippet cannot be taken back. Restarting the IDE drops it.")
					.put("errors", errors); //$NON-NLS-1$
		}
		List<CSSEngine> engines = engines(themeEngine);
		Parsed parsed = new Parsed(false, -1);
		if (!drop && engines.isEmpty()) {
			return result.put("applied", Boolean.FALSE) //$NON-NLS-1$
					.put("reason", //$NON-NLS-1$
							"No CSS engine is attached to this display, so this IDE is not styled by the theme engine at all.")
					.put("errors", errors); //$NON-NLS-1$
		}
		if (!drop) {
			for (CSSEngine engine : engines) {
				Parsed one = parse(engine, css, errors);
				parsed = new Parsed(parsed.ok() || one.ok(), Math.max(parsed.rules(), one.rules()));
				try {
					engine.reapply();
				} catch (RuntimeException e) {
					errors.add("Re-applying the styles failed: " + e); //$NON-NLS-1$
				}
			}
			// a snippet the parser rejected is not in place, and saying it is would make
			// the next reset look like it had something to take back
			snippet = parsed.ok() ? css : null;
		}
		return result.put("applied", Boolean.valueOf(!drop && parsed.ok())) //$NON-NLS-1$
				.put("previousSnippet", previous) //$NON-NLS-1$
				.put("theme", activeThemeId(themeEngine)) //$NON-NLS-1$
				.put("themeReapplied", Boolean.valueOf(themeReset)) //$NON-NLS-1$
				.put("engines", Integer.valueOf(engines.size())) //$NON-NLS-1$
				.put("rules", parsed.rules() < 0 ? null : Integer.valueOf(parsed.rules())) //$NON-NLS-1$
				.put("errors", errors) //$NON-NLS-1$
				.put("elapsedMillis", Long.valueOf((System.nanoTime() - start) / 1_000_000L)) //$NON-NLS-1$
				.put("note", themeReset //$NON-NLS-1$
						? "The snippet lives in memory only. It is gone on the next theme change, on eclipse_restart, and when this plug-in stops."
						: "The theme engine could not be reached, so the snippet was added without re-applying the theme first and can only be taken back by restarting the IDE.");
	}

	/** Whether a snippet parsed, and how many rules it produced where that can be counted. */
	private record Parsed(boolean ok, int rules) {
	}

	/** Parses a snippet into an engine, which is what puts it into the cascade. */
	private static Parsed parse(CSSEngine engine, String css, JsonArray errors) {
		CSSErrorHandler previous = engine.getErrorHandler();
		List<Exception> reported = new ArrayList<>();
		try {
			engine.setErrorHandler(reported::add);
			// reflection because parseStyleSheet changed its return type: a call compiled
			// against the target platform dies with NoSuchMethodError on an IDE that has
			// the newer engine, and this tool exists to be used on somebody else's IDE.
			// Appended last, so the snippet wins the cascade ties it is written to win.
			Object sheet = CSSEngine.class.getMethod("parseStyleSheet", Reader.class).invoke(engine, //$NON-NLS-1$
					new StringReader(css));
			return new Parsed(true, rules(sheet));
		} catch (InvocationTargetException e) {
			// e4's parser throws unchecked as well, and the message carries line and column
			errors.add("The snippet was not applied: " + e.getCause()); //$NON-NLS-1$
			return new Parsed(false, -1);
		} catch (ReflectiveOperationException | RuntimeException e) {
			errors.add("The snippet was not applied: " + e); //$NON-NLS-1$
			return new Parsed(false, -1);
		} finally {
			reported.forEach(e -> errors.add(String.valueOf(e)));
			engine.setErrorHandler(previous);
		}
	}

	/** How many rules a parsed sheet holds, under either spelling of a stylesheet. */
	private static int rules(Object sheet) {
		if (sheet instanceof CSSStyleSheet parsed) {
			return parsed.getCssRules().getLength();
		}
		try {
			return sheet.getClass().getMethod("getRules").invoke(sheet) instanceof Collection<?> parsed //$NON-NLS-1$
					? parsed.size()
					: -1;
		} catch (ReflectiveOperationException | RuntimeException e) {
			return -1;
		}
	}

	/** The engines the theme engine drives, or the one this display is styled by. */
	private static List<CSSEngine> engines(Object themeEngine) {
		List<CSSEngine> engines = new ArrayList<>();
		if (themeEngine != null) {
			try {
				if (themeEngine.getClass().getMethod("getCSSEngines").invoke(themeEngine) instanceof Collection<?> known) { //$NON-NLS-1$
					known.forEach(each -> {
						if (each instanceof CSSEngine engine) {
							engines.add(engine);
						}
					});
				}
			} catch (ReflectiveOperationException | RuntimeException e) {
				// falls through to the display's own engine
			}
		}
		if (engines.isEmpty()) {
			Display display = PlatformUI.getWorkbench().getDisplay();
			CSSEngine engine = display == null ? null : WidgetElement.getEngine(display);
			if (engine != null) {
				engines.add(engine);
			}
		}
		return engines;
	}

	/**
	 * The theme engine of the running workbench, {@code null} when there is none.
	 * <p>
	 * Looked up by name rather than by type: the interface is exported to a friends
	 * list this bundle is not on, and nothing here needs to compile against it.
	 */
	private static Object themeEngine() {
		try {
			IEclipseContext context = PlatformUI.getWorkbench().getService(IEclipseContext.class);
			return context == null ? null : context.get("org.eclipse.e4.ui.css.swt.theme.IThemeEngine"); //$NON-NLS-1$
		} catch (LinkageError | RuntimeException e) {
			return null;
		}
	}

	private static String activeThemeId(Object themeEngine) {
		if (themeEngine == null) {
			return null;
		}
		try {
			Object theme = themeEngine.getClass().getMethod("getActiveTheme").invoke(themeEngine); //$NON-NLS-1$
			return theme == null ? null : String.valueOf(theme.getClass().getMethod("getId").invoke(theme)); //$NON-NLS-1$
		} catch (ReflectiveOperationException | RuntimeException e) {
			return null;
		}
	}

	/**
	 * Takes an applied snippet back when the plug-in stops.
	 * <p>
	 * A snippet can leave the IDE unreadable, so the server going away must not be
	 * the moment that becomes permanent for the rest of the session.
	 */
	static void dropIfApplied() {
		if (snippet == null || !PlatformUI.isWorkbenchRunning()) {
			return;
		}
		Display display = PlatformUI.getWorkbench().getDisplay();
		if (display == null || display.isDisposed()) {
			return;
		}
		display.syncExec(() -> apply(null, true));
	}
}
