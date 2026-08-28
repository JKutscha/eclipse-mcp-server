package com.vogella.eclipse.mcp.ui.internal;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.IEditorReference;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;

import com.vogella.eclipse.mcp.core.IMcpTool;
import com.vogella.eclipse.mcp.core.McpToolResult;
import com.vogella.eclipse.mcp.core.ToolArguments;
import com.vogella.eclipse.mcp.core.json.JsonArray;
import com.vogella.eclipse.mcp.core.json.JsonObject;

/**
 * Restarts the IDE, after answering.
 */
public final class RestartTool implements IMcpTool {

	private static final long UI_TIMEOUT_SECONDS = 10;

	/** Long enough for the HTTP response to be on the wire before the server dies with the IDE. */
	private static final int RESTART_DELAY_MILLIS = 2000;

	/** Where the workbench reads the arguments it hands the launcher for the next start. */
	public static final String EXIT_DATA_PROPERTY = "eclipse.exitdata"; //$NON-NLS-1$

	public static final String NO_SPLASH = "-nosplash"; //$NON-NLS-1$

	@Override
	public String getName() {
		return "eclipse_restart"; //$NON-NLS-1$
	}

	@Override
	public String getDescription() {
		return "Restarts the Eclipse IDE, into the same workspace or into another one, which is what makes an installed or updated feature active. The answer names the workspace it will return to and the one it is leaving, so a caller that switches can find its way back. PASS workspace TO SWITCH: an absolute path, created when it is not there, which is how a measurement gets a workspace of its own instead of sharing the one somebody works in. The IDE that comes up is this same installation with this same server in it, reachable at the same port with the same token, but everything workspace-shaped is different: other projects, other preferences, a build from nothing, and the local history and the element tree of the old workspace stay behind. THE CONNECTION WILL DROP BY DESIGN: this tool answers first and restarts a couple of seconds later, so a dropped connection right after a successful result is the expected outcome and not a failure. Reconnect with the same bearer token, which survives restarts and updates. Refuses when editors have unsaved changes or a modal dialog is open, naming which of the two fired, unless save or force is passed. A blocking dialog is better cleared with eclipse_dismiss_dialog than forced past. It works independently of eclipse_update, so a half applied update can still be recovered by restarting. Pass splash false to come back without the splash screen: this appends -nosplash to the arguments the workbench hands the launcher, the same channel it uses to pass the workspace, and splashSuppressed reports whether that argument was added, not what the launcher then did with it."; //$NON-NLS-1$
	}

	@Override
	public String getInputSchema() {
		return """
				{
				  "type": "object",
				  "properties": {
				    "save":  {"type":"boolean","default":false,"description":"Save dirty editors first, then restart."},
				    "force":  {"type":"boolean","default":false,"description":"Restart even with unsaved changes or an open modal dialog. Discards that work."},
				    "splash": {"type":"boolean","default":true,"description":"False comes back without the splash screen. The argument is added to the relaunch arguments; whether the launcher honours it is not something this server can observe."},
				    "workspace": {"type":"string","description":"Absolute path of the workspace to start into. Omit to come back into the current one. The directory is created when it does not exist, because a path that is not there opens the workspace chooser and waits for a person. A workspace another IDE has open cannot be taken over, and that is only visible once the relaunch has happened."}
				  },
				  "additionalProperties": false
				}"""; //$NON-NLS-1$
	}

	@Override
	public McpToolResult call(Map<String, Object> arguments, IProgressMonitor monitor) {
		if (!PlatformUI.isWorkbenchRunning()) {
			return McpToolResult.error("There is no running workbench to restart."); //$NON-NLS-1$
		}
		ToolArguments args = ToolArguments.of(arguments);
		boolean save = args.getBoolean("save", false); //$NON-NLS-1$
		boolean force = args.getBoolean("force", false); //$NON-NLS-1$
		boolean splash = args.getBoolean("splash", true); //$NON-NLS-1$
		String workspace = args.getString("workspace"); //$NON-NLS-1$
		if (workspace != null) {
			McpToolResult refusal = checkWorkspace(workspace);
			if (refusal != null) {
				return refusal;
			}
		}

		CompletableFuture<JsonObject> pending = new CompletableFuture<>();
		PlatformUI.getWorkbench().getDisplay().asyncExec(() -> {
			try {
				pending.complete(prepare(save, force, splash, workspace));
			} catch (RuntimeException e) {
				pending.completeExceptionally(e);
			}
		});
		try {
			JsonObject result = pending.get(UI_TIMEOUT_SECONDS, TimeUnit.SECONDS);
			return Boolean.TRUE.equals(result.remove("restarting")) //$NON-NLS-1$
					? McpToolResult.of(result.toString())
					: McpToolResult.error(result.toString());
		} catch (TimeoutException e) {
			pending.cancel(false);
			return McpToolResult.error("The Eclipse UI is busy, try again."); //$NON-NLS-1$
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return McpToolResult.error("The request was interrupted."); //$NON-NLS-1$
		} catch (ExecutionException e) {
			return McpToolResult.error("Could not restart: " + (e.getCause() == null ? e : e.getCause()));
		}
	}

	/**
	 * Adds {@code -nosplash} to the arguments the workbench hands the launcher.
	 * <p>
	 * Reports whether the argument is in place, which is all this side can know:
	 * the splash is painted by the native launcher before the JVM exists, so
	 * whether it honours the relaunch arguments cannot be observed from here.
	 */
	public static boolean appendNoSplash() {
		try {
			String existing = System.getProperty(EXIT_DATA_PROPERTY, ""); //$NON-NLS-1$
			if (contains(existing, NO_SPLASH)) {
				return true;
			}
			System.setProperty(EXIT_DATA_PROPERTY, existing + NO_SPLASH + "\n"); //$NON-NLS-1$
			return true;
		} catch (RuntimeException e) {
			// a restart that happens with a splash beats one that does not happen
			return false;
		}
	}

	/** The arguments are newline separated, so a substring match would hit -nosplashfoo. */
	public static boolean contains(String arguments, String argument) {
		for (String line : arguments.split("\n")) { //$NON-NLS-1$
			if (argument.equals(line.trim())) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Whether the IDE can be sent to this workspace at all.
	 *
	 * @return the refusal, or null when it can
	 */
	private static McpToolResult checkWorkspace(String workspace) {
		java.nio.file.Path path = pathOf(workspace);
		if (path == null) {
			return McpToolResult.error("'%s' is not a usable path.".formatted(workspace)); //$NON-NLS-1$
		}
		if (!path.isAbsolute()) {
			return McpToolResult.error(
					"'workspace' has to be an absolute path; the launcher resolves a relative one against a working directory nobody here knows."); //$NON-NLS-1$
		}
		if (java.nio.file.Files.exists(path) && !java.nio.file.Files.isDirectory(path)) {
			return McpToolResult.error("'%s' exists and is not a directory.".formatted(path)); //$NON-NLS-1$
		}
		if (org.eclipse.core.runtime.Platform.inDevelopmentMode()) {
			// the workbench forces a plain restart in development mode, so the
			// relaunch arguments are dropped and the switch would silently not happen
			return McpToolResult.error(
					"This IDE runs in development mode, where the platform keeps the command line of the launch it came from, so it cannot be sent to another workspace. Restart it by hand with -data '%s'." //$NON-NLS-1$
							.formatted(path));
		}
		try {
			java.nio.file.Files.createDirectories(path);
		} catch (java.io.IOException e) {
			return McpToolResult.error("Could not create '%s': %s".formatted(path, e.getMessage())); //$NON-NLS-1$
		}
		return null;
	}

	/**
	 * A filesystem path from what a caller passed, a file URI included.
	 * <p>
	 * The URI form is what this tool used to report as the workspace, so a caller
	 * that hands back what it was given would otherwise be refused.
	 */
	private static java.nio.file.Path pathOf(String workspace) {
		try {
			if (workspace.startsWith("file:")) { //$NON-NLS-1$
				return java.nio.file.Path.of(java.net.URI.create(workspace).getPath());
			}
			return java.nio.file.Path.of(workspace);
		} catch (RuntimeException e) {
			return null;
		}
	}

	/**
	 * Points the relaunch at another workspace.
	 * <p>
	 * This is what {@code restart(true)} does for the current workspace: the
	 * launcher reads the arguments out of the exit data, and the exit code decides
	 * whether they are used at all, so both have to be set together.
	 */
	private static void relaunchInto(String workspace) {
		String existing = System.getProperty(EXIT_DATA_PROPERTY, ""); //$NON-NLS-1$
		System.setProperty(EXIT_DATA_PROPERTY, existing + "-data\n" + workspace + "\n"); //$NON-NLS-1$ //$NON-NLS-2$
		// the code that makes the launcher read the arguments above; a plain restart
		// ignores them and comes back where it was
		System.setProperty("eclipse.exitcode", //$NON-NLS-1$
				org.eclipse.equinox.app.IApplication.EXIT_RELAUNCH.toString());
	}

	private static JsonObject prepare(boolean save, boolean force, boolean splash, String workspace) {
		IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
		IWorkbenchPage page = window == null ? null : window.getActivePage();
		JsonArray dirty = new JsonArray();
		if (page != null) {
			for (IEditorReference reference : page.getEditorReferences()) {
				if (reference.isDirty()) {
					dirty.add(reference.getTitle());
				}
			}
		}
		JsonArray modal = new JsonArray();
		for (Shell shell : PlatformUI.getWorkbench().getDisplay().getShells()) {
			boolean isModal = (shell.getStyle()
					& (SWT.APPLICATION_MODAL | SWT.PRIMARY_MODAL | SWT.SYSTEM_MODAL)) != 0;
			if (isModal && shell.isVisible()) {
				modal.add(shell.getText());
			}
		}
		if (dirty.size() > 0 && save && page != null) {
			page.saveAllEditors(false);
			dirty = new JsonArray();
		}
		if (!force && (dirty.size() > 0 || modal.size() > 0)) {
			// compose from whichever guard actually fired: naming unsaved work when the
			// blocker is a dialog sends the caller to save, which changes nothing
			StringBuilder reason = new StringBuilder("Refused: "); //$NON-NLS-1$
			if (dirty.size() > 0) {
				reason.append("editors have unsaved changes, which restarting would discard. Pass save to save them first, or force to discard them."); //$NON-NLS-1$
			}
			if (modal.size() > 0) {
				if (dirty.size() > 0) {
					reason.append(' ');
				}
				reason.append("a modal dialog is open, and restarting under one loses whatever is in it. Close it with eclipse_dismiss_dialog, or pass force."); //$NON-NLS-1$
			}
			return new JsonObject().put("restarting", Boolean.FALSE) //$NON-NLS-1$
					.put("dirtyEditors", dirty) //$NON-NLS-1$
					.put("openModalDialogs", modal) //$NON-NLS-1$
					.put("reason", reason.toString()); //$NON-NLS-1$
		}
		// before the restart, and deliberately not by waiting for either of them. A
		// build has nothing worth saving across a restart, and a launched JVM that
		// outlives the IDE keeps its workspace lock with nobody left who knows where
		// it came from
		JsonObject cleared = clearTheWay();
		// before the restart is scheduled: Workbench.buildCommandLine reads this
		// property and appends the workspace to whatever is already in it, so adding
		// the argument here is the same channel the platform uses for -data
		boolean splashSuppressed = splash ? false : appendNoSplash();
		String target = workspace == null ? null : String.valueOf(pathOf(workspace));
		if (target != null) {
			relaunchInto(target);
		}
		// answer first, restart after: the server dies with the IDE, so restarting
		// inside the call gives the caller a dropped connection instead of a result
		Display display = PlatformUI.getWorkbench().getDisplay();
		// restart(true), not restart(): the no argument form relaunches without -data,
		// so the IDE comes back up asking for a workspace and waits for a human.
		// With a workspace of our own the arguments are already set, and restart(true)
		// would append the current one after it
		boolean current = workspace == null;
		display.timerExec(RESTART_DELAY_MILLIS, () -> PlatformUI.getWorkbench().restart(current));
		return new JsonObject().put("restarting", Boolean.TRUE) //$NON-NLS-1$
				.put("inMillis", RESTART_DELAY_MILLIS) //$NON-NLS-1$
				.put("cleared", cleared) //$NON-NLS-1$
				.put("splashSuppressed", Boolean.valueOf(splashSuppressed)) //$NON-NLS-1$
				.put("workspace", target == null ? workspaceLocation() : target) //$NON-NLS-1$
				.put("previousWorkspace", workspaceLocation()) //$NON-NLS-1$
				.put("workspaceChanged", Boolean.valueOf(workspace != null)) //$NON-NLS-1$
				.put("savedEditors", save) //$NON-NLS-1$
				.put("verifyRestartWith", //$NON-NLS-1$
						"Read startedAt from the discovery file before and after; it changes only when a new server process comes up.") //$NON-NLS-1$
				.put("note", //$NON-NLS-1$
						"The connection will drop when the IDE goes down. IMPORTANT: this answer is sent BEFORE the restart, so the old server keeps answering for a couple of seconds and a reachability check will succeed against the process that is about to die. Wait for the connection to drop and then return, or compare startedAt in the discovery file. Reconnect with the same bearer token, which survives restarts and updates. The IDE is relaunched into the workspace named above; if it comes back asking which workspace to use, the relaunch lost its arguments and a human has to answer the chooser." //$NON-NLS-1$
								+ (workspace == null ? "" //$NON-NLS-1$
										: " THIS IS A DIFFERENT WORKSPACE: check what came up before measuring anything in it, because a workspace another IDE holds open is refused by the lock and the chooser opens instead. previousWorkspace is the way back.")); //$NON-NLS-1$
	}

	/**
	 * Cancels what is building and ends the launches this server started.
	 * <p>
	 * Neither is waited for beyond a short bound. A build interrupted by a restart
	 * costs another build and nothing else, so waiting minutes for one would be
	 * pure delay. A launched JVM is the opposite case: it survives the restart,
	 * keeps holding the workspace lock and the ports it took, and the next launch
	 * then fails inside its own process with a dialog no tool here can reach,
	 * hours after anybody could connect it to a restart.
	 */
	private static JsonObject clearTheWay() {
		JsonObject cleared = new JsonObject();
		org.eclipse.core.runtime.jobs.IJobManager jobs = org.eclipse.core.runtime.jobs.Job.getJobManager();
		int builds = jobs.find(org.eclipse.core.resources.ResourcesPlugin.FAMILY_MANUAL_BUILD).length
				+ jobs.find(org.eclipse.core.resources.ResourcesPlugin.FAMILY_AUTO_BUILD).length;
		if (builds > 0) {
			jobs.cancel(org.eclipse.core.resources.ResourcesPlugin.FAMILY_MANUAL_BUILD);
			jobs.cancel(org.eclipse.core.resources.ResourcesPlugin.FAMILY_AUTO_BUILD);
			cleared.put("buildsCancelled", Integer.valueOf(builds)) //$NON-NLS-1$
					.put("buildNote", //$NON-NLS-1$
							"Cancelled rather than waited for: a build has no state worth carrying across a restart, and the workspace rebuilds afterwards."); //$NON-NLS-1$
		}
		JsonArray terminated = new JsonArray();
		JsonArray leftRunning = new JsonArray();
		for (org.eclipse.debug.core.ILaunch launch : org.eclipse.debug.core.DebugPlugin.getDefault()
				.getLaunchManager().getLaunches()) {
			if (!startedByMcp(launch) || launch.isTerminated() || !launch.canTerminate()) {
				continue;
			}
			try {
				launch.terminate();
				terminated.add(nameOf(launch));
			} catch (org.eclipse.core.runtime.CoreException e) {
				leftRunning.add(nameOf(launch));
			}
		}
		if (terminated.size() > 0 || leftRunning.size() > 0) {
			cleared.put("launchesTerminated", terminated) //$NON-NLS-1$
					.put("launchNote", //$NON-NLS-1$
							"Only launches this server started. A launch the person at the IDE started is left alone, and it will outlive the restart."); //$NON-NLS-1$
		}
		if (leftRunning.size() > 0) {
			cleared.put("launchesLeftRunning", leftRunning) //$NON-NLS-1$
					.put("launchWarning", //$NON-NLS-1$
							"These refused to terminate and will survive the restart, still holding whatever workspace or port they took."); //$NON-NLS-1$
		}
		return cleared;
	}

	private static boolean startedByMcp(org.eclipse.debug.core.ILaunch launch) {
		try {
			return launch.getLaunchConfiguration() != null && launch.getLaunchConfiguration()
					.getAttribute(com.vogella.eclipse.mcp.core.LaunchAttributes.STARTED_BY_MCP, false);
		} catch (org.eclipse.core.runtime.CoreException e) {
			return false;
		}
	}

	private static String nameOf(org.eclipse.debug.core.ILaunch launch) {
		return launch.getLaunchConfiguration() == null ? "unnamed" : launch.getLaunchConfiguration().getName(); //$NON-NLS-1$
	}

	/**
	 * The workspace the IDE is in, as a path rather than a URL.
	 * <p>
	 * A path is what the workspace argument takes, so what this reports can be
	 * handed straight back to get here again.
	 */
	private static String workspaceLocation() {
		var location = org.eclipse.core.runtime.Platform.getInstanceLocation();
		if (location == null || location.getURL() == null) {
			return null;
		}
		java.nio.file.Path path = pathOf(location.getURL().toString());
		return path == null ? location.getURL().toString() : path.toString();
	}
}
