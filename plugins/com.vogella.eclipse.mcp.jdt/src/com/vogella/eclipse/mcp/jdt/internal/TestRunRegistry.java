package com.vogella.eclipse.mcp.jdt.internal;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.eclipse.jdt.junit.JUnitCore;
import org.eclipse.jdt.junit.TestRunListener;
import org.eclipse.jdt.junit.model.ITestCaseElement;
import org.eclipse.jdt.junit.model.ITestElement;
import org.eclipse.jdt.junit.model.ITestRunSession;

import com.vogella.eclipse.mcp.core.json.JsonArray;
import com.vogella.eclipse.mcp.core.json.JsonObject;

/**
 * Collects JUnit results from the IDE's own test runner.
 * <p>
 * {@link JUnitCore#addTestRunListener} is global and fires for every run in the
 * IDE, including ones a person started from the UI. Runs are therefore matched
 * by the launch configuration name, which is generated per run, so a run
 * started at the keyboard is never reported as one of ours.
 */
public final class TestRunRegistry {

	private static final TestRunRegistry INSTANCE = new TestRunRegistry();

	private static final String NAME_PREFIX = "MCP tests "; //$NON-NLS-1$

	private final AtomicLong ids = new AtomicLong();

	private final Map<String, Run> runs = new LinkedHashMap<>() {
		private static final long serialVersionUID = 1L;

		@Override
		protected boolean removeEldestEntry(Map.Entry<String, Run> eldest) {
			return size() > 20 && !eldest.getValue().running;
		}
	};

	private String lastId;

	private boolean listening;

	public static TestRunRegistry getInstance() {
		return INSTANCE;
	}

	private TestRunRegistry() {
	}

	/** One test run. */
	public static final class Run {

		private final String id;
		private final String launchName;
		private final String scope;
		private final long startedAt = System.currentTimeMillis();
		private final CountDownLatch finished = new CountDownLatch(1);
		private final List<Case> cases = new ArrayList<>();

		private volatile boolean running = true;
		private volatile long endedAt;
		private volatile String state = "running"; //$NON-NLS-1$
		private volatile String message;
		private volatile String launchedAs;
		private volatile org.eclipse.debug.core.ILaunch launch;

		Run(String id, String launchName, String scope) {
			this.id = id;
			this.launchName = launchName;
			this.scope = scope;
		}

		public String id() {
			return id;
		}

		public boolean running() {
			return running;
		}

		/** Which launcher was used, kept so a polled result can say what it is waiting on. */
		public void launchedAs(String value) {
			launchedAs = value;
		}

		String launchName() {
			return launchName;
		}

		public boolean await(long seconds) throws InterruptedException {
			return finished.await(seconds, TimeUnit.SECONDS);
		}

		void fail(String reason) {
			message = reason;
			if ("running".equals(state)) { //$NON-NLS-1$
				state = "failed"; //$NON-NLS-1$
			}
			end();
		}

		void end() {
			endedAt = System.currentTimeMillis();
			running = false;
			finished.countDown();
		}
	}

	private record Case(String className, String methodName, String result, double seconds, String trace,
			String expected, String actual) {
	}

	/** Registers the listener once, lazily, so a workspace that never runs tests pays nothing. */
	private synchronized void listen() {
		if (listening) {
			return;
		}
		JUnitCore.addTestRunListener(new TestRunListener() {
			@Override
			public void sessionStarted(ITestRunSession session) {
				Run run = match(session);
				if (run != null) {
					run.state = "running"; //$NON-NLS-1$
				}
			}

			@Override
			public void testCaseFinished(ITestCaseElement element) {
				Run run = match(element.getTestRunSession());
				if (run == null) {
					return;
				}
				ITestElement.FailureTrace trace = element.getFailureTrace();
				synchronized (run.cases) {
					run.cases.add(new Case(element.getTestClassName(), element.getTestMethodName(),
							// Result.toString() is "Failure", not "FAILURE"; normalising here is
							// what stops the counters below silently matching nothing but OK
							String.valueOf(element.getTestResult(false)).toUpperCase(java.util.Locale.ROOT),
							element.getElapsedTimeInSeconds(),
							trace == null ? null : trace.getTrace(), trace == null ? null : trace.getExpected(),
							trace == null ? null : trace.getActual()));
				}
			}

			@Override
			public void sessionFinished(ITestRunSession session) {
				Run run = match(session);
				if (run != null) {
					run.state = "done"; //$NON-NLS-1$
					run.end();
				}
			}
		});
		listening = true;
	}

	/** Matches by launch configuration name, which is unique per run. */
	private synchronized Run match(ITestRunSession session) {
		if (session == null || session.getTestRunName() == null) {
			return null;
		}
		for (Run run : runs.values()) {
			if (session.getTestRunName().startsWith(run.launchName())) {
				return run;
			}
		}
		return null;
	}

	public synchronized Run create(String scope) {
		listen();
		String id = "testrun-" + ids.incrementAndGet(); //$NON-NLS-1$
		Run run = new Run(id, NAME_PREFIX + id, scope);
		runs.put(id, run);
		lastId = id;
		return run;
	}

	public synchronized Run find(String id) {
		return runs.get(id);
	}

	/** The run still in progress, if any. JDT's AST parser is not safe to share. */
	public synchronized Run findRunning() {
		for (Run run : runs.values()) {
			if (run.running) {
				return run;
			}
		}
		return null;
	}

	public synchronized Run findLatest() {
		return lastId == null ? null : runs.get(lastId);
	}

	public static void failed(Run run, String reason) {
		run.fail(reason);
	}

	/**
	 * Ends a run whose launch died or never reported.
	 * <p>
	 * A launch cancelled at the compile error prompt terminates without ever
	 * producing a test event, and without this the run sits in {@code running}
	 * forever. Combined with the one-run-at-a-time guard that disabled the tool for
	 * the rest of the session, recoverable only by restarting the IDE.
	 */
	static void watch(Run run, org.eclipse.debug.core.ILaunch launch, int staleAfterSeconds) {
		run.launch = launch;
		Thread watchdog = new Thread(() -> {
			long deadline = System.currentTimeMillis() + staleAfterSeconds * 1000L;
			while (run.running) {
				try {
					if (run.await(2)) {
						return;
					}
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					return;
				}
				boolean anyResult;
				synchronized (run.cases) {
					anyResult = !run.cases.isEmpty();
				}
				if (launch != null && launch.isTerminated() && !anyResult) {
					run.state = "cancelled"; //$NON-NLS-1$
					run.fail("The launch ended without producing any test event. It was most likely cancelled, at the 'Errors in Workspace' prompt or otherwise."); //$NON-NLS-1$
					return;
				}
				if (System.currentTimeMillis() > deadline && !anyResult) {
					run.state = "failed"; //$NON-NLS-1$
					run.fail("No test event arrived within %d seconds, so the run was abandoned rather than left blocking further runs." //$NON-NLS-1$
							.formatted(staleAfterSeconds));
					return;
				}
			}
		}, "MCP test watchdog " + run.id()); //$NON-NLS-1$
		watchdog.setDaemon(true);
		watchdog.start();
	}

	/** Abandons a run, terminating its launch if it still has one. */
	public static boolean abandon(Run run) {
		if (!run.running) {
			return false;
		}
		try {
			if (run.launch != null && run.launch.canTerminate()) {
				run.launch.terminate();
			}
		} catch (org.eclipse.core.runtime.CoreException e) {
			// terminating is best effort; the run is abandoned either way
		}
		run.state = "cancelled"; //$NON-NLS-1$
		run.fail("Abandoned on request."); //$NON-NLS-1$
		return true;
	}

	/** Failures first, since that is what a caller asked the question for. */
	public static JsonObject toJson(Run run, int maxResults, boolean includePassed) {
		List<Case> cases;
		synchronized (run.cases) {
			cases = List.copyOf(run.cases);
		}
		int passed = 0;
		int failed = 0;
		int errors = 0;
		int ignored = 0;
		int unclassified = 0;
		List<Case> interesting = new ArrayList<>();
		for (Case testCase : cases) {
			switch (testCase.result()) {
			case "OK" -> passed++; //$NON-NLS-1$
			case "FAILURE" -> failed++; //$NON-NLS-1$
			case "ERROR" -> errors++; //$NON-NLS-1$
			case "IGNORED" -> ignored++; //$NON-NLS-1$
			// a result JDT names something else must still be counted: silently
			// dropping it is how 38 errors were once summarised as zero
			default -> unclassified++;
			}
			if (includePassed || !"OK".equals(testCase.result())) { //$NON-NLS-1$
				interesting.add(testCase);
			}
		}
		JsonArray reported = new JsonArray();
		for (Case testCase : interesting.subList(0, Math.min(maxResults, interesting.size()))) {
			JsonObject json = new JsonObject().put("class", testCase.className()) //$NON-NLS-1$
					.put("method", testCase.methodName()) //$NON-NLS-1$
					.put("result", testCase.result()) //$NON-NLS-1$
					.put("seconds", testCase.seconds()); //$NON-NLS-1$
			if (testCase.trace() != null) {
				json.put("trace", testCase.trace()) //$NON-NLS-1$
						.put("expected", testCase.expected()) //$NON-NLS-1$
						.put("actual", testCase.actual()); //$NON-NLS-1$
			}
			reported.add(json);
		}
		JsonObject counted = new JsonObject();
		if (unclassified > 0) {
			counted.put("unclassified", unclassified); //$NON-NLS-1$
		}
		// the counters must account for every case, or the summary contradicts the list
		if (passed + failed + errors + ignored + unclassified != cases.size()) {
			counted.put("countsInconsistent", //$NON-NLS-1$
					"The counters do not sum to total; trust the tests array."); //$NON-NLS-1$
		}
		return counted.put("runId", run.id) //$NON-NLS-1$
				.put("scope", run.scope) //$NON-NLS-1$
				.put("launchedAs", run.launchedAs) //$NON-NLS-1$
				.put("state", run.state) //$NON-NLS-1$
				.put("elapsedMillis", (run.endedAt == 0 ? System.currentTimeMillis() : run.endedAt) - run.startedAt)
				.put("total", cases.size()) //$NON-NLS-1$
				.put("passed", passed) //$NON-NLS-1$
				.put("failed", failed) //$NON-NLS-1$
				.put("errors", errors) //$NON-NLS-1$
				.put("ignored", ignored) //$NON-NLS-1$
				.put("truncated", interesting.size() > reported.size()) //$NON-NLS-1$
				// how many were dropped, so a caller knows to ask eclipse_get_test_results
				// for the rest rather than only that something is missing
				.put("omitted", interesting.size() - reported.size()) //$NON-NLS-1$
				.put("message", run.message) //$NON-NLS-1$
				.put("tests", reported); //$NON-NLS-1$
	}
}
