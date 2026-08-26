package com.vogella.eclipse.mcp.server.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IProjectDescription;
import org.eclipse.core.resources.IncrementalProjectBuilder;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.preferences.InstanceScope;
import org.eclipse.jdt.core.IClasspathEntry;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.launching.JavaRuntime;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.vogella.eclipse.mcp.core.IMcpTool;
import com.vogella.eclipse.mcp.core.McpToolRegistry;
import com.vogella.eclipse.mcp.server.McpEndpoint;
import com.vogella.eclipse.mcp.server.McpPreferences;
import com.vogella.eclipse.mcp.server.McpServerService;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapperSupplier;
import io.modelcontextprotocol.json.schema.JsonSchemaValidator;
import io.modelcontextprotocol.json.schema.jackson3.JacksonJsonSchemaValidatorSupplier;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.Tool;

/**
 * Drives the running server the way an MCP client does, over Streamable HTTP.
 */
class McpServerServiceTest {

	/** A port unlikely to collide with a developer's IDE running the same server. */
	private static final int TEST_PORT = 18642;

	private static final String PROJECT = "mcp-endpoint-test";

	private static final String SAMPLE = "/%s/src/example/Sample.java".formatted(PROJECT);

	private static final Set<String> WRITES_FILES = Set.of("eclipse_organize_imports", "eclipse_format",
			"eclipse_write_file");

	/** A target whose only location is an empty directory, so resolving it needs no network. */
	private static final String TARGET = "/%s/smoke.target".formatted(PROJECT);

	/** A throwaway bundle jar the install tool can look at without installing it. */
	private static Path SMOKE_JAR;

	/** An initialize request answers 200 without a session, which makes it a usable probe. */
	private static final String INITIALIZE = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":"
			+ "{\"protocolVersion\":\"2025-06-18\",\"capabilities\":{},"
			+ "\"clientInfo\":{\"name\":\"probe\",\"version\":\"1\"}}}";

	@BeforeAll
	static void startServer() throws Exception {
		InstanceScope.INSTANCE.getNode(McpPreferences.QUALIFIER).putInt(McpPreferences.KEY_PORT, TEST_PORT);
		// the command tools are off until a directory is allowed, so the smoke test
		// allows a temporary one rather than asserting that every tool refuses
		InstanceScope.INSTANCE.getNode(McpPreferences.QUALIFIER).put(McpPreferences.KEY_COMMAND_ROOTS,
				System.getProperty("java.io.tmpdir"));
		McpServerService.getInstance().start();
		assertNotNull(endpoint(), "The server did not report an endpoint");
		createSampleProject();
		SMOKE_JAR = createSmokeBundleJar();
	}

	private static Path createSmokeBundleJar() throws Exception {
		Path path = Files.createTempFile("mcp-install-bundle-smoke", ".jar");
		path.toFile().deleteOnExit();
		Manifest manifest = new Manifest();
		Attributes main = manifest.getMainAttributes();
		main.putValue("Manifest-Version", "1.0");
		main.putValue("Bundle-ManifestVersion", "2");
		main.putValue("Bundle-SymbolicName", "org.mcp.smoke.installable");
		main.putValue("Bundle-Version", "0.0.1");
		try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(path), manifest)) {
			out.flush();
		}
		return path;
	}

	/** The Java tools need something to work on, otherwise they rightly refuse. */
	private static void createSampleProject() throws Exception {
		IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(PROJECT);
		project.create(new NullProgressMonitor());
		project.open(new NullProgressMonitor());
		IProjectDescription description = project.getDescription();
		description.setNatureIds(new String[] { JavaCore.NATURE_ID });
		project.setDescription(description, new NullProgressMonitor());

		IFolder source = project.getFolder("src");
		source.create(false, true, new NullProgressMonitor());
		IJavaProject javaProject = JavaCore.create(project);
		javaProject.setRawClasspath(new IClasspathEntry[] { JavaCore.newSourceEntry(source.getFullPath()),
				JavaRuntime.getDefaultJREContainerEntry() }, project.getFolder("bin").getFullPath(),
				new NullProgressMonitor());
		javaProject.getPackageFragmentRoot(source).createPackageFragment("example", false, new NullProgressMonitor())
				.createCompilationUnit("Sample.java", """
						package example;

						public class Sample {
							void run() {
							}
						}
						""", false, new NullProgressMonitor());
		java.nio.file.Path empty = Files.createTempDirectory("mcp-endpoint-test");
		empty.toFile().deleteOnExit();
		project.getFile("smoke.target").create("""
				<?xml version="1.0" encoding="UTF-8" standalone="no"?>
				<?pde version="3.8"?>
				<target name="MCP smoke target" sequenceNumber="1">
				<locations>
				<location path="%s" type="Directory"/>
				</locations>
				</target>
				""".formatted(empty.toAbsolutePath()).getBytes(java.nio.charset.StandardCharsets.UTF_8),
				org.eclipse.core.resources.IResource.NONE, new NullProgressMonitor());
		project.build(IncrementalProjectBuilder.FULL_BUILD, new NullProgressMonitor());
	}

	/** Read live, because regenerating the token replaces it. */
	private static McpEndpoint endpoint() {
		return McpServerService.getInstance().getEndpoint();
	}

	@AfterAll
	static void stopServer() throws Exception {
		IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(PROJECT);
		if (project.exists()) {
			project.delete(true, true, new NullProgressMonitor());
		}
		McpServerService.getInstance().stop();
		InstanceScope.INSTANCE.getNode(McpPreferences.QUALIFIER).remove(McpPreferences.KEY_PORT);
		InstanceScope.INSTANCE.getNode(McpPreferences.QUALIFIER).remove(McpPreferences.KEY_COMMAND_ROOTS);
		InstanceScope.INSTANCE.getNode(McpPreferences.QUALIFIER).flush();
	}

	@Test
	void listensOnTheConfiguredLoopbackPort() {
		assertTrue(McpServerService.getInstance().isRunning());
		assertEquals(TEST_PORT, McpServerService.getInstance().getPort());
		assertEquals("http://127.0.0.1:%d/mcp".formatted(TEST_PORT), endpoint().url());
	}

	@Test
	void writesTheDiscoveryFile() throws Exception {
		String content = Files.readString(McpServerService.getEndpointFile());
		assertTrue(content.contains(endpoint().url()), "The endpoint file should carry the URL: " + content);
		assertTrue(content.contains(endpoint().token()), "The endpoint file should carry the token");
		assertTrue(content.contains("\"listening\""), "and say that it is listening: " + content);
	}

	@Test
	void recordsThatTheServerStoppedRatherThanRemovingTheFile() throws Exception {
		McpServerService service = McpServerService.getInstance();
		try {
			service.stop();
			String content = Files.readString(McpServerService.getEndpointFile());
			// a missing file cannot be told from one that was never written, and the
			// case that matters is a self update that stops this bundle and does not
			// finish: nothing else is left to say what happened
			assertTrue(content.contains("\"stopped\""), "expected a stopped record, got " + content);
			assertFalse(content.contains("\"url\""), "a stopped record should not advertise a URL: " + content);
		} finally {
			service.start();
		}
	}

	@Test
	void rejectsRequestsWithoutTheBearerToken() throws Exception {
		assertEquals(401, post(null));
		assertEquals(401, post("Bearer not-the-token"));
	}

	@Test
	void initializesAndListsEveryRegisteredTool() throws Exception {
		try (McpSyncClient client = connect()) {
			assertNotNull(client.initialize());
			List<String> served = client.listTools().tools().stream().map(Tool::name).sorted().toList();
			List<String> registered = McpToolRegistry.getInstance().getTools().stream().map(IMcpTool::getName).sorted()
					.toList();
			assertEquals(registered, served);
		}
	}

	@Test
	void callsEveryRegisteredTool() throws Exception {
		try (McpSyncClient client = connect()) {
			client.initialize();
			for (Tool tool : client.listTools().tools()) {
				CallToolResult result = client.callTool(new CallToolRequest(tool.name(), arguments(tool.name())));
				assertFalse(result.content().isEmpty(), tool.name() + " returned no content");
				assertTrue(result.content().get(0) instanceof TextContent,
						tool.name() + " returned something other than text");
				assertNotEquals(Boolean.TRUE, result.isError(),
						tool.name() + " failed: " + ((TextContent) result.content().get(0)).text());
			}
		}
	}

	@Test
	void refusesTheMostRecentDefaultWhileASecondClientIsConnected() throws Exception {
		try (McpSyncClient first = connect(); McpSyncClient second = connect()) {
			first.initialize();
			second.initialize();

			CallToolResult result = first
					.callTool(new CallToolRequest("eclipse_get_build_status", Map.of()));

			// the registries are global, so "the most recent build" may be the other
			// client's and the wrong answer looks exactly like a right one
			assertEquals(Boolean.TRUE, result.isError(), "expected a refusal");
			String text = ((TextContent) result.content().get(0)).text();
			assertTrue(text.contains("MCP sessions"), text);
			// sessions, not clients: a client opening one per call and never ending it
			// otherwise reads as a room full of other agents
			assertTrue(text.contains("HTTP DELETE"), "the refusal should say how a session ends: " + text);
			assertTrue(text.contains("buildId"), "the refusal should name the argument to pass: " + text);
		}
	}

	@Test
	void reportsToolFailuresAsErrorResults() throws Exception {
		try (McpSyncClient client = connect()) {
			client.initialize();
			for (String name : WRITES_FILES) {
				// content only where the schema has it: an unknown argument would be
				// refused by the validator, and the refusal under test is the tool's
				Map<String, Object> arguments = "eclipse_write_file".equals(name)
						? Map.of("path", "/no-such-project/src/Nothing.java", "content", "nothing")
						: Map.of("path", "/no-such-project/src/Nothing.java");
				CallToolResult result = client.callTool(new CallToolRequest(name, arguments));
				assertEquals(Boolean.TRUE, result.isError(), name + " should report a missing file as an error");
				assertTrue(result.content().get(0) instanceof TextContent, name + " should explain itself in text");
			}
		}
	}

	@Test
	void keepsTheTokenAcrossRestarts() throws Exception {
		String before = endpoint().token();
		McpServerService.getInstance().stop();
		McpServerService.getInstance().start();
		assertEquals(before, endpoint().token(), "The token should survive a restart of the server");
	}

	@Test
	void keepsTheTokenOutOfTheRealUserScopeLocation() {
		// this test class regenerates the token, and user scope is shared by every
		// Eclipse this user runs, so without the redirect it would replace the token
		// the developer's own IDE is serving
		assertNotNull(System.getProperty("com.vogella.eclipse.mcp.tokenDirectory"),
				"The tests have to redirect the token away from ~/.eclipse; see the surefire argLine");
	}

	@Test
	void regeneratingTheTokenReplacesItAndKeepsTheServerRunning() throws Exception {
		String before = endpoint().token();
		McpServerService.getInstance().regenerateToken();
		assertNotEquals(before, endpoint().token(), "The token should have changed");
		assertTrue(McpServerService.getInstance().isRunning(), "The server should still be running");
		assertEquals(401, post("Bearer " + before), "The old token should no longer be accepted");
		assertEquals(200, post("Bearer " + endpoint().token()), "The new token should be accepted");
	}

	/**
	 * Arguments that let every tool produce a real answer rather than a "missing argument"
	 * error, so that the round trip is actually exercised.
	 */
	private static Map<String, Object> arguments(String toolName) {
		return switch (toolName) {
		case "eclipse_get_type_hierarchy", "eclipse_get_source" -> Map.of("typeName", "java.lang.Object");
		// scoped to the fixture: a binary type is searched by name, so asking for
		// references to java.lang.Object walks the whole workspace
		case "eclipse_find_references" -> Map.of("typeName", "example.Sample", "project", PROJECT);
		case "eclipse_search_types" -> Map.of("pattern", "java.lang.Object");
		case "eclipse_get_preferences" -> Map.of("qualifier", "org.eclipse.core.resources");
		// writes one entry into the test IDE's own log, which is what the tool is for
		case "eclipse_log_status" -> Map.of("message", "written by the smoke test");
		case "eclipse_refresh", "eclipse_get_classpath" -> Map.of("project", PROJECT);
		// a dry run against the fixture, so the smoke test edits nothing
		case "eclipse_edit_file" -> Map.of("path", "/" + PROJECT + "/src/example/Sample.java",
				"oldText", "class Sample", "newText", "class Sample", "dryRun", Boolean.TRUE);
		// dry run: the smoke test must not launch a JVM
		case "eclipse_run_tests" -> Map.of("project", PROJECT, "dryRun", Boolean.TRUE);
		case "eclipse_open" -> Map.of("path", SAMPLE);
		// headless, so there is no part to capture; a named one keeps the refusal specific
		case "eclipse_screenshot" -> Map.of("target", "part", "part", "org.eclipse.ui.views.ProblemView");
		// scoped to the fixture: an unscoped search for a JDK method walks the whole
		// workspace and outruns the client timeout on a slow machine
		case "eclipse_get_call_hierarchy" ->
			Map.of("typeName", "example.Sample", "methodName", "run", "project", PROJECT, "depth", 1);
		// a dry run against the sample type, so the smoke test renames nothing
		case "eclipse_rename" -> Map.of("typeName", "example.Sample", "newName", "Renamed");
		case "eclipse_set_preference" ->
			Map.of("qualifier", "org.eclipse.core.runtime", "key", "mcp.server.smoke.test", "value", "yes");
		// a dry run against a name that matches nothing, so the smoke test changes nothing
		case "eclipse_set_project_state" -> Map.of("state", "open", "namePattern", "no-such-project-*");
		case "eclipse_organize_imports", "eclipse_format" -> Map.of("path", SAMPLE);
		case "eclipse_read_file" -> Map.of("path", SAMPLE);
		// a command that does nothing, in the temporary directory the setup allows
		case "eclipse_run_command" -> Map.of("args", List.of("true"), "directory",
				System.getProperty("java.io.tmpdir"), "wait", Boolean.TRUE);
		// a dry run, so the smoke test writes nothing
		case "eclipse_write_file" -> Map.of("path", "/%s/written.txt".formatted(PROJECT), "content", "smoke\n",
				"dryRun", Boolean.TRUE);
		// resolveOnly against an empty directory: nothing is downloaded, and the
		// target platform of the test IDE stays what it was
		case "eclipse_set_target_platform" -> Map.of("file", TARGET, "resolveOnly", Boolean.TRUE, "timeoutSeconds", 60);
		// dryRun defaults true, so the smoke test rewrites nothing
		case "eclipse_remove_unused_imports" -> Map.of("path", SAMPLE);
		// dryRun defaults true, so the smoke test transforms nothing
		case "eclipse_clean_up" -> Map.of("path", SAMPLE, "cleanUps", List.of("cleanup.remove_unused_imports"));
		// dryRun defaults true, so the smoke test deletes nothing; naming the fixture
		// type keeps the refusal specific rather than a resolution failure
		case "eclipse_delete" -> Map.of("typeName", "example.Sample");
		// a dry run against a throwaway jar, so the smoke test installs nothing
		case "eclipse_install_bundle" -> Map.of("jar", SMOKE_JAR.toString(), "dryRun", Boolean.TRUE);
		// the shortest recording the schema allows, so the smoke test cannot leave one
		// running in the test JVM for half an hour
		case "eclipse_start_flight_recording" -> Map.of("durationSeconds", Integer.valueOf(1),
				"maxSizeMegabytes", Integer.valueOf(1), "settings", "default");
		// scoped to the fixture: an unscoped text search reads every file in the
		// workspace and outruns the client timeout
		case "eclipse_search_text" -> Map.of("pattern", "class", "projects", List.of(PROJECT));
		default -> Map.of();
		};
	}

	private static McpSyncClient connect() {
		HttpClientStreamableHttpTransport transport = HttpClientStreamableHttpTransport
				.builder("http://127.0.0.1:%d".formatted(TEST_PORT)).endpoint("/mcp")
				.jsonMapper(new JacksonMcpJsonMapperSupplier().get())
				.httpRequestCustomizer((builder, method, uri, body, context) -> builder.header("Authorization",
						"Bearer " + endpoint().token()))
				.build();
		return McpClient.sync(transport).jsonSchemaValidator(schemaValidator()).build();
	}

	/**
	 * The validator reads its bundled meta-schemas through the context class loader, which
	 * inside Equinox cannot see them. Clearing it makes the library fall back to its own.
	 */
	private static JsonSchemaValidator schemaValidator() {
		Thread thread = Thread.currentThread();
		ClassLoader previous = thread.getContextClassLoader();
		thread.setContextClassLoader(null);
		try {
			return new JacksonJsonSchemaValidatorSupplier().get();
		} finally {
			thread.setContextClassLoader(previous);
		}
	}

	private static int post(String authorization) throws Exception {
		HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(endpoint().url()))
				.header("Content-Type", "application/json").header("Accept", "application/json, text/event-stream")
				.POST(HttpRequest.BodyPublishers.ofString(INITIALIZE));
		if (authorization != null) {
			request.header("Authorization", authorization);
		}
		try (HttpClient client = HttpClient.newHttpClient()) {
			return client.send(request.build(), HttpResponse.BodyHandlers.ofString()).statusCode();
		}
	}
}
