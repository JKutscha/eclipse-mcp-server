package com.vogella.eclipse.mcp.p2.internal;

import java.util.Map;

import org.eclipse.core.runtime.IProgressMonitor;

import com.vogella.eclipse.mcp.core.IMcpTool;
import com.vogella.eclipse.mcp.core.McpToolResult;
import com.vogella.eclipse.mcp.core.ToolArguments;
import com.vogella.eclipse.mcp.core.json.JsonObject;

/** Reports an update or install started through the provisioning tools. */
public final class GetProvisioningStatusTool implements IMcpTool {

	@Override
	public String getName() {
		return "eclipse_get_provisioning_status"; //$NON-NLS-1$
	}

	@Override
	public String getDescription() {
		return "Reports an update or install started through eclipse_update or eclipse_install: running, done, failed or cancelled, what it changed and the previous configuration timestamp to revert to. Separate from eclipse_get_build_status because a build and an install are not the same kind of thing."; //$NON-NLS-1$
	}

	@Override
	public String getInputSchema() {
		return """
				{
				  "type": "object",
				  "properties": {
				    "operationId": {"type":"string","description":"Identifier returned by eclipse_update or eclipse_install. Omit for the most recent."}
				  },
				  "additionalProperties": false
				}"""; //$NON-NLS-1$
	}

	@Override
	public McpToolResult call(Map<String, Object> arguments, IProgressMonitor monitor) {
		String id = ToolArguments.of(arguments).getString("operationId"); //$NON-NLS-1$
		Provisioning.Operation operation = id == null ? Provisioning.findLatest() : Provisioning.find(id);
		if (operation == null) {
			if (id != null) {
				return McpToolResult.error("No provisioning operation with the id '%s'.".formatted(id)); //$NON-NLS-1$
			}
			return McpToolResult.of(new JsonObject().put("state", "none") //$NON-NLS-1$ //$NON-NLS-2$
					.put("message", "No update or install has been started.").toString()); //$NON-NLS-1$ //$NON-NLS-2$
		}
		return McpToolResult.of(operation.toJson().toString());
	}
}
