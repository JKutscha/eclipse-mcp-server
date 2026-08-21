package com.vogella.eclipse.mcp.p2.internal;

import java.util.Map;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.equinox.p2.core.IProvisioningAgent;
import org.eclipse.equinox.p2.operations.ProvisioningJob;
import org.eclipse.equinox.p2.operations.ProvisioningSession;
import org.eclipse.equinox.p2.operations.Update;
import org.eclipse.equinox.p2.operations.UpdateOperation;

import com.vogella.eclipse.mcp.core.IMcpTool;
import com.vogella.eclipse.mcp.core.McpToolResult;
import com.vogella.eclipse.mcp.core.ToolArguments;
import com.vogella.eclipse.mcp.core.json.JsonArray;
import com.vogella.eclipse.mcp.core.json.JsonObject;

/** Applies available updates to the running IDE. */
public final class UpdateTool implements IMcpTool {

	@Override
	public String getName() {
		return "eclipse_update"; //$NON-NLS-1$
	}

	@Override
	public String getDescription() {
		return "Applies the available updates to this IDE. MODIFIES THE INSTALLATION. Only units that are already installed are updated, from the repositories already configured; it installs nothing new. Runs as a job and returns an operationId to poll through eclipse_get_provisioning_status, because resolution can take minutes. THIS IS SELF UPDATING MACHINERY: if a bad build lands, the tools that would fix it are the tools that just broke. The result names the previous configuration timestamp so the installation can be reverted from Help > About > Installation Details even when this path is dead, and eclipse_restart works independently of this tool."; //$NON-NLS-1$
	}

	@Override
	public String getInputSchema() {
		return """
				{
				  "type": "object",
				  "properties": {
				    "units":          {"type":"array","items":{"type":"string"},"description":"Installed unit ids to update. Scopes the resolution to the repositories that can supply them, so a targeted update makes one network round trip instead of one per configured site. Omit to update everything that has an update."},
				    "refresh":        {"type":"boolean","default":true,"description":"Re-read the repository metadata first. Without it the update resolves against p2's cache and may find nothing to apply."},
				    "trustUnsigned":  {"type":"boolean","default":true,"description":"Accept unsigned content, or content signed by a certificate this IDE does not trust. On by default, because an install performed by this server is unattended and there is nobody to answer the dialog p2 would otherwise raise. What bounds it is the repository allowlist: only sites the IDE is already configured with can be installed from. Nothing is added to the IDE's permanent trust store, and whatever was accepted is reported. Set false to refuse unsigned content instead."},
				    "wait":           {"type":"boolean","default":false,"description":"Wait for the job. Updates are slow, so this is off by default."},
				    "timeoutSeconds": {"type":"integer","default":25,"minimum":1,"maximum":3600}
				  },
				  "additionalProperties": false
				}"""; //$NON-NLS-1$
	}

	@Override
	public McpToolResult call(Map<String, Object> arguments, IProgressMonitor monitor) {
		ToolArguments args = ToolArguments.of(arguments);
		IProvisioningAgent agent = Provisioning.agent();
		if (agent == null) {
			return McpToolResult.error("No p2 provisioning agent is available, so this IDE cannot update itself."); //$NON-NLS-1$
		}
		// refresh first for the same reason as the check tool: without it the update
		// resolves against cached metadata and finds nothing to apply
		java.util.List<String> unitIds = Provisioning.stringList(arguments, "units"); //$NON-NLS-1$
		var units = Provisioning.installedUnits(agent, unitIds);
		if (!unitIds.isEmpty() && units.isEmpty()) {
			return McpToolResult.error("None of %s is installed, so there is nothing to update.".formatted(unitIds)); //$NON-NLS-1$
		}
		java.net.URI[] locations = units.isEmpty() ? null : Provisioning.sourcesFor(agent, units, monitor);
		Provisioning.describeRepositories(agent, args.getBoolean("refresh", true), locations, monitor); //$NON-NLS-1$
		UpdateOperation operation = units.isEmpty() ? new UpdateOperation(new ProvisioningSession(agent))
				: new UpdateOperation(new ProvisioningSession(agent), units);
		operation.setProvisioningContext(Provisioning.scope(agent, locations));
		IStatus resolution = operation.resolveModal(monitor);
		Update[] possible = operation.getPossibleUpdates();
		if (possible == null || possible.length == 0) {
			return McpToolResult.of(Provisioning
					.record("update", "done", "Nothing to update; everything installed is already current.", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
							new JsonArray())
					.toJson().toString());
		}
		if (!resolution.isOK() && resolution.getSeverity() == IStatus.ERROR) {
			return McpToolResult.error("The update could not be resolved: " + resolution.getMessage()); //$NON-NLS-1$
		}
		JsonArray changes = new JsonArray();
		for (Update update : possible) {
			changes.add(new JsonObject().put("unit", update.toUpdate.getId()) //$NON-NLS-1$
					.put("fromVersion", update.toUpdate.getVersion().toString()) //$NON-NLS-1$
					.put("toVersion", update.replacement.getVersion().toString())); //$NON-NLS-1$
		}
		boolean trustUnsigned = args.getBoolean("trustUnsigned", true); //$NON-NLS-1$
		HeadlessTrust trust = new HeadlessTrust(trustUnsigned);
		Object previousTrust = HeadlessTrust.install(agent, trust);
		ProvisioningJob job = operation.getProvisioningJob(null);
		if (job == null) {
			HeadlessTrust.restore(agent, previousTrust);
			return McpToolResult.error("p2 produced no provisioning job for the resolved update."); //$NON-NLS-1$
		}
		Provisioning.Operation handle = Provisioning.start("update", tracked -> { //$NON-NLS-1$
			Provisioning.setChanges(tracked, changes);
			return job;
		});
		Provisioning.onFinished(handle, () -> {
			Provisioning.setTrust(handle, trust, trustUnsigned);
			HeadlessTrust.restore(agent, previousTrust);
		});
		if (args.getBoolean("wait", false)) { //$NON-NLS-1$
			try {
				handle.await(args.getInt("timeoutSeconds", 25, 1, 3600)); //$NON-NLS-1$
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		}
		return McpToolResult.of(handle.toJson().toString());
	}
}
