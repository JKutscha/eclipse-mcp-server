package com.vogella.eclipse.mcp.ui.internal;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import com.vogella.eclipse.mcp.core.json.JsonArray;
import com.vogella.eclipse.mcp.core.json.JsonObject;

/**
 * Samples thread stacks on a daemon thread, so that a freeze can be diagnosed.
 * <p>
 * Nothing here touches the UI thread or takes a workspace lock: a profiler that
 * queues behind the freeze is useless for the one case it exists for.
 * {@link ThreadMXBean} does not need the sampled thread to be responsive.
 */
public final class SamplingRegistry {

	private static final SamplingRegistry INSTANCE = new SamplingRegistry();

	private final AtomicLong ids = new AtomicLong();

	private final Map<String, Session> sessions = new LinkedHashMap<>() {
		private static final long serialVersionUID = 1L;

		@Override
		protected boolean removeEldestEntry(Map.Entry<String, Session> eldest) {
			return size() > 10 && !eldest.getValue().running;
		}
	};

	public static SamplingRegistry getInstance() {
		return INSTANCE;
	}

	private SamplingRegistry() {
	}

	/** One sampling run. */
	public static final class Session {

		private final String id;
		private final long[] threadIds;
		private final int intervalMillis;
		private final int maxSamples;
		private final int maxDepth;
		private final long startedAt = System.currentTimeMillis();
		private final List<StackTraceElement[]> samples = new ArrayList<>();

		private volatile boolean running = true;
		private volatile long endedAt;
		private volatile int ticks;
		private volatile boolean stoppedByBudget;
		private Thread sampler;

		Session(String id, long[] threadIds, int intervalMillis, int maxSamples, int maxDepth) {
			this.id = id;
			this.threadIds = threadIds;
			this.intervalMillis = intervalMillis;
			this.maxSamples = maxSamples;
			this.maxDepth = maxDepth;
		}

		public String id() {
			return id;
		}

		public boolean running() {
			return running;
		}

		public int sampleCount() {
			synchronized (samples) {
				return samples.size();
			}
		}

		/** Sampling rounds. One tick produces one stack per sampled thread. */
		public int ticks() {
			return ticks;
		}

		public boolean stoppedByBudget() {
			return stoppedByBudget;
		}

		public long elapsedMillis() {
			return (endedAt == 0 ? System.currentTimeMillis() : endedAt) - startedAt;
		}

		public int intervalMillis() {
			return intervalMillis;
		}

		void run() {
			ThreadMXBean threads = ManagementFactory.getThreadMXBean();
			while (running) {
				ThreadInfo[] infos = threads.getThreadInfo(threadIds, maxDepth);
				synchronized (samples) {
					for (ThreadInfo info : infos) {
						if (info != null && info.getStackTrace().length > 0) {
							samples.add(info.getStackTrace());
						}
					}
					ticks++;
					// the budget counts rounds, not stacks. Counting stacks meant that
					// with 70 live threads a 200 budget ended after three rounds, long
					// before the operation being profiled had got going
					if (ticks >= maxSamples) {
						running = false;
						stoppedByBudget = true;
					}
				}
				try {
					Thread.sleep(intervalMillis);
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					running = false;
				}
			}
			endedAt = System.currentTimeMillis();
		}

		void stop() {
			running = false;
			if (sampler != null) {
				sampler.interrupt();
			}
			if (endedAt == 0) {
				endedAt = System.currentTimeMillis();
			}
		}

		List<StackTraceElement[]> snapshot() {
			synchronized (samples) {
				return List.copyOf(samples);
			}
		}
	}

	public synchronized Session start(long[] threadIds, int intervalMillis, int maxSamples, int maxDepth) {
		String id = "sampling-" + ids.incrementAndGet(); //$NON-NLS-1$
		Session session = new Session(id, threadIds, intervalMillis, maxSamples, maxDepth);
		sessions.put(id, session);
		Thread sampler = new Thread(session::run, "MCP stack sampler " + id); //$NON-NLS-1$
		sampler.setDaemon(true);
		// below normal, so that sampling never competes with the work being measured
		sampler.setPriority(Thread.NORM_PRIORITY - 1);
		session.sampler = sampler;
		sampler.start();
		return session;
	}

	public synchronized Session find(String id) {
		return sessions.get(id);
	}

	public synchronized Session findLatest() {
		Session latest = null;
		for (Session session : sessions.values()) {
			latest = session;
		}
		return latest;
	}

	/**
	 * Aggregates the samples rather than returning them.
	 * <p>
	 * A hundred samples of seventy frames is seven thousand lines, which is unusable
	 * in a model context window and is the same mistake as an uncapped screenshot.
	 */
	public static JsonObject aggregate(Session session, int topMethods, int minSamples, boolean includeRaw,
			boolean includeIdle) {
		List<StackTraceElement[]> all = session.snapshot();
		int idle = 0;
		List<StackTraceElement[]> samples = new ArrayList<>();
		for (StackTraceElement[] sample : all) {
			if (isIdle(sample)) {
				idle++;
				if (includeIdle) {
					samples.add(sample);
				}
			} else {
				samples.add(sample);
			}
		}
		JsonObject result = new JsonObject().put("sessionId", session.id()) //$NON-NLS-1$
				.put("running", session.running()) //$NON-NLS-1$
				.put("ticks", session.ticks()) //$NON-NLS-1$
				.put("samples", samples.size()) //$NON-NLS-1$
				.put("idleSamplesExcluded", includeIdle ? 0 : idle) //$NON-NLS-1$
				.put("intervalMillis", session.intervalMillis()) //$NON-NLS-1$
				.put("elapsedMillis", session.elapsedMillis());
		if (session.stoppedByBudget()) {
			result.put("note", //$NON-NLS-1$
					"Sampling stopped on its own after %d ticks because maxSamples was reached, %d ms in. If that is shorter than the operation you meant to profile, raise maxSamples." //$NON-NLS-1$
							.formatted(session.ticks(), session.elapsedMillis()));
		}
		if (samples.isEmpty()) {
			return result.put("note", idle > 0 //$NON-NLS-1$
					? "Every sample was a thread parked or waiting. Nothing was running; sample the ui thread to profile UI work." //$NON-NLS-1$
					: "No samples were taken. The threads may have been idle or already gone."); //$NON-NLS-1$
		}

		// self time: the innermost frame is where the thread actually was
		Map<String, Integer> self = new LinkedHashMap<>();
		Map<String, Integer> total = new LinkedHashMap<>();
		for (StackTraceElement[] sample : samples) {
			self.merge(frame(sample[0]), 1, Integer::sum);
			java.util.Set<String> seen = new java.util.LinkedHashSet<>();
			for (StackTraceElement element : sample) {
				if (seen.add(frame(element))) {
					total.merge(frame(element), 1, Integer::sum);
				}
			}
		}
		result.put("topBySelfTime", top(self, topMethods, samples.size())); //$NON-NLS-1$
		result.put("topByPresence", top(total, topMethods, samples.size())); //$NON-NLS-1$
		result.put("tree", tree(samples, minSamples)); //$NON-NLS-1$
		if (includeRaw) {
			JsonArray raw = new JsonArray();
			for (StackTraceElement[] sample : samples) {
				JsonArray frames = new JsonArray();
				for (StackTraceElement element : sample) {
					frames.add(frame(element));
				}
				raw.add(frames);
			}
			result.put("rawSamples", raw); //$NON-NLS-1$
		}
		return result;
	}

	/**
	 * A stack parked in a pool is not where time is going. Without this, threads:all
	 * on an IDE with seventy pooled threads reports Unsafe.park as the hot frame.
	 */
	private static boolean isIdle(StackTraceElement[] sample) {
		String innermost = sample[0].getClassName() + '.' + sample[0].getMethodName();
		return innermost.equals("jdk.internal.misc.Unsafe.park") //$NON-NLS-1$
				|| innermost.equals("java.lang.Object.wait0") //$NON-NLS-1$
				|| innermost.equals("java.lang.Object.wait") //$NON-NLS-1$
				|| innermost.equals("java.lang.Thread.sleep0") //$NON-NLS-1$
				|| innermost.equals("java.lang.Thread.sleep") //$NON-NLS-1$
				|| innermost.startsWith("sun.nio.ch.EPoll.wait") //$NON-NLS-1$
				|| innermost.startsWith("sun.nio.ch.Net.poll"); //$NON-NLS-1$
	}

	private static JsonArray top(Map<String, Integer> counts, int limit, int samples) {
		List<Map.Entry<String, Integer>> sorted = new ArrayList<>(counts.entrySet());
		sorted.sort(Map.Entry.<String, Integer>comparingByValue().reversed()
				.thenComparing(Comparator.comparing(Map.Entry::getKey)));
		JsonArray array = new JsonArray();
		for (Map.Entry<String, Integer> entry : sorted.subList(0, Math.min(limit, sorted.size()))) {
			array.add(new JsonObject().put("frame", entry.getKey()) //$NON-NLS-1$
					.put("samples", entry.getValue()) //$NON-NLS-1$
					.put("percent", Math.round(entry.getValue() * 1000.0 / samples) / 10.0)); //$NON-NLS-1$
		}
		return array;
	}

	/** Merges the samples into one call tree, outermost frame first. */
	private static JsonArray tree(List<StackTraceElement[]> samples, int minSamples) {
		Node root = new Node("");  //$NON-NLS-1$
		for (StackTraceElement[] sample : samples) {
			Node current = root;
			for (int i = sample.length - 1; i >= 0; i--) {
				current = current.child(frame(sample[i]));
				current.count++;
			}
		}
		return root.toJson(minSamples);
	}

	private static final class Node {
		private final String frame;
		private final Map<String, Node> children = new LinkedHashMap<>();
		private int count;

		Node(String frame) {
			this.frame = frame;
		}

		Node child(String name) {
			return children.computeIfAbsent(name, Node::new);
		}

		JsonArray toJson(int minSamples) {
			List<Node> sorted = new ArrayList<>(children.values());
			sorted.sort(Comparator.comparingInt((Node n) -> n.count).reversed());
			JsonArray array = new JsonArray();
			for (Node node : sorted) {
				if (node.count < minSamples) {
					continue;
				}
				JsonObject json = new JsonObject().put("frame", node.frame).put("samples", node.count); //$NON-NLS-1$ //$NON-NLS-2$
				JsonArray children = node.toJson(minSamples);
				if (children.size() > 0) {
					json.put("children", children); //$NON-NLS-1$
				}
				array.add(json);
			}
			return array;
		}
	}

	private static String frame(StackTraceElement element) {
		return element.getClassName() + '.' + element.getMethodName()
				+ (element.getLineNumber() > 0 ? ":" + element.getLineNumber() : ""); //$NON-NLS-1$ //$NON-NLS-2$
	}
}
