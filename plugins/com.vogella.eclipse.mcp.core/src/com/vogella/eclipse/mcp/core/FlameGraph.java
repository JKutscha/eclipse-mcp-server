package com.vogella.eclipse.mcp.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds a self contained dark themed page showing a stack trace profile as a flame
 * graph, with the numbers that go with it.
 * <p>
 * Self contained on purpose: the page is served by an IDE that may have no network, so
 * everything it needs is in the one document. Nothing is loaded from a CDN and no file
 * sits beside it.
 */
public final class FlameGraph {

	private FlameGraph() {
	}

	/** One frame of the merged call tree, with the weight of everything beneath it. */
	public static final class Node {

		private final String frame;

		private final Map<String, Node> children = new LinkedHashMap<>();

		private long value;

		Node(String frame) {
			this.frame = frame;
		}

		private Node child(String name) {
			return children.computeIfAbsent(name, Node::new);
		}
	}

	/** Collects stacks into the merged tree a flame graph draws. */
	public static final class Builder {

		private final Node root = new Node("all"); //$NON-NLS-1$

		private long total;

		private int stacks;

		/**
		 * Adds one stack and its weight.
		 *
		 * @param framesRootFirst outermost frame first, the order a flame graph stacks
		 *                        them upwards
		 */
		public Builder add(List<String> framesRootFirst, long weight) {
			if (framesRootFirst == null || framesRootFirst.isEmpty() || weight <= 0) {
				return this;
			}
			stacks++;
			total += weight;
			root.value += weight;
			Node current = root;
			for (String frame : framesRootFirst) {
				current = current.child(frame);
				current.value += weight;
			}
			return this;
		}

		public boolean isEmpty() {
			return total == 0;
		}

		public long total() {
			return total;
		}

		public int stacks() {
			return stacks;
		}

		/** One frame and a weight, for the tables beside the graph. */
		public record Ranked(String frame, long weight) {
		}

		/**
		 * Frames by the weight sitting in them rather than below them, which is where
		 * the cost actually is: a frame's own weight less everything its children took.
		 */
		public List<Ranked> topSelf(int limit) {
			Map<String, Long> self = new LinkedHashMap<>();
			collectSelf(root, self);
			return rank(self, limit);
		}

		/** Frames by the weight of everything beneath them, which is how wide they draw. */
		public List<Ranked> topTotal(int limit) {
			Map<String, Long> total = new LinkedHashMap<>();
			collectTotal(root, total);
			return rank(total, limit);
		}

		private static void collectSelf(Node node, Map<String, Long> into) {
			long below = 0;
			for (Node child : node.children.values()) {
				below += child.value;
				collectSelf(child, into);
			}
			long own = node.value - below;
			if (own > 0) {
				into.merge(node.frame, Long.valueOf(own), (a, b) -> Long.valueOf(a.longValue() + b.longValue()));
			}
		}

		private static void collectTotal(Node node, Map<String, Long> into) {
			for (Node child : node.children.values()) {
				// merged across every place the frame appears, so recursion does not
				// split one method into a dozen rows
				into.merge(child.frame, Long.valueOf(child.value),
						(a, b) -> Long.valueOf(a.longValue() + b.longValue()));
				collectTotal(child, into);
			}
		}

		private static List<Ranked> rank(Map<String, Long> weights, int limit) {
			List<Ranked> ranked = new ArrayList<>();
			weights.forEach((frame, weight) -> ranked.add(new Ranked(frame, weight.longValue())));
			ranked.sort((a, b) -> Long.compare(b.weight(), a.weight()));
			return ranked.subList(0, Math.min(limit, ranked.size()));
		}

		/** The tree as the compact JSON the page's script walks. */
		String toJson() {
			StringBuilder out = new StringBuilder();
			write(root, out);
			return out.toString();
		}

		private static void write(Node node, StringBuilder out) {
			out.append("{\"n\":"); //$NON-NLS-1$
			quote(node.frame, out);
			out.append(",\"v\":").append(node.value); //$NON-NLS-1$
			if (!node.children.isEmpty()) {
				out.append(",\"c\":["); //$NON-NLS-1$
				boolean first = true;
				// biggest first, so the eye lands on the expensive branch
				List<Node> ordered = new ArrayList<>(node.children.values());
				ordered.sort((a, b) -> Long.compare(b.value, a.value));
				for (Node child : ordered) {
					if (!first) {
						out.append(',');
					}
					first = false;
					write(child, out);
				}
				out.append(']');
			}
			out.append('}');
		}
	}

	/** What the page says about itself, beside the graph. */
	public record Spec(String title, String subtitle, String unit, Builder flame, List<Table> tables, String note) {
	}

	/** One summary table: a caption and its rows. */
	public record Table(String caption, List<String> columns, List<List<String>> rows) {
	}

	public static Builder builder() {
		return new Builder();
	}

	/** Splits a rendered stack such as {@code a &lt;- b &lt;- c}, innermost frame first. */
	public static List<String> parseArrowStack(String stack) {
		List<String> frames = new ArrayList<>();
		for (String part : stack.split("<-")) { //$NON-NLS-1$
			String frame = part.strip();
			if (!frame.isEmpty()) {
				frames.add(frame);
			}
		}
		// the rendered form is innermost first, a flame graph stacks outermost first
		Collections.reverse(frames);
		return frames;
	}

	/** Bytes as something a person reads, since an allocation profile is mostly megabytes. */
	public static String bytes(long value) {
		if (value < 1024) {
			return value + " B"; //$NON-NLS-1$
		}
		if (value < 1024 * 1024) {
			return "%.1f KB".formatted(Double.valueOf(value / 1024.0)); //$NON-NLS-1$
		}
		if (value < 1024L * 1024 * 1024) {
			return "%.1f MB".formatted(Double.valueOf(value / (1024.0 * 1024))); //$NON-NLS-1$
		}
		return "%.2f GB".formatted(Double.valueOf(value / (1024.0 * 1024 * 1024))); //$NON-NLS-1$
	}

	public static String page(Spec spec) {
		StringBuilder out = new StringBuilder(1 << 16);
		out.append("<!doctype html>\n<html lang=\"en\" data-unit=\""); //$NON-NLS-1$
		escape(spec.unit(), out);
		out.append("\">\n<head>\n<meta charset=\"utf-8\">\n"); //$NON-NLS-1$
		out.append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">\n<title>"); //$NON-NLS-1$
		escape(spec.title(), out);
		out.append("</title>\n<style>\n").append(STYLE).append("\n</style>\n</head>\n<body>\n"); //$NON-NLS-1$ //$NON-NLS-2$

		out.append("<header><h1>"); //$NON-NLS-1$
		escape(spec.title(), out);
		out.append("</h1><p class=\"sub\">"); //$NON-NLS-1$
		escape(spec.subtitle(), out);
		out.append("</p></header>\n"); //$NON-NLS-1$

		if (spec.flame() == null || spec.flame().isEmpty()) {
			out.append("<div class=\"empty\">No stacks were recorded, so there is nothing to draw. " //$NON-NLS-1$
					+ "A profile with no samples usually means the recording was too short, or that every sample was filtered out.</div>\n"); //$NON-NLS-1$
		} else {
			out.append("<section class=\"flamewrap\">\n"); //$NON-NLS-1$
			out.append("<div class=\"toolbar\">"); //$NON-NLS-1$
			out.append("<input id=\"find\" type=\"search\" placeholder=\"Highlight frames matching…\" spellcheck=\"false\">"); //$NON-NLS-1$
			out.append("<button id=\"reset\" type=\"button\">Reset zoom</button>"); //$NON-NLS-1$
			out.append("<span id=\"status\"></span>"); //$NON-NLS-1$
			out.append("</div>\n<div id=\"flame\" role=\"img\" aria-label=\"Flame graph of the recorded stacks\"></div>\n"); //$NON-NLS-1$
			out.append("<div id=\"tip\" hidden></div>\n</section>\n"); //$NON-NLS-1$
		}

		if (spec.tables() != null && !spec.tables().isEmpty()) {
			out.append("<section class=\"tables\">\n"); //$NON-NLS-1$
			for (Table table : spec.tables()) {
				if (table.rows().isEmpty()) {
					continue;
				}
				out.append("<div class=\"card\"><h2>"); //$NON-NLS-1$
				escape(table.caption(), out);
				out.append("</h2><div class=\"scroll\"><table><thead><tr>"); //$NON-NLS-1$
				for (String column : table.columns()) {
					out.append("<th>"); //$NON-NLS-1$
					escape(column, out);
					out.append("</th>"); //$NON-NLS-1$
				}
				out.append("</tr></thead><tbody>"); //$NON-NLS-1$
				for (List<String> row : table.rows()) {
					out.append("<tr>"); //$NON-NLS-1$
					for (int i = 0; i < row.size(); i++) {
						out.append(i == 0 ? "<td>" : "<td class=\"num\">"); //$NON-NLS-1$ //$NON-NLS-2$
						escape(row.get(i), out);
						out.append("</td>"); //$NON-NLS-1$
					}
					out.append("</tr>"); //$NON-NLS-1$
				}
				out.append("</tbody></table></div></div>\n"); //$NON-NLS-1$
			}
			out.append("</section>\n"); //$NON-NLS-1$
		}

		if (spec.note() != null && !spec.note().isBlank()) {
			out.append("<footer>"); //$NON-NLS-1$
			escape(spec.note(), out);
			out.append("</footer>\n"); //$NON-NLS-1$
		}

		if (spec.flame() != null && !spec.flame().isEmpty()) {
			out.append("<script id=\"profile\" type=\"application/json\">"); //$NON-NLS-1$
			// inside a script element, so only the closing tag has to be broken up
			out.append(spec.flame().toJson().replace("</", "<\\/")); //$NON-NLS-1$ //$NON-NLS-2$
			out.append("</script>\n<script>\n").append(SCRIPT).append("\n</script>\n"); //$NON-NLS-1$ //$NON-NLS-2$
		}
		out.append("</body>\n</html>\n"); //$NON-NLS-1$
		return out.toString();
	}

	private static void quote(String value, StringBuilder out) {
		out.append('"');
		for (int i = 0; i < value.length(); i++) {
			char c = value.charAt(i);
			switch (c) {
			case '"' -> out.append("\\\""); //$NON-NLS-1$
			case '\\' -> out.append("\\\\"); //$NON-NLS-1$
			case '\n' -> out.append("\\n"); //$NON-NLS-1$
			case '\r' -> out.append("\\r"); //$NON-NLS-1$
			case '\t' -> out.append("\\t"); //$NON-NLS-1$
			default -> {
				if (c < 0x20) {
					out.append("\\u%04x".formatted(Integer.valueOf(c))); //$NON-NLS-1$
				} else {
					out.append(c);
				}
			}
			}
		}
		out.append('"');
	}

	private static void escape(String value, StringBuilder out) {
		if (value == null) {
			return;
		}
		for (int i = 0; i < value.length(); i++) {
			char c = value.charAt(i);
			switch (c) {
			case '&' -> out.append("&amp;"); //$NON-NLS-1$
			case '<' -> out.append("&lt;"); //$NON-NLS-1$
			case '>' -> out.append("&gt;"); //$NON-NLS-1$
			case '"' -> out.append("&quot;"); //$NON-NLS-1$
			case '\'' -> out.append("&#39;"); //$NON-NLS-1$
			default -> out.append(c);
			}
		}
	}

	private static final String STYLE = """
			:root {
			  color-scheme: dark;
			  --bg: #14161c; --panel: #1b1e26; --line: #2a2f3a;
			  --text: #e6e8ee; --dim: #98a0b3; --accent: #7aa2f7;
			}
			* { box-sizing: border-box; }
			body {
			  margin: 0; background: var(--bg); color: var(--text);
			  font: 13px/1.5 ui-sans-serif, system-ui, -apple-system, "Segoe UI", Roboto, sans-serif;
			}
			header { padding: 20px 24px 12px; border-bottom: 1px solid var(--line); }
			h1 { margin: 0; font-size: 17px; font-weight: 600; letter-spacing: -0.01em; }
			.sub { margin: 4px 0 0; color: var(--dim); font-size: 12.5px; }
			.flamewrap { padding: 16px 24px 4px; }
			.toolbar { display: flex; gap: 10px; align-items: center; margin-bottom: 10px; flex-wrap: wrap; }
			input[type=search] {
			  flex: 1 1 240px; min-width: 180px; padding: 7px 10px;
			  background: var(--panel); border: 1px solid var(--line); border-radius: 7px;
			  color: var(--text); font: inherit;
			}
			input[type=search]:focus { outline: 2px solid var(--accent); outline-offset: -1px; }
			button {
			  padding: 7px 12px; background: var(--panel); border: 1px solid var(--line);
			  border-radius: 7px; color: var(--text); font: inherit; cursor: pointer;
			}
			button:hover { border-color: var(--accent); }
			#status { color: var(--dim); font-variant-numeric: tabular-nums; }
			#flame { width: 100%; overflow-x: auto; }
			#flame svg { display: block; }
			#flame rect { cursor: pointer; }
			#flame text { pointer-events: none; font: 11px ui-monospace, SFMono-Regular, Menlo, Consolas, monospace; fill: #0e1013; }
			#tip {
			  position: fixed; z-index: 10; max-width: 60ch; padding: 8px 10px;
			  background: #0e1013; border: 1px solid var(--line); border-radius: 8px;
			  font: 12px ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
			  color: var(--text); pointer-events: none; box-shadow: 0 8px 24px #0008;
			  overflow-wrap: anywhere;
			}
			[hidden] { display: none !important; }
			.tables { display: grid; grid-template-columns: repeat(auto-fit, minmax(320px, 1fr)); gap: 16px; padding: 16px 24px 24px; }
			.card { background: var(--panel); border: 1px solid var(--line); border-radius: 10px; padding: 14px 16px; min-width: 0; }
			.card h2 { margin: 0 0 10px; font-size: 12px; font-weight: 600; text-transform: uppercase; letter-spacing: .07em; color: var(--dim); }
			.scroll { overflow-x: auto; }
			table { border-collapse: collapse; width: 100%; font-size: 12.5px; }
			th, td { text-align: left; padding: 5px 8px; border-bottom: 1px solid var(--line); }
			th { color: var(--dim); font-weight: 500; }
			tr:last-child td { border-bottom: 0; }
			td { font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace; overflow-wrap: anywhere; }
			td.num { text-align: right; font-variant-numeric: tabular-nums; white-space: nowrap; }
			.empty { margin: 24px; padding: 20px; background: var(--panel); border: 1px solid var(--line); border-radius: 10px; color: var(--dim); }
			footer { padding: 0 24px 28px; color: var(--dim); font-size: 12px; max-width: 90ch; }
			"""; //$NON-NLS-1$

	private static final String SCRIPT = """
			(function () {
			  var root = JSON.parse(document.getElementById('profile').textContent);
			  var unit = document.documentElement.dataset.unit || '';
			  var host = document.getElementById('flame');
			  var tip = document.getElementById('tip');
			  var status = document.getElementById('status');
			  var find = document.getElementById('find');
			  var NS = 'http://www.w3.org/2000/svg';
			  var ROW = 18, focus = root, needle = '';

			  function fmt(v) {
			    if (unit === 'bytes') {
			      if (v < 1024) return v + ' B';
			      if (v < 1048576) return (v / 1024).toFixed(1) + ' KB';
			      if (v < 1073741824) return (v / 1048576).toFixed(1) + ' MB';
			      return (v / 1073741824).toFixed(2) + ' GB';
			    }
			    return v.toLocaleString() + (unit ? ' ' + unit : '');
			  }
			  // only the levels that will actually be drawn: one narrow branch thirty
			  // frames deep would otherwise size the canvas and leave the rest empty
			  function depth(n, span) {
			    if (span < 0.4) return 0;
			    var d = 1, kids = n.c || [];
			    for (var i = 0; i < kids.length; i++) {
			      d = Math.max(d, 1 + depth(kids[i], span * kids[i].v / n.v));
			    }
			    return d;
			  }
			  // a warm ramp, and stable per frame so the same method keeps its colour
			  function colour(name, hot) {
			    var h = 0;
			    for (var i = 0; i < name.length; i++) h = (h * 31 + name.charCodeAt(i)) | 0;
			    var hue = 12 + Math.abs(h) % 42;
			    return hot ? 'hsl(' + hue + ' 92% 68%)' : 'hsl(' + hue + ' 62% ' + (52 + Math.abs(h >> 7) % 10) + '%)';
			  }

			  function draw() {
			    var width = Math.max(host.clientWidth || 900, 320);
			    var levels = Math.max(depth(focus, width), 1);
			    var height = levels * ROW + 4;
			    var svg = document.createElementNS(NS, 'svg');
			    svg.setAttribute('width', width);
			    svg.setAttribute('height', height);
			    svg.setAttribute('viewBox', '0 0 ' + width + ' ' + height);

			    var matches = 0, matched = 0;
			    (function place(node, x, level, span) {
			      var w = span;
			      if (w < 0.4) return;
			      var hit = needle && node.n.toLowerCase().indexOf(needle) >= 0;
			      if (hit) { matches++; matched += node.v; }
			      var g = document.createElementNS(NS, 'g');
			      var r = document.createElementNS(NS, 'rect');
			      r.setAttribute('x', x.toFixed(2));
			      r.setAttribute('y', height - (level + 1) * ROW);
			      r.setAttribute('width', Math.max(w - 0.5, 0.5).toFixed(2));
			      r.setAttribute('height', ROW - 1);
			      r.setAttribute('rx', 2);
			      r.setAttribute('fill', needle ? (hit ? '#8ce99a' : '#3a3f4b') : colour(node.n, false));
			      g.appendChild(r);
			      if (w > 42) {
			        var t = document.createElementNS(NS, 'text');
			        t.setAttribute('x', (x + 4).toFixed(2));
			        t.setAttribute('y', height - level * ROW - 6);
			        var label = node.n, max = Math.floor((w - 8) / 6.1);
			        t.textContent = label.length > max ? label.slice(0, Math.max(max - 1, 1)) + '\\u2026' : label;
			        g.appendChild(t);
			      }
			      g.addEventListener('mousemove', function (e) {
			        tip.hidden = false;
			        tip.textContent = node.n + '  \\u2014  ' + fmt(node.v)
			          + '  (' + (node.v * 100 / root.v).toFixed(1) + '% of all)';
			        var pad = 14;
			        var left = Math.min(e.clientX + pad, window.innerWidth - tip.offsetWidth - 8);
			        var top = e.clientY + pad + tip.offsetHeight > window.innerHeight
			          ? e.clientY - tip.offsetHeight - pad : e.clientY + pad;
			        tip.style.left = Math.max(8, left) + 'px';
			        tip.style.top = top + 'px';
			      });
			      g.addEventListener('mouseleave', function () { tip.hidden = true; });
			      g.addEventListener('click', function () { focus = node; tip.hidden = true; draw(); });
			      svg.appendChild(g);

			      var kids = node.c || [], at = x;
			      for (var i = 0; i < kids.length; i++) {
			        place(kids[i], at, level + 1, span * kids[i].v / node.v);
			        at += span * kids[i].v / node.v;
			      }
			    })(focus, 0, 0, width);

			    host.replaceChildren(svg);
			    var scope = focus === root ? 'all ' + fmt(root.v)
			      : focus.n + ' \\u2014 ' + fmt(focus.v) + ' (' + (focus.v * 100 / root.v).toFixed(1) + '%), click Reset to zoom out';
			    status.textContent = needle
			      ? matches + ' frame' + (matches === 1 ? '' : 's') + ' matched, ' + fmt(matched) + ' \\u00b7 ' + scope
			      : scope;
			  }

			  document.getElementById('reset').addEventListener('click', function () { focus = root; draw(); });
			  var pending;
			  find.addEventListener('input', function () {
			    clearTimeout(pending);
			    pending = setTimeout(function () { needle = find.value.trim().toLowerCase(); draw(); }, 90);
			  });
			  var resizing;
			  window.addEventListener('resize', function () {
			    clearTimeout(resizing);
			    resizing = setTimeout(draw, 120);
			  });
			  draw();
			})();
			"""; //$NON-NLS-1$
}
