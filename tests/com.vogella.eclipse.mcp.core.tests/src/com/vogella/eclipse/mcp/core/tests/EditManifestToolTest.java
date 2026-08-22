package com.vogella.eclipse.mcp.core.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
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

/**
 * Editing OSGi headers through PDE's project model.
 */
class EditManifestToolTest {

	private static final String TOOL = "eclipse_edit_manifest";

	private static final String PROJECT = "mcp-manifest-test";

	private final TestFixture fixture = new TestFixture();

	@AfterEach
	void deleteTestProjects() throws Exception {
		fixture.dispose();
	}

	@Test
	void aDryRunShowsTheHeaderWithoutWritingIt() throws Exception {
		IProject project = createPlugin(PROJECT, "Export-Package: example.api\n");

		Map<String, Object> result = TestFixture.callAndParse(TOOL, Map.of("project", PROJECT, "addExportPackage",
				List.of(Map.of("package", "example.added"))));

		assertEquals(Boolean.FALSE, result.get("applied"));
		// the change, not the whole manifest: the full dump was tens of kilobytes on a
		// platform bundle to describe one added line
		assertTrue(added(result, "exportPackage").contains("example.added"), "got " + result);
		assertNull(result.get("exportPackage"), "the full header is opt-in through includeFullHeaders");
		assertFalse(manifest(project).contains("example.added"), "a dry run must not write the manifest");
	}

	@Test
	void theFullHeadersAreAvailableOnRequest() throws Exception {
		createPlugin(PROJECT, "Export-Package: example.api\n");

		Map<String, Object> result = TestFixture.callAndParse(TOOL, Map.of("project", PROJECT,
				"includeFullHeaders", Boolean.TRUE, "addExportPackage", List.of(Map.of("package", "example.added"))));

		assertTrue(packages(result).contains("example.api"), "got " + result);
		assertTrue(packages(result).contains("example.added"), "got " + result);
	}

	@Test
	void addsAnExportWithItsFriends() throws Exception {
		IProject project = createPlugin(PROJECT, "Export-Package: example.api\n");

		TestFixture.callAndParse(TOOL,
				Map.of("project", PROJECT, "dryRun", Boolean.FALSE, "addExportPackage",
						List.of(Map.of("package", "example.internal", "friends", List.of("some.friend")))));

		String manifest = manifest(project);
		assertTrue(manifest.contains("example.internal"), manifest);
		// written by PDE rather than by us, so the folding and the directive syntax
		// are whatever PDE writes and not something this tool has to get right
		assertTrue(manifest.contains("x-friends"), manifest);
		assertTrue(manifest.contains("some.friend"), manifest);
	}

	@Test
	void marksAnExportInternal() throws Exception {
		IProject project = createPlugin(PROJECT, "Export-Package: example.api\n");

		TestFixture.callAndParse(TOOL, Map.of("project", PROJECT, "dryRun", Boolean.FALSE, "addExportPackage",
				List.of(Map.of("package", "example.api", "internal", Boolean.TRUE))));

		assertTrue(manifest(project).contains("x-internal"), manifest(project));
	}

	@Test
	void addsARequiredBundleAndReexportsIt() throws Exception {
		IProject project = createPlugin(PROJECT, "");

		TestFixture.callAndParse(TOOL, Map.of("project", PROJECT, "dryRun", Boolean.FALSE, "addRequireBundle",
				List.of(Map.of("bundle", "org.eclipse.core.runtime", "reexport", Boolean.TRUE))));

		String manifest = manifest(project);
		assertTrue(manifest.contains("Require-Bundle"), manifest);
		assertTrue(manifest.contains("org.eclipse.core.runtime"), manifest);
		assertTrue(manifest.contains("reexport"), manifest);
	}

	@Test
	void removesAnExportNobodyConsumes() throws Exception {
		IProject project = createPlugin(PROJECT, "Export-Package: example.api,example.gone\n");

		Map<String, Object> result = TestFixture.callAndParse(TOOL,
				Map.of("project", PROJECT, "dryRun", Boolean.FALSE, "removeExportPackage", List.of("example.gone")));

		assertEquals(Boolean.TRUE, result.get("applied"));
		assertFalse(manifest(project).contains("example.gone"), manifest(project));
	}

	@Test
	void refusesToRemoveAnExportAWorkspaceBundleImports() throws Exception {
		createPlugin(PROJECT, "Export-Package: example.api\n");
		createPlugin(PROJECT + "-consumer", "Import-Package: example.api\n");

		Map<String, Object> result = TestFixture.callAndParse(TOOL,
				Map.of("project", PROJECT, "dryRun", Boolean.FALSE, "removeExportPackage", List.of("example.api")));

		// the consumer keeps compiling and fails to resolve at runtime, which is why
		// this is a refusal rather than a warning
		assertEquals(Boolean.FALSE, result.get("applied"), "got " + result);
		assertTrue(String.valueOf(result.get("refusedBecause")).contains("force"), "got " + result);
		assertTrue(String.valueOf(result.get("wouldBreak")).contains(PROJECT + "-consumer"), "got " + result);
	}

	@Test
	void refusesAProjectThatIsNotAPlugin() throws Exception {
		fixture.createProject(PROJECT + "-plain");
		var result = TestFixture.call(TOOL, Map.of("project", PROJECT + "-plain"));

		assertTrue(result.isError());
		assertTrue(result.text().contains("plug-in project"), result.text());
	}

	private IProject createPlugin(String name, String extraHeaders) throws Exception {
		IJavaProject javaProject = fixture.createJavaProject(name);
		IProject project = javaProject.getProject();
		IProjectDescription description = project.getDescription();
		description.setNatureIds(new String[] { JavaCore.NATURE_ID, "org.eclipse.pde.PluginNature" });
		project.setDescription(description, new NullProgressMonitor());

		IFolder folder = project.getFolder("META-INF");
		folder.create(false, true, new NullProgressMonitor());
		String content = """
				Manifest-Version: 1.0
				Bundle-ManifestVersion: 2
				Bundle-Name: Manifest Test
				Bundle-SymbolicName: %s;singleton:=true
				Bundle-Version: 1.0.0.qualifier
				""".formatted(name) + extraHeaders;
		project.getFile("META-INF/MANIFEST.MF").create(
				new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)), false, new NullProgressMonitor());
		return project;
	}

	private static String manifest(IProject project) throws Exception {
		return TestFixture.read(project.getFile("META-INF/MANIFEST.MF"));
	}

	@SuppressWarnings("unchecked")
	private static String added(Map<String, Object> result, String header) {
		Map<String, Object> changes = (Map<String, Object>) result.get("changes");
		Map<String, Object> difference = (Map<String, Object>) changes.get(header);
		StringBuilder all = new StringBuilder();
		for (Map<String, Object> entry : (List<Map<String, Object>>) difference.get("added")) {
			all.append(entry.get("package")).append(' ');
		}
		return all.toString();
	}

	@SuppressWarnings("unchecked")
	private static String packages(Map<String, Object> result) {
		StringBuilder all = new StringBuilder();
		for (Map<String, Object> entry : (List<Map<String, Object>>) result.get("exportPackage")) {
			all.append(entry.get("package")).append(' ');
		}
		return all.toString();
	}
}
