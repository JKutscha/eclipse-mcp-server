# AGENTS.md

Guidance for coding agents working in this repository.
See `README.md` for what the feature does and how a user installs it.

## What this is

An Eclipse plug-in project that turns a running IDE into a read-only MCP server.
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
`eclipse_organize_imports` and `eclipse_format` modify the file they are given, and `eclipse_get_problems` triggers a build when auto-build is off.
Everything else must not write, and no tool may open a dialog or perform a refactoring.
A new tool that writes has to say so in its own description, because that is the only place the model sees it.

**Threading.**
Tool calls arrive on Jetty worker threads.
Never call `Display.syncExec` from one; hand work to `asyncExec` and wait on a future with a short timeout, the way `GetEditorContextTool` does.
Marker reads and JDT searches are safe off the UI thread and need no workspace lock.
The server aborts any call that has not finished after 30 seconds.

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

**`BundleJsonSchemaValidator` clears the context class loader on purpose.**
The networknt schema library reads its bundled meta-schemas through the context class loader and only falls back to its own when there is none.
Inside Equinox the context class loader cannot see them, so without this the server fails to start with `FileNotFoundException: classpath:draft/2020-12/schema`.
Any MCP client built inside the IDE needs the same treatment, as `McpServerServiceTest.schemaValidator()` shows.

**The MCP SDK is used with explicit `jsonMapper` and `jsonSchemaValidator`.**
Letting the SDK fall back to `McpJsonDefaults` makes it depend on `ServiceLoader` discovery across bundles, which is fragile under OSGi.

**The feature lists third party bundles the Eclipse SDK does not ship.**
Jetty ee11, the MCP SDK, Jackson 3, networknt and reactor are included so that the p2 repository is installable.
`slf4j.api` and `jakarta.servlet-api` are deliberately left out, because the host IDE ships satisfying versions.

**Versions in `META-INF/MANIFEST.MF` and the Jetty imports are pinned to `[12.1.12,13)`.**
`jetty-ee11-servlet` requires the Jetty core packages at that exact floor, and the IDE ships an older 12.1.x that would not satisfy it.

## Releasing

Push a `v<version>` tag.
`.github/workflows/release.yml` builds it, copies the p2 repository into `releases/<version>/` on the `gh-pages` branch, regenerates the composite metadata with `releng/update-composite-site.sh`, pushes the site and attaches the repository archive to the GitHub release.

The published site is a p2 composite repository at `https://vogellacompany.github.io/com.vogella.eclipse.mcp/`, with one child per release.
Never edit `compositeContent.xml`, `compositeArtifacts.xml`, `p2.index` or `index.html` on `gh-pages` by hand; they are generated.
Never overwrite an existing `releases/<version>/` with different content, because p2 caches repositories aggressively and a changed repository under an unchanged URL produces confusing install failures.

Each release directory is a full copy at roughly 9 MB, most of it third party bundles that rarely change.
If the branch grows uncomfortably, either drop old releases from the composite or move the third party bundles into their own child repository.

Pushing anything under `.github/workflows/` needs a token with the `workflow` scope, or an SSH remote.

When verifying a freshly published release against the live site, remember that Tycho caches remote p2 repositories for 60 minutes under `~/.m2/repository/.cache/tycho`.
A resolve that returns the previous version right after a release is almost always that cache, not a broken publish.
`-Dtycho.p2.transport.min-cache-minutes=0` is not enough; delete the cache directory for the host.

## Conventions

Java 25, tabs for indentation, the Eclipse formatter defaults.
Javadoc says what a class or method does in a sentence or two; no `@param` or `@return` for anything obvious from the name.
Inline comments are rare and explain the non-obvious why, never the what.
Markdown uses one sentence per line.
