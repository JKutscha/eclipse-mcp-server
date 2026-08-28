package com.vogella.eclipse.mcp.core.internal;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;

import com.vogella.eclipse.mcp.core.IMcpTool;
import com.vogella.eclipse.mcp.core.McpToolResult;
import com.vogella.eclipse.mcp.core.ToolArguments;
import com.vogella.eclipse.mcp.core.json.JsonArray;
import com.vogella.eclipse.mcp.core.json.JsonObject;

/**
 * Saves the workspace on demand, the way shutting down does.
 */
public final class SaveWorkspaceTool implements IMcpTool {

	@Override
	public String getName() {
		return "eclipse_save_workspace"; //$NON-NLS-1$
	}

	@Override
	public String getDescription() {
		return "Saves the workspace, which is what the IDE otherwise only does while shutting down. MODIFIES THE WORKSPACE METADATA, and a full save is NOT a read-only operation: it writes the element tree, the markers and the sync info of every project, moves the save number on, deletes the snapshots and runs the local history pruning, which removes file states by the history policy. That is the same work an exit does, so it is repeatable and measurable here rather than only observable once per process. It takes the workspace root scheduling rule, so nothing else can change the workspace while it runs, and it runs in a job rather than on the calling thread. THE INTERESTING PART OF THE ANSWER IS THE STATUS: each save participant contributes its own child status, so a plug-in that fails or complains while saving is named instead of vanishing into one number. mode snapshot writes only what changed since the last full save and skips the pruning, which is what the workspace does periodically by itself."; //$NON-NLS-1$
	}

	@Override
	public String getInputSchema() {
		return """
				{
				  "type": "object",
				  "properties": {
				    "mode":           {"type":"string","enum":["full","snapshot"],"default":"full","description":"'full' is the exit-time save: whole tree, all markers and sync info, snapshots reset, local history pruned. 'snapshot' only appends what changed and prunes nothing."},
				    "timeoutSeconds": {"type":"integer","default":25,"minimum":1,"maximum":600,"description":"How long to wait before answering with state 'running'. The save keeps going either way; it holds the workspace rule until it is done."},
				    "dryRun":         {"type":"boolean","default":true,"description":"Report what a save would work on and change nothing: the projects, the marker count, and for a full save how much local history there is against the policy that would prune it. On by default like every other changing tool here, because that pruning deletes file versions somebody may want back; pass false to actually save."}
				  },
				  "additionalProperties": false
				}"""; //$NON-NLS-1$
	}

	@Override
	public McpToolResult call(Map<String, Object> arguments, IProgressMonitor monitor) {
		ToolArguments args = ToolArguments.of(arguments);
		boolean full = !"snapshot".equals(args.getString("mode", "full")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
		int timeoutSeconds = args.getInt("timeoutSeconds", 25, 1, 600); //$NON-NLS-1$
		if (args.getBoolean("dryRun", true)) { //$NON-NLS-1$
			return McpToolResult.of(preview(full).toString());
		}

		CountDownLatch done = new CountDownLatch(1);
		IStatus[] outcome = new IStatus[1];
		long[] elapsed = new long[1];
		Job job = Job.create("MCP workspace save", progress -> { //$NON-NLS-1$
			long startedAt = System.currentTimeMillis();
			try {
				outcome[0] = ResourcesPlugin.getWorkspace().save(full, progress);
			} catch (CoreException e) {
				outcome[0] = e.getStatus();
			} finally {
				elapsed[0] = System.currentTimeMillis() - startedAt;
				done.countDown();
			}
			return Status.OK_STATUS;
		});
		// the root rule is what the save takes internally; asking for it here means
		// the job queues behind other workspace work instead of fighting it
		job.setRule(ResourcesPlugin.getWorkspace().getRoot());
		job.setUser(false);
		job.schedule();

		boolean finished;
		try {
			finished = done.await(timeoutSeconds, TimeUnit.SECONDS);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return McpToolResult.error("Interrupted while waiting for the save."); //$NON-NLS-1$
		}
		JsonObject result = new JsonObject().put("mode", full ? "full" : "snapshot"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
		if (!finished) {
			return McpToolResult.of(result.put("state", "running") //$NON-NLS-1$ //$NON-NLS-2$
					.put("waitedSeconds", Integer.valueOf(timeoutSeconds)) //$NON-NLS-1$
					.put("note", //$NON-NLS-1$
							"Still saving after %d seconds, and it holds the workspace rule until it finishes, so anything else touching the workspace waits. Ask again with a longer timeoutSeconds to see the outcome." //$NON-NLS-1$
									.formatted(Integer.valueOf(timeoutSeconds)))
					.toString());
		}
		return McpToolResult.of(result.put("state", "done") //$NON-NLS-1$ //$NON-NLS-2$
				.put("elapsedMillis", Long.valueOf(elapsed[0])) //$NON-NLS-1$
				.put("status", describe(outcome[0])) //$NON-NLS-1$
				.put("note", note(full)) //$NON-NLS-1$
				.toString());
	}

	/**
	 * What a save would work on, since a save has no list of items to show.
	 * <p>
	 * The figures are chosen for the one decision this dry run exists for: a full
	 * save prunes local history by the workspace's own policy, and without knowing
	 * how much history there is and what the policy allows, that cost is invisible
	 * until the files are gone.
	 */
	private static JsonObject preview(boolean full) {
		var workspace = ResourcesPlugin.getWorkspace();
		var description = workspace.getDescription();
		int open = 0;
		for (var project : workspace.getRoot().getProjects()) {
			if (project.isOpen()) {
				open++;
			}
		}
		JsonObject json = new JsonObject().put("mode", full ? "full" : "snapshot") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
				.put("dryRun", Boolean.TRUE) //$NON-NLS-1$
				.put("projects", new JsonObject().put("total", //$NON-NLS-1$ //$NON-NLS-2$
						Integer.valueOf(workspace.getRoot().getProjects().length)).put("open", Integer.valueOf(open))) //$NON-NLS-1$
				.put("markers", markers()) //$NON-NLS-1$
				.put("autoBuilding", Boolean.valueOf(workspace.isAutoBuilding())); //$NON-NLS-1$
		if (full) {
			json.put("localHistory", history(description)); //$NON-NLS-1$
		}
		return json.put("note", "Nothing was saved. " + note(full) + " Pass dryRun false to carry it out."); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
	}

	/** The markers a full save writes out, which is the bulk of visitAndSave. */
	private static Object markers() {
		try {
			return Integer.valueOf(ResourcesPlugin.getWorkspace().getRoot()
					.findMarkers(null, true, org.eclipse.core.resources.IResource.DEPTH_INFINITE).length);
		} catch (CoreException | RuntimeException e) {
			return null;
		}
	}

	/**
	 * How much local history there is and what the policy would keep, which is the
	 * part of a full save that deletes something.
	 */
	private static JsonObject history(org.eclipse.core.resources.IWorkspaceDescription description) {
		JsonObject json = new JsonObject()
				.put("maxFileStates", Integer.valueOf(description.getMaxFileStates())) //$NON-NLS-1$
				.put("maxFileStateSize", Long.valueOf(description.getMaxFileStateSize())) //$NON-NLS-1$
				.put("longevityDays", Long.valueOf(description.getFileStateLongevity() / (24L * 60 * 60 * 1000))); //$NON-NLS-1$
		var location = ResourcesPlugin.getWorkspace().getRoot().getLocation();
		if (location == null) {
			return json;
		}
		java.nio.file.Path store = location.toFile().toPath()
				.resolve(".metadata/.plugins/org.eclipse.core.resources/.history"); //$NON-NLS-1$
		if (!java.nio.file.Files.isDirectory(store)) {
			return json.put("bytes", Long.valueOf(0)).put("files", Integer.valueOf(0)); //$NON-NLS-1$ //$NON-NLS-2$
		}
		long bytes = 0;
		int files = 0;
		try (var walk = java.nio.file.Files.walk(store)) {
			for (java.nio.file.Path path : walk.filter(java.nio.file.Files::isRegularFile).toList()) {
				files++;
				bytes += path.toFile().length();
			}
		} catch (java.io.IOException | RuntimeException e) {
			return json.put("note", "The history store could not be measured: " + e.getMessage()); //$NON-NLS-1$ //$NON-NLS-2$
		}
		return json.put("bytes", Long.valueOf(bytes)).put("files", Integer.valueOf(files)) //$NON-NLS-1$ //$NON-NLS-2$
				.put("note", //$NON-NLS-1$
						"A full save applies the policy above to these files and deletes what falls outside it. That is the one part of a save that removes something a person might want back."); //$NON-NLS-1$
	}

	private static String note(boolean full) {
		if (full) {
			return "This was the full save an exit performs: the tree, the markers and the sync info of every project were rewritten, the snapshots were reset and the local history was pruned by policy. Running it again measures the same work, which is what makes it usable for profiling; each run also does that work for real."; //$NON-NLS-1$
		}
		return "A snapshot appends what changed since the last full save and prunes nothing, so it is cheap and is what the workspace does on its own from time to time."; //$NON-NLS-1$
	}

	/**
	 * The status with its children.
	 * <p>
	 * The children are the point: the save broadcasts to every save participant and
	 * merges what each returns, so a plug-in that complains while saving appears
	 * here by name rather than as one severity on the whole operation.
	 */
	private static JsonObject describe(IStatus status) {
		if (status == null) {
			return null;
		}
		JsonObject json = new JsonObject().put("severity", severity(status.getSeverity())) //$NON-NLS-1$
				.put("plugin", status.getPlugin()) //$NON-NLS-1$
				.put("message", status.getMessage()); //$NON-NLS-1$
		if (status.getException() != null) {
			json.put("exception", String.valueOf(status.getException())); //$NON-NLS-1$
		}
		if (status.getChildren().length > 0) {
			JsonArray children = new JsonArray();
			for (IStatus child : status.getChildren()) {
				children.add(describe(child));
			}
			json.put("children", children); //$NON-NLS-1$
		}
		return json;
	}

	private static String severity(int severity) {
		return switch (severity) {
		case IStatus.ERROR -> "error"; //$NON-NLS-1$
		case IStatus.WARNING -> "warning"; //$NON-NLS-1$
		case IStatus.INFO -> "info"; //$NON-NLS-1$
		case IStatus.CANCEL -> "cancel"; //$NON-NLS-1$
		default -> "ok"; //$NON-NLS-1$
		};
	}
}
