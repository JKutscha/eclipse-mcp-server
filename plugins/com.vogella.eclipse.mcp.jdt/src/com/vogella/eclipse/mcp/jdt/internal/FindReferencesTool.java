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
		return "Finds all references to a Java type, method or field across the workspace, using the JDT search engine. Far more accurate than a text search because it resolves overloads and inheritance. For fields it also splits the references into reads and writes, which decides whether a field is actually used: one written in four places and read in none is dead, though a text search sees four live occurrences. A field initializer counts as a write but is a declaration rather than a reference, so byKind need not sum to total."; //$NON-NLS-1$
	}

	@Override
	public String getInputSchema() {
		return """
				{
				  "type": "object",
				  "required": ["typeName"],
				  "properties": {
				    "typeName":   {"type":"string","description":"Fully qualified type name, e.g. org.eclipse.jface.viewers.TreeViewer"},
				    "memberName": {"type":"string","description":"Optional method or field name. Omit to find references to the type itself. All overloads of a method name are searched."},
				    "project":    {"type":"string","description":"Optional project used to resolve the type and to scope the search. Defaults to the whole workspace."},
				    "maxResults": {"type":"integer","default":200,"minimum":1,"maximum":2000},
				    "accessKind": {"type":"string","enum":["all","read","write"],"default":"all","description":"Restrict to read or to write accesses. Only meaningful for fields. With 'all', field results additionally carry a byKind summary and a kind per match. A field initializer is a write but not a reference, so byKind counts can exceed total."}
				  },
				  "additionalProperties": false
				}"""; //$NON-NLS-1$
	}

	@Override
	public McpToolResult call(Map<String, Object> arguments, IProgressMonitor monitor) throws McpToolException {
		ToolArguments args = ToolArguments.of(arguments);
		String typeName = args.getString("typeName"); //$NON-NLS-1$
		if (typeName == null) {
			return McpToolResult.error("The argument 'typeName' is required."); //$NON-NLS-1$
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
			type = JavaModelSupport.findType(typeName, projects);
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

		SearchPattern pattern = memberName == null
				? SearchPattern.createPattern(type, IJavaSearchConstants.REFERENCES)
				: pattern(ALL.equals(accessKind) ? members : fields, limitTo(accessKind));
		if (pattern == null) {
			return McpToolResult.error("Could not build a search pattern for '%s'.".formatted(resolved)); //$NON-NLS-1$
		}

		IJavaSearchScope scope = projectName == null ? SearchEngine.createWorkspaceScope()
				: SearchEngine.createJavaSearchScope(new IJavaElement[] { projects.get(0) }, true);

		List<SearchMatch> matches = search(pattern, scope, monitor, resolved);

		// a field written but never read is dead while every text search sees live occurrences,
		// so the split is reported without the caller having to ask for it twice
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
			IProject project = resource == null ? null : resource.getProject();
			JsonObject entry = new JsonObject();
			entry.put("path", resource == null ? null : resource.getFullPath().toString()); //$NON-NLS-1$
			entry.put("project", project == null ? null : project.getName()); //$NON-NLS-1$
			int line = lines.lineOf(resource, match.getOffset());
			entry.put("line", line < 0 ? null : Integer.valueOf(line)); //$NON-NLS-1$
			entry.put("offset", match.getOffset()); //$NON-NLS-1$
			entry.put("length", match.getLength()); //$NON-NLS-1$
			entry.put("enclosingElement", //$NON-NLS-1$
					match.getElement() instanceof IJavaElement element ? JavaModelSupport.describe(element) : null);
			entry.put("kind", kindOf(match, accessKind, reads, writes)); //$NON-NLS-1$
			reported.add(entry);
		}

		JsonObject result = new JsonObject().put("resolved", resolved) //$NON-NLS-1$
				.put("accessKind", accessKind) //$NON-NLS-1$
				.put("total", matches.size()) //$NON-NLS-1$
				.put("truncated", matches.size() > reported.size()); //$NON-NLS-1$
		if (reads != null) {
			result.put("byKind", new JsonObject().put("read", reads.size()).put("write", writes.size())); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
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

	private static Set<String> locationsOf(List<SearchMatch> matches) {
		return matches.stream().map(FindReferencesTool::locationOf).collect(Collectors.toSet());
	}

	private static String locationOf(SearchMatch match) {
		IResource resource = match.getResource();
		return (resource == null ? "?" : resource.getFullPath().toString()) + ':' + match.getOffset(); //$NON-NLS-1$
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
