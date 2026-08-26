package com.vogella.eclipse.mcp.debug.internal;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.debug.core.ILaunchConfiguration;
import org.eclipse.debug.core.ILaunchConfigurationType;
import org.eclipse.debug.core.ILaunchConfigurationWorkingCopy;
import org.eclipse.debug.core.ILaunchManager;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.launching.IJavaLaunchConfigurationConstants;

import com.vogella.eclipse.mcp.core.CallBudget;
import com.vogella.eclipse.mcp.core.IMcpTool;
import com.vogella.eclipse.mcp.core.McpToolException;
import com.vogella.eclipse.mcp.core.McpToolResult;
import com.vogella.eclipse.mcp.core.ToolArguments;
import com.vogella.eclipse.mcp.core.json.JsonObject;

/**
 * Starts a debug session, from a saved launch configuration or from a project and
 * a main type, and answers with the session id.
 */
public final class DebugLaunchTool implements IMcpTool {

	private static final String JAVA_APPLICATION = IJavaLaunchConfigurationConstants.ID_JAVA_APPLICATION;

	/**
	 * Keeps a launch nobody is watching from asking a question: without these a
	 * suspend raises the modal perspective switch prompt, which blocks the IDE.
	 * The marker is what tells an adopted session apart from one a person started.
	 */
	static void unattended(org.eclipse.debug.core.ILaunchConfigurationWorkingCopy configuration) {
		configuration.setAttribute(com.vogella.eclipse.mcp.core.LaunchAttributes.TARGET_DEBUG_PERSPECTIVE,
				com.vogella.eclipse.mcp.core.LaunchAttributes.PERSPECTIVE_NONE);
		configuration.setAttribute(com.vogella.eclipse.mcp.core.LaunchAttributes.TARGET_RUN_PERSPECTIVE,
				com.vogella.eclipse.mcp.core.LaunchAttributes.PERSPECTIVE_NONE);
		configuration.setAttribute(com.vogella.eclipse.mcp.core.LaunchAttributes.STARTED_BY_MCP, true);
	}

	@Override
	public String getName() {
		return "eclipse_debug_launch"; //$NON-NLS-1$
	}

	@Override
	public String getDescription() {
		return "Starts a debug session and returns its sessionId plus the state at the moment of answering. STARTS A PROCESS: this runs project code under the IDE's debugger, so anything main does, the debugged program does. Give either 'configuration', the name of an existing launch configuration, or 'project' plus 'mainType'; the latter builds a transient configuration that never appears in the user's saved launches. With 'stopInMain' the program suspends at the first line of main; otherwise set breakpoints first through eclipse_set_breakpoint and wait for them through eclipse_debug_status with waitForSuspendSeconds. Sessions started here are terminated again when this plug-in stops or after autoTerminateAfterSeconds, whichever comes first. To run tests under the debugger use eclipse_run_tests with debug true instead."; //$NON-NLS-1$
	}

	@Override
	public String getInputSchema() {
		return """
				{
				  "type": "object",
				  "properties": {
				    "configuration":           {"type":"string","description":"Name of an existing launch configuration to start in debug mode."},
				    "project":                 {"type":"string","description":"Project holding mainType, when not launching a saved configuration."},
				    "mainType":                {"type":"string","description":"Fully qualified class whose main(String[]) starts the program."},
				    "arguments":               {"type":"string","description":"Program arguments."},
				    "vmArguments":             {"type":"string","description":"VM arguments."},
				    "stopInMain":              {"type":"boolean","default":false,"description":"Suspend at the first executable line of main."},
				    "autoTerminateAfterSeconds":{"type":"integer","default":900,"minimum":0,"maximum":86400,"description":"Terminate the program after this long, whether it finished or not. Only sessions this tool started are ever terminated."},
				    "waitForSuspendSeconds":   {"type":"integer","default":20,"minimum":0,"maximum":25,"description":"Wait for the first suspend (a breakpoint or stopInMain) before answering."},
				    "maxResults":              {"type":"integer","default":50,"minimum":1,"maximum":500,"description":"Threads reported per answer."}
				  },
				  "additionalProperties": false
				}"""; //$NON-NLS-1$
	}

	@Override
	public McpToolResult call(Map<String, Object> arguments, IProgressMonitor monitor) throws McpToolException {
		ToolArguments args = ToolArguments.of(arguments);
		int waitForSuspend = args.getInt("waitForSuspendSeconds", 20, 0, 25); //$NON-NLS-1$
		ILaunchConfigurationWorkingCopy configuration;
		try {
			configuration = configuration(args);
		} catch (CoreException e) {
			throw new McpToolException("Could not build the launch configuration: %s".formatted(e.getMessage()), e);
		}
		try {
			DebugSessionRegistry registry = DebugSessionRegistry.getInstance();
			DebugSessionRegistry.Session session = registry.prepare(configuration.getName());
			int idle = args.getInt("autoTerminateAfterSeconds", 900, 0, 86_400); //$NON-NLS-1$
			if (idle > 0) {
				registry.scheduleAutoTerminate(session, idle);
			}
			// launching happens in a job: creating the JVM takes seconds, and doing it on
			// the calling thread would hold the request open for all of it
			org.eclipse.core.runtime.jobs.Job.create("MCP debug launch " + session.id(), progress -> { //$NON-NLS-1$
				// the same prompt eclipse_run_tests answers: launching a project with
				// compile errors otherwise opens a modal dialog and the launch waits for
				// a person who does not know they are being asked
				String promptWas = com.vogella.eclipse.mcp.core.CompileErrorPrompt.suppress();
				try {
					org.eclipse.debug.core.ILaunch launched = configuration.launch(ILaunchManager.DEBUG_MODE, progress);
					if (!session.registered()) {
						// belt and braces for a launch event lost to timing
						session.attach(launched);
					}
				} catch (CoreException | RuntimeException e) {
					session.failed(e.getMessage() == null ? String.valueOf(e) : e.getMessage());
				} finally {
					com.vogella.eclipse.mcp.core.CompileErrorPrompt.restore(promptWas);
				}
				return org.eclipse.core.runtime.Status.OK_STATUS;
			}).schedule();

			long registrationWait = Math.min(CallBudget.maxWaitSeconds(), Math.max(waitForSuspend, 10));
			if (!session.awaitRegistration(registrationWait)) {
				return McpToolResult.error(("The JVM of session %s did not come up within %d seconds. %s")
						.formatted(session.id(), Long.valueOf(registrationWait),
								session.failure() == null ? "It may still be starting." : session.failure())); //$NON-NLS-1$
			}
			if (session.failure() != null && session.launch() == null) {
				return McpToolResult
						.error("The launch failed: %s".formatted(session.failure())); //$NON-NLS-1$
			}
			JsonObject json = DebugSupport.sessionJson(session, args.getInt("maxResults", 50, 1, 500)); //$NON-NLS-1$
			if (waitForSuspend > 0 && !session.suspended()) {
				DebugSessionRegistry.SuspendSignal signal = registry.onNextSuspend(session);
				boolean arrived = signal.await(CallBudget.boundedWaitSeconds(waitForSuspend));
				json = DebugSupport.sessionJson(session, args.getInt("maxResults", 50, 1, 500)); //$NON-NLS-1$
				if (!arrived && !session.suspended()) {
					json.put("timedOut", Boolean.TRUE).put("waitNote", //$NON-NLS-1$ //$NON-NLS-2$
							"No suspend within %d seconds; the program is probably still running. Poll eclipse_debug_status with waitForSuspendSeconds to keep waiting.".formatted(Integer.valueOf(waitForSuspend))); //$NON-NLS-1$
				} else {
					json.put("timedOut", Boolean.FALSE); //$NON-NLS-1$
				}
			}
			json.put("note", "The session ends with eclipse_debug_control action terminate; it also terminates by itself after autoTerminateAfterSeconds."); //$NON-NLS-1$ //$NON-NLS-2$
			return McpToolResult.of(json.toString());
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new McpToolException("Interrupted while waiting for the debug session to start.", e);
		}
	}

	private ILaunchConfigurationWorkingCopy configuration(ToolArguments args)
			throws McpToolException, CoreException {
		String name = args.getString("configuration"); //$NON-NLS-1$
		ILaunchManager manager = DebugPlugin.getDefault().getLaunchManager();
		if (name != null) {
			List<String> names = new ArrayList<>();
			for (ILaunchConfiguration candidate : manager.getLaunchConfigurations()) {
				names.add(candidate.getName());
				if (candidate.getName().equals(name)) {
					return candidate.getWorkingCopy();
				}
			}
			throw new McpToolException(
					"No launch configuration named '%s'. Existing ones: %s".formatted(name, String.join(", ", names))); //$NON-NLS-1$ //$NON-NLS-2$
		}
		String projectName = args.getString("project"); //$NON-NLS-1$
		String mainType = args.getString("mainType"); //$NON-NLS-1$
		if (projectName == null || mainType == null) {
			throw new McpToolException(
					"Give 'configuration', or both 'project' and 'mainType'."); //$NON-NLS-1$
		}
		IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
		if (!project.isAccessible()) {
			throw new McpToolException("No open project named '%s' in this workspace.".formatted(projectName)); //$NON-NLS-1$
		}
		IJavaProject javaProject = JavaCore.create(project);
		if (javaProject == null || !javaProject.exists()) {
			throw new McpToolException("'%s' is not a Java project.".formatted(projectName)); //$NON-NLS-1$
		}
		IType type = javaProject.findType(mainType);
		if (type == null) {
			throw new McpToolException("No type '%s' resolvable from project '%s'.".formatted(mainType, projectName)); //$NON-NLS-1$
		}
		ILaunchConfigurationType launchType = manager.getLaunchConfigurationType(JAVA_APPLICATION);
		if (launchType == null) {
			throw new McpToolException("This IDE has no Java Application launch configuration type."); //$NON-NLS-1$
		}
		ILaunchConfigurationWorkingCopy configuration = launchType.newInstance(null, "MCP debug " + mainType + " " + System.nanoTime()); //$NON-NLS-1$ //$NON-NLS-2$
		configuration.setAttribute(IJavaLaunchConfigurationConstants.ATTR_PROJECT_NAME, projectName);
		configuration.setAttribute(IJavaLaunchConfigurationConstants.ATTR_MAIN_TYPE_NAME, mainType);
		unattended(configuration);
		if (args.getBoolean("stopInMain", false)) { //$NON-NLS-1$
			configuration.setAttribute(IJavaLaunchConfigurationConstants.ATTR_STOP_IN_MAIN, true);
		}
		if (args.getString("arguments") != null) { //$NON-NLS-1$
			configuration.setAttribute(IJavaLaunchConfigurationConstants.ATTR_PROGRAM_ARGUMENTS,
					args.getString("arguments")); //$NON-NLS-1$
		}
		if (args.getString("vmArguments") != null) { //$NON-NLS-1$
			configuration.setAttribute(IJavaLaunchConfigurationConstants.ATTR_VM_ARGUMENTS,
					args.getString("vmArguments")); //$NON-NLS-1$
		}
		return configuration;
	}
}
