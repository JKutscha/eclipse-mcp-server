package com.vogella.eclipse.mcp.core.json;

/**
 * JSON that is already serialised and is written through untouched.
 * <p>
 * A tool that reports what another tool answered would otherwise escape a whole
 * document into a string, which a client then has to parse a second time.
 */
public record JsonRaw(String json) {

	@Override
	public String toString() {
		return json;
	}
}
