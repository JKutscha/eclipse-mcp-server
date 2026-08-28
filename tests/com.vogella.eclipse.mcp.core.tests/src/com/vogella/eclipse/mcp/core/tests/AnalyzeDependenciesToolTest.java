package com.vogella.eclipse.mcp.core.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IProjectDescription;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.JavaCore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.vogella.eclipse.mcp.core.McpToolResult;

/**
 * Declared against actually used, which is what a dependency cleanup needs.
 */
class AnalyzeDependenciesToolTest {

	private static final String TOOL = "eclipse_analyze_dependencies";

	private static final String PROJECT = "mcp-analyze-deps-test";

	private final TestFixture fixture = new TestFixture();

	@AfterEach
	void deleteTestProjects() throws Exception {
		fixture.dispose();
	}

	@Test
	@SuppressWarnings("unchecked")
	void reportsADeclaredBundleNothingResolvesAgainstAsUnused() throws Exception {
		IJavaProject project = plugin();
		TestFixture.addType(project, "example", "Holder", """
				package example;
				public class Holder {
					String value = "plain java only";
				}
				""");
		TestFixture.build(project.getProject());

		Map<String, Object> result = only(TestFixture.callAndParse(TOOL, Map.of("project", PROJECT)));

		List<Map<String, Object>> unused = (List<Map<String, Object>>) result.get("unused");
		assertTrue(unused.stream().anyMatch(entry -> "org.eclipse.core.resources".equals(entry.get("bundle"))),
				"nothing in the source resolves to it, got " + result);
		// and it says plainly that this is not a deletion instruction
		assertTrue(String.valueOf(result.get("caveats")).contains("not a deletion instruction"), "got " + result);
	}

	@Test
	@SuppressWarnings("unchecked")
	void reportsABundleTheSourceActuallyUses() throws Exception {
		IJavaProject project = plugin();
		TestFixture.addType(project, "example", "Holder", """
				package example;
				import org.eclipse.core.resources.IProject;
				public class Holder {
					IProject project;
				}
				""");
		TestFixture.build(project.getProject());

		Map<String, Object> result = only(TestFixture.callAndParse(TOOL, Map.of("project", PROJECT)));

		List<Map<String, Object>> used = (List<Map<String, Object>>) result.get("actuallyUsed");
		Map<String, Object> resources = used.stream()
				.filter(entry -> "org.eclipse.core.resources".equals(entry.get("bundle"))).findFirst()
				.orElseThrow(() -> new AssertionError("not reported as used: " + result));
		assertTrue(((Number) resources.get("types")).intValue() >= 1, "got " + resources);
		assertTrue(String.valueOf(resources.get("sample")).contains("IProject"), "got " + resources);
	}

	@Test
	void refusesAProjectThatIsNotAPlugin() throws Exception {
		fixture.createJavaProject(PROJECT + "-plain");
		Map<String, Object> result = only(
				TestFixture.callAndParse(TOOL, Map.of("project", PROJECT + "-plain")));
		assertTrue(String.valueOf(result.get("error")).contains("plug-in project"), "got " + result);
	}

	@Test
	void needsAProject() throws Exception {
		McpToolResult result = TestFixture.call(TOOL, Map.of());
		assertTrue(result.isError());
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> only(Map<String, Object> result) {
		List<Map<String, Object>> projects = (List<Map<String, Object>>) result.get("projects");
		assertEquals(1, projects.size(), "got " + result);
		return projects.get(0);
	}

	private IJavaProject plugin() throws Exception {
		IJavaProject javaProject = fixture.createJavaProject(PROJECT);
		IProject project = javaProject.getProject();
		IProjectDescription description = project.getDescription();
		description.setNatureIds(new String[] { JavaCore.NATURE_ID, "org.eclipse.pde.PluginNature" });
		project.setDescription(description, new NullProgressMonitor());

		IFolder folder = project.getFolder("META-INF");
		folder.create(false, true, new NullProgressMonitor());
		String content = """
				Manifest-Version: 1.0
				Bundle-ManifestVersion: 2
				Bundle-Name: Analyze Test
				Bundle-SymbolicName: %s;singleton:=true
				Bundle-Version: 1.0.0
				Require-Bundle: org.eclipse.core.resources
				""".formatted(PROJECT);
		project.getFile("META-INF/MANIFEST.MF").create(
				new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)), false, new NullProgressMonitor());
		project.refreshLocal(IProject.DEPTH_INFINITE, new NullProgressMonitor());

		// a plug-in project resolves its types through PDE's container, not through
		// the build path a plain Java project gets: without it nothing in
		// Require-Bundle resolves and every dependency looks unused
		List<org.eclipse.jdt.core.IClasspathEntry> entries = new ArrayList<>(
				List.of(javaProject.getRawClasspath()));
		entries.add(JavaCore.newContainerEntry(
				org.eclipse.core.runtime.IPath.fromPortableString("org.eclipse.pde.core.requiredPlugins")));
		javaProject.setRawClasspath(entries.toArray(org.eclipse.jdt.core.IClasspathEntry[]::new),
				new NullProgressMonitor());
		return javaProject;
	}
}
