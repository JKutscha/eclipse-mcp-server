package com.vogella.eclipse.mcp.server.internal;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.NullProgressMonitor;

import com.vogella.eclipse.mcp.core.IMcpTool;
import com.vogella.eclipse.mcp.core.McpToolResult;

import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Tool;

/**
 * Presents an {@link IMcpTool} as an MCP tool, under a hard call timeout.
 */
public final class McpToolAdapter {

	static final Duration CALL_TIMEOUT = Duration.ofSeconds(30);

	private McpToolAdapter() {
	}

	public static SyncToolSpecification toSpecification(IMcpTool tool, McpJsonMapper jsonMapper,
			ExecutorService executor) {
		Tool descriptor = Tool.builder().name(tool.getName()).description(tool.getDescription())
				.inputSchema(jsonMapper, tool.getInputSchema()).build();
		return SyncToolSpecification.builder().tool(descriptor)
				.callHandler((exchange, request) -> call(tool, request.arguments(), executor)).build();
	}

	private static CallToolResult call(IMcpTool tool, Map<String, Object> arguments, ExecutorService executor) {
		NullProgressMonitor monitor = new NullProgressMonitor();
		Future<McpToolResult> pending = executor
				.submit(() -> tool.call(arguments == null ? Map.of() : arguments, monitor));
		try {
			McpToolResult result = pending.get(CALL_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
			return CallToolResult.builder().addTextContent(result.text()).isError(result.isError()).build();
		} catch (TimeoutException e) {
			monitor.setCanceled(true);
			pending.cancel(true);
			return error("The tool '%s' did not finish within %d seconds.".formatted(tool.getName(), //$NON-NLS-1$
					CALL_TIMEOUT.toSeconds()));
		} catch (InterruptedException e) {
			pending.cancel(true);
			Thread.currentThread().interrupt();
			return error("The call to '%s' was interrupted.".formatted(tool.getName())); //$NON-NLS-1$
		} catch (ExecutionException e) {
			Throwable cause = e.getCause() == null ? e : e.getCause();
			ILog.get().error("The MCP tool '%s' failed".formatted(tool.getName()), cause); //$NON-NLS-1$
			return error("The tool '%s' failed: %s".formatted(tool.getName(), cause.getMessage())); //$NON-NLS-1$
		}
	}

	private static CallToolResult error(String message) {
		return CallToolResult.builder().addTextContent(message).isError(Boolean.TRUE).build();
	}
}
