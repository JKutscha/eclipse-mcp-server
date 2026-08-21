package com.vogella.eclipse.mcp.jdt.internal;

import java.util.List;
import java.util.Map;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IMember;
import org.eclipse.jdt.core.IType;

import com.vogella.eclipse.mcp.core.IMcpTool;
import com.vogella.eclipse.mcp.core.McpToolException;
import com.vogella.eclipse.mcp.core.McpToolResult;
import com.vogella.eclipse.mcp.core.ToolArguments;
import com.vogella.eclipse.mcp.core.json.JsonArray;
import com.vogella.eclipse.mcp.core.json.JsonObject;

/**
 * Returns the source of a type or member, including from source attachments on the
 * classpath.
 */
public final class GetSourceTool implements IMcpTool {

	private static final int DEFAULT_MAX_LENGTH = 40000;

	@Override
	public String getName() {
		return "eclipse_get_source"; //$NON-NLS-1$
	}

	@Override
	public String getDescription() {
		return "Returns the Java source and Javadoc of a type or of its members, resolved through the project classpath. Works for types in libraries too, as long as a source attachment exists, so use this instead of guessing the API of a dependency."; //$NON-NLS-1$
	}

	@Override
	public String getInputSchema() {
		return """
				{
				  "type": "object",
				  "required": ["typeName"],
				  "properties": {
				    "typeName":   {"type":"string","description":"Fully qualified type name, e.g. org.eclipse.jface.viewers.TreeViewer"},
				    "memberName": {"type":"string","description":"Optional method or field name. Omit to get the source of the whole type. All overloads of a method name are returned."},
				    "project":    {"type":"string","description":"Optional project used to resolve the type. Defaults to the whole workspace."},
				    "maxLength":  {"type":"integer","default":40000,"minimum":100,"maximum":200000,"description":"Maximum number of characters per returned element."}
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
		int maxLength = args.getInt("maxLength", DEFAULT_MAX_LENGTH, 100, 200000); //$NON-NLS-1$

		IType type;
		List<? extends IMember> members;
		try {
			List<IJavaProject> projects = JavaModelSupport.javaProjects(projectName);
			if (projects.isEmpty()) {
				return McpToolResult.error("The workspace contains no open Java project."); //$NON-NLS-1$
			}
			type = JavaModelSupport.findType(typeName, projects, monitor);
			members = memberName == null ? List.of(type) : JavaModelSupport.findMembers(type, memberName);
		} catch (ToolInputException e) {
			return McpToolResult.error(e.getMessage());
		}

		JsonArray elements = new JsonArray();
		boolean anySource = false;
		for (IMember member : members) {
			String source = JavaModelSupport.sourceOf(member);
			anySource |= source != null;
			boolean truncated = source != null && source.length() > maxLength;
			elements.add(new JsonObject().put("element", JavaModelSupport.describe(member)) //$NON-NLS-1$
					.put("line", lineOrNull(member)) //$NON-NLS-1$
					.put("source", truncated ? source.substring(0, maxLength) : source) //$NON-NLS-1$
					.put("truncated", truncated)); //$NON-NLS-1$
		}

		JsonObject result = new JsonObject().put("type", type.getFullyQualifiedName()) //$NON-NLS-1$
				.put("binary", type.isBinary()) //$NON-NLS-1$
				.put("path", type.getPath() == null ? null : type.getPath().toString()) //$NON-NLS-1$
				.put("sourceAvailable", anySource) //$NON-NLS-1$
				.put("elements", elements); //$NON-NLS-1$
		if (!anySource) {
			result.put("hint", //$NON-NLS-1$
					"No source is attached for this type. Attach a sources jar to the classpath entry to read it.");
		}
		return McpToolResult.of(result.toString());
	}

	private static Integer lineOrNull(IMember member) {
		int line = JavaModelSupport.lineOf(member);
		return line < 0 ? null : Integer.valueOf(line);
	}
}
