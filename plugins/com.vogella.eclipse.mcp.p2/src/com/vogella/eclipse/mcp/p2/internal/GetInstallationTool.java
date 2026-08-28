package com.vogella.eclipse.mcp.p2.internal;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.eclipse.core.runtime.IProduct;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.Platform;
import org.eclipse.equinox.p2.core.IProvisioningAgent;
import org.eclipse.equinox.p2.engine.IProfile;
import org.eclipse.equinox.p2.engine.IProfileRegistry;
import org.eclipse.equinox.p2.metadata.IInstallableUnit;
import org.eclipse.equinox.p2.query.QueryUtil;
import org.osgi.framework.Bundle;

import com.vogella.eclipse.mcp.core.IMcpTool;
import com.vogella.eclipse.mcp.core.McpToolException;
import com.vogella.eclipse.mcp.core.McpToolResult;
import com.vogella.eclipse.mcp.core.ToolArguments;
import com.vogella.eclipse.mcp.core.json.JsonArray;
import com.vogella.eclipse.mcp.core.json.JsonObject;

/**
 * Reports what is installed in this IDE and at which version.
 */
public final class GetInstallationTool implements IMcpTool {

	private static final int DEFAULT_MAX_RESULTS = 100;

	@Override
	public String getName() {
		return "eclipse_get_installation"; //$NON-NLS-1$
	}

	@Override
	public String getDescription() {
		return "Reports the product, the installed feature groups with their versions, and the configuration timestamps this installation can be reverted to. Changes nothing. This is what confirms that an install or an update actually landed, and it answers 'which version of the MCP server is this IDE running', neither of which the other tools can: eclipse_check_for_updates only reports units that HAVE an update, so it says nothing at all when everything is current, and eclipse_get_bundle_info describes the active TARGET PLATFORM rather than the running installation, which looks like the same question and is not. Use 'filter' to narrow the list, because an SDK installs a lot of feature groups. currentTimestamp is the one eclipse_update reports as previousConfiguration, so a caller can name what a revert would go back to."; //$NON-NLS-1$
	}

	@Override
	public String getInputSchema() {
		return """
				{
				  "type": "object",
				  "properties": {
				    "filter":     {"type":"string","description":"Only feature groups whose id or name contains this text, case insensitive, e.g. 'vogella' or 'jdt'. The total counts everything installed, not only what matched."},
				    "maxResults": {"type":"integer","default":100,"minimum":1,"maximum":2000},
				    "timestamps": {"type":"boolean","default":false,"description":"Also list the configuration timestamps that can be reverted to, newest first. Off by default because an IDE that is updated often has many."}
				  },
				  "additionalProperties": false
				}"""; //$NON-NLS-1$
	}

	@Override
	public McpToolResult call(Map<String, Object> arguments, IProgressMonitor monitor) throws McpToolException {
		ToolArguments args = ToolArguments.of(arguments);
		String filter = args.getString("filter"); //$NON-NLS-1$
		int maxResults = args.getInt("maxResults", DEFAULT_MAX_RESULTS, 1, 2000); //$NON-NLS-1$
		IProvisioningAgent agent = Provisioning.agent();
		if (agent == null) {
			return McpToolResult.error("This IDE has no provisioning agent, so it cannot report its installation."); //$NON-NLS-1$
		}
		IProfileRegistry registry = agent.getService(IProfileRegistry.class);
		if (registry == null) {
			return McpToolResult.error("This IDE has no profile registry, so it cannot report its installation."); //$NON-NLS-1$
		}
		IProfile profile = registry.getProfile(IProfileRegistry.SELF);
		if (profile == null) {
			return McpToolResult.error(
					"This IDE has no self profile, which is normal for one started from a development launch rather than installed."); //$NON-NLS-1$
		}

		String needle = filter == null ? null : filter.toLowerCase(Locale.ROOT);
		List<IInstallableUnit> matched = new ArrayList<>();
		int total = 0;
		for (IInstallableUnit unit : profile.query(QueryUtil.createIUGroupQuery(), monitor)) {
			total++;
			if (needle == null || contains(unit, needle)) {
				matched.add(unit);
			}
		}
		matched.sort((left, right) -> left.getId().compareToIgnoreCase(right.getId()));

		JsonArray features = new JsonArray();
		for (IInstallableUnit unit : matched) {
			if (features.size() >= maxResults) {
				break;
			}
			features.add(new JsonObject().put("id", unit.getId()) //$NON-NLS-1$
					.put("version", unit.getVersion().toString()) //$NON-NLS-1$
					.put("name", unit.getProperty(IInstallableUnit.PROP_NAME, null))); //$NON-NLS-1$
		}

		JsonObject result = new JsonObject().put("product", product()) //$NON-NLS-1$
				.put("profile", profile.getProfileId()) //$NON-NLS-1$
				.put("currentTimestamp", Long.valueOf(profile.getTimestamp())) //$NON-NLS-1$
				.put("total", Integer.valueOf(total)) //$NON-NLS-1$
				.put("matched", Integer.valueOf(matched.size())) //$NON-NLS-1$
				.put("truncated", Boolean.valueOf(matched.size() > features.size())) //$NON-NLS-1$
				.put("features", features); //$NON-NLS-1$
		if (args.getBoolean("timestamps", false)) { //$NON-NLS-1$
			JsonArray stamps = new JsonArray();
			long[] values = registry.listProfileTimestamps(IProfileRegistry.SELF);
			if (values != null) {
				for (int i = values.length - 1; i >= 0; i--) {
					stamps.add(new JsonObject().put("timestamp", Long.valueOf(values[i])) //$NON-NLS-1$
							.put("when", new Date(values[i]).toString())); //$NON-NLS-1$
				}
			}
			result.put("revertPoints", stamps); //$NON-NLS-1$
		}
		return McpToolResult.of(result.toString());
	}

	private static boolean contains(IInstallableUnit unit, String needle) {
		if (unit.getId().toLowerCase(Locale.ROOT).contains(needle)) {
			return true;
		}
		String name = unit.getProperty(IInstallableUnit.PROP_NAME, null);
		return name != null && name.toLowerCase(Locale.ROOT).contains(needle);
	}

	/** The product this IDE runs, and the version of the bundle that defines it. */
	private static JsonObject product() {
		IProduct product = Platform.getProduct();
		if (product == null) {
			return null;
		}
		Bundle defining = product.getDefiningBundle();
		return new JsonObject().put("id", product.getId()) //$NON-NLS-1$
				.put("name", product.getName()) //$NON-NLS-1$
				.put("definingBundle", defining == null ? null : defining.getSymbolicName()) //$NON-NLS-1$
				.put("version", defining == null ? null : defining.getVersion().toString()); //$NON-NLS-1$
	}
}
