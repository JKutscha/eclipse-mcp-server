package com.vogella.eclipse.mcp.core.tests;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IProjectDescription;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IncrementalProjectBuilder;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.OperationCanceledException;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.jdt.core.IClasspathEntry;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IPackageFragment;
import org.eclipse.jdt.core.IPackageFragmentRoot;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.launching.JavaRuntime;

import com.vogella.eclipse.mcp.core.IMcpTool;
import com.vogella.eclipse.mcp.core.McpToolException;
import com.vogella.eclipse.mcp.core.McpToolRegistry;
import com.vogella.eclipse.mcp.core.McpToolResult;

import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapperSupplier;

/**
 * Creates throw-away projects and invokes tools the way the server does.
 */
final class TestFixture {

	private static final McpJsonMapper JSON = new JacksonMcpJsonMapperSupplier().get();

	private final List<IProject> created = new ArrayList<>();

	static IMcpTool tool(String name) {
		return McpToolRegistry.getInstance().findTool(name)
				.orElseThrow(() -> new AssertionError("No tool contributed under the name " + name));
	}

	static McpToolResult call(String toolName, Map<String, Object> arguments) throws McpToolException {
		return tool(toolName).call(arguments, new NullProgressMonitor());
	}

	/** Runs the tool and parses its JSON payload. Fails when the tool reported an error. */
	static Map<String, Object> callAndParse(String toolName, Map<String, Object> arguments) throws Exception {
		McpToolResult result = call(toolName, arguments);
		if (result.isError()) {
			throw new AssertionError("Tool %s reported an error: %s".formatted(toolName, result.text()));
		}
		return parse(result.text());
	}

	@SuppressWarnings("unchecked")
	static Map<String, Object> parse(String json) throws IOException {
		return JSON.readValue(json, Map.class);
	}

	IProject createProject(String name) throws CoreException {
		IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(name);
		project.create(new NullProgressMonitor());
		project.open(new NullProgressMonitor());
		created.add(project);
		return project;
	}

	IJavaProject createJavaProject(String name) throws CoreException {
		IProject project = createProject(name);
		IProjectDescription description = project.getDescription();
		description.setNatureIds(new String[] { JavaCore.NATURE_ID });
		project.setDescription(description, new NullProgressMonitor());

		IFolder source = project.getFolder("src");
		source.create(false, true, new NullProgressMonitor());

		IJavaProject javaProject = JavaCore.create(project);
		IClasspathEntry jre = JavaRuntime.getDefaultJREContainerEntry();
		assertNotNull(jre, "No default JRE container available in the test workspace");
		javaProject.setRawClasspath(new IClasspathEntry[] { JavaCore.newSourceEntry(source.getFullPath()), jre },
				project.getFolder("bin").getFullPath(), new NullProgressMonitor());
		return javaProject;
	}

	static IFile addType(IJavaProject javaProject, String packageName, String typeName, String source)
			throws CoreException {
		IFolder folder = javaProject.getProject().getFolder("src");
		IPackageFragmentRoot root = javaProject.getPackageFragmentRoot(folder);
		IPackageFragment fragment = root.createPackageFragment(packageName, false, new NullProgressMonitor());
		return (IFile) fragment.createCompilationUnit(typeName + ".java", source, false, new NullProgressMonitor())
				.getResource();
	}

	/** Reads the file from disk, refreshing first so that IDE-side writes are visible. */
	static String read(IFile file) throws CoreException, IOException {
		file.refreshLocal(IResource.DEPTH_ZERO, new NullProgressMonitor());
		try (InputStream in = file.getContents(true)) {
			return new String(in.readAllBytes(), file.getCharset());
		}
	}

	static void build(IProject project) throws CoreException {
		project.build(IncrementalProjectBuilder.FULL_BUILD, new NullProgressMonitor());
		waitForBackgroundJobs();
	}

	static void waitForBackgroundJobs() {
		for (Object family : new Object[] { ResourcesPlugin.FAMILY_AUTO_BUILD, ResourcesPlugin.FAMILY_MANUAL_BUILD }) {
			try {
				Job.getJobManager().join(family, new NullProgressMonitor());
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return;
			} catch (OperationCanceledException e) {
				return;
			}
		}
	}

	void dispose() throws CoreException {
		for (IProject project : created) {
			if (project.exists()) {
				project.delete(true, true, new NullProgressMonitor());
			}
		}
		created.clear();
	}
}
