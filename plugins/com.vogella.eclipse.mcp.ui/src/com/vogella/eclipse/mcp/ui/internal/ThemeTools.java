package com.vogella.eclipse.mcp.ui.internal;

import java.util.Map;

import org.eclipse.core.runtime.IProgressMonitor;

import com.vogella.eclipse.mcp.core.IMcpTool;
import com.vogella.eclipse.mcp.core.McpToolResult;
import com.vogella.eclipse.mcp.core.ToolArguments;
import com.vogella.eclipse.mcp.core.json.JsonObject;

/**
 * Lists the registered themes and switches the active one, through the same
 * reflective engine access {@code CssStyling} uses.
 */
public final class ThemeTools {

	/** Two full re-styles of every shell, so it needs the room ApplyCssTool needs. */
	private static final long UI_TIMEOUT_SECONDS = 25;

	private ThemeTools() {
	}

	/** Lists every theme the engine has registered. */
	public static final class ListThemes implements IMcpTool {

		@Override
		public String getName() {
			return "eclipse_list_themes"; //$NON-NLS-1$
		}

		@Override
		public String getDescription() {
			return "Lists every theme the running IDE's CSS engine has registered: its id and label, and which one is active. READ ONLY, and the answer to 'what is this theme's id actually called', which otherwise means grepping plugin.xml files in a workspace for org.eclipse.e4.ui.css.swt.theme contributions; ids are easy to misremember, com.vogella.eclipse.themes.githubdark is not com.vogella.eclipse.themes.github.dark. Feed an id to eclipse_set_theme."; //$NON-NLS-1$
		}

		@Override
		public String getInputSchema() {
			return """
					{
					  "type": "object",
					  "properties": {
					    "maxResults": {"type":"integer","default":200,"minimum":1,"maximum":1000}
					  },
					  "additionalProperties": false
					}"""; //$NON-NLS-1$
		}

		@Override
		public McpToolResult call(Map<String, Object> arguments, IProgressMonitor monitor) {
			ToolArguments args = ToolArguments.of(arguments);
			int maxResults = args.getInt("maxResults", 200, 1, 1000); //$NON-NLS-1$
			return UiThread.call(UI_TIMEOUT_SECONDS, () -> CssStyling.listThemes(maxResults));
		}
	}

	/** Switches the active theme. */
	public static final class SetTheme implements IMcpTool {

		@Override
		public String getName() {
			return "eclipse_set_theme"; //$NON-NLS-1$
		}

		@Override
		public String getDescription() {
			return "Switches the IDE to another registered theme, named by id or by label, so that everything the theme draws restyles at once. CHANGES WHAT THE USER SEES across the whole IDE, and with persist (the default) the choice survives restarts. An ambiguous name is refused with the candidates rather than guessed. The answer reports previousThemeId so the old theme can be put back exactly. This is also the reliable way to make an IEclipsePreferences block from eclipse_apply_css take effect: preference blocks are styled on the theme activation path only. A theme change drops any snippet eclipse_apply_css put on top."; //$NON-NLS-1$
		}

		@Override
		public String getInputSchema() {
			return """
					{
					  "type": "object",
					  "required": ["theme"],
					  "properties": {
					    "theme":   {"type":"string","description":"Theme id or label, e.g. org.eclipse.e4.ui.css.theme.e4_dark or Dark. eclipse_list_themes lists both."},
					    "persist": {"type":"boolean","default":true,"description":"Remember the choice across restarts. False switches this session only."}
					  },
					  "additionalProperties": false
					}"""; //$NON-NLS-1$
		}

		@Override
		public McpToolResult call(Map<String, Object> arguments, IProgressMonitor monitor) {
			ToolArguments args = ToolArguments.of(arguments);
			String wanted = args.getString("theme"); //$NON-NLS-1$
			if (wanted == null) {
				return McpToolResult.error("The argument 'theme' is required. Use eclipse_list_themes."); //$NON-NLS-1$
			}
			boolean persist = args.getBoolean("persist", true); //$NON-NLS-1$
			UiThread.TimedOutcome outcome = UiThread.timed(UI_TIMEOUT_SECONDS, () -> CssStyling.switchTheme(wanted,
					persist));
			if (outcome.error() != null) {
				return McpToolResult.error(outcome.error());
			}
			if (outcome.timedOut()) {
				return McpToolResult.of(new JsonObject().put("switched", Boolean.FALSE) //$NON-NLS-1$
						.put("theme", wanted) //$NON-NLS-1$
						.put("timedOut", Boolean.TRUE) //$NON-NLS-1$
						.put("note", "The switch did not finish within %d seconds, because a theme change restyles every shell. It may have completed anyway; ask eclipse_list_themes which theme is active before retrying." //$NON-NLS-1$
								.formatted(Long.valueOf(UI_TIMEOUT_SECONDS)))
						.toString());
			}
			return McpToolResult.of(outcome.value().toString());
		}
	}
}
