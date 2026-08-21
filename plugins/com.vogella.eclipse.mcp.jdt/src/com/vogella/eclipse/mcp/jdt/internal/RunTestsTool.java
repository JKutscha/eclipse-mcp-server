package com.vogella.eclipse.mcp.jdt.internal;

import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.debug.core.ILaunchConfigurationType;
import org.eclipse.debug.core.ILaunchConfigurationWorkingCopy;
import org.eclipse.debug.core.ILaunchManager;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.junit.JUnitCore;
import org.eclipse.jdt.launching.IJavaLaunchConfigurationConstants;
import org.eclipse.pde.launching.IPDELauncherConstants;

import com.vogella.eclipse.mcp.core.IMcpTool;
import com.vogella.eclipse.mcp.core.McpToolException;
import com.vogella.eclipse.mcp.core.McpToolResult;
import com.vogella.eclipse.mcp.core.ToolArguments;
import com.vogella.eclipse.mcp.core.json.JsonArray;
import com.vogella.eclipse.mcp.core.json.JsonObject;

/**
 * Runs JUnit tests through the IDE's own test runner.
 */
public final class RunTestsTool implements IMcpTool {

	private static final String LAUNCH_TYPE = "org.eclipse.jdt.junit.launchconfig"; //$NON-NLS-1$

	/** Declared by org.eclipse.pde.launching, which despite the id has no UI dependency. */
	private static final String PLUGIN_LAUNCH_TYPE = "org.eclipse.pde.ui.JunitLaunchConfig"; //$NON-NLS-1$

	/** Runs the tests in a platform with no workbench. */
	private static final String CORE_TEST_APPLICATION = "org.eclipse.pde.junit.runtime.coretestapplication"; //$NON-NLS-1$

	/** Opens a workbench window, so it is never the default. */
	private static final String UI_TEST_APPLICATION = "org.eclipse.pde.junit.runtime.uitestapplication"; //$NON-NLS-1$

	private static final String PLUGIN_NATURE = "org.eclipse.pde.PluginNature"; //$NON-NLS-1$

	/**
	 * The preference the "Errors in Workspace / Always launch without asking" toggle
	 * writes. Launching with compile errors otherwise raises a modal dialog through
	 * the debug.ui status handler, which blocks a call nobody is watching.
	 */
	private static final String DEBUG_UI = "org.eclipse.debug.ui"; //$NON-NLS-1$

	/** The constant is named PREF_CONTINUE_WITH_COMPILE_ERROR; its value is not. */
	private static final String CONTINUE_WITH_COMPILE_ERROR = "org.eclipse.debug.ui.cancel_launch_with_compile_errors"; //$NON-NLS-1$

	/** Launch configuration attributes of the JUnit launcher, which are a stable contract. */
	private static final String ATTR_CONTAINER = "org.eclipse.jdt.junit.CONTAINER"; //$NON-NLS-1$

	private static final String ATTR_TEST_KIND = "org.eclipse.jdt.junit.TEST_KIND"; //$NON-NLS-1$

	private static final String ATTR_TEST_NAME = "org.eclipse.jdt.junit.TESTNAME"; //$NON-NLS-1$

	@Override
	public String getName() {
		return "eclipse_run_tests"; //$NON-NLS-1$
	}

	@Override
	public String getDescription() {
		return "Runs JUnit tests through the IDE's own test runner and reports the failures with their stack traces, expected and actual values. RUNS PROJECT CODE. The JUnit version is detected from the project's own build path and the runtime classpath is the one Run As > JUnit Test would use, so nothing has to be configured. Runs as a launched JVM and returns a runId to poll through eclipse_get_test_results. A plug-in project is run as a JUnit Plug-in Test by default, which launches a second Eclipse with a running platform in its own cleared workspace, because tests needing OSGi produce meaningless errors under a plain JUnit launch. That is slower. The UI test application, which opens a workbench window, is opt-in. launchedAs in the answer says which was used."; //$NON-NLS-1$
	}

	@Override
	public String getInputSchema() {
		return """
				{
				  "type": "object",
				  "required": ["project"],
				  "properties": {
				    "project":        {"type":"string","description":"Project holding the tests."},
				    "testClass":      {"type":"string","description":"Fully qualified test class. Omit to run every test in the project."},
				    "testMethod":     {"type":"string","description":"Single method of testClass."},
				    "pluginTest":     {"type":"string","enum":["auto","true","false"],"default":"auto","description":"Run as a JUnit Plug-in Test, which launches a second Eclipse with a running platform. 'auto' uses it when the project is a plug-in project. Tests that need OSGi fail as plain JUnit with errors that look like broken tests rather than real results."},
				    "ui":             {"type":"boolean","default":false,"description":"Use the UI test application, which opens a workbench window on the user's screen. Off by default: a launched IDE should never be a surprise."},
				    "runtimeWorkspace": {"type":"string","description":"Workspace directory for the launched platform. Defaults to a sibling junit-workspace, and it is cleared on every run."},
				    "maxResults":     {"type":"integer","default":50,"minimum":1,"maximum":2000,"description":"Reported cases. A suite of several hundred truncates; omitted says how many were dropped and eclipse_get_test_results returns the rest."},
				    "dryRun":         {"type":"boolean","default":false,"description":"List the test types that would run, without running anything."},
				    "wait":           {"type":"boolean","default":true,"description":"Defaults to false for a plug-in test: launching a second Eclipse takes tens of seconds, well past the server's call timeout, so waiting would abandon the call rather than answer it."},
				    "timeoutSeconds": {"type":"integer","default":25,"minimum":1,"maximum":3600,"description":"Keep below the server's tool call timeout; poll with eclipse_get_test_results for longer runs."}
				  },
				  "additionalProperties": false
				}"""; //$NON-NLS-1$
	}

	@Override
	public McpToolResult call(Map<String, Object> arguments, IProgressMonitor monitor) throws McpToolException {
		ToolArguments args = ToolArguments.of(arguments);
		String projectName = args.getString("project"); //$NON-NLS-1$
		if (projectName == null) {
			return McpToolResult.error("The argument 'project' is required."); //$NON-NLS-1$
		}
		IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
		if (!project.isAccessible()) {
			return McpToolResult.error("No open project named '%s' in this workspace.".formatted(projectName)); //$NON-NLS-1$
		}
		IJavaProject javaProject = JavaCore.create(project);
		if (javaProject == null || !javaProject.exists()) {
			return McpToolResult.error("'%s' is not a Java project.".formatted(projectName)); //$NON-NLS-1$
		}
		String testClass = args.getString("testClass"); //$NON-NLS-1$
		String testMethod = args.getString("testMethod"); //$NON-NLS-1$
		if (testMethod != null && testClass == null) {
			return McpToolResult.error("'testMethod' needs a 'testClass' to belong to."); //$NON-NLS-1$
		}

		try {
			IType type = null;
			if (testClass != null) {
				type = javaProject.findType(testClass);
				if (type == null || !type.exists()) {
					return McpToolResult.error("No type '%s' in project '%s'.".formatted(testClass, projectName)); //$NON-NLS-1$
				}
			}
			if (args.getBoolean("dryRun", false)) { //$NON-NLS-1$
				return McpToolResult.of(dryRun(javaProject, type, monitor).toString());
			}

			TestRunRegistry.Run active = TestRunRegistry.getInstance().findRunning();
			if (active != null) {
				return McpToolResult.error(
						"A test run is already in progress (%s). Two overlapping runs share JDT's AST parser and can fail with an IllegalStateException, so this one is refused; poll eclipse_get_test_results for the active run first." //$NON-NLS-1$
								.formatted(active.id()));
			}
			String kind = testKind(javaProject);
			String pluginTest = args.getString("pluginTest", "auto"); //$NON-NLS-1$ //$NON-NLS-2$
			boolean asPlugin = "true".equals(pluginTest) //$NON-NLS-1$
					|| ("auto".equals(pluginTest) && project.hasNature(PLUGIN_NATURE)); //$NON-NLS-1$
			boolean ui = args.getBoolean("ui", false); //$NON-NLS-1$
			String launchedAs = asPlugin ? (ui ? "pluginTest-ui" : "pluginTest") : "junit"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
			TestRunRegistry.Run run = TestRunRegistry.getInstance()
					.create(testClass == null ? projectName : testClass + (testMethod == null ? "" : "#" + testMethod)); //$NON-NLS-1$ //$NON-NLS-2$

			ILaunchManager manager = DebugPlugin.getDefault().getLaunchManager();
			ILaunchConfigurationType launchType = manager
					.getLaunchConfigurationType(asPlugin ? PLUGIN_LAUNCH_TYPE : LAUNCH_TYPE);
			if (launchType == null) {
				return McpToolResult.error(asPlugin
						? "This IDE has no plug-in JUnit launch configuration type, so PDE is probably not installed. Pass pluginTest false to run as plain JUnit." //$NON-NLS-1$
						: "This IDE has no JUnit launch configuration type."); //$NON-NLS-1$
			}
			ILaunchConfigurationWorkingCopy configuration = launchType.newInstance(null, run.launchName());
			configuration.setAttribute(IJavaLaunchConfigurationConstants.ATTR_PROJECT_NAME, projectName);
			configuration.setAttribute(ATTR_TEST_KIND, kind);
			if (type == null) {
				// a container runs everything under it, which is how Run As on a project works
				configuration.setAttribute(ATTR_CONTAINER, javaProject.getHandleIdentifier());
			} else {
				configuration.setAttribute(IJavaLaunchConfigurationConstants.ATTR_MAIN_TYPE_NAME, testClass);
				if (testMethod != null) {
					configuration.setAttribute(ATTR_TEST_NAME, testMethod);
				}
			}
			// launching happens in a job: preLaunchCheck alone can take a while, and
			// doing it here would defeat wait:false exactly as the p2 refresh once did
			run.launchedAs(launchedAs);
			org.eclipse.core.runtime.jobs.Job.create("MCP test launch " + run.id(), progress -> { //$NON-NLS-1$
				Object previous = suppressCompileErrorPrompt();
				try {
					org.eclipse.debug.core.ILaunch launch = configuration.launch(ILaunchManager.RUN_MODE, null);
					TestRunRegistry.watch(run, launch, asPlugin ? 300 : 120);
				} catch (CoreException | RuntimeException e) {
					// the runner bundles ship with the SDK, and JDT reports a missing one
					// as an assertion rather than a CoreException
					TestRunRegistry.failed(run, describe(e));
				} finally {
					restoreCompileErrorPrompt(previous);
				}
				return org.eclipse.core.runtime.Status.OK_STATUS;
			}).schedule();

			// a launched platform starts far too slowly to hold a call open for
			if (args.getBoolean("wait", !asPlugin)) { //$NON-NLS-1$
				try {
					run.await(args.getInt("timeoutSeconds", 25, 1, 3600)); //$NON-NLS-1$
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				}
			}
			JsonObject result = TestRunRegistry.toJson(run, args.getInt("maxResults", 50, 1, 2000), false) //$NON-NLS-1$
					.put("testKind", kind) //$NON-NLS-1$
					;
			if (asPlugin && run.running()) {
				result.put("note", //$NON-NLS-1$
						"A second Eclipse is starting, which takes tens of seconds before the first test runs. Poll eclipse_get_test_results with this runId."); //$NON-NLS-1$
			}
			JsonArray broken = projectsWithErrors(project);
			if (broken.size() > 0) {
				result.put("launchedWithCompileErrors", broken) //$NON-NLS-1$
						.put("compileErrorNote", //$NON-NLS-1$
								"These projects do not compile. Eclipse would normally ask whether to launch anyway; this server answered yes, because a dialog would block a call nobody is watching. Failures may be stale classes rather than real results."); //$NON-NLS-1$
			}
			if (asPlugin && !ui) {
				result.put("headless", //$NON-NLS-1$
						"Running the core test application, which has no workbench. Tests that need a Display fail here; pass ui true to run them in a real workbench window."); //$NON-NLS-1$
			}
			if (!asPlugin && project.hasNature(PLUGIN_NATURE)) {
				result.put("caveat", //$NON-NLS-1$
						"'%s' is a plug-in project but was run as plain JUnit, so tests needing OSGi fail with errors such as 'The application has not been initialized', a null IExtensionRegistry or NoClassDefFoundError. Those are not test failures. Omit pluginTest to launch a platform." //$NON-NLS-1$
								.formatted(projectName));
			}
			return McpToolResult.of(result.toString());
		} catch (CoreException e) {
			// the cause's own text in the message: a bare "could not run the tests"
			// restates the request and says nothing about what went wrong
			throw new McpToolException(
					"Could not run the tests of %s: %s".formatted(projectName, describe(e)), e); //$NON-NLS-1$
		}
	}

	/** Pre-answers the compile error prompt, returning the previous setting. */
	private static Object suppressCompileErrorPrompt() {
		var node = org.eclipse.core.runtime.preferences.InstanceScope.INSTANCE.getNode(DEBUG_UI);
		String previous = node.get(CONTINUE_WITH_COMPILE_ERROR, null);
		node.put(CONTINUE_WITH_COMPILE_ERROR, "always"); //$NON-NLS-1$
		return previous;
	}

	private static void restoreCompileErrorPrompt(Object previous) {
		var node = org.eclipse.core.runtime.preferences.InstanceScope.INSTANCE.getNode(DEBUG_UI);
		if (previous instanceof String value) {
			node.put(CONTINUE_WITH_COMPILE_ERROR, value);
		} else {
			node.remove(CONTINUE_WITH_COMPILE_ERROR);
		}
	}

	/**
	 * The projects the launch depends on that do not compile, transitively.
	 * <p>
	 * Direct references are not enough: the launch delegate checks the whole
	 * required closure, which is why the prompt named a project this field did not.
	 */
	private static JsonArray projectsWithErrors(IProject project) {
		java.util.Set<String> seen = new java.util.LinkedHashSet<>();
		java.util.List<IProject> queue = new java.util.ArrayList<>(java.util.List.of(project));
		JsonArray broken = new JsonArray();
		while (!queue.isEmpty() && seen.size() < 500) {
			IProject current = queue.remove(0);
			if (!current.isAccessible() || !seen.add(current.getName())) {
				continue;
			}
			if (hasErrors(current)) {
				broken.add(current.getName());
			}
			try {
				queue.addAll(java.util.List.of(current.getReferencedProjects()));
			} catch (CoreException | RuntimeException e) {
				// PDE can throw computing dynamic references on a stale bundle wiring
			}
		}
		return broken;
	}

	private static boolean hasErrors(IProject project) {
		try {
			for (org.eclipse.core.resources.IMarker marker : project.findMarkers(
					org.eclipse.core.resources.IMarker.PROBLEM, true,
					org.eclipse.core.resources.IResource.DEPTH_INFINITE)) {
				if (marker.getAttribute(org.eclipse.core.resources.IMarker.SEVERITY,
						-1) == org.eclipse.core.resources.IMarker.SEVERITY_ERROR) {
					return true;
				}
			}
		} catch (CoreException e) {
			// a project whose markers cannot be read is not evidence either way
		}
		return false;
	}

	/** An exception with no message is useless to a caller; name the type at least. */
	private static String describe(Throwable e) {
		if (e.getMessage() != null && !e.getMessage().isBlank()) {
			return e.getMessage();
		}
		StackTraceElement[] frames = e.getStackTrace();
		return e.getClass().getName() + (frames.length == 0 ? "" : " at " + frames[0]); //$NON-NLS-1$ //$NON-NLS-2$
	}

	/**
	 * A plug-in test launches a second Eclipse, so it needs its own workspace and an
	 * application to run. The workbench one is opt-in: it opens a window on the
	 * user's screen, which should never happen by surprise.
	 */
	private static void configurePlatform(ILaunchConfigurationWorkingCopy configuration, String runtimeWorkspace,
			boolean ui) {
		// APP_TO_TEST is what the delegate actually reads: it compares that against the
		// core test application to decide headless, and setting only APPLICATION left
		// it defaulting to the product's workbench and starting a full IDE
		configuration.setAttribute(IPDELauncherConstants.APP_TO_TEST,
				ui ? "org.eclipse.ui.ide.workbench" : CORE_TEST_APPLICATION); //$NON-NLS-1$
		configuration.setAttribute(IPDELauncherConstants.APPLICATION, ui ? UI_TEST_APPLICATION : CORE_TEST_APPLICATION);
		configuration.setAttribute(IPDELauncherConstants.USE_PRODUCT, false);
		configuration.setAttribute(IPDELauncherConstants.LOCATION,
				runtimeWorkspace == null ? "${workspace_loc}/../mcp-junit-workspace" : runtimeWorkspace); //$NON-NLS-1$
		// cleared and never asked about: a prompt would block a call nobody is watching
		configuration.setAttribute(IPDELauncherConstants.DOCLEAR, true);
		configuration.setAttribute(IPDELauncherConstants.ASKCLEAR, false);
		configuration.setAttribute(IPDELauncherConstants.CONFIG_CLEAR_AREA, true);
		// take the whole target platform plus the workspace plug-ins, which is what
		// the launch tab does by default and what makes an unconfigured run resolve
		configuration.setAttribute(IPDELauncherConstants.AUTOMATIC_ADD, true);
	}

	private static JsonObject dryRun(IJavaProject javaProject, IType type, IProgressMonitor monitor)
			throws CoreException {
		IType[] found = JUnitCore.findTestTypes(type == null ? javaProject : type, monitor);
		JsonArray types = new JsonArray();
		for (IType candidate : found) {
			types.add(candidate.getFullyQualifiedName());
		}
		return new JsonObject().put("dryRun", Boolean.TRUE) //$NON-NLS-1$
				.put("testKind", testKind(javaProject)) //$NON-NLS-1$
				.put("total", found.length) //$NON-NLS-1$
				.put("testTypes", types); //$NON-NLS-1$
	}

	/**
	 * Which JUnit the project is on, read from its own build path. JDT then supplies
	 * the matching runner, so nothing here has to know about JUnit itself.
	 */
	private static String testKind(IJavaProject javaProject) throws CoreException {
		if (javaProject.findType("org.junit.jupiter.api.Test") != null) { //$NON-NLS-1$
			return "org.eclipse.jdt.junit.loader.junit5"; //$NON-NLS-1$
		}
		if (javaProject.findType("org.junit.Test") != null) { //$NON-NLS-1$
			return "org.eclipse.jdt.junit.loader.junit4"; //$NON-NLS-1$
		}
		return "org.eclipse.jdt.junit.loader.junit3"; //$NON-NLS-1$
	}
}
