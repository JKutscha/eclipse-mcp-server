package com.vogella.eclipse.mcp.core.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.jobs.IJobChangeEvent;
import org.eclipse.core.runtime.jobs.JobChangeAdapter;
import org.eclipse.pde.core.target.ITargetHandle;
import org.eclipse.pde.core.target.ITargetPlatformService;
import org.eclipse.pde.core.target.LoadTargetDefinitionJob;
import org.osgi.framework.BundleContext;
import org.osgi.framework.FrameworkUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.vogella.eclipse.mcp.core.McpToolResult;

@SuppressWarnings("unchecked")
class TargetPlatformToolsTest {

	private static final String GET = "eclipse_get_target_platform";

	private static final String SET = "eclipse_set_target_platform";

	private static final String PROJECT = "mcp-target-test";

	private final TestFixture fixture = new TestFixture();

	@AfterEach
	void deleteTestProjects() throws Exception {
		fixture.dispose();
	}

	@Test
	void theActiveTargetPlatformIsReported() throws Exception {
		Map<String, Object> result = TestFixture.callAndParse(GET, Map.of());

		assertNotNull(result.get("targetSet"), "targetSet says whether a definition is set at all");
		assertNotNull(result.get("active"), "PDE always answers with a definition, the default one included");
	}

	@Test
	void theKnownDefinitionsIncludeATargetFileInTheWorkspace() throws Exception {
		createTarget();

		Map<String, Object> result = TestFixture.callAndParse(GET, Map.of("includeKnown", Boolean.TRUE));

		Object known = result.get("known");
		assertTrue(known instanceof List, "includeKnown lists the definitions the IDE knows");
		assertTrue(String.valueOf(known).contains("MCP test target"), "the workspace .target file is one of them: " + known);
	}

	@Test
	void anUnknownFileIsRefusedWithoutTouchingTheTargetPlatform() throws Exception {
		McpToolResult result = TestFixture.call(SET, Map.of("file", "/no-such-project/none.target"));

		assertTrue(result.isError());
		assertTrue(result.text().contains("none.target"), result.text());
	}

	@Test
	void resolveOnlyResolvesTheDefinitionWithoutActivatingIt() throws Exception {
		IFile target = createTarget();
		Object before = TestFixture.callAndParse(GET, Map.of()).get("active");

		Map<String, Object> result = TestFixture.callAndParse(SET,
				Map.of("file", target.getFullPath().toString(), "resolveOnly", Boolean.TRUE, "timeoutSeconds", 60));

		assertEquals("resolved", result.get("state"), String.valueOf(result));
		assertEquals(Boolean.TRUE, definition(result).get("resolved"));
		assertEquals("MCP test target", definition(result).get("name"));
		assertEquals(Integer.valueOf(0), definition(result).get("bundleCount"), "the location is an empty directory");
		assertEquals(before, TestFixture.callAndParse(GET, Map.of()).get("active"),
				"resolveOnly must leave the active target platform alone");
	}

	@Test
	void aDefinitionBecomesTheActiveTargetPlatform() throws Exception {
		IFile target = createTarget();
		ITargetHandle previous = service().getWorkspaceTargetHandle();
		try {
			Map<String, Object> result = TestFixture.callAndParse(SET,
					Map.of("file", target.getFullPath().toString(), "timeoutSeconds", 120));

			assertEquals("done", result.get("state"), String.valueOf(result));

			Map<String, Object> active = (Map<String, Object>) TestFixture.callAndParse(GET, Map.of()).get("active");
			assertEquals(Boolean.TRUE, TestFixture.callAndParse(GET, Map.of()).get("targetSet"));
			assertEquals("MCP test target", active.get("name"));
			assertEquals(Boolean.TRUE, active.get("resolved"));
		} finally {
			restore(previous);
		}
	}

	/** Puts the target platform of the test workspace back, whatever it was. */
	private static void restore(ITargetHandle previous) throws Exception {
		CountDownLatch restored = new CountDownLatch(1);
		LoadTargetDefinitionJob.load(previous == null ? null : previous.getTargetDefinition(),
				new JobChangeAdapter() {
					@Override
					public void done(IJobChangeEvent event) {
						restored.countDown();
					}
				});
		assertTrue(restored.await(120, TimeUnit.SECONDS), "the previous target platform was not restored");
	}

	private static ITargetPlatformService service() {
		BundleContext context = FrameworkUtil.getBundle(TargetPlatformToolsTest.class).getBundleContext();
		return context.getService(context.getServiceReference(ITargetPlatformService.class));
	}

	private static Map<String, Object> definition(Map<String, Object> result) {
		return (Map<String, Object>) result.get("definition");
	}

	/** A target whose only location is an empty directory, so resolving it needs no network. */
	private IFile createTarget() throws Exception {
		Path empty = Files.createTempDirectory("mcp-target-test");
		empty.toFile().deleteOnExit();
		IProject project = fixture.createProject(PROJECT);
		IFile file = project.getFile("test.target");
		String content = """
				<?xml version="1.0" encoding="UTF-8" standalone="no"?>
				<?pde version="3.8"?>
				<target name="MCP test target" sequenceNumber="1">
				<locations>
				<location path="%s" type="Directory"/>
				</locations>
				</target>
				""".formatted(empty.toAbsolutePath());
		file.create(new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)), true,
				new NullProgressMonitor());
		return file;
	}
}
