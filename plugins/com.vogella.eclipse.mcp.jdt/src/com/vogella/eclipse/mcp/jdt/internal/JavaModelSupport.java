package com.vogella.eclipse.mcp.jdt.internal;

import java.util.ArrayList;
import java.util.List;
import java.util.StringJoiner;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IField;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IMember;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.ISourceRange;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jdt.core.Signature;

import com.vogella.eclipse.mcp.core.McpToolException;

/**
 * Shared resolution of projects, types and members for the Java model tools.
 */
final class JavaModelSupport {

	private JavaModelSupport() {
	}

	/**
	 * Returns the Java projects to work on: the named one, or every open Java project.
	 *
	 * @throws ToolInputException if the named project does not exist, is closed or is not a Java project
	 */
	static List<IJavaProject> javaProjects(String projectName) throws ToolInputException, McpToolException {
		if (projectName == null) {
			try {
				return List.of(JavaCore.create(ResourcesPlugin.getWorkspace().getRoot()).getJavaProjects());
			} catch (JavaModelException e) {
				throw new McpToolException("Could not read the Java projects of the workspace", e); //$NON-NLS-1$
			}
		}
		IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
		if (!project.exists()) {
			throw new ToolInputException("There is no project named '%s' in the workspace.".formatted(projectName)); //$NON-NLS-1$
		}
		if (!project.isOpen()) {
			throw new ToolInputException("Project '%s' is closed.".formatted(projectName)); //$NON-NLS-1$
		}
		try {
			if (!project.hasNature(JavaCore.NATURE_ID)) {
				throw new ToolInputException("Project '%s' is not a Java project.".formatted(projectName)); //$NON-NLS-1$
			}
		} catch (CoreException e) {
			throw new McpToolException("Could not read the natures of project " + projectName, e); //$NON-NLS-1$
		}
		return List.of(JavaCore.create(project));
	}

	/**
	 * Returns the first type resolvable under the given fully qualified name.
	 *
	 * @throws ToolInputException if no project resolves the name
	 */
	static IType findType(String typeName, List<IJavaProject> projects) throws ToolInputException, McpToolException {
		for (IJavaProject project : projects) {
			try {
				IType type = project.findType(typeName);
				if (type != null && type.exists()) {
					return type;
				}
			} catch (JavaModelException e) {
				throw new McpToolException("Could not resolve type %s in project %s".formatted(typeName, //$NON-NLS-1$
						project.getElementName()), e);
			}
		}
		throw new ToolInputException(
				"Could not resolve the type '%s' on the classpath of %s. Use a fully qualified name." //$NON-NLS-1$
						.formatted(typeName, projects.size() == 1 ? "project " + projects.get(0).getElementName() //$NON-NLS-1$
								: "any Java project in the workspace")); //$NON-NLS-1$
	}

	/**
	 * Returns the methods and fields of {@code type} named {@code memberName}, all overloads included.
	 *
	 * @throws ToolInputException if the type has no such member
	 */
	static List<IMember> findMembers(IType type, String memberName) throws ToolInputException, McpToolException {
		List<IMember> members = new ArrayList<>();
		List<String> available = new ArrayList<>();
		try {
			for (IMethod method : type.getMethods()) {
				available.add(method.getElementName());
				if (method.getElementName().equals(memberName)) {
					members.add(method);
				}
			}
			for (IField field : type.getFields()) {
				available.add(field.getElementName());
				if (field.getElementName().equals(memberName)) {
					members.add(field);
				}
			}
		} catch (JavaModelException e) {
			throw new McpToolException("Could not read the members of " + type.getFullyQualifiedName(), e); //$NON-NLS-1$
		}
		if (members.isEmpty()) {
			throw new ToolInputException("Type '%s' has no member named '%s'. Available members: %s".formatted( //$NON-NLS-1$
					type.getFullyQualifiedName(), memberName,
					available.isEmpty() ? "none" : String.join(", ", available.stream().distinct().sorted().toList()))); //$NON-NLS-1$ //$NON-NLS-2$
		}
		return members;
	}

	/**
	 * Resolves a workspace path such as {@code /app/src/com/example/Main.java} to a Java
	 * source file on a project's build path.
	 */
	static ICompilationUnit compilationUnit(String path) throws ToolInputException {
		IResource resource = ResourcesPlugin.getWorkspace().getRoot().findMember(IPath.fromPortableString(path));
		if (!(resource instanceof IFile file)) {
			throw new ToolInputException(
					"There is no file at the workspace path '%s'. Paths look like /project/src/com/example/Main.java." //$NON-NLS-1$
							.formatted(path));
		}
		if (!(JavaCore.create(file) instanceof ICompilationUnit unit) || !unit.exists()) {
			throw new ToolInputException(
					"'%s' is not a Java source file on the build path of its project.".formatted(path)); //$NON-NLS-1$
		}
		return unit;
	}

	/**
	 * Returns the source of a member including its Javadoc, or {@code null} when no source
	 * is attached.
	 */
	static String sourceOf(IMember member) throws McpToolException {
		try {
			String all = member.getTypeRoot().getSource();
			ISourceRange range = member.getSourceRange();
			if (all == null || range == null || range.getOffset() < 0) {
				return null;
			}
			ISourceRange javadoc = member.getJavadocRange();
			int start = javadoc != null && javadoc.getOffset() >= 0 ? javadoc.getOffset() : range.getOffset();
			int end = Math.min(range.getOffset() + range.getLength(), all.length());
			return start >= end ? null : all.substring(start, end);
		} catch (JavaModelException e) {
			throw new McpToolException("Could not read the source of " + member.getElementName(), e); //$NON-NLS-1$
		}
	}

	/** Returns the one-based line the member starts on, or {@code -1} when unknown. */
	static int lineOf(IMember member) {
		try {
			String all = member.getTypeRoot().getSource();
			ISourceRange range = member.getSourceRange();
			if (all == null || range == null || range.getOffset() < 0) {
				return -1;
			}
			int line = 1;
			for (int i = 0; i < range.getOffset() && i < all.length(); i++) {
				if (all.charAt(i) == '\n') {
					line++;
				}
			}
			return line;
		} catch (JavaModelException e) {
			return -1;
		}
	}

	/** Returns a readable label such as {@code com.example.View.createPartControl(Composite)}. */
	static String describe(IJavaElement element) {
		return switch (element) {
		case null -> null;
		case IType type -> type.getFullyQualifiedName();
		case IMethod method -> qualify(method) + parameters(method);
		case IMember member -> qualify(member);
		default -> element.getElementName();
		};
	}

	private static String qualify(IMember member) {
		IType declaringType = member.getDeclaringType();
		if (declaringType == null) {
			return member.getElementName();
		}
		return declaringType.getFullyQualifiedName() + '.' + member.getElementName();
	}

	private static String parameters(IMethod method) {
		StringJoiner joiner = new StringJoiner(", ", "(", ")"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
		for (String parameterType : method.getParameterTypes()) {
			joiner.add(Signature.getSimpleName(Signature.toString(parameterType)));
		}
		return joiner.toString();
	}
}
