package com.vogella.eclipse.mcp.jdt.internal;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.jdt.core.IJavaElement;
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

	@Override
	public String getName() {
		return "eclipse_find_references"; //$NON-NLS-1$
	}

	@Override
	public String getDescription() {
		return "Finds all references to a Java type, method or field across the workspace, using the JDT search engine. Far more accurate than a text search because it resolves overloads and inheritance."; //$NON-NLS-1$
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
				    "maxResults": {"type":"integer","default":200,"minimum":1,"maximum":2000}
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

		List<IJavaProject> projects;
		IType type;
		SearchPattern pattern;
		String resolved;
		try {
			projects = JavaModelSupport.javaProjects(projectName);
			if (projects.isEmpty()) {
				return McpToolResult.error("The workspace contains no open Java project."); //$NON-NLS-1$
			}
			type = JavaModelSupport.findType(typeName, projects);
			if (memberName == null) {
				pattern = SearchPattern.createPattern(type, IJavaSearchConstants.REFERENCES);
				resolved = type.getFullyQualifiedName();
			} else {
				pattern = memberPattern(JavaModelSupport.findMembers(type, memberName));
				resolved = type.getFullyQualifiedName() + '#' + memberName;
			}
		} catch (ToolInputException e) {
			return McpToolResult.error(e.getMessage());
		}
		if (pattern == null) {
			return McpToolResult.error("Could not build a search pattern for '%s'.".formatted(resolved)); //$NON-NLS-1$
		}

		IJavaSearchScope scope = projectName == null ? SearchEngine.createWorkspaceScope()
				: SearchEngine.createJavaSearchScope(new IJavaElement[] { projects.get(0) }, true);

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
			reported.add(entry);
		}

		JsonObject result = new JsonObject().put("resolved", resolved) //$NON-NLS-1$
				.put("total", matches.size()) //$NON-NLS-1$
				.put("truncated", matches.size() > reported.size()) //$NON-NLS-1$
				.put("matches", reported); //$NON-NLS-1$
		return McpToolResult.of(result.toString());
	}

	private static SearchPattern memberPattern(List<IMember> members) {
		SearchPattern combined = null;
		for (IMember member : members) {
			SearchPattern pattern = SearchPattern.createPattern(member, IJavaSearchConstants.REFERENCES);
			if (pattern == null) {
				continue;
			}
			combined = combined == null ? pattern : SearchPattern.createOrPattern(combined, pattern);
		}
		return combined;
	}
}
