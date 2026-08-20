package com.vogella.eclipse.mcp.p2.internal;

import java.util.Map;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.equinox.p2.core.IProvisioningAgent;
import org.eclipse.equinox.p2.operations.ProvisioningSession;
import org.eclipse.equinox.p2.operations.Update;
import org.eclipse.equinox.p2.operations.UpdateOperation;

import com.vogella.eclipse.mcp.core.IMcpTool;
import com.vogella.eclipse.mcp.core.McpToolResult;
import com.vogella.eclipse.mcp.core.json.JsonArray;
import com.vogella.eclipse.mcp.core.json.JsonObject;

/** Reports which installed units have updates, changing nothing. */
public final class CheckForUpdatesTool implements IMcpTool {

	@Override
	public String getName() {
		return "eclipse_check_for_updates"; //$NON-NLS-1$
	}

	@Override
	public String getDescription() {
		return "Reports which installed units have an update available, from which version to which version and from which repository. Changes nothing. Resolution contacts every configured update site, so it can take a while on a slow mirror."; //$NON-NLS-1$
	}

	@Override
	public String getInputSchema() {
		return """
				{"type":"object","properties":{},"additionalProperties":false}"""; //$NON-NLS-1$
	}

	@Override
	public McpToolResult call(Map<String, Object> arguments, IProgressMonitor monitor) {
		IProvisioningAgent agent = Provisioning.agent();
		if (agent == null) {
			return McpToolResult.error(
					"No p2 provisioning agent is available. This IDE was probably not installed through p2, so it cannot update itself."); //$NON-NLS-1$
		}
		UpdateOperation operation = new UpdateOperation(new ProvisioningSession(agent));
		IStatus status = operation.resolveModal(monitor);
		JsonObject result = new JsonObject().put("resolution", status.isOK() ? "ok" : status.getMessage()); //$NON-NLS-1$ //$NON-NLS-2$
		JsonArray updates = new JsonArray();
		Update[] possible = operation.getPossibleUpdates();
		if (possible != null) {
			for (Update update : possible) {
				updates.add(new JsonObject().put("unit", update.toUpdate.getId()) //$NON-NLS-1$
						.put("fromVersion", update.toUpdate.getVersion().toString()) //$NON-NLS-1$
						.put("toVersion", update.replacement.getVersion().toString()) //$NON-NLS-1$
						.put("name", update.replacement.getProperty("org.eclipse.equinox.p2.name", null))); //$NON-NLS-1$ //$NON-NLS-2$
			}
		}
		JsonArray repositories = new JsonArray();
		Provisioning.knownRepositories(agent).forEach(uri -> repositories.add(uri.toString()));
		return McpToolResult.of(result.put("total", updates.size()) //$NON-NLS-1$
				.put("updates", updates) //$NON-NLS-1$
				.put("configuredRepositories", repositories) //$NON-NLS-1$
				.toString());
	}
}
