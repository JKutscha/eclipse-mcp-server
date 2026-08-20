package com.vogella.eclipse.mcp.p2.internal;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.jobs.IJobChangeEvent;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.core.runtime.jobs.JobChangeAdapter;
import org.eclipse.equinox.p2.core.IProvisioningAgent;
import org.eclipse.equinox.p2.engine.IProfileRegistry;
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
					.put("previousConfiguration", previousConfiguration); //$NON-NLS-1$
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

	static synchronized Operation find(String id) {
		return OPERATIONS.get(id);
	}

	static synchronized Operation findLatest() {
		return lastId == null ? null : OPERATIONS.get(lastId);
	}

	static void setChanges(Operation operation, JsonArray changes) {
		operation.changes = changes;
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
