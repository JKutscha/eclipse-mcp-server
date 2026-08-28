package com.vogella.eclipse.mcp.core.internal;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipEntry;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.Platform;

import com.vogella.eclipse.mcp.core.IMcpTool;
import com.vogella.eclipse.mcp.core.McpToolResult;
import com.vogella.eclipse.mcp.core.ToolArguments;
import com.vogella.eclipse.mcp.core.json.JsonArray;
import com.vogella.eclipse.mcp.core.json.JsonObject;

/**
 * Runs the IDE against a workspace project's bundle instead of the installed
 * one.
 */
public final class SubstituteBundleTool implements IMcpTool {

	/** Where the packed jars go, next to the installation rather than in it. */
	private static final String JARS = "mcp-substituted"; //$NON-NLS-1$

	/** What was replaced and with what, so a restore needs no knowledge from the caller. */
	private static final String RECORD = "mcp-substituted/substitutions.txt"; //$NON-NLS-1$

	private static final String BUNDLES_INFO = "org.eclipse.equinox.simpleconfigurator/bundles.info"; //$NON-NLS-1$

	@Override
	public String getName() {
		return "eclipse_substitute_bundle"; //$NON-NLS-1$
	}

	@Override
	public String getDescription() {
		return "Makes the IDE run a workspace project's bundle in place of the installed one at the next restart, by packing the project and pointing this installation's bundles.info line at the packed jar. CHANGES THE INSTALLATION, not the workspace, and runs as a dry run unless dryRun is set to false; the dry run shows the exact line before and after. THIS IS THE ONLY WAY IN FOR MOST OF THE SDK: a hot install through eclipse_install_bundle is invisible to anything that reads the registry once at startup, the theme engine among them, and the dropins directory cannot replace a bundle that belongs to an installed feature, because a feature demands its bundles at an exact version, which covers nearly everything in an SDK. THE RISK IS REAL: a bundles.info that names a jar which does not resolve leaves an IDE that will not start, and then no tool here can put it back. The original line is recorded, so action restore needs nothing from the caller, and action status reports what is substituted right now, checked against bundles.info rather than believed from the record, including a substitution another session made, which is what stops somebody debugging an IDE that is not running what its plugins directory holds. THE LINE DOES NOT SURVIVE A RESTART UNCHANGED: simpleconfigurator rewrites bundles.info at every start, taking the version from the substituted jar's own manifest and making the path relative to the installation, so the text written here is not the text found afterwards. Everything here therefore matches on the bundle name, which is the one stable field."; //$NON-NLS-1$
	}

	@Override
	public String getInputSchema() {
		return """
				{
				  "type": "object",
				  "properties": {
				    "action":  {"type":"string","enum":["substitute","restore","status"],"default":"status","description":"'substitute' packs the project and points bundles.info at it, 'restore' puts the recorded original lines back, 'status' only reports."},
				    "jar":     {"type":"string","description":"Absolute path of a jar that is already built, used instead of 'project'. Its Bundle-SymbolicName and Bundle-Version are read from its own manifest rather than guessed from the file name, which for a Maven build matches neither. The jar is copied, so rebuilding it afterwards does not silently change what this IDE runs."},
				    "project": {"type":"string","description":"Plug-in project to pack, for substitute. Its output folder and the bin.includes of build.properties are what goes into the jar. CHECK WHICH CLONE IT IS: the answer reports packedFrom, because a workspace project can point at one clone of a repository while the change being measured lives in another, and then this packs a tree without it."},
				    "dryRun":  {"type":"boolean","default":true,"description":"Report the line that would change, and change nothing."}
				  },
				  "additionalProperties": false
				}"""; //$NON-NLS-1$
	}

	@Override
	public McpToolResult call(Map<String, Object> arguments, IProgressMonitor monitor) {
		ToolArguments args = ToolArguments.of(arguments);
		String action = args.getString("action", "status"); //$NON-NLS-1$ //$NON-NLS-2$
		Path configuration = configurationDirectory();
		if (configuration == null) {
			return McpToolResult.error(
					"This IDE has no writable configuration directory, so bundles.info cannot be read or changed."); //$NON-NLS-1$
		}
		Path bundlesInfo = configuration.resolve(BUNDLES_INFO);
		if (!Files.isRegularFile(bundlesInfo)) {
			// asking what is substituted is answerable even here, and the answer is
			// nothing; only changing something needs the file to exist
			if ("status".equals(action)) { //$NON-NLS-1$
				return McpToolResult.of(new JsonObject().put("substituted", new JsonArray()) //$NON-NLS-1$
						.put("count", Integer.valueOf(0)) //$NON-NLS-1$
						.put("note", //$NON-NLS-1$
								"This installation has no bundles.info at %s, so it is not managed by simpleconfigurator and nothing can be substituted in it." //$NON-NLS-1$
										.formatted(bundlesInfo))
						.toString());
			}
			return McpToolResult.error("No bundles.info at %s, so this installation is not managed by simpleconfigurator." //$NON-NLS-1$
					.formatted(bundlesInfo));
		}
		try {
			return switch (action) {
			case "status" -> McpToolResult.of(status(configuration, bundlesInfo).toString()); //$NON-NLS-1$
			case "restore" -> restore(configuration, bundlesInfo, args.getBoolean("dryRun", true)); //$NON-NLS-1$ //$NON-NLS-2$
			case "substitute" -> substitute(configuration, bundlesInfo, args, monitor);
			default -> McpToolResult.error("'action' is 'substitute', 'restore' or 'status'."); //$NON-NLS-1$
			};
		} catch (IOException e) {
			return McpToolResult.error("Could not work with the installation: " + e); //$NON-NLS-1$
		}
	}

	/**
	 * What is substituted right now, checked against the file rather than believed
	 * from the record.
	 * <p>
	 * The two can disagree, and that disagreement is what made a restore fail
	 * silently once: simpleconfigurator rewrites bundles.info at every start, and
	 * it normalises what it writes, taking the version from the jar's own manifest
	 * and making the path relative to the installation. The recorded line is then
	 * no longer in the file even though the substitution is still in force.
	 */
	private static JsonObject status(Path configuration, Path bundlesInfo) throws IOException {
		List<String[]> records = records(configuration);
		List<String> lines = Files.isRegularFile(bundlesInfo)
				? Files.readAllLines(bundlesInfo, StandardCharsets.UTF_8)
				: List.of();
		JsonArray active = new JsonArray();
		int stillSubstituted = 0;
		for (String[] record : records) {
			String current = lineFor(lines, record[0]);
			String state = current == null ? "missing" //$NON-NLS-1$
					: current.equals(record[1]) ? "restored" : "substituted"; //$NON-NLS-1$ //$NON-NLS-2$
			if ("substituted".equals(state)) { //$NON-NLS-1$
				stillSubstituted++;
			}
			JsonObject entry = new JsonObject().put("bundle", record[0]) //$NON-NLS-1$
					.put("state", state) //$NON-NLS-1$
					.put("originalLine", record[1]) //$NON-NLS-1$
					.put("recordedLine", record[2]) //$NON-NLS-1$
					.put("currentLine", current); //$NON-NLS-1$
			if (current != null && !current.equals(record[2]) && "substituted".equals(state)) { //$NON-NLS-1$
				entry.put("rewritten", //$NON-NLS-1$
						"The line differs from what was written: simpleconfigurator rewrote it at a restart, normalising the version from the jar's manifest and the path relative to the installation. It is still the substituted jar."); //$NON-NLS-1$
			}
			active.add(entry);
		}
		return new JsonObject().put("substituted", active) //$NON-NLS-1$
				.put("count", Integer.valueOf(active.size())) //$NON-NLS-1$
				.put("stillInForce", Integer.valueOf(stillSubstituted)) //$NON-NLS-1$
				.put("note", stillSubstituted == 0 //$NON-NLS-1$
						? "No substitution is in force; this IDE runs what its plugins directory holds. A record with state 'restored' is history and can be forgotten." //$NON-NLS-1$
						: "These bundles are NOT the installed ones, checked against bundles.info rather than taken from the record. The record survives restarts and sessions, so this is also what another session's substitution looks like. action restore puts them back, and it takes a restart either way."); //$NON-NLS-1$
	}

	/** The line for a bundle, found by its name, which is the one stable field. */
	private static String lineFor(List<String> lines, String bundle) {
		for (String line : lines) {
			if (line.startsWith(bundle + ",")) { //$NON-NLS-1$
				return line;
			}
		}
		return null;
	}

	private static McpToolResult restore(Path configuration, Path bundlesInfo, boolean dryRun) throws IOException {
		List<String[]> records = records(configuration);
		if (records.isEmpty()) {
			return McpToolResult.of(new JsonObject().put("restored", Integer.valueOf(0)) //$NON-NLS-1$
					.put("note", "Nothing was substituted, so there is nothing to put back.").toString()); //$NON-NLS-1$ //$NON-NLS-2$
		}
		List<String> lines = new ArrayList<>(Files.readAllLines(bundlesInfo, StandardCharsets.UTF_8));
		JsonArray done = new JsonArray();
		JsonArray missed = new JsonArray();
		for (String[] record : records) {
			int index = -1;
			for (int i = 0; i < lines.size(); i++) {
				// by bundle name, not by the whole line: simpleconfigurator rewrites
				// the version and the path form at every start, so the line written
				// here is not the line found later
				if (lines.get(i).startsWith(record[0] + ",")) { //$NON-NLS-1$
					index = i;
					break;
				}
			}
			if (index < 0) {
				missed.add(new JsonObject().put("bundle", record[0]) //$NON-NLS-1$
						.put("reason", "bundles.info has no line for it at all.")); //$NON-NLS-1$ //$NON-NLS-2$
				continue;
			}
			String current = lines.get(index);
			if (current.equals(record[1])) {
				missed.add(new JsonObject().put("bundle", record[0]) //$NON-NLS-1$
						.put("reason", "It already holds the original line; nothing to undo.")); //$NON-NLS-1$ //$NON-NLS-2$
				continue;
			}
			if (!dryRun) {
				lines.set(index, record[1]);
			}
			done.add(new JsonObject().put("bundle", record[0]) //$NON-NLS-1$
					.put("was", current) //$NON-NLS-1$
					.put("line", record[1])); //$NON-NLS-1$
		}
		if (!dryRun && done.size() > 0) {
			Files.write(bundlesInfo, lines, StandardCharsets.UTF_8);
			Files.deleteIfExists(configuration.resolve(RECORD));
		}
		JsonObject result = new JsonObject().put("dryRun", Boolean.valueOf(dryRun)) //$NON-NLS-1$
				.put("restored", done) //$NON-NLS-1$
				.put("restoredCount", Integer.valueOf(done.size())); //$NON-NLS-1$
		if (missed.size() > 0) {
			result.put("notRestored", missed); //$NON-NLS-1$
		}
		if (done.size() == 0) {
			// saying "done" over an empty list is worse than an error: a caller reads
			// the note, stops looking, and keeps an IDE that is not what it thinks
			return McpToolResult.error(result
					.put("note", //$NON-NLS-1$
							"NOTHING WAS PUT BACK. Every recorded substitution is either already restored or has no line in bundles.info, so the file was not written. Check action status and the notRestored entries before assuming this IDE runs its installed bundles.") //$NON-NLS-1$
					.toString());
		}
		return McpToolResult.of(result.put("restartRequired", Boolean.TRUE) //$NON-NLS-1$
				.put("note", dryRun ? "Nothing was changed. Pass dryRun false to put these lines back." //$NON-NLS-1$ //$NON-NLS-2$
						: "The installed bundles are back in bundles.info; restart with eclipse_restart for the IDE to run them. The packed jars under configuration/mcp-substituted are no longer referenced and can be deleted.") //$NON-NLS-1$
				.toString());
	}

	private static McpToolResult substitute(Path configuration, Path bundlesInfo, ToolArguments args,
			IProgressMonitor monitor) throws IOException {
		if (args.getString("jar") != null) { //$NON-NLS-1$
			return substituteJar(configuration, bundlesInfo, args);
		}
		String projectName = args.getString("project"); //$NON-NLS-1$
		if (projectName == null) {
			return McpToolResult.error("Name what to substitute with: 'project' to pack a workspace project, or 'jar' for one that is already built."); //$NON-NLS-1$
		}
		IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
		if (!project.isAccessible() || project.getLocation() == null) {
			return McpToolResult.error("No open project named '%s' in this workspace.".formatted(projectName)); //$NON-NLS-1$
		}
		Path projectPath = project.getLocation().toFile().toPath();
		Path manifest = projectPath.resolve("META-INF/MANIFEST.MF"); //$NON-NLS-1$
		if (!Files.isRegularFile(manifest)) {
			return McpToolResult.error("'%s' has no META-INF/MANIFEST.MF, so it is not a plug-in project." //$NON-NLS-1$
					.formatted(projectName));
		}
		String symbolicName = symbolicName(manifest);
		if (symbolicName == null) {
			return McpToolResult.error("Could not read Bundle-SymbolicName from %s.".formatted(manifest)); //$NON-NLS-1$
		}

		List<String> lines = new ArrayList<>(Files.readAllLines(bundlesInfo, StandardCharsets.UTF_8));
		int index = -1;
		for (int i = 0; i < lines.size(); i++) {
			if (lines.get(i).startsWith(symbolicName + ",")) { //$NON-NLS-1$
				index = i;
				break;
			}
		}
		if (index < 0) {
			return McpToolResult.error(
					"bundles.info has no line for '%s', so this installation does not run that bundle and there is nothing to substitute." //$NON-NLS-1$
							.formatted(symbolicName));
		}
		String original = lines.get(index);
		String[] fields = original.split(","); //$NON-NLS-1$
		if (fields.length < 5) {
			return McpToolResult.error("The bundles.info line for '%s' is not in the expected five field form: %s" //$NON-NLS-1$
					.formatted(symbolicName, original));
		}
		Path jars = configuration.resolve(JARS);
		Path jar = jars.resolve("%s_%d.jar".formatted(symbolicName, Long.valueOf(System.currentTimeMillis()))); //$NON-NLS-1$
		String substituted = "%s,%s,%s,%s,%s".formatted(fields[0], fields[1], jar.toUri(), fields[3], fields[4]); //$NON-NLS-1$

		JsonObject result = new JsonObject().put("bundle", symbolicName) //$NON-NLS-1$
				.put("project", projectName) //$NON-NLS-1$
				// the path, not just the name: a workspace can hold a project from one
				// clone while the patch being measured sits in another, and then this
				// packs the wrong tree and everything afterwards is measured wrongly
				.put("packedFrom", projectPath.toString()) //$NON-NLS-1$
				.put("originalLine", original) //$NON-NLS-1$
				.put("substitutedLine", substituted) //$NON-NLS-1$
				.put("jar", jar.toString()); //$NON-NLS-1$
		if (args.getBoolean("dryRun", true)) { //$NON-NLS-1$
			return McpToolResult.of(result.put("dryRun", Boolean.TRUE) //$NON-NLS-1$
					.put("note", //$NON-NLS-1$
							"Nothing was changed and no jar was packed. Pass dryRun false to carry it out; it needs a restart afterwards, and an unresolvable jar leaves an IDE that does not start.") //$NON-NLS-1$
					.toString());
		}

		Files.createDirectories(jars);
		int entries = pack(projectPath, outputFolder(projectPath), jar);
		lines.set(index, substituted);
		Files.write(bundlesInfo, lines, StandardCharsets.UTF_8);
		record(configuration, symbolicName, original, substituted);
		return McpToolResult.of(result.put("dryRun", Boolean.FALSE) //$NON-NLS-1$
				.put("entries", Integer.valueOf(entries)) //$NON-NLS-1$
				.put("restartRequired", Boolean.TRUE) //$NON-NLS-1$
				.put("note", //$NON-NLS-1$
						"Restart with eclipse_restart for the IDE to run this jar. Until then it still runs the installed bundle. The original line is recorded, so action restore puts it back without you keeping it, and action status reports the substitution to any session that asks.") //$NON-NLS-1$
				.toString());
	}

	/**
	 * Substitutes a jar that somebody else already built.
	 * <p>
	 * The identity comes from the jar's own manifest, never from its file name: a
	 * Maven build is called artifact-version-SNAPSHOT.jar and matches neither the
	 * symbolic name nor the OSGi version, so guessing from the name would point
	 * bundles.info at the wrong line or at none.
	 */
	private static McpToolResult substituteJar(Path configuration, Path bundlesInfo, ToolArguments args)
			throws IOException {
		Path source = Path.of(args.getString("jar")); //$NON-NLS-1$
		if (!Files.isRegularFile(source)) {
			return McpToolResult.error("There is no file at '%s'.".formatted(source)); //$NON-NLS-1$
		}
		String symbolicName;
		String version;
		try (java.util.jar.JarFile jarFile = new java.util.jar.JarFile(source.toFile())) {
			java.util.jar.Manifest manifest = jarFile.getManifest();
			if (manifest == null) {
				return McpToolResult.error("'%s' has no manifest, so it is not an OSGi bundle.".formatted(source)); //$NON-NLS-1$
			}
			String declared = manifest.getMainAttributes().getValue("Bundle-SymbolicName"); //$NON-NLS-1$
			version = manifest.getMainAttributes().getValue("Bundle-Version"); //$NON-NLS-1$
			symbolicName = declared == null ? null : declared.split(";")[0].strip(); //$NON-NLS-1$
		}
		if (symbolicName == null) {
			return McpToolResult.error("'%s' declares no Bundle-SymbolicName.".formatted(source)); //$NON-NLS-1$
		}

		List<String> lines = new ArrayList<>(Files.readAllLines(bundlesInfo, StandardCharsets.UTF_8));
		int index = -1;
		for (int i = 0; i < lines.size(); i++) {
			if (lines.get(i).startsWith(symbolicName + ",")) { //$NON-NLS-1$
				index = i;
				break;
			}
		}
		if (index < 0) {
			return McpToolResult.error(
					"bundles.info has no line for '%s', which is what %s declares, so this installation does not run that bundle." //$NON-NLS-1$
							.formatted(symbolicName, source.getFileName()));
		}
		String original = lines.get(index);
		String[] fields = original.split(","); //$NON-NLS-1$
		if (fields.length < 5) {
			return McpToolResult.error("The bundles.info line for '%s' is not in the expected five field form: %s" //$NON-NLS-1$
					.formatted(symbolicName, original));
		}
		Path jars = configuration.resolve(JARS);
		Path copy = jars.resolve("%s_%d.jar".formatted(symbolicName, Long.valueOf(System.currentTimeMillis()))); //$NON-NLS-1$
		String substituted = "%s,%s,%s,%s,%s".formatted(fields[0], fields[1], copy.toUri(), fields[3], fields[4]); //$NON-NLS-1$

		JsonObject result = new JsonObject().put("bundle", symbolicName) //$NON-NLS-1$
				.put("jar", source.toString()) //$NON-NLS-1$
				.put("jarVersion", version) //$NON-NLS-1$
				.put("originalLine", original) //$NON-NLS-1$
				.put("substitutedLine", substituted); //$NON-NLS-1$
		if (args.getBoolean("dryRun", true)) { //$NON-NLS-1$
			return McpToolResult.of(result.put("dryRun", Boolean.TRUE) //$NON-NLS-1$
					.put("note", //$NON-NLS-1$
							"Nothing was changed and nothing was copied. Pass dryRun false to carry it out; it needs a restart afterwards, and an unresolvable jar leaves an IDE that does not start.") //$NON-NLS-1$
					.toString());
		}
		Files.createDirectories(jars);
		// copied rather than referenced in place: a later rebuild of the source jar
		// would otherwise change what this IDE runs without anybody saying so
		Files.copy(source, copy, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
		lines.set(index, substituted);
		Files.write(bundlesInfo, lines, StandardCharsets.UTF_8);
		record(configuration, symbolicName, original, substituted);
		return McpToolResult.of(result.put("dryRun", Boolean.FALSE) //$NON-NLS-1$
				.put("copiedTo", copy.toString()) //$NON-NLS-1$
				.put("restartRequired", Boolean.TRUE) //$NON-NLS-1$
				.put("note", //$NON-NLS-1$
						"Restart with eclipse_restart for the IDE to run this jar. The copy is what is referenced, so rebuilding the source jar changes nothing until this is called again. action restore puts the original line back.") //$NON-NLS-1$
				.toString());
	}

	/**
	 * Packs the project the way PDE would: the compiled output, plus what
	 * build.properties lists under bin.includes, which is where the manifest, the
	 * plugin.xml and any css or icons live.
	 */
	private static int pack(Path projectPath, Path output, Path jar) throws IOException {
		List<String> includes = binIncludes(projectPath);
		int written = 0;
		try (OutputStream stream = Files.newOutputStream(jar); JarOutputStream out = new JarOutputStream(stream)) {
			if (Files.isDirectory(output)) {
				written += copyTree(output, output, out);
			}
			for (String include : includes) {
				if (".".equals(include)) { //$NON-NLS-1$
					continue;
				}
				Path source = projectPath.resolve(include);
				if (Files.isDirectory(source)) {
					written += copyTree(projectPath, source, out);
				} else if (Files.isRegularFile(source)) {
					written += copyOne(projectPath, source, out);
				}
			}
		}
		return written;
	}

	private static int copyTree(Path base, Path directory, JarOutputStream out) throws IOException {
		int written = 0;
		try (var walk = Files.walk(directory)) {
			for (Path path : walk.filter(Files::isRegularFile).toList()) {
				written += copyOne(base, path, out);
			}
		}
		return written;
	}

	private static int copyOne(Path base, Path file, JarOutputStream out) throws IOException {
		String name = base.relativize(file).toString();
		try {
			out.putNextEntry(new ZipEntry(name));
			Files.copy(file, out);
			out.closeEntry();
			return 1;
		} catch (IOException e) {
			// a duplicate entry is the usual cause, when bin.includes names something the
			// output folder already carries; the first one written wins
			return 0;
		}
	}

	private static List<String> binIncludes(Path projectPath) throws IOException {
		Path properties = projectPath.resolve("build.properties"); //$NON-NLS-1$
		if (!Files.isRegularFile(properties)) {
			return List.of("META-INF/", "plugin.xml"); //$NON-NLS-1$ //$NON-NLS-2$
		}
		String text = Files.readString(properties).replace("\\\n", ""); //$NON-NLS-1$ //$NON-NLS-2$
		for (String line : text.split("\n")) { //$NON-NLS-1$
			String stripped = line.strip();
			if (stripped.startsWith("bin.includes")) { //$NON-NLS-1$
				List<String> values = new ArrayList<>();
				for (String value : stripped.substring(stripped.indexOf('=') + 1).split(",")) { //$NON-NLS-1$
					if (!value.isBlank()) {
						values.add(value.strip());
					}
				}
				return values;
			}
		}
		return List.of("META-INF/", "plugin.xml"); //$NON-NLS-1$ //$NON-NLS-2$
	}

	/**
	 * The compiled output, read from .classpath rather than through the Java model,
	 * so this bundle keeps no dependency on JDT for one path.
	 */
	private static Path outputFolder(Path projectPath) {
		Path classpath = projectPath.resolve(".classpath"); //$NON-NLS-1$
		if (Files.isRegularFile(classpath)) {
			try {
				java.util.regex.Matcher matcher = java.util.regex.Pattern
						.compile("kind=\"output\"\\s+path=\"([^\"]+)\"") //$NON-NLS-1$
						.matcher(Files.readString(classpath));
				if (matcher.find()) {
					return projectPath.resolve(matcher.group(1));
				}
			} catch (IOException | RuntimeException e) {
				// an unreadable .classpath is no reason to give up on the conventional name
			}
		}
		return projectPath.resolve("bin"); //$NON-NLS-1$
	}

	private static String symbolicName(Path manifest) throws IOException {
		for (String line : Files.readAllLines(manifest, StandardCharsets.UTF_8)) {
			if (line.startsWith("Bundle-SymbolicName:")) { //$NON-NLS-1$
				return line.substring("Bundle-SymbolicName:".length()).split(";")[0].strip(); //$NON-NLS-1$ //$NON-NLS-2$
			}
		}
		return null;
	}

	private static List<String[]> records(Path configuration) throws IOException {
		Path record = configuration.resolve(RECORD);
		if (!Files.isRegularFile(record)) {
			return List.of();
		}
		List<String[]> records = new ArrayList<>();
		for (String line : Files.readAllLines(record, StandardCharsets.UTF_8)) {
			String[] parts = line.split("\t", 3); //$NON-NLS-1$
			if (parts.length == 3) {
				records.add(parts);
			}
		}
		return records;
	}

	private static void record(Path configuration, String bundle, String original, String substituted)
			throws IOException {
		Path record = configuration.resolve(RECORD);
		Files.createDirectories(record.getParent());
		String line = "%s\t%s\t%s%n".formatted(bundle, original, substituted); //$NON-NLS-1$
		Files.writeString(record, line, StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.CREATE,
				java.nio.file.StandardOpenOption.APPEND);
	}

	private static Path configurationDirectory() {
		var location = Platform.getConfigurationLocation();
		if (location == null || location.getURL() == null) {
			return null;
		}
		try {
			return Path.of(location.getURL().toURI());
		} catch (java.net.URISyntaxException | RuntimeException e) {
			return null;
		}
	}
}
