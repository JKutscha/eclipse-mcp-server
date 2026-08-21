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
		return "Runs JUnit tests through the IDE's own test runner and reports the failures with their stack traces, expected and actual values. RUNS PROJECT CODE. The JUnit version is detected from the project's own build path and the runtime classpath is the one Run As > JUnit Test would use, so nothing has to be configured. Runs as a launched JVM and returns a runId to poll through eclipse_get_test_results. This is plain JUnit on a Java project; it does not launch a second Eclipse, so it will not run plug-in tests that need a target platform."; //$NON-NLS-1$
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
				    "dryRun":         {"type":"boolean","default":false,"description":"List the test types that would run, without running anything."},
				    "wait":           {"type":"boolean","default":true},
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

			String kind = testKind(javaProject);
			TestRunRegistry.Run run = TestRunRegistry.getInstance()
					.create(testClass == null ? projectName : testClass + (testMethod == null ? "" : "#" + testMethod)); //$NON-NLS-1$ //$NON-NLS-2$

			ILaunchManager manager = DebugPlugin.getDefault().getLaunchManager();
			ILaunchConfigurationType launchType = manager.getLaunchConfigurationType(LAUNCH_TYPE);
			if (launchType == null) {
				return McpToolResult.error("This IDE has no JUnit launch configuration type."); //$NON-NLS-1$
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
			try {
				// never saved: a run started here must not litter the user's launch history
				configuration.launch(ILaunchManager.RUN_MODE, null);
			} catch (RuntimeException e) {
				// the runner bundles are part of the SDK; a stripped IDE can lack them,
				// and JDT reports that as an assertion rather than a CoreException
				TestRunRegistry.failed(run, e.getMessage());
				return McpToolResult.error(
						"Could not launch the tests: %s. The JUnit runner for %s may not be installed in this IDE." //$NON-NLS-1$
								.formatted(e.getMessage(), kind));
			}

			if (args.getBoolean("wait", true)) { //$NON-NLS-1$
				try {
					run.await(args.getInt("timeoutSeconds", 25, 1, 3600)); //$NON-NLS-1$
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				}
			}
			return McpToolResult.of(TestRunRegistry.toJson(run, 50, false).put("testKind", kind).toString()); //$NON-NLS-1$
		} catch (CoreException e) {
			throw new McpToolException("Could not run the tests of " + projectName, e); //$NON-NLS-1$
		}
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
