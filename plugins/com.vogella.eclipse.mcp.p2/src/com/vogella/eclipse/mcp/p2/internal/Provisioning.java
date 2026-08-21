package com.vogella.eclipse.mcp.p2.internal;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.OperationCanceledException;
import org.eclipse.core.runtime.jobs.IJobChangeEvent;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.core.runtime.jobs.JobChangeAdapter;
import org.eclipse.equinox.p2.core.IProvisioningAgent;
import org.eclipse.equinox.p2.engine.IProfileRegistry;
import org.eclipse.equinox.p2.engine.ProvisioningContext;
import org.eclipse.equinox.p2.repository.metadata.IMetadataRepositoryManager;
import org.osgi.framework.BundleContext;
import org.osgi.framework.FrameworkUtil;

import com.vogella.eclipse.mcp.core.json.JsonArray;
import com.vogella.eclipse.mcp.core.json.JsonObject;

/**
 * Shared plumbing for the provisioning tools: the agent, the repository
 * allowlist and the job handles.
 */
final class Provisioning {

	private static final AtomicLong IDS = new AtomicLong();

	private static final Map<String, Operation> OPERATIONS = new LinkedHashMap<>() {
		private static final long serialVersionUID = 1L;

		@Override
		protected boolean removeEldestEntry(Map.Entry<String, Operation> eldest) {
			return size() > 10 && !eldest.getValue().running;
		}
	};

	private static String lastId;

	private Provisioning() {
	}

	/** One provisioning job, polled through {@code eclipse_get_provisioning_status}. */
	static final class Operation {

		private final String id;
		private final String kind;
		private final long startedAt = System.currentTimeMillis();
		private final CountDownLatch finished = new CountDownLatch(1);

		private volatile boolean running = true;
		private volatile long endedAt;
		private volatile String state = "running"; //$NON-NLS-1$
		private volatile String message;
		private volatile JsonArray changes = new JsonArray();
		private volatile String previousConfiguration;
		private volatile JsonArray refusedTrust = new JsonArray();
		private volatile boolean trustedUnsigned;

		Operation(String id, String kind) {
			this.id = id;
			this.kind = kind;
		}

		boolean await(long seconds) throws InterruptedException {
			return finished.await(seconds, TimeUnit.SECONDS);
		}

		JsonObject toJson() {
			JsonObject json = new JsonObject().put("operationId", id) //$NON-NLS-1$
					.put("kind", kind) //$NON-NLS-1$
					.put("state", state) //$NON-NLS-1$
					.put("elapsedMillis", (endedAt == 0 ? System.currentTimeMillis() : endedAt) - startedAt)
					.put("changes", changes) //$NON-NLS-1$
					.put("message", message) //$NON-NLS-1$
					.put("previousConfiguration", previousConfiguration) //$NON-NLS-1$
					.put("trustedUnsigned", trustedUnsigned) //$NON-NLS-1$
					.put("refusedTrust", refusedTrust); //$NON-NLS-1$
			if (refusedTrust.size() > 0 && !trustedUnsigned) {
				json.put("blockedBy", //$NON-NLS-1$
						"p2 asked whether to trust content that is unsigned or signed by an untrusted certificate, and this server answered no rather than raising a dialog nobody may be there to click. Pass trustUnsigned to accept it for this one call, after checking what refusedTrust names. Signing the artifacts on the update site removes the question for every consumer instead."); //$NON-NLS-1$
			}
			if ("done".equals(state)) { //$NON-NLS-1$
				json.put("restartRequired", true) //$NON-NLS-1$
						.put("note", //$NON-NLS-1$
								"The new code is not active until the IDE restarts. Use eclipse_restart, which works independently of this tool, so a half applied update can still be recovered. If the update turns out to be bad, revert from Help > About > Installation Details > Installation History using the previousConfiguration timestamp above."); //$NON-NLS-1$
			}
			return json;
		}
	}

	static synchronized Operation start(String kind, java.util.function.Function<Operation, Job> jobFactory) {
		String id = "provisioning-" + IDS.incrementAndGet(); //$NON-NLS-1$
		Operation operation = new Operation(id, kind);
		operation.previousConfiguration = currentConfiguration();
		OPERATIONS.put(id, operation);
		lastId = id;
		Job job = jobFactory.apply(operation);
		job.addJobChangeListener(new JobChangeAdapter() {
			@Override
			public void done(IJobChangeEvent event) {
				IStatus status = event.getResult();
				operation.state = status == null || status.isOK() ? "done" //$NON-NLS-1$
						: status.getSeverity() == IStatus.CANCEL ? "cancelled" : "failed"; //$NON-NLS-1$ //$NON-NLS-2$
				if (status != null && !status.isOK()) {
					operation.message = status.getMessage();
				}
				operation.endedAt = System.currentTimeMillis();
				operation.running = false;
				operation.finished.countDown();
			}
		});
		job.schedule();
		return operation;
	}

	/** Records a finished operation that never needed a job, such as a failed resolve. */
	static synchronized Operation record(String kind, String state, String message, JsonArray changes) {
		String id = "provisioning-" + IDS.incrementAndGet(); //$NON-NLS-1$
		Operation operation = new Operation(id, kind);
		operation.previousConfiguration = currentConfiguration();
		operation.state = state;
		operation.message = message;
		operation.changes = changes == null ? new JsonArray() : changes;
		operation.running = false;
		operation.endedAt = System.currentTimeMillis();
		operation.finished.countDown();
		OPERATIONS.put(id, operation);
		lastId = id;
		return operation;
	}

	/** Runs {@code after} once the operation's job has finished, however it ended. */
	static void onFinished(Operation operation, Runnable after) {
		Thread waiter = new Thread(() -> {
			try {
				operation.finished.await();
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
			after.run();
		}, "MCP provisioning cleanup " + operation.id); //$NON-NLS-1$
		waiter.setDaemon(true);
		waiter.start();
	}

	static synchronized Operation find(String id) {
		return OPERATIONS.get(id);
	}

	static synchronized Operation findLatest() {
		return lastId == null ? null : OPERATIONS.get(lastId);
	}

	static void setChanges(Operation operation, JsonArray changes) {
		operation.changes = changes;
	}

	/** Records what p2 asked about, so a refusal is visible instead of looking like a slow download. */
	static void setTrust(Operation operation, HeadlessTrust trust, boolean trustUnsigned) {
		JsonArray refused = new JsonArray();
		trust.refused().forEach(refused::add);
		operation.refusedTrust = refused;
		operation.trustedUnsigned = trustUnsigned && trust.prompted();
	}

	static IProvisioningAgent agent() {
		BundleContext context = FrameworkUtil.getBundle(Provisioning.class).getBundleContext();
		if (context == null) {
			return null;
		}
		var reference = context.getServiceReference(IProvisioningAgent.class);
		return reference == null ? null : context.getService(reference);
	}

	/**
	 * The repositories the IDE is already configured with.
	 * <p>
	 * Installing from an arbitrary URL fetches and runs code from the network, which
	 * is a larger step than anything else here does, so the allowlist is what the
	 * user already trusted rather than a switch that gets turned on once.
	 */
	static List<URI> knownRepositories(IProvisioningAgent agent) {
		IMetadataRepositoryManager manager = agent.getService(IMetadataRepositoryManager.class);
		if (manager == null) {
			return List.of();
		}
		List<URI> known = new ArrayList<>();
		for (URI uri : manager.getKnownRepositories(IMetadataRepositoryManager.REPOSITORIES_ALL)) {
			known.add(uri);
		}
		return known;
	}

	/**
	 * Re-reads the configured repositories and reports the state of each.
	 * <p>
	 * p2 caches repository metadata, and a cached miss is reported as "no updates
	 * found", which is exactly what a genuinely current IDE reports. That makes a
	 * stale cache invisible in the one workflow these tools exist for. The composite
	 * document itself has to be re-read, because a release replaces the child rather
	 * than adding one.
	 */
	private static JsonArray describe(IProvisioningAgent agent, boolean refresh, List<URI> locations,
			IProgressMonitor monitor) {
		// the metadata manager only. An update check never reads artifact metadata, and
		// refreshing both is what makes a check cost twice what it needs to
		IMetadataRepositoryManager manager = agent.getService(IMetadataRepositoryManager.class);
		JsonArray reported = new JsonArray();
		if (manager == null) {
			return reported;
		}
		for (URI uri : locations) {
			JsonObject entry = new JsonObject().put("uri", uri.toString()); //$NON-NLS-1$
			if (refresh) {
				try {
					manager.refreshRepository(uri, monitor);
					entry.put("refreshed", Boolean.TRUE); //$NON-NLS-1$
				} catch (org.eclipse.equinox.p2.core.ProvisionException | OperationCanceledException e) {
					entry.put("refreshed", Boolean.FALSE).put("error", e.getMessage()); //$NON-NLS-1$ //$NON-NLS-2$
				}
			} else {
				entry.put("refreshed", Boolean.FALSE); //$NON-NLS-1$
			}
			String timestamp = manager.getRepositoryProperty(uri, org.eclipse.equinox.p2.repository.IRepository.PROP_TIMESTAMP);
			entry.put("timestamp", timestamp == null ? null : stamp(timestamp)); //$NON-NLS-1$
			reported.add(entry);
		}
		return reported;
	}

	private static String stamp(String millis) {
		try {
			return millis + " (" + new java.util.Date(Long.parseLong(millis)) + ")"; //$NON-NLS-1$ //$NON-NLS-2$
		} catch (NumberFormatException e) {
			return millis;
		}
	}

	static List<String> stringList(Map<String, Object> arguments, String name) {
		List<String> values = new ArrayList<>();
		if (arguments != null && arguments.get(name) instanceof List<?> list) {
			for (Object entry : list) {
				String value = String.valueOf(entry).trim();
				if (!value.isEmpty()) {
					values.add(value);
				}
			}
		}
		return values;
	}

	/** The installed units matching {@code ids}, for scoping an update to named things. */
	static java.util.Set<org.eclipse.equinox.p2.metadata.IInstallableUnit> installedUnits(IProvisioningAgent agent,
			List<String> ids) {
		IProfileRegistry registry = agent.getService(IProfileRegistry.class);
		var profile = registry == null ? null : registry.getProfile(IProfileRegistry.SELF);
		java.util.Set<org.eclipse.equinox.p2.metadata.IInstallableUnit> units = new java.util.LinkedHashSet<>();
		if (profile == null) {
			return units;
		}
		for (String id : ids) {
			profile.query(org.eclipse.equinox.p2.query.QueryUtil.createIUQuery(id), null).forEach(units::add);
		}
		return units;
	}

	/**
	 * The repositories that can supply {@code units}, resolved through composite
	 * children and references.
	 * <p>
	 * Asking p2 which locations matter beats refreshing every configured site: an
	 * IDE with a dozen of them pays a network round trip for each, and a targeted
	 * check only ever needed one.
	 */
	static URI[] sourcesFor(IProvisioningAgent agent,
			java.util.Collection<org.eclipse.equinox.p2.metadata.IInstallableUnit> units, IProgressMonitor monitor) {
		if (units.isEmpty()) {
			return null;
		}
		ProvisioningContext context = new ProvisioningContext(agent);
		Map<URI, java.util.Set<org.eclipse.equinox.p2.metadata.IInstallableUnit>> sources = context
				.getInstallableUnitSources(units, monitor);
		return sources == null || sources.isEmpty() ? null : sources.keySet().toArray(URI[]::new);
	}

	/**
	 * Restricts an operation to {@code locations}, or leaves it workspace wide when
	 * {@code locations} is null. Only the named repositories are loaded at all.
	 */
	static ProvisioningContext scope(IProvisioningAgent agent, URI[] locations) {
		ProvisioningContext context = new ProvisioningContext(agent);
		if (locations != null && locations.length > 0) {
			context.setMetadataRepositories(locations);
		}
		return context;
	}

	/** Refreshes just {@code locations}, or every configured repository when null. */
	static JsonArray describeRepositories(IProvisioningAgent agent, boolean refresh, URI[] locations,
			IProgressMonitor monitor) {
		List<URI> scoped = locations == null ? knownRepositories(agent) : List.of(locations);
		return describe(agent, refresh, scoped, monitor);
	}

	/** The timestamp of the current configuration, which is the revert point. */
	private static String currentConfiguration() {
		IProvisioningAgent agent = agent();
		if (agent == null) {
			return null;
		}
		IProfileRegistry registry = agent.getService(IProfileRegistry.class);
		if (registry == null) {
			return null;
		}
		long[] timestamps = registry.listProfileTimestamps(IProfileRegistry.SELF);
		if (timestamps == null || timestamps.length == 0) {
			return null;
		}
		long latest = timestamps[timestamps.length - 1];
		return latest + " (" + new java.util.Date(latest) + ")"; //$NON-NLS-1$ //$NON-NLS-2$
	}
}
