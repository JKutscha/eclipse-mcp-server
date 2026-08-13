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
import java.util.List;
import java.util.Map;

import org.eclipse.core.runtime.preferences.InstanceScope;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.osgi.service.prefs.BackingStoreException;

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

	/** An initialize request answers 200 without a session, which makes it a usable probe. */
	private static final String INITIALIZE = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":"
			+ "{\"protocolVersion\":\"2025-06-18\",\"capabilities\":{},"
			+ "\"clientInfo\":{\"name\":\"probe\",\"version\":\"1\"}}}";

	@BeforeAll
	static void startServer() throws Exception {
		InstanceScope.INSTANCE.getNode(McpPreferences.QUALIFIER).putInt(McpPreferences.KEY_PORT, TEST_PORT);
		McpServerService.getInstance().start();
		assertNotNull(endpoint(), "The server did not report an endpoint");
	}

	/** Read live, because regenerating the token replaces it. */
	private static McpEndpoint endpoint() {
		return McpServerService.getInstance().getEndpoint();
	}

	@AfterAll
	static void stopServer() throws BackingStoreException {
		McpServerService.getInstance().stop();
		InstanceScope.INSTANCE.getNode(McpPreferences.QUALIFIER).remove(McpPreferences.KEY_PORT);
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
	void regeneratingTheTokenReplacesItAndKeepsTheServerRunning() throws Exception {
		String before = endpoint().token();
		McpServerService.getInstance().regenerateToken();
		assertNotEquals(before, endpoint().token(), "The token should have changed");
		assertTrue(McpServerService.getInstance().isRunning(), "The server should still be running");
		assertEquals(401, post("Bearer " + before), "The old token should no longer be accepted");
		assertEquals(200, post("Bearer " + endpoint().token()), "The new token should be accepted");
	}

	/** Arguments that let every tool produce a result rather than a "missing argument" error. */
	private static Map<String, Object> arguments(String toolName) {
		return switch (toolName) {
		case "eclipse_find_references", "eclipse_get_type_hierarchy" -> Map.of("typeName", "java.lang.Object");
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
