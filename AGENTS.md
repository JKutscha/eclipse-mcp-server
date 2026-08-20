# AGENTS.md

Guidance for coding agents working in this repository.
See `README.md` for what the feature does and how a user installs it.

## What this is

An Eclipse plug-in project that turns a running IDE into an MCP server.
Built with Maven and Tycho, pomless: the root `pom.xml` is the only pom in the repository.

## Build and test

```bash
mvn clean verify
```

Requires JDK 25 and Maven 3.9 or newer.
Always run this from the repository root.
Running it from inside a module directory resolves that module alone and fails, because the sibling bundles are then missing from the reactor.

The first run downloads the Eclipse SDK for the target platform, so expect it to take a while.
Later runs reuse the Tycho cache under `~/.m2/repository/.cache/tycho`.

There is no faster partial build worth using: `mvn verify -pl` on a single bundle still resolves the whole target platform.

## Layout

```
plugins/com.vogella.eclipse.mcp.core     tool API, registry, extension point, workspace tools
plugins/com.vogella.eclipse.mcp.server   MCP protocol, embedded Jetty, bearer token
plugins/com.vogella.eclipse.mcp.jdt      Java model tools
plugins/com.vogella.eclipse.mcp.ui       editor context tool, preference page, startup hook
plugins/com.vogella.eclipse.mcp.pde      PDE tools
features/com.vogella.eclipse.mcp.feature
tests/com.vogella.eclipse.mcp.core.tests    the tools, headless
tests/com.vogella.eclipse.mcp.server.tests  the HTTP endpoint, driven by a real MCP client
target-platform/com.vogella.eclipse.mcp.target
update-site/com.vogella.eclipse.mcp.repository
releng/update-composite-site.sh
```

## Rules that are not obvious from the code

**`com.vogella.eclipse.mcp.core` must stay clean.**
No reference to the MCP SDK, to Jetty or to any UI bundle.
The bundle is meant to stay a candidate for contribution to the Eclipse Platform, and the split exists only for that reason.
It also has no JSON library, which is why it carries the small writer in `com.vogella.eclipse.mcp.core.json`.

**Most tools are read-only, and the exceptions are deliberate.**
`eclipse_organize_imports` and `eclipse_format` modify the file they are given, `eclipse_build` runs builders, and `eclipse_get_problems` triggers a build when auto-build is off.
Everything else must not write, and no tool may open a dialog or perform a refactoring.
A new tool that writes has to say so in its own description, because that is the only place the model sees it.

**Threading.**
Tool calls arrive on Jetty worker threads.
Never call `Display.syncExec` from one; hand work to `asyncExec` and wait on a future with a short timeout, the way `GetEditorContextTool` does.
Marker reads and JDT searches are safe off the UI thread and need no workspace lock.
The server aborts any call that has not finished within the configured call timeout, 30 seconds by default.
`McpToolAdapter` reads `McpPreferences.getCallTimeout()` per call, so a changed preference applies without a restart.
A tool that can outlast that timeout must not block on it; start a job and hand back a handle, the way `eclipse_build` and `eclipse_get_build_status` do.

**Every list-returning tool honours `maxResults` and reports `total` and `truncated`.**
A new tool that returns a list without those fields is incomplete.

**Anything derived from files must refresh first.**
A client edits through its own shell, so the workspace does not know about those edits until `WorkspaceSync.refresh` runs.
`eclipse_get_problems` does it by default and reports `upToDate`; the two editing tools refresh the file they touch.
Reporting stale markers as if they were current is worse than returning nothing.

## Adding a tool

1. Implement `com.vogella.eclipse.mcp.core.IMcpTool`.
2. Contribute it through the `com.vogella.eclipse.mcp.core.tools` extension point in the `plugin.xml` of the bundle that owns it.
3. Put it in the bundle whose dependencies it needs, so that the layering above survives.
4. Add a test. `McpToolRegistryTest` already checks that every registered tool has a name, a description and an input schema that parses as JSON.

## Gotchas already paid for

Do not undo these without understanding why they are there.

**`.mvn/maven.config` sets `tycho.pomless.aggregator.names`.**
Pomless Tycho only treats `bundles,plugins,tests,features,sites,products,releng` as aggregator directories.
Without the override, `update-site/` is not recognised and the build fails with "Child module .../update-site/pom.xml does not exist".

**JUnit comes from the Eclipse SDK, not from Maven.**
The target platform already contains `junit-jupiter-api` 6.1.0 through the SDK feature.
Adding a Maven location for JUnit is redundant, and the resulting 5.x bundles lose to the SDK's 6.x ones anyway.
Note the bundle symbolic names are `junit-jupiter-api` and so on, not the `org.junit.jupiter.api` that older Orbit builds used.

**The bearer token is persisted, the port is not negotiated.**
`TokenStore` keeps the token in `<state>/token` with owner-only permissions so that client configurations survive restarts.
The server never falls back to a different port when the configured one is taken; it stays down and records the reason in `McpServerService.getLastError()`, which the preference page shows.
Silently moving to another port would break every configured client, which is worse than not starting.

**Field reads and writes are counted from separate searches, and the counts do not sum to `total`.**
`eclipse_find_references` runs `READ_ACCESSES` and `WRITE_ACCESSES` in addition to `REFERENCES` when the target is a field, and matches them up by path and offset.
A field initializer is a write access but not a reference, so it appears in `byKind` and never in `matches`; a compound assignment is both, and is tagged `readWrite`.
Do not "fix" the mismatch by making the numbers agree, it is the truth of what JDT reports.

**`OrganizeImportsTool` seeds JDT UI preferences.**
JDT reads the import order, the on-demand thresholds and the type filter from the `org.eclipse.jdt.ui` preference node, which only that plug-in registers.
Headless, or before the UI plug-in has started, the lookup returns null and the operation fails with a `NullPointerException` deep inside `CodeStyleConfiguration` or `TypeNameMatchCollector`.
`ensureCodeStylePreferences` sets the node id and fills the default scope only where nothing is set, so a user or project setting always wins.

**A refactoring change has to be initialised before it can be performed.**
`createChange` returns a change whose validation state is empty, and `PerformChangeOperation` then fails with "TextFileChange has not been initialialized".
`RenameTool` calls `initializeValidationData` first and runs the operation through `IWorkspace.run`.

**`RenameTool` needs the same jdt.ui preference node as `OrganizeImportsTool`.**
The rename processors read `JavaManipulation.getPreference`, which throws `IllegalArgumentException` out of `ProjectScope.getNode` when the node id is unset.
Renaming a field hits it through `GetterSetterUtil`; renaming a type does not, so this fails for one kind of rename and not another.

**`BundleJsonSchemaValidator` clears the context class loader on purpose.**
The networknt schema library reads its bundled meta-schemas through the context class loader and only falls back to its own when there is none.
Inside Equinox the context class loader cannot see them, so without this the server fails to start with `FileNotFoundException: classpath:draft/2020-12/schema`.
Any MCP client built inside the IDE needs the same treatment, as `McpServerServiceTest.schemaValidator()` shows.

**The MCP SDK is used with explicit `jsonMapper` and `jsonSchemaValidator`.**
Letting the SDK fall back to `McpJsonDefaults` makes it depend on `ServiceLoader` discovery across bundles, which is fragile under OSGi.

**`eclipse_get_log_entries` parses `.metadata/.log` instead of listening to `ILog`.**
A listener registered when the bundle starts cannot see anything logged before that, and loses everything from previous sessions, which is exactly where the interesting UI freezes and builder exceptions already are.
The file is the complete record and its `!ENTRY` / `!SUBENTRY` / `!MESSAGE` / `!STACK` format keeps multi status nesting and stack traces intact, so parsing it costs less than it looks.
`PlatformLogFile` is exported to the test bundle through `x-friends`, so the parser can be tested against fixtures without the tool reading an arbitrary caller-supplied path.

**A UI freeze is logged at severity WARNING, not ERROR.**
`org.eclipse.ui.monitoring` uses `IStatus.WARNING`, so `eclipse_get_log_entries` defaults to `severity: all` while `eclipse_get_problems` defaults to `error`.
The two defaults differ on purpose; do not align them.

**Everything slow belongs inside the job, the refresh included.**
The refresh first ran before the job was scheduled, so `wait: false` still blocked for its whole duration and an unscoped refresh of a large workspace blew the 30 second call timeout before the async path was ever reached.
It also refreshed the workspace root even when one project was named.
`BuildRegistry.Request` now carries the refresh, `scopes` limits it to the named projects, and `refreshMillis` and `buildMillis` are reported separately because the refresh can cost more than the build.

**`eclipse_build` returns a handle rather than blocking to completion.**
`BuildRegistry` runs the build as a job under the workspace build rule and keeps the last 20 outcomes, so a build longer than the call timeout is polled through `eclipse_get_build_status` instead of dying with the request.
`timeoutSeconds` defaults to 25 to sit under the default 30 second call timeout; core cannot read the server bundle's preference without breaking the layering, so the two numbers are kept in step by hand.

**A builder that throws does not fail the build, so `builderFailures` reads the log.**
`BuildManager` runs builders inside a `SafeRunner`: the exception is caught and logged, `IProject.build` returns normally, and `BuildRegistry.collect` finds nothing.
This was shipped broken once and caught only against a real workspace, where a clean build of `JavaEclipseProject` reported `builderFailures: []` while logging `JavaBuilder handling CoreException` in the same second.
`collectLogged` therefore also reports platform log errors and warnings from the build's time window.
It over-reports by design, because anything logged during the window is included; calling a broken build clean is worse.
The unit tests can only check that entries from before the build are excluded, since making a builder throw on demand is not worth the fixture. The positive case is verified against a real workspace.

**`eclipse_set_preference` writes an allowlist, `eclipse_get_preferences` reads anything.**
The asymmetry is the point: a wrongly set compiler or formatter preference is invisible and long-lived, so the writable qualifiers are the four in `SetPreferenceTool.ALLOWED_QUALIFIERS`.
Widening that list is a decision, not a fix.
Auto-build goes through `IWorkspaceDescription.setAutoBuilding`, not through a raw write of `description.autobuilding`, which is the usual way to get it subtly wrong.

**Platform mismatch is read from `Eclipse-PlatformFilter`, not from the project name.**
`PlatformFilters` parses the manifest header as an OSGi filter and matches it against `osgi.ws`, `osgi.os` and `osgi.arch`.
The name heuristic is the fallback for projects without the header, and the reason string says which of the two was used.
Do not promote the heuristic to the primary signal; it works for Eclipse's own naming convention and misfires everywhere else.

**Closing a project that others reference creates errors instead of removing them.**
`SetProjectStateTool` reports `openDependents` from `IProject.getReferencingProjects()`, which covers both the JDT build path and PDE required bundles, and refuses without `force`.
It also defaults to `dryRun`, and requires an explicit selection, so that no call can close the whole workspace by omission.

**`IBundleProjectDescription.apply()` does not touch `.classpath`.**
It writes the manifest header and nothing else, so `eclipse_set_bree` points the JRE container at the new environment itself with `JavaRuntime.newJREContainerPath`.
This was assumed to be automatic and the test caught it; do not remove `setJreContainer` on the belief that PDE reconciles the project.

**Only compliance, source and target are written, though the environment offers more.**
`IExecutionEnvironment.getComplianceOptions()` returns further options whose values do not round-trip through `IJavaProject.getOption`, so comparing all of them meant a project could never be seen as up to date and every run reported a change.
Write and compare exactly `COMPLIANCE_KEYS`.

**`com.vogella.eclipse.mcp.pde` needs `Bundle-ActivationPolicy: lazy`.**
It looks up `IBundleProjectService` from its own `BundleContext`, which is null while the bundle is merely resolved.
The tool falls back to PDE's own context and then to a readable error, rather than a `NullPointerException`.

**`callsEveryRegisteredTool` does not call every tool.**
`com.vogella.eclipse.mcp.server.tests` requires only core, server and jdt, so the ui, pde and p2 tools are not registered in that headless run and the smoke test cannot see them.
That is deliberate for `eclipse_restart`, which must never be invoked by a test, but do not read a green smoke test as protocol level coverage of the other bundles.

**The provisioning tools update the IDE that is running them.**
If a bad build lands, the tools that would fix it are the tools that just broke.
`eclipse_restart` therefore lives in the ui bundle and does not depend on the p2 bundle, so a half applied update can still be recovered, and every provisioning result carries the previous configuration timestamp so a human can revert from Installation History without the server.
`eclipse_install` refuses repositories the IDE is not already configured with: adding one fetches and runs code from a new source, which is the user's decision and not the server's.
Do not replace that allowlist with a single opt-in preference; a switch flipped once is never flipped back.

**Unscoped type resolution finds build output before source.**
`IJavaProject.findType` happily returns a class file from a product jar under `target/`, whose `getCompilationUnit()` is null.
`JavaModelSupport.findType` now prefers a source type and only falls back to a binary one, and `RenameTool` refuses a binary element outright, because `RenameTypeProcessor.checkInitialConditions` dereferences the compilation unit without checking and dies on a raw `NullPointerException`.

**The sampler's budget counts ticks, not stacks.**
Counting stacks meant that on an IDE with seventy live threads a budget of 200 was spent after three rounds, roughly 300 ms, so sampling stopped before the operation being profiled had started.
Parked and waiting threads are also excluded by default, otherwise `Unsafe.park` is reported as the hot frame of an idle IDE.

**Root capture returns a blank image on this machine, and on GTK4, without erroring.**
`GC.java` only calls `gdk_cairo_set_source_window` when `!GTK.GTK4`, so on GTK4 the group is painted empty and handed back as a valid image.
Separately, a compositing window manager redirects a window's contents into an offscreen pixmap, so reading the X11 root drawable through XWayland also yields uniform pixels.
The uniform check is therefore the only reliable signal, and `Control.print` is the fallback when it trips. It has GTK gaps, which is why it is second rather than first, but the alternative in that case is no image.
Do not add an environment check back to `unsupportedReason`: `WAYLAND_DISPLAY` and `XDG_SESSION_TYPE` stay set when `GDK_BACKEND=x11` binds X11 through XWayland, so the environment cannot distinguish a display that captures from one that does not. It was tried and it refused on a machine where `widgetPrint` works.

**Never interpolate a Java element into a message with `toString()`.**
`IPackageFragmentRoot.toString()` prints every package it contains, which turned one rename refusal into 221 lines with the useful advice at the bottom.
Use `getElementName()`, `getFullyQualifiedName()` or `JavaModelSupport.describe`.

**The sampler must not need the UI thread or a workspace lock.**
It exists to diagnose freezes, so anything that queues behind one is useless.
`ThreadMXBean` does not require the sampled thread to be responsive; do not replace it with anything that runs on the Display.
Note the wider caveat: tools that avoid the UI thread can still block on the workspace or Java model lock if the frozen UI thread holds one, so "MCP still answers while the IDE is frozen" is reliably true for thread contention and only usually true for lock contention.

**The feature lists third party bundles the Eclipse SDK does not ship.**
Jetty ee11, the MCP SDK, Jackson 3, networknt and reactor are included so that the p2 repository is installable.
`slf4j.api` and `jakarta.servlet-api` are deliberately left out, because the host IDE ships satisfying versions.

**`com.vogella.eclipse.mcp.ui` is the branding plugin of the feature.**
`feature.xml` names it in its `plugin` attribute, and its `about.ini` and `about.properties` are what *Help > About Eclipse IDE > Installation Details > Features* shows.
`featureImage` has to be 32x32; the larger source of that icon is `icons/eclipse-mcp-server.png` in the repository root.
No `about.mappings`, because its `{0}` build id token is substituted by PDE build and not by Tycho, so it would show up literally.

**Versions in `META-INF/MANIFEST.MF` and the Jetty imports are pinned to `[12.1.12,13)`.**
`jetty-ee11-servlet` requires the Jetty core packages at that exact floor, and the IDE ships an older 12.1.x that would not satisfy it.

## Releasing

Run `gh workflow run release.yml`.
`.github/workflows/release.yml` builds `main`, copies the p2 repository into `releases/<built version>/` on the `gh-pages` branch, regenerates the composite metadata with `releng/update-composite-site.sh` and pushes the site.
It takes no version input: the directory is named after the feature jar the build produced, qualifier included, so publishing twice from the same source still lands on two different URLs.

The version in the manifests is meant to stay put across ordinary changes.
Tycho stamps every build with a fresh `yyyyMMddHHmm` qualifier, and p2 treats a higher qualifier under an unchanged version as an update, so *Check for Updates* picks up a new build without any version being bumped.
Bump `Bundle-Version`, `feature.xml` and `pom.xml` together when something worth naming lands, not per change.

Pushing a `v<version>` tag runs the same workflow and additionally creates the GitHub release with the repository archive attached.
That is the only thing that produces a downloadable zip, so a version anyone else should be able to install deserves a tag.

The published site is a p2 composite repository at `https://vogellacompany.github.io/eclipse-mcp-server/`.
The workflow passes `--only "$version"`, so publishing deletes every other `releases/<version>/` and the composite is left with a single child.
It is `--only` and not `--keep 1` because "newest" under `sort -V` is not "the one just published": `0.2.1` sorts above `0.2.0.202608201136`, so `--keep 1` once deleted the build it had just published and kept a stale directory instead.
Older builds stay reachable only as the repository zip attached to a tagged GitHub release.
Never edit `compositeContent.xml`, `compositeArtifacts.xml`, `p2.index` or `index.html` on `gh-pages` by hand; they are generated.
Never overwrite an existing `releases/<version>/` with different content, because p2 caches repositories aggressively and a changed repository under an unchanged URL produces confusing install failures.

Deleting a release directory does not shrink the branch: `gh-pages` keeps every published copy in its history, at roughly 9 MB each.
Only a force-pushed orphan commit would reclaim that, and it would throw away the history of the site.

Pushing anything under `.github/workflows/` needs a token with the `workflow` scope, or an SSH remote.

When verifying a freshly published release against the live site, remember that Tycho caches remote p2 repositories for 60 minutes under `~/.m2/repository/.cache/tycho`.
A resolve that returns the previous version right after a release is almost always that cache, not a broken publish.
`-Dtycho.p2.transport.min-cache-minutes=0` is not enough; delete the cache directory for the host.

## Conventions

Java 25, tabs for indentation, the Eclipse formatter defaults.
Javadoc says what a class or method does in a sentence or two; no `@param` or `@return` for anything obvious from the name.
Inline comments are rare and explain the non-obvious why, never the what.
Markdown uses one sentence per line.
