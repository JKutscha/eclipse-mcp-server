package com.vogella.eclipse.mcp.git.internal;

import java.io.File;
import java.io.IOException;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.egit.core.RepositoryCache;
import org.eclipse.jgit.lib.Repository;

/**
 * Finds the repository a request is about.
 * <p>
 * Every reference to EGit and JGit sits behind this class and the two tools, and
 * both bundles are optional: without them the tools report that EGit is not
 * installed rather than the server failing to resolve.
 */
final class EGit {

	static final String NOT_INSTALLED = "EGit is not installed in this IDE, so the git tools cannot work. Install the 'Git integration for Eclipse' feature, or use eclipse_run_command to drive git directly, which leaves the workspace unaware of the change until it is refreshed."; //$NON-NLS-1$

	private EGit() {
	}

	/** Whether the optional EGit bundles resolved. */
	static boolean isAvailable() {
		try {
			Class.forName("org.eclipse.egit.core.RepositoryCache"); //$NON-NLS-1$
			return true;
		} catch (ClassNotFoundException | LinkageError e) {
			return false;
		}
	}

	/**
	 * The repository for a project name or a directory on disk, or {@code null}.
	 * <p>
	 * A project name is resolved through EGit's own mapping, so a project inside a
	 * repository resolves the same way it does in the Git Repositories view. A path
	 * is looked up directly, which is what makes a repository outside the workspace
	 * reachable at all.
	 */
	static Repository lookup(String projectName, String directory) throws IOException {
		if (projectName != null && !projectName.isBlank()) {
			IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName.strip());
			return project.isAccessible() ? RepositoryCache.INSTANCE.getRepository(project) : null;
		}
		if (directory == null || directory.isBlank()) {
			return null;
		}
		File file = new File(directory.strip());
		Repository known = RepositoryCache.INSTANCE.getRepository(new org.eclipse.core.runtime.Path(file.getPath()));
		return known == null ? RepositoryCache.INSTANCE.lookupRepository(gitDir(file)) : known;
	}

	/** {@code .git} under the directory, or the directory itself when it already is one. */
	private static File gitDir(File directory) {
		File candidate = new File(directory, ".git"); //$NON-NLS-1$
		return candidate.exists() ? candidate : directory;
	}
}
