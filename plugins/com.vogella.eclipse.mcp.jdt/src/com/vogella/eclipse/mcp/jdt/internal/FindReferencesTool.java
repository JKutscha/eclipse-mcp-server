package com.vogella.eclipse.mcp.jdt.internal;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IField;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IMember;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.search.IJavaSearchConstants;
import org.eclipse.jdt.core.search.IJavaSearchScope;
import org.eclipse.jdt.core.search.SearchEngine;
import org.eclipse.jdt.core.search.SearchMatch;
import org.eclipse.jdt.core.search.SearchParticipant;
import org.eclipse.jdt.core.search.SearchPattern;
import org.eclipse.jdt.core.search.SearchRequestor;

import com.vogella.eclipse.mcp.core.IMcpTool;
import com.vogella.eclipse.mcp.core.McpToolException;
import com.vogella.eclipse.mcp.core.McpToolResult;
import com.vogella.eclipse.mcp.core.ToolArguments;
import com.vogella.eclipse.mcp.core.json.JsonArray;
import com.vogella.eclipse.mcp.core.json.JsonObject;

/**
 * Finds references to a Java type, method or field with the JDT search engine.
 */
public final class FindReferencesTool implements IMcpTool {

	private static final int DEFAULT_MAX_RESULTS = 200;

	private static final String ALL = "all"; //$NON-NLS-1$

	private static final String READ = "read"; //$NON-NLS-1$

	private static final String WRITE = "write"; //$NON-NLS-1$

	private static final Set<String> ACCESS_KINDS = Set.of(ALL, READ, WRITE);

	@Override
	public String getName() {
		return "eclipse_find_references"; //$NON-NLS-1$
	}

	@Override
	public String getDescription() {
		return "Finds all references to a Java type, method or field across the workspace, using the JDT search engine. Far more accurate than a text search because it resolves overloads and inheritance. For fields it also splits the references into reads and writes, which decides whether a field is actually used: one written in four places and read in none is dead, though a text search sees four live occurrences. A field initializer counts as a write but is a declaration rather than a reference, so byKind need not sum to total; with accessKind 'write' the initializer IS among the matches, and it is marked with declaration true and counted separately in byKind, so 'is this ever assigned outside its own declaration', which is what decides whether a field can be final, is one call. Every match carries an origin of source or binary: a binary match is inside a compiled jar on some project's build path, not in anyone's source, and byOrigin counts the two separately. Judge 'how many consumers does this API have' from the source count. resolvedFrom names the jar or source folder the type was resolved from, and matchedBy says whether the search followed the compiled binding or matched the qualified name; a type that exists only in jars is searched by name, because binding to one copy of a library silently misses every reference compiled against another."; //$NON-NLS-1$
	}

	@Override
	public String getInputSchema() {
		return """
				{
				  "type": "object",

				  "properties": {
				    "queries":    {"type":"array","description":"Ask about many elements in one call, which returns COUNTS ONLY: [{typeName, memberName?, accessKind?}]. A dead code sweep asks 'how many references' about hundreds of candidates, and one call per candidate is mostly round trips. Use the single form when you need the match locations.","items":{"type":"object","properties":{"typeName":{"type":"string"},"memberName":{"type":"string"},"accessKind":{"type":"string","enum":["all","read","write"]}},"required":["typeName"],"additionalProperties":false}},
				    "typeName":   {"type":"string","description":"Fully qualified type name, e.g. org.eclipse.jface.viewers.TreeViewer"},
				    "memberName": {"type":"string","description":"Optional method or field name. Omit to find references to the type itself. All overloads of a method name are searched."},
				    "project":    {"type":"string","description":"Optional project used to resolve the type. It scopes the search to that project AND everything on its build path, which includes other workspace projects and jars, so it narrows less than it looks. Defaults to the whole workspace."},
				    "maxResults": {"type":"integer","default":200,"minimum":1,"maximum":2000},
				    "accessKind": {"type":"string","enum":["all","read","write"],"default":"all","description":"Restrict to read or to write accesses. Only meaningful for fields. With 'all', field results additionally carry a byKind summary and a kind per match. A field initializer is a write but not a reference, so byKind counts can exceed total. With 'read' or 'write' the matches include the declaration itself, marked with declaration true and counted under declaration in byKind."}
				  },
				  "additionalProperties": false
				}"""; //$NON-NLS-1$
	}

	@Override
	public McpToolResult call(Map<String, Object> arguments, IProgressMonitor monitor) throws McpToolException {
		ToolArguments args = ToolArguments.of(arguments);
		List<Map<String, Object>> queries = queries(arguments);
		if (!queries.isEmpty()) {
			return countAll(queries, args.getString("project"), monitor); //$NON-NLS-1$
		}
		String typeName = args.getString("typeName"); //$NON-NLS-1$
		if (typeName == null) {
			return McpToolResult.error("Give 'typeName', or 'queries' to ask about many elements at once."); //$NON-NLS-1$
		}
		String memberName = args.getString("memberName"); //$NON-NLS-1$
		String projectName = args.getString("project"); //$NON-NLS-1$
		int maxResults = args.getInt("maxResults", DEFAULT_MAX_RESULTS, 1, 2000); //$NON-NLS-1$
		String accessKind = args.getString("accessKind", ALL); //$NON-NLS-1$
		if (!ACCESS_KINDS.contains(accessKind)) {
			return McpToolResult.error("Unknown accessKind '%s', expected one of all, read, write.".formatted(accessKind)); //$NON-NLS-1$
		}

		List<IJavaProject> projects;
		IType type;
		List<IMember> members;
		List<IMember> fields;
		String resolved;
		try {
			projects = JavaModelSupport.javaProjects(projectName);
			if (projects.isEmpty()) {
				return McpToolResult.error("The workspace contains no open Java project."); //$NON-NLS-1$
			}
			type = JavaModelSupport.findType(typeName, projects, monitor);
			members = memberName == null ? List.of() : JavaModelSupport.findMembers(type, memberName);
			fields = members.stream().filter(IField.class::isInstance).toList();
			resolved = memberName == null ? type.getFullyQualifiedName()
					: type.getFullyQualifiedName() + '#' + memberName;
		} catch (ToolInputException e) {
			return McpToolResult.error(e.getMessage());
		}
		if (!ALL.equals(accessKind) && fields.isEmpty()) {
			return McpToolResult.error(
					"Read and write accesses only exist for fields, and '%s' does not resolve to one. Use accessKind 'all'." //$NON-NLS-1$
							.formatted(resolved));
		}

		// A binary type binds to the one jar it was resolved from, so an element based
		// search finds only references compiled against that copy. When the same
		// qualified name is supplied by several jars, which is the norm for a library
		// like JUnit, that silently answers zero. Search the name instead and say so.
		boolean byName = type.isBinary() && memberName == null;
		SearchPattern pattern;
		if (byName) {
			pattern = SearchPattern.createPattern(type.getFullyQualifiedName(), IJavaSearchConstants.TYPE,
					IJavaSearchConstants.REFERENCES,
					SearchPattern.R_EXACT_MATCH | SearchPattern.R_CASE_SENSITIVE);
		} else if (memberName == null) {
			pattern = SearchPattern.createPattern(type, IJavaSearchConstants.REFERENCES);
		} else {
			pattern = pattern(ALL.equals(accessKind) ? members : fields, limitTo(accessKind));
		}
		if (pattern == null) {
			return McpToolResult.error("Could not build a search pattern for '%s'.".formatted(resolved)); //$NON-NLS-1$
		}

		IJavaSearchScope scope = projectName == null ? SearchEngine.createWorkspaceScope()
				: SearchEngine.createJavaSearchScope(new IJavaElement[] { projects.get(0) }, true);

		List<SearchMatch> matches = search(pattern, scope, monitor, resolved);

		// a field written but never read is dead while every text search sees live occurrences,
		// so the split is reported without the caller having to ask for it twice
		// the declarations, so that an initializer can be told from an assignment.
		// A WRITE_ACCESSES search reports the field's own initializer, which is a
		// declaration rather than a reference: "can this be final" is exactly the
		// question that needs the two separated
		Set<String> declarations = declarationsOf(fields);
		Set<String> reads = null;
		Set<String> writes = null;
		if (ALL.equals(accessKind) && !fields.isEmpty()) {
			reads = locationsOf(search(pattern(fields, IJavaSearchConstants.READ_ACCESSES), scope, monitor, resolved));
			writes = locationsOf(search(pattern(fields, IJavaSearchConstants.WRITE_ACCESSES), scope, monitor, resolved));
		}

		LineIndex lines = new LineIndex();
		JsonArray reported = new JsonArray();
		for (SearchMatch match : matches.subList(0, Math.min(maxResults, matches.size()))) {
			IResource resource = match.getResource();
			JsonObject entry = new JsonObject();
			JavaModelSupport.describeLocation(match, entry);
			int line = lines.lineOf(resource, match.getOffset());
			entry.put("line", line < 0 ? null : Integer.valueOf(line)); //$NON-NLS-1$
			entry.put("offset", match.getOffset()); //$NON-NLS-1$
			entry.put("length", match.getLength()); //$NON-NLS-1$
			entry.put("enclosingElement", //$NON-NLS-1$
					match.getElement() instanceof IJavaElement element ? JavaModelSupport.describe(element) : null);
			entry.put("kind", kindOf(match, accessKind, reads, writes)); //$NON-NLS-1$
			if (declarations.contains(locationOf(match))) {
				entry.put("declaration", Boolean.TRUE); //$NON-NLS-1$
			}
			reported.add(entry);
		}

		int binary = 0;
		for (SearchMatch match : matches) {
			if (match.getElement() instanceof IJavaElement element
					&& element.getAncestor(IJavaElement.CLASS_FILE) != null) {
				binary++;
			}
		}
		JsonObject result = new JsonObject().put("resolved", resolved) //$NON-NLS-1$
				.put("resolvedFrom", JavaModelSupport.originOf(type)) //$NON-NLS-1$
				.put("matchedBy", byName ? "name" : "binding") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
				.put("accessKind", accessKind) //$NON-NLS-1$
				.put("total", matches.size()) //$NON-NLS-1$
				.put("byOrigin", new JsonObject().put("source", matches.size() - binary).put("binary", binary)) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
				.put("truncated", matches.size() > reported.size()); //$NON-NLS-1$
		if (reads != null) {
			result.put("byKind", new JsonObject().put("read", reads.size()).put("write", writes.size())); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
		} else if (!fields.isEmpty()) {
			// a byKind here too, so that "is this ever assigned outside its
			// declaration" is one call rather than a subtraction the caller has to
			// know to make
			int declared = 0;
			for (SearchMatch match : matches) {
				if (declarations.contains(locationOf(match))) {
					declared++;
				}
			}
			result.put("byKind", new JsonObject().put(accessKind, matches.size() - declared) //$NON-NLS-1$
					.put("declaration", Integer.valueOf(declared))); //$NON-NLS-1$
		}
		if (type.isBinary() && memberName != null) {
			result.put("caveat", //$NON-NLS-1$
					"'%s' resolved to a compiled type in %s, and a member search binds to that one copy. If several jars supply this qualified name, references compiled against the others are not counted. Search the type without memberName for a name based count." //$NON-NLS-1$
							.formatted(type.getFullyQualifiedName(), JavaModelSupport.originOf(type)));
		} else if (JavaModelSupport.isBuildOutput(type)) {
			result.put("caveat", //$NON-NLS-1$
					"'%s' resolved to a copy inside build output (%s). No project compiles against that, so treat this count with suspicion." //$NON-NLS-1$
							.formatted(type.getFullyQualifiedName(), JavaModelSupport.originOf(type)));
		}
		result.put("matches", reported); //$NON-NLS-1$
		return McpToolResult.of(result.toString());
	}

	private static int limitTo(String accessKind) {
		return switch (accessKind) {
		case READ -> IJavaSearchConstants.READ_ACCESSES;
		case WRITE -> IJavaSearchConstants.WRITE_ACCESSES;
		default -> IJavaSearchConstants.REFERENCES;
		};
	}

	/** {@code null} for anything that is not a field, since only fields are read and written. */
	private static String kindOf(SearchMatch match, String accessKind, Set<String> reads, Set<String> writes) {
		if (!ALL.equals(accessKind)) {
			return accessKind;
		}
		if (reads == null) {
			return null;
		}
		String location = locationOf(match);
		boolean read = reads.contains(location);
		boolean written = writes.contains(location);
		if (read && written) {
			return "readWrite"; //$NON-NLS-1$
		}
		return read ? READ : written ? WRITE : null;
	}

	/** Where the fields themselves are declared, as match locations. */
	private static Set<String> declarationsOf(List<? extends org.eclipse.jdt.core.IMember> fields) {
		Set<String> locations = new java.util.LinkedHashSet<>();
		for (org.eclipse.jdt.core.IMember field : fields) {
			try {
				org.eclipse.jdt.core.ISourceRange range = field.getNameRange();
				IResource resource = field.getResource();
				if (range != null && resource != null) {
					// the same format locationOf produces, because two spellings of one
					// key is how this silently never matched anything
					locations.add(locationOf(resource, range.getOffset()));
				}
			} catch (org.eclipse.jdt.core.JavaModelException e) {
				// a field whose range cannot be read simply is not recognised as a
				// declaration, which reports more rather than less
			}
		}
		return locations;
	}

	private static Set<String> locationsOf(List<SearchMatch> matches) {
		return matches.stream().map(FindReferencesTool::locationOf).collect(Collectors.toSet());
	}

	/**
	 * Counts references for many elements in one call.
	 * <p>
	 * Counts only, deliberately. A sweep asks "how many references" about hundreds
	 * of candidates and needs the locations for almost none of them, so returning
	 * matches would make the answer enormous to save the round trips that were the
	 * problem. The type resolution and the search index are shared across the batch.
	 */
	private McpToolResult countAll(List<Map<String, Object>> queries, String projectName, IProgressMonitor monitor)
			throws McpToolException {
		List<IJavaProject> projects;
		try {
			projects = JavaModelSupport.javaProjects(projectName);
		} catch (ToolInputException e) {
			return McpToolResult.error(e.getMessage());
		}
		if (projects.isEmpty()) {
			return McpToolResult.error("The workspace contains no open Java project."); //$NON-NLS-1$
		}
		IJavaSearchScope scope = projectName == null ? SearchEngine.createWorkspaceScope()
				: SearchEngine.createJavaSearchScope(new IJavaElement[] { projects.get(0) }, true);
		JsonArray results = new JsonArray();
		for (Map<String, Object> query : queries) {
			if (monitor != null && monitor.isCanceled()) {
				return McpToolResult.error("The request was cancelled."); //$NON-NLS-1$
			}
			results.add(count(query, projects, scope, monitor));
		}
		return McpToolResult.of(new JsonObject().put("total", Integer.valueOf(results.size())) //$NON-NLS-1$
				.put("countsOnly", Boolean.TRUE) //$NON-NLS-1$
				.put("note", //$NON-NLS-1$
						"Counts only. Ask the single form about anything whose match locations you need.") //$NON-NLS-1$
				.put("results", results).toString()); //$NON-NLS-1$
	}

	private JsonObject count(Map<String, Object> query, List<IJavaProject> projects, IJavaSearchScope scope,
			IProgressMonitor monitor) throws McpToolException {
		String typeName = String.valueOf(query.get("typeName")); //$NON-NLS-1$
		Object member = query.get("memberName"); //$NON-NLS-1$
		String memberName = member == null || String.valueOf(member).isBlank() ? null
				: String.valueOf(member).trim();
		Object kind = query.get("accessKind"); //$NON-NLS-1$
		String accessKind = kind == null ? ALL : String.valueOf(kind);
		JsonObject entry = new JsonObject().put("typeName", typeName).put("memberName", memberName); //$NON-NLS-1$ //$NON-NLS-2$
		if (!ACCESS_KINDS.contains(accessKind)) {
			return entry.put("error", "Unknown accessKind '%s'.".formatted(accessKind)); //$NON-NLS-1$ //$NON-NLS-2$
		}
		IType type;
		List<IMember> members;
		try {
			type = JavaModelSupport.findType(typeName, projects, monitor);
			members = memberName == null ? List.of() : JavaModelSupport.findMembers(type, memberName);
		} catch (ToolInputException e) {
			return entry.put("error", e.getMessage()); //$NON-NLS-1$
		}
		List<IMember> fields = members.stream().filter(IField.class::isInstance).toList();
		if (!ALL.equals(accessKind) && fields.isEmpty()) {
			return entry.put("error", "Read and write accesses only exist for fields."); //$NON-NLS-1$ //$NON-NLS-2$
		}
		SearchPattern pattern;
		if (memberName == null) {
			pattern = type.isBinary()
					? SearchPattern.createPattern(type.getFullyQualifiedName(), IJavaSearchConstants.TYPE,
							IJavaSearchConstants.REFERENCES,
							SearchPattern.R_EXACT_MATCH | SearchPattern.R_CASE_SENSITIVE)
					: SearchPattern.createPattern(type, IJavaSearchConstants.REFERENCES);
		} else {
			pattern = pattern(ALL.equals(accessKind) ? members : fields, limitTo(accessKind));
		}
		if (pattern == null) {
			return entry.put("error", "Could not build a search pattern."); //$NON-NLS-1$ //$NON-NLS-2$
		}
		List<SearchMatch> matches = search(pattern, scope, monitor, typeName);
		Set<String> declarations = declarationsOf(fields);
		int binary = 0;
		int declared = 0;
		for (SearchMatch match : matches) {
			if (match.getElement() instanceof IJavaElement element
					&& element.getAncestor(IJavaElement.CLASS_FILE) != null) {
				binary++;
			}
			if (declarations.contains(locationOf(match))) {
				declared++;
			}
		}
		return entry.put("accessKind", accessKind) //$NON-NLS-1$
				.put("total", Integer.valueOf(matches.size())) //$NON-NLS-1$
				.put("source", Integer.valueOf(matches.size() - binary)) //$NON-NLS-1$
				.put("binary", Integer.valueOf(binary)) //$NON-NLS-1$
				.put("declaration", Integer.valueOf(declared)); //$NON-NLS-1$
	}

	@SuppressWarnings("unchecked")
	private static List<Map<String, Object>> queries(Map<String, Object> arguments) {
		List<Map<String, Object>> values = new ArrayList<>();
		if (arguments != null && arguments.get("queries") instanceof List<?> list) { //$NON-NLS-1$
			for (Object value : list) {
				if (value instanceof Map<?, ?> map && map.get("typeName") != null) { //$NON-NLS-1$
					values.add((Map<String, Object>) map);
				}
			}
		}
		return values;
	}

	private static String locationOf(SearchMatch match) {
		return locationOf(match.getResource(), match.getOffset());
	}

	private static String locationOf(IResource resource, int offset) {
		return (resource == null ? "?" : resource.getFullPath().toString()) + ':' + offset; //$NON-NLS-1$
	}

	private List<SearchMatch> search(SearchPattern pattern, IJavaSearchScope scope, IProgressMonitor monitor,
			String resolved) throws McpToolException {
		if (pattern == null) {
			return List.of();
		}
		List<SearchMatch> matches = new ArrayList<>();
		SearchRequestor requestor = new SearchRequestor() {
			@Override
			public void acceptSearchMatch(SearchMatch match) {
				matches.add(match);
			}
		};
		try {
			new SearchEngine().search(pattern, new SearchParticipant[] { SearchEngine.getDefaultSearchParticipant() },
					scope, requestor, monitor);
		} catch (CoreException e) {
			throw new McpToolException("The JDT search for references to %s failed".formatted(resolved), e); //$NON-NLS-1$
		}
		return matches;
	}

	private static SearchPattern pattern(List<IMember> members, int limitTo) {
		SearchPattern combined = null;
		for (IMember member : members) {
			SearchPattern pattern = SearchPattern.createPattern(member, limitTo);
			if (pattern == null) {
				continue;
			}
			combined = combined == null ? pattern : SearchPattern.createOrPattern(combined, pattern);
		}
		return combined;
	}
}
