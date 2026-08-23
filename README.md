<img src="icons/eclipse-mcp-server.png" alt="" width="128" align="right">

# Eclipse MCP Server

Turns a running Eclipse IDE into an [MCP](https://modelcontextprotocol.io) server, so that external LLM clients (Claude Code, Cursor, any MCP-capable agent) can ask the IDE for information they cannot cheaply reconstruct from files alone.

An agent with a shell already has files, grep and git.
What it does not have is the resolved Java model, the incremental builder's problem markers and the user's current editor context.
Those are the capabilities exposed here.

Most tools are read-only. The exceptions are marked as such below: `eclipse_organize_imports` and `eclipse_format` rewrite the file they are pointed at, `eclipse_build` runs the project's builders, `eclipse_set_preference` changes IDE configuration within an allowlist, `eclipse_set_project_state` opens and closes projects, and `eclipse_set_bree` rewrites plug-in manifests.
There is no general file writing, no refactoring, no terminal and no debugger control.
The server is **disabled by default**, listens on the loopback interface only, and rejects every request that does not carry a bearer token.

## Building

Requires JDK 25 and Maven 3.9 or newer.

```bash
mvn clean verify
```

The build resolves everything from the target definition in `target-platform/com.vogella.eclipse.mcp.target`, so no extra setup is needed.
It produces a p2 repository under `update-site/com.vogella.eclipse.mcp.repository/target/repository`.

## Installing

In Eclipse, choose *Help > Install New Software*, add

```
https://vogellacompany.github.io/eclipse-mcp-server/
```

as an update site and install the **Eclipse MCP Server** feature.
That URL is a composite repository which always offers the newest version.
The site carries that release alone; publishing a new one removes the previous directory under `releases/`, so there is no older version to point at.

To install or to pin an older version, use the repository zip attached to its GitHub release, through *Add > Archive*.
When building from source, the same repository is produced under `update-site/com.vogella.eclipse.mcp.repository/target/repository` and can be added as a local site.

## Releasing

`gh workflow run release.yml` builds `main` and publishes it to the update site, under a directory named after the qualifier of the build.
The site keeps that build alone; the previous one is deleted.

Pushing a `v<version>` tag runs the same workflow and additionally creates the GitHub release with the repository archive attached, which is the only way a downloadable zip is produced.

## Developing in the IDE

1. Import the projects with *File > Import > Existing Projects into Workspace*, pointing at the repository root and enabling *Search for nested projects*.
2. Open `target-platform/com.vogella.eclipse.mcp.target/com.vogella.eclipse.mcp.target.target` and click *Set as Active Target Platform*. Resolving it downloads the Eclipse SDK, so the first run takes a while.

## Enabling the server

*Preferences > General > MCP Server*:

* **Enable MCP server**, off by default
* **Port**, `8642` by default
* **Tool call timeout**, `30` seconds by default, between 5 and 3600

The setting takes effect immediately, and the server also starts on the next IDE startup while it stays enabled.

The timeout bounds a single tool call. It is read per call, so raising it does not need a restart.
Raise it when a workspace is large enough that a refreshing `eclipse_get_problems` or a build does not finish in 30 seconds.

The same preference page shows the endpoint once the server is listening: the URL, the bearer token and the path of the discovery file, each with a *Copy* button, plus a *Regenerate token* button.
When the port is already in use the server does not fall back to another one, it stays down and the page says why, so the URL never changes behind a client's back.
That it is listening, and on which port, is also written to the Error Log view.

## Connecting a client

On startup the server writes a discovery file so that no value has to be copied by hand:

```
<workspace>/.metadata/.plugins/com.vogella.eclipse.mcp.server/endpoint.json
```

```json
{
  "url": "http://127.0.0.1:8642/mcp",
  "token": "0f0f2a2e-1f9c-4c4a-9a0e-6d0f8f0f1e2b",
  "startedAt": 1787300000000
}
```

`state` is `listening` or `stopped`. The file is left behind with a `stopped` record rather than deleted, because a missing file cannot be told from one that was never written, and the case that matters most is the one where the server does not come back.

**Several clients can use one server.** The transport is session based, so each client gets its own session over the same port, and the bearer token is the same for all of them: the server cannot tell them apart, and they all act on one workspace with no locking between them.

The trap is not the connection, it is *the most recent*. `eclipse_get_build_status`, `eclipse_get_test_results`, `eclipse_stop_sampling` and `eclipse_get_provisioning_status` answer about the latest entry in a **global** registry when the id is omitted, so another client's run started in between would be reported as yours and would look entirely correct. While more than one client is connected those four **refuse the implicit default** and name the ids to choose from. Pass `buildId`, `runId`, `sessionId` or `operationId` explicitly and it never arises.

A client is counted from the session id on its requests and drops out when it ends its session or after a minute of silence, so reconnecting, which is what a client does after `eclipse_restart`, does not make it look like two.

`startedAt` identifies the server process. It matters after `eclipse_restart`, which answers *before* it restarts: the old server keeps responding for a couple of seconds, so a plain reachability check succeeds against the process that is about to die. Compare `startedAt` across the reconnect to know the new one is really up.

The file is created with owner-only permissions and deleted when the server stops.

The token is generated on first use and kept in `token` next to the discovery file, also with owner-only permissions, so it survives IDE restarts and a client has to be configured only once.
*Regenerate token* on the preference page replaces it and restarts the server, which rejects every client still using the old one.

The transport is Streamable HTTP.
Every request has to carry the token:

```
Authorization: Bearer <token>
```

Requests without it are answered with `401`.
This is a plain bearer token, not the MCP authorization specification, so there is no OAuth flow and nothing to discover: a client that can send a static header is enough.

The socket is bound to `127.0.0.1`, so it is not reachable from another machine.
The `Host` header is additionally checked against `127.0.0.1` and `localhost`, which keeps a browser on the same machine from reaching the server through a rebound DNS name.

For Claude Code:

```bash
claude mcp add --transport http eclipse http://127.0.0.1:8642/mcp \
  --header "Authorization: Bearer $(jq -r .token <workspace>/.metadata/.plugins/com.vogella.eclipse.mcp.server/endpoint.json)"
```

## MCP capabilities

The server offers tools and nothing else.
It declares the `tools` capability with `listChanged: false`, which means it answers exactly these methods:

| Method | Notes |
|---|---|
| `initialize` | reports the server as `eclipse-mcp` with the bundle version, plus an instructions string |
| `ping` | |
| `tools/list` | the tools below |
| `tools/call` | arguments are validated against the tool's input schema before the tool runs |
| `notifications/initialized`, `notifications/roots/list_changed` | accepted and ignored |

Everything else, `resources/list`, `prompts/list`, `logging/setLevel` and `completion/complete` among them, is answered with method not found.
Sessions are carried in the `mcp-session-id` header, a `GET` opens the server-to-client SSE stream and a `DELETE` ends the session.

## Tools

Every tool returns a single text block containing pretty-printed JSON.
Every list-returning tool honours `maxResults` and reports `total` and `truncated`, so the model can tell when it is seeing a partial answer.
Read-only except the tools marked as changing something: `eclipse_organize_imports` and `eclipse_format` rewrite a file, `eclipse_build` runs builders, `eclipse_set_preference` writes configuration, `eclipse_set_project_state` opens and closes projects, and `eclipse_set_target_platform` replaces what every plug-in project compiles against.

### `eclipse_list_projects`

Lists the projects in the workspace, with their natures and open/closed state.
Takes `maxResults` (500), and reports `total` and `truncated`.

```json
{"projects":[{"name":"com.example.app","open":true,
              "natures":["org.eclipse.jdt.core.javanature"],"natureSource":"model",
              "location":"/home/user/git/app"}]}
```

A closed project still reports its natures, read from `.project` on disk, and `natureSource` says which of the two answered: `model`, `projectFile`, or `unknown` when the file could not be read.
`IProject.getDescription` fails on a closed project, and reporting that as "no natures" is worse than saying nothing.
A client classifying projects by nature otherwise gets a different answer for the same workspace depending on which projects happen to be open at the time, which is not a rule but a coin flip.

### `eclipse_get_problems`

Returns the compilation errors and warnings computed by the incremental builder.

| Argument | Type | Default | Meaning |
|---|---|---|---|
| `severity` | `error` \| `warning` \| `info` \| `all` | `error` | Only problems of exactly this severity. `all` returns every severity. |
| `project` | string | all projects | Restrict to this project name. |
| `pathPrefix` | string | all paths | Restrict to workspace paths starting with this prefix. |
| `maxResults` | integer, 1 to 2000 | 200 | |
| `refresh` | boolean | `true` | Refresh from disk and wait for the build before reading the markers. |

Errors sort before warnings before infos, so truncation keeps the most important entries.

```json
{"total":3,"truncated":false,"upToDate":true,"autoBuild":true,"problems":[
  {"path":"/app/src/com/example/Main.java","project":"app","line":42,
   "severity":"error","message":"Foo cannot be resolved to a type",
   "type":"org.eclipse.jdt.core.problem"}]}
```

A client that edits files through its own shell is invisible to the IDE until the workspace is refreshed, so without `refresh` the markers describe the state before those edits.
That is why it defaults to `true`.
`upToDate` says whether the refresh and the build actually completed, and `autoBuild` reports whether the workspace builds on its own; when `upToDate` is `false` the problems may be stale.
Set `refresh` to `false` for a faster answer when nothing has changed on disk.

### `eclipse_mark_problems`

Records the problems the workspace has right now and returns a marker. Changes nothing, no arguments.

Pass it to `eclipse_get_problems` as `marker` and the answer is only what appeared since, plus `resolved`, the ones that went away. Everything unchanged is omitted, which is the point: the alternative is reading every problem before and after and diffing them client-side, which on a large project is a hundred kilobytes a call for an answer that is usually a few lines.

The diff is taken over the scope of the `eclipse_get_problems` call, not over the whole workspace the marker recorded. A marker is workspace wide and a query usually is not, so without that narrowing a project-scoped call reports every problem in every other project as resolved while it is still sitting there. `severity`, `pathPrefix` and `messageFilter` narrow the baseline the same way. `resolved` honours `maxResults` and reports `resolvedTruncated`.

It deliberately does not build or refresh. It records the state as it stands; making that state current is the caller's decision, through `eclipse_get_problems` or `eclipse_build`, which is where the cost belongs. Only the last few markers are kept, and an aged-out one is refused by name rather than silently treated as empty.

### `eclipse_get_log_entries`

Returns entries from the platform log, the file behind the Error Log view.
This is where UI freezes reported by `org.eclipse.ui.monitoring` and exceptions thrown by builders end up.
None of those become problem markers, so `eclipse_get_problems` cannot see them.

| Argument | Type | Default | Meaning |
|---|---|---|---|
| `severity` | `error` \| `warning` \| `info` \| `all` | `all` | Only entries of exactly this severity. |
| `plugin` | string | all bundles | Restrict to this bundle symbolic name. |
| `messageFilter` | string | no filter | Only entries whose message contains this text, case insensitive. |
| `since` | string | no limit | Only entries at or after this local timestamp, `2026-08-20T11:00` or `2026-08-20`. |
| `maxResults` | integer, 1 to 500 | 50 | |
| `includeStackTraces` | boolean | `true` | Include the full stack trace of every entry and child. |
| `newestFirst` | boolean | `true` | Newest first, so that truncation keeps the most recent entries. |

The default severity is `all` rather than `error` on purpose: `org.eclipse.ui.monitoring` logs a UI freeze as a **warning**, so a default of `error` would hide exactly the entries worth asking for.

```json
{"logFile":"/home/user/workspace/.metadata/.log","total":6,"truncated":false,"entries":[
  {"plugin":"org.eclipse.ui.monitoring","severity":"warning","code":0,
   "timestamp":"2026-08-20T11:58:27.932","message":"UI freeze of 3.2s at 11:58:24.766",
   "exception":null,"stackTrace":null,
   "children":[{"plugin":"org.eclipse.ui.monitoring","severity":"info","code":0,
                "timestamp":"2026-08-20T11:58:27.932",
                "message":"Sample at 11:58:26.099 (+1.333s)\nThread 'main' tid=3 (RUNNABLE)",
                "exception":null,
                "stackTrace":"Stack Trace\n\tat org.eclipse.jdt.internal.core.JavaModelManager.create(...)"}]}]}
```

A UI freeze is a multi status whose children carry the sampled thread stacks, and those children are the whole point, so they come through in full rather than being flattened into the `UI freeze of 3.2s` headline.
Stack traces are never truncated either, which means a handful of freezes can amount to megabytes; `maxResults`, `plugin` and `includeStackTraces: false` are the levers for keeping an answer small.

The entries are read from the log file rather than from a listener registered at startup, so entries from before the server started, and from previous sessions still present in the file, are included.

### `eclipse_mark_log` and `eclipse_clear_log`

Two ways to get "everything in the log is from this run" before a long test run.

`eclipse_mark_log` records the current end of the log and returns an opaque marker. No arguments, changes nothing. `eclipse_get_log_entries` then takes `marker` and reports only what was logged after that point.

**Prefer the marker.** It is exact where `since` is not, and it destroys nothing. `since` needs a timestamp from the caller's clock checked against timestamps the IDE wrote, and those are not the same clock; the marker is a byte position in the IDE's own file, so no clock is involved. `since` also filters without shrinking, so old entries still compete for `maxResults` on a run that logs heavily. And a `since` window that spans a log rotation silently loses entries, which the caller cannot detect: a marker whose file has since shrunk comes back with `markerStale` and everything readable, rather than a window that would be wrong.

`eclipse_clear_log` **destroys the log irreversibly**, including entries from earlier sessions. It runs as a dry run unless `dryRun` is `false`, and reports `entriesDiscarded` and `bytes` either way.

| Argument | Type | Default | Meaning |
|---|---|---|---|
| `dryRun` | boolean | `true` | |
| `includeRotated` | boolean | `true` | Also remove the rotated `.log.bak` sibling. |

It deletes the file, which is exactly what the Error Log view's own delete action does. The rotated sibling goes too by default, because leaving it means a later query can still reach entries from before the clear.

After a real clear it writes one entry, reads it back, and reports `stillLogging`. The framework writes the log through a handle of its own, so a delete underneath it could in principle leave later entries going somewhere nothing can read, and that failure would be silent until someone noticed an empty log much later. Equinox reopens the file, so this works, and `LogStateToolsTest.clearingLeavesTheLogWritableAndReadable` holds it that way rather than leaving it as an assumption.

### `eclipse_get_preferences`

Reads preferences for a qualifier and reports which scope each value comes from.
Use it to find out what has actually been customized here, auto-build being the common case: qualifier `org.eclipse.core.resources`, key `description.autobuilding`.

| Argument | Type | Default | Meaning |
|---|---|---|---|
| `qualifier` | string, required | | Preference qualifier, usually a bundle symbolic name. |
| `key` | string | all keys | Exact preference key. |
| `keyPattern` | string | no filter | Glob over keys, `*` and `?`, case insensitive. |
| `scope` | `instance` \| `project` \| `configuration` \| `default` \| `all` | `instance` | Only keys set in this scope. |
| `project` | string | | Required for the project scope. |
| `includeDefaults` | boolean | `false` | Also list keys only set in the default scope. |
| `maxResults` | integer, 1 to 2000 | 200 | |

```json
{"qualifier":"org.eclipse.jdt.core","project":null,"scope":"instance","total":1,"truncated":false,
 "preferences":[{"key":"org.eclipse.jdt.core.compiler.source","effective":"25","effectiveScope":"instance",
                 "values":{"instance":"25","default":"21"}}]}
```

`values` holds every scope that sets the key, in lookup order, and `effectiveScope` names the one that won.
An effective value without its origin cannot explain why one project behaves unlike its neighbours, which is the question this tool exists to answer.
The default value of a listed key is always reported; `includeDefaults` only controls whether keys that are *only* set in the default scope are listed at all, because for a qualifier like `org.eclipse.jdt.core` that is several hundred entries of noise.

### `eclipse_set_preference`

**Modifies the IDE configuration.**
Writes one preference and returns the previous value, so any change can be undone.

| Argument | Type | Default | Meaning |
|---|---|---|---|
| `qualifier` | string, required | | Restricted to an allowlist, see below. |
| `key` | string, required | | |
| `value` | string | remove the key | Omitting it removes the key, letting the value below it in the lookup order take over. |
| `scope` | `instance` \| `project` | `instance` | |
| `project` | string | | Required for the project scope. |

Only `org.eclipse.core.resources`, `org.eclipse.jdt.core`, `org.eclipse.jdt.ui` and `org.eclipse.core.runtime` may be written.
Reading is not restricted: `eclipse_get_preferences` takes any qualifier.
The asymmetry is deliberate. Preferences span the whole `org.eclipse.*` key space, and a wrongly set compiler or formatter option is invisible in the IDE while producing confusing results for a long time afterwards, so the writable set starts from what is defensible rather than from everything.

Auto-build is special cased. Setting `org.eclipse.core.resources` / `description.autobuilding` goes through `IWorkspaceDescription.setAutoBuilding` rather than writing the raw key, which is the usual way to get this subtly wrong, and the answer says so in `appliedThrough`.

### `eclipse_analyze_dependencies`

Compares what a plug-in declares in `Require-Bundle` and `Import-Package` with the bundles its source actually resolves against. Read-only. Takes `project` or `projects` and `maxResults`.

| Field | Meaning |
|---|---|
| `declaredRequireBundle` | as written, with `reexported`, `optional`, `resolved` |
| `actuallyUsed` | bundles supplying at least one type the source resolves against, with a count and a sample |
| `unused` | declared, and nothing in the source resolves to them |
| `viaReexport` | used, but only reachable because a declared bundle reexports them, with the chain |
| `undeclared` | used and reachable neither directly nor through a reexport |

`viaReexport` is the edit list a reexport cleanup needs: those entries have to be declared here before the reexport that supplies them can be dropped.

Usage is computed from **resolved bindings, not import statements**: a fully qualified use has no import, and an import can outlive the last use of it.

**`unused` is not a deletion instruction**, for the same reason `dead` is not in `eclipse_list_declarations`. A bundle can be needed for a class named in `plugin.xml`, an OSGi service, or a `Class.forName` that leaves no type reference. An optional or platform-filtered requirement that does not resolve on this machine is reported with `resolved: false` rather than judged.

### `eclipse_get_bundle_info`

Reports OSGi bundles as PDE resolved them against the active target platform.

| Argument | Type | Default | Meaning |
|---|---|---|---|
| `symbolicName` | string | | Exact bundle symbolic name. |
| `namePattern` | string | | Glob over symbolic names. |
| `workspaceOnly` | boolean | `true` | With `false`, target platform bundles are included. |
| `unresolvedOnly` | boolean | `false` | Only bundles that did not resolve. |
| `includeConstraints` | boolean | `true` | List `Require-Bundle` and `Import-Package` with resolution status. |
| `maxResults` | integer, 1 to 2000 | 100 | |

Every `Require-Bundle` and `Import-Package` entry carries `resolved` and, when it resolved, `boundTo`, the bundle or package that actually satisfied it.
That is the difference between this and reading `MANIFEST.MF`: the manifest shows what was asked for and never what was found, so *Cannot resolve plug-in: org.eclipse.opengl* stays a guess until something tells you nothing supplies it.

`platformFilter` and `fragmentHost` come from the resolver rather than from a regex over the manifest, which is also what `eclipse_set_project_state` uses for `platformMismatch`.

### `eclipse_get_target_platform` and `eclipse_set_target_platform`

Reads the active target platform, and sets a target definition as the active one, which is what the *Set as Active Target Platform* link of the target editor does.

`eclipse_get_target_platform` is read-only.

| Argument | Type | Default | Meaning |
|---|---|---|---|
| `includeLocations` | boolean | `true` | Report each location with its own resolution status. |
| `includeKnown` | boolean | `false` | List the definitions the IDE knows, workspace `.target` files included, with the memento of each. |
| `maxProblems` | integer, 1 to 1000 | 50 | Cap on the reported bundles that failed to resolve. |

`targetSet` is the difference between a definition being active and PDE falling back to the IDE's own installation: PDE answers with a default definition either way, and only the handle behind it says whether anything was set.

**`eclipse_set_target_platform` changes the IDE.**
It replaces the bundles every plug-in project compiles against, PDE recomputes the plug-in classpaths, and problem markers across the workspace change with it.

| Argument | Type | Default | Meaning |
|---|---|---|---|
| `file` | string | | The `.target` file, as a workspace path such as `/target-platform/example.target` or an absolute file system path. |
| `memento` | string | | A target handle memento instead of a file, as reported by `includeKnown`. |
| `resolveOnly` | boolean | `false` | Resolve and report, without activating. |
| `wait` | boolean | `true` | |
| `timeoutSeconds` | integer, 1 to 3600 | 25 | Below the tool call timeout, so a slow resolve returns `running` rather than being killed. |
| `includeLocations` | boolean | `true` | |
| `maxProblems` | integer, 1 to 1000 | 50 | |

```json
{"target":"/target-platform/example.target","state":"done","resolveOnly":false,
 "elapsedMillis":48213,"resolveMillis":47019,
 "resolveStatus":{"severity":"OK","message":"OK"},
 "loadStatus":{"severity":"OK","message":"OK"},
 "previous":{"name":"Old target","memento":"..."},
 "definition":{"name":"Example","resolved":true,"bundleCount":1782,"featureCount":41,
   "bundleProblems":[],"bundleProblemCount":0,
   "locations":[{"type":"InstallableUnit","location":"https://download.eclipse.org/releases/2026-06",
     "resolved":true,"bundleCount":1782,"status":{"severity":"OK","message":"OK"}}]}}
```

Resolving a target that is not cached downloads from its p2 repositories and takes minutes, which is longer than a tool call may last, so the work runs as a job.
A call that runs out of `timeoutSeconds` returns `state: "running"` and the job carries on; `eclipse_get_target_platform` then reports it as `lastLoad` until it ends.
Starting a second load cancels the first, the way PDE itself does.

The status is reported with its children rather than as a sentence, because a target that does not resolve fails in one location, and which repository was unreachable or which unit is missing is only in there.
`resolveOnly` answers that question without touching the workspace, which is the difference between checking a `.target` file and committing to it.

Note that the server has no tool that writes arbitrary files, so a client that wants to activate a target definition of its own has to write the `.target` file through its own file access, then call `eclipse_refresh` before naming it here.

### `eclipse_set_bree`

**Rewrites `META-INF/MANIFEST.MF` and `.classpath`.**
Sets the `Bundle-RequiredExecutionEnvironment` of plug-in projects and the JDT compiler settings that have to agree with it, in one operation.
Runs as a dry run unless `dryRun` is set to `false`.

| Argument | Type | Default | Meaning |
|---|---|---|---|
| `bree` | string, required | | Execution environment id, e.g. `JavaSE-21`. Must be one the IDE knows. |
| `projects` | array of strings | | Plug-in project names to act on. |
| `namePattern` | string | | Glob over project names, `*` and `?`, case insensitive. |
| `currentBree` | string | any | Only projects currently declaring this environment. |
| `updateCompliance` | boolean | `true` | Also set compiler compliance, source and target. |
| `dryRun` | boolean | `true` | |
| `maxResults` | integer, 1 to 2000 | 200 | |

```json
{"bree":"JavaSE-21","dryRun":false,"total":1,"changed":1,"skipped":0,"truncated":false,"projects":[
  {"name":"com.example.bundle","previousBree":"JavaSE-17","bree":"JavaSE-21",
   "previousJreContainer":"org.eclipse.jdt.launching.JRE_CONTAINER/.../JavaSE-17",
   "compliance":{"compliance":{"from":"17","to":"21"},"source":{"from":"17","to":"21"},
                 "target":{"from":"17","to":"21"}},
   "jreContainer":"org.eclipse.jdt.launching.JRE_CONTAINER/.../JavaSE-21",
   "changed":true,"skippedBecause":null}]}
```

The manifest header is written through PDE's `IBundleProjectDescription`, which is public API.
The JRE container in `.classpath` is then pointed at the new environment explicitly, because `apply()` writes the header and leaves the classpath alone.

The compiler settings come from `IExecutionEnvironment.getComplianceOptions()` rather than from a version table, and only compliance, source and target are written. Setting the header without them leaves a project whose manifest and compiler disagree, which is the state PDE raises a marker for; doing both together is the point of the tool.

Non plug-in projects in the selection are ignored rather than reported as failures. `currentBree` is how you move a whole set off one version. At least one of `projects` or `namePattern` is required.

Note that BREE is the older mechanism: OSGi R7 replaced it with `Require-Capability: osgi.ee`. Eclipse's own bundles still use BREE almost everywhere, which is why this tool writes it, but it is not the modern spelling.

### `eclipse_set_project_state`

**Opens and closes projects.**
Reversible: no files are lost and no project code runs.
Runs as a dry run unless `dryRun` is set to `false`.

| Argument | Type | Default | Meaning |
|---|---|---|---|
| `state` | `open` \| `closed`, required | | |
| `projects` | array of strings | | Project names to act on. |
| `namePattern` | string | | Glob over project names, `*` and `?`, case insensitive. |
| `platformMismatch` | boolean | `false` | Only projects whose bundle cannot run on this platform. |
| `dryRun` | boolean | `true` | |
| `force` | boolean | `false` | Close even when open projects depend on the project. |
| `maxResults` | integer, 1 to 2000 | 200 | |

```json
{"state":"closed","dryRun":true,"total":2,"changed":1,"skipped":1,"truncated":false,"projects":[
  {"name":"org.eclipse.compare.win32","previousState":"open",
   "platformReason":"Eclipse-PlatformFilter does not match: (& (osgi.ws=win32) (osgi.os=win32))",
   "changed":true,"newState":"closed","skippedBecause":null},
  {"name":"org.eclipse.ui.win32","previousState":"open","openDependents":["org.eclipse.ui.ide"],
   "changed":false,"newState":"open",
   "skippedBecause":"Open projects reference it, and closing it would give them build path errors rather than removing errors. Pass force to close it anyway."}]}
```

At least one of `projects`, `namePattern` or `platformMismatch` is required; the tool refuses to act on every project in the workspace.

`platformMismatch` reads the `Eclipse-PlatformFilter` header from the project's `META-INF/MANIFEST.MF` and evaluates it as an OSGi filter against the running `osgi.ws`, `osgi.os` and `osgi.arch`. That is a declaration, not a guess. Only when a project has no such header does it fall back to looking for a foreign platform token in the name, and `platformReason` then says that it is a heuristic. Name matching alone would work for Eclipse's own naming convention and quietly misfire elsewhere.

Closing a project that open projects reference does not remove errors, it gives the dependents build path errors instead. So `openDependents` is always reported, from `IProject.getReferencingProjects()`, which covers both JDT build path references and PDE required bundles, and closing is refused unless `force` is passed.

**A batch is resolved as a whole.** `getReferencingProjects()` reports the projects that are open right now, so closing a cluster used to refuse every member whose dependents were themselves in the same call: the refusal described a state that would not exist once the call returned, and closing a cluster took one pass per layer of the graph.
The projects a call will actually close are now computed as a fixpoint first, and only dependents that will still be open afterwards block anything. Those appear in `openDependents` as before; the ones closing in the same call appear in `dependentsClosingTogether`, which is reported but never blocks.
Removing one project from the set can block another, which is why this iterates rather than subtracting the selection once, and it resolves the same way for a dry run as for a real one.

### `eclipse_build`

**Runs builders.**
Builds the workspace or named projects.

| Argument | Type | Default | Meaning |
|---|---|---|---|
| `kind` | `incremental` \| `full` \| `clean` | `incremental` | |
| `project` | string | whole workspace | Single project to build. |
| `projects` | array of strings | | Several projects, instead of `project`. |
| `wait` | boolean | `true` | Wait for the build before answering. |
| `timeoutSeconds` | integer, 1 to 3600 | 25 | How long to wait before answering with `running`. |
| `returnProblems` | boolean | `true` | Count errors and warnings once the build ended. |
| `refresh` | boolean | `true` | Refresh from disk first, scoped to the named projects. |
| `buildAfterClean` | boolean | `false` | Build again after a clean. |

```json
{"buildId":"build-3","kind":"full","state":"done","scope":"projects","projects":["app"],
 "elapsedMillis":8412,"refreshMillis":204,"buildMillis":8208,"note":null,
 "errors":2,"warnings":17,"builderFailures":[]}
```

Everything slow happens inside the job, the refresh included, so `wait: false` always returns straight away with a `buildId`, and a build longer than `timeoutSeconds` comes back as `state: "running"` rather than holding the request open until the call timeout kills it.
Keep `timeoutSeconds` below that timeout; the default 25 fits under the default 30.

`refreshMillis` and `buildMillis` are reported separately because on a large workspace the refresh can cost more than the build, and a single number hides that.

A `clean` only deletes build state. With auto-build off nothing rebuilds afterwards, so the error count describes an unbuilt workspace rather than a working one; the answer then carries a `note` saying so. `buildAfterClean` rebuilds, the way the *Build immediately* checkbox of *Project > Clean* does.

`builderFailures` carries what went wrong without becoming a problem marker, so that a build whose `JavaBuilder` threw is not reported as a clean one.
It has two sources. Exceptions that reach `IProject.build` are flattened out of the multi status they arrive in. But most builder failures never get that far: `BuildManager` runs builders inside a `SafeRunner`, which catches the exception and writes it to the Error Log, so the build returns normally and there is nothing to catch. Those are picked up by reading the platform log for errors and warnings logged while the build ran.

The second source is correlated by time, not by causation, so anything else the IDE logged during the same window is included too. Over-reporting was the deliberate choice: calling a broken build clean is the worse failure.

### `eclipse_refresh`

Reads changes made outside the IDE into the workspace, and nothing else.
Use it after switching branches, updating submodules or editing through a shell, when you want the IDE to see the new files without also building or reading markers.

| Argument | Type | Default | Meaning |
|---|---|---|---|
| `project` | string | whole workspace | Single project to refresh. |
| `projects` | array of strings | | Several projects, instead of `project`. |
| `wait` | boolean | `true` | Wait for the refresh before answering. |
| `timeoutSeconds` | integer, 1 to 3600 | 25 | |

It runs as a job and answers in the same shape as `eclipse_build`, with `kind: "refresh"`, so `eclipse_get_build_status` reports on it too.
It never counts markers: a refresh does not build, so any count would describe whatever the last build left behind and invite a wrong conclusion.

Refreshing is available on `eclipse_build` and `eclipse_get_problems` as well, but only as a step before something else. With auto-build off, picking up external edits and deciding whether to build are separate decisions, which is why this exists on its own.

### `eclipse_get_build_status`

Reports a build started through `eclipse_build`, by `buildId`, or the most recent one when that is omitted.
The answer has the same shape as the one above.
The last 20 builds are kept; asking for an older id is an error, while asking before anything has been built returns `{"state":"none"}` rather than an error.

### `eclipse_find_references`

`queries` asks about many elements in one call and returns **counts only**: `[{typeName, memberName?, accessKind?}]`, and per query `total`, `source`, `binary` and `declaration`. A dead code sweep asks "how many references" about hundreds of candidates and needs the locations for almost none of them, so returning matches would make the answer enormous to save the round trips that were the problem. A name that does not resolve fails that query alone, not the batch. Use the single form when you need the match locations.


Finds all references to a Java type, method or field across the workspace with the JDT search engine.
Far more accurate than a text search, because it resolves overloads and inheritance.

| Argument | Type | Default | Meaning |
|---|---|---|---|
| `typeName` | string, required | | Fully qualified type name. |
| `memberName` | string | the type itself | Method or field name. All overloads of a method name are searched. |
| `project` | string | whole workspace | Project used to resolve the type and to scope the search. |
| `maxResults` | integer, 1 to 2000 | 200 | |
| `accessKind` | `all` \| `read` \| `write` | `all` | Restrict to read or write accesses. Fields only. |

```json
{"resolved":"org.eclipse.jface.viewers.TreeViewer#setInput","accessKind":"all",
 "total":17,"byOrigin":{"source":15,"binary":2},"truncated":false,
 "matches":[{"path":"/app/src/com/example/View.java","project":"app","line":88,
             "offset":2451,"length":8,"kind":null,
             "enclosingElement":"com.example.View.createPartControl(Composite)"}]}
```

Every match carries an `origin`. A `source` match has a workspace `path` and a `project`. A `binary` match is inside a compiled jar on some project's build path, and reports the jar as `library` with `path` and `project` both null.

That distinction is not cosmetic. `SearchMatch.getResource()` returns the *project that owns the classpath entry* for a match inside a jar, so the raw path is a bare project name with no file. Reported as-is, a hit inside `org.eclipse.jdt.ui.jar` looks like a source reference in whichever project happens to depend on that jar, and nothing marks it as second hand. Judge "how many consumers does this API have" from the `source` count in `byOrigin`.

The `project` argument scopes the search to that project **and everything on its build path**, which includes other workspace projects and jars. It narrows less than it looks.

A name based search is broader work than a binding based one, so asking for references to a very common JDK type across a large workspace is slow. Pass `project` to scope it.

An unresolvable type name comes back as an error result naming the type, not as a protocol error.

**Reads and writes.**
When the member resolves to a field, the answer also carries a `byKind` summary and a `kind` on every match, without a second call:

```json
{"resolved":"com.example.Cache#lastSelection","accessKind":"all","total":4,
 "byKind":{"read":0,"write":4},"truncated":false,"matches":[...]}
```

This is the one thing a text search cannot approximate: a field written in four places and read in none is dead, while every text tool sees four live occurrences.
`kind` is `read`, `write`, `readWrite` for a compound assignment such as `count += 1`, or `null` for anything that is not a field.

Two details that matter if you act on the numbers.
A field initializer is a write access but a declaration rather than a reference, so it is counted in `byKind` while being absent from `total` and from `matches`; the counts need not sum.
And `read` or `write` on a type or a method is an error rather than an empty answer, because only fields are read and written.

### `eclipse_run_tests` and `eclipse_get_test_results`

**Runs project code.**
Runs JUnit tests through the IDE's own test runner and reports the failures with their stack traces, expected and actual values.

| Argument | Type | Default | Meaning |
|---|---|---|---|
| `project` | string, required | | Project holding the tests. |
| `testClass` | string | the whole project | Fully qualified test class. |
| `testMethod` | string | | Single method of `testClass`. |
| `dryRun` | boolean | `false` | List the test types that would run, without running them. |
| `wait` | boolean | `true` | |
| `timeoutSeconds` | integer, 1 to 3600 | 25 | |

The JUnit version is detected from the project's own build path and the runtime classpath is the one *Run As > JUnit Test* would use, so nothing has to be configured and no JUnit dependency is resolved here. The launch configuration is never saved, so a run started this way does not appear in the user's launch history.

`eclipse_get_test_results` reports a run by `runId`, or the most recent, with counts and the failing cases. Passing tests are omitted unless `includePassed` is set, because the failures are what the question was about.

| `pluginTest` | `auto` \| `true` \| `false` | `auto` | Run as a JUnit Plug-in Test. `auto` uses it for plug-in projects. |
| `ui` | boolean | `false` | Use the UI test application, which opens a workbench window. |
| `runtimeWorkspace` | string | a sibling `mcp-junit-workspace` | Workspace for the launched platform, cleared each run. |

A **plug-in project is run as a JUnit Plug-in Test by default**, launching a second Eclipse with a running platform in its own cleared workspace, through `org.eclipse.pde.ui.JunitLaunchConfig`. That type is declared by `org.eclipse.pde.launching`, which despite the historical id has no UI dependency, so it works headlessly.

This matters because the alternative is not a slower answer but a wrong one: tests needing OSGi fail under a plain JUnit launch with `The application has not been initialized`, a null `IExtensionRegistry` or `NoClassDefFoundError`, which read as broken tests rather than as the platform being absent. `launchedAs` says which launcher ran, and forcing `pluginTest: false` on a plug-in project adds a `caveat` explaining why the results are suspect.

The **UI test application is opt-in**. It opens a workbench window on the user's screen, and a launched IDE should never be a surprise. The runtime workspace is cleared without asking, since a prompt would block a call nobody is watching.

Results are collected through `JUnitCore.addTestRunListener`, which is global and fires for every run in the IDE. Runs are matched by a launch configuration name generated per run, so a test run someone starts at the keyboard is never reported as one of ours.

### `eclipse_list_declarations`

Enumerates the types, methods or fields a project declares in its own source, and cross-checks each against the places an Eclipse runtime instantiates a class by name.

| Argument | Type | Default | Meaning |
|---|---|---|---|
| `typeNames` | array of strings | | Report only these types, resolved directly instead of by walking a project. |
| `project` | string | | Project to enumerate. |
| `projects` | array of strings | | Several projects, instead of `project`. |
| `kinds` | array of `types` \| `methods` \| `fields` | `["types"]` | |
| `visibility` | array of `public` \| `protected` \| `package` \| `private` | all | |
| `status` | `dead` \| `live-via-registry` \| `undecidable` \| `all` | `all` | Report only this verdict. |
| `includeTest` | boolean | `false` | Include source folders the build path marks as test. |
| `includeReflection` | boolean | `true` | Scan source for `Class.forName` and `loadClass`. |
| `maxResults` | integer, 1 to 5000 | 500 | |

`typeNames` turns it around: when you already have candidates, it resolves each one and gives you the registry and API verdict per type without walking anything, sharing one registry index across the batch. Names that do not resolve to a source type come back in `unresolved`.

This is the candidate generation step of a dead code sweep. `eclipse_find_references` is the confirm step, and neither replaces the other: search is fast and resolves overloads and inheritance, but it cannot enumerate, and zero references does not mean dead.

Binary types are never listed. Only source package fragment roots are walked, so a class that exists a dozen times over inside built jars appears once, as source, rather than being deduplicated afterwards.

**`registryStatus` is three-valued, and the distinction is the point.**

- `dead` means only that no registry position this tool understands names it. It never means deleting it is safe.
- `live-via-registry` means not provably dead. It does not mean anything still uses it: an extension can be contributed to a point nobody reads any more, in a bundle that ships in no feature.
- `undecidable` means something names it in a position that cannot be judged.

A boolean verdict would report the undecidable cases as dead, which is the one failure mode that makes a tool like this dangerous rather than merely incomplete.

**Extension attributes are resolved through the schema, not grepped.** The rule is positional: a class name in a comment, a changelog or a `.txt` file keeps nothing alive. An element is resolved to its extension point, the point's `.exsd` says which of its attributes are java-typed, and only those count:

```xml
<attribute name="class" type="string" use="required">
  <appInfo>
    <meta.attribute kind="java" basedOn="org.eclipse.core.resources.filtermatchers.AbstractFileInfoMatcher:"/>
  </appInfo>
</attribute>
```

`basedOn` is verified and reported, but it **never demotes a verdict**. It is a single-valued hint that several real schemas cannot express: `org.eclipse.ui.decorators` names `ILabelDecorator`, while every decorator declared `lightweight="true"` implements `ILightweightLabelDecorator` instead. The schema is not lying, it is incapable of saying what it means. So `basedOnSatisfied` is a flag for a person to read, not an input to the status: unverifiable is not refuted, and unsatisfied is not refuted either.

It is `null` in two cases: the supertype is not resolvable in that project at all, and the named class is an `IExecutableExtensionFactory`, where `class="a.b.Factory:product"` means `basedOn` describes what the factory produces rather than the factory itself.

A class satisfies a `basedOn` that names the class itself.

**`apiTier` says what a workspace search can prove**, and it qualifies every verdict rather than replacing it. For an OSGi bundle the declaring package's export decides whether consumers can exist where you cannot see them:

| `apiTier` | Meaning | `searchIsAuthoritative` |
|---|---|---|
| `not-exported` | nothing outside the bundle may reference it | `true` |
| `internal-api` | exported `x-internal`, no legitimate outside consumer | `true` |
| `internal-api-friends` | exported `x-friends`, and the list is enumerable | `true` when every friend is a project here |
| `public-api` | consumers may exist anywhere | `false` |

`x-internal` counts as authoritative for the same reason `not-exported` does, and it is the stronger declaration of the two internal tiers: it says *no* bundle should use the package, where `x-friends` names some that may. Both rest on an access rule JDT and PDE check when consumers compile, not on anything OSGi enforces at runtime, and the answer's caveats say so rather than the field claiming more than it has.

`dead` on a `public-api` type proves nothing at all: `org.eclipse.ui.ide.IGotoMarker` has no workspace references and is implemented across the ecosystem. `dead` where `searchIsAuthoritative` is `true` has nowhere left to hide, and for `x-friends` that is exact rather than a heuristic, because the friend list names every bundle allowed to reference the package.

`apiRestrictions` reports the PDE API Tools javadoc tags on a type (`noreference`, `noextend`, `noimplement`, `noinstantiate`, `nooverride`). A type in a public package tagged `@noreference` is documented as not for consumption, so no references means more there than it does for untagged public API. The tags are read from the source; no API baseline is involved, since comparing against a baseline answers the different question of whether removing something breaks anyone.

**`apiTier` says what a workspace search can prove**, and it qualifies every verdict rather than replacing it. For an OSGi bundle the declaring package's export decides whether consumers can exist where you cannot see them:

| `apiTier` | Meaning | `searchIsAuthoritative` |
|---|---|---|
| `not-exported` | nothing outside the bundle may reference it | `true` |
| `internal-api` | exported `x-internal`, no legitimate outside consumer | `true` |
| `internal-api-friends` | exported `x-friends`, and the list is enumerable | `true` when every friend is a project here |
| `public-api` | consumers may exist anywhere | `false` |

`x-internal` counts as authoritative for the same reason `not-exported` does, and it is the stronger declaration of the two internal tiers: it says *no* bundle should use the package, where `x-friends` names some that may. Both rest on an access rule JDT and PDE check when consumers compile, not on anything OSGi enforces at runtime, and the answer's caveats say so rather than the field claiming more than it has.

`dead` on a `public-api` type proves nothing at all: `org.eclipse.ui.ide.IGotoMarker` has no workspace references and is implemented across the ecosystem. `dead` where `searchIsAuthoritative` is `true` has nowhere left to hide, and for `x-friends` that is exact rather than a heuristic, because the friend list names every bundle allowed to reference the package.

`apiRestrictions` reports the PDE API Tools javadoc tags on a type (`noreference`, `noextend`, `noimplement`, `noinstantiate`, `nooverride`). A type in a public package tagged `@noreference` is documented as not for consumption, so no references means more there than it does for untagged public API. The tags are read from source; no API baseline is involved, since comparing against a baseline answers the different question of whether removing something breaks anyone.

`typeTests` is reported separately from `registryEvidence` and never changes a verdict. A class named only by `<instanceof value="..."/>` in an enablement expression is `dead` by the rule above, because a type test is not instantiation, but deleting it breaks the expression *silently*: it stops matching rather than failing to compile, which is worse than an error.

The other positions read are declarative services (`implementation@class`, `provide@interface`, and the lifecycle and binding method names), `Bundle-Activator`, `META-INF/services` (the file name is the interface, each line a provider), and reflective loads whose argument is a single string literal.

A name built at runtime is not resolvable by any static analysis, so those sites are reported in `dynamicReflectionSites` and every `dead` verdict in that project is flagged provisional rather than silently downgraded.

Members of a live type are `undecidable`, not dead: the framework holds the instance and calls whatever its contract says, and no declaration list can see that.

Extension points these projects contribute to but that are declared outside the workspace have no readable schema. Class-looking attribute values under those points come back `undecidable`, and the points are listed in `extensionPointsWithoutSchema`, scoped to the projects that were asked about rather than to the whole workspace.

### `eclipse_get_call_hierarchy`

Returns the callers of a Java method, to the requested depth.

| Argument | Type | Default | Meaning |
|---|---|---|---|
| `typeName` | string, required | | Fully qualified type declaring the method. |
| `methodName` | string, required | | All overloads are followed. |
| `project` | string | whole workspace | |
| `direction` | `callers` \| `callees` \| `both` | `callers` | Only `callers` is implemented, see below. |
| `depth` | integer, 1 to 5 | 2 | Each level costs another search. |
| `maxResults` | integer, 1 to 2000 | 200 | Bounds the whole tree, not each level. |

For dead code the useful question is not whether something is referenced but whether it is reachable from anything that is itself reachable, and `eclipse_find_references` cannot answer that: a method whose only callers are themselves uncalled is still dead.

Callers already in the tree are not expanded again, so mutual recursion terminates instead of looping.

`callees` is **not implemented** and says so rather than returning an empty answer. Callers come from the search index; callees would need the AST of every method body, which is a different and far more expensive traversal.

### `eclipse_get_type_hierarchy`

Returns the supertypes and subtypes of a Java type as known to JDT, including types from the classpath.

| Argument | Type | Default | Meaning |
|---|---|---|---|
| `typeName` | string, required | | Fully qualified type name. |
| `project` | string | whole workspace | Project used to resolve the type and to scope the hierarchy. |
| `direction` | `supertypes` \| `subtypes` \| `both` | `both` | Only the requested direction is computed, the subtype direction being the expensive one. |
| `maxResults` | integer, 1 to 2000 | 200 | |

```json
{"type":"org.eclipse.jface.viewers.TreeViewer",
 "supertypes":["org.eclipse.jface.viewers.AbstractTreeViewer"],
 "subtypes":["org.eclipse.jface.viewers.CheckboxTreeViewer"],
 "truncated":false}
```

### `eclipse_get_source`

Returns the Java source and Javadoc of a type or of its members, resolved through the project classpath.
Works for types in libraries as well, as long as a source attachment exists, which is the part a shell cannot do.

| Argument | Type | Default | Meaning |
|---|---|---|---|
| `typeName` | string, required | | Fully qualified type name. |
| `memberName` | string | the whole type | Method or field name. All overloads are returned. |
| `project` | string | whole workspace | Project used to resolve the type. |
| `maxLength` | integer, 100 to 200000 | 40000 | Maximum characters per returned element. |

```json
{"type":"org.eclipse.jface.viewers.TreeViewer","binary":true,
 "path":"/home/user/.p2/.../org.eclipse.jface_3.35.0.jar","sourceAvailable":true,
 "elements":[{"element":"org.eclipse.jface.viewers.TreeViewer.setInput(Object)",
              "line":812,"source":"/** ... */\npublic void setInput(Object input) { ... }",
              "truncated":false}]}
```

When no source is attached, `sourceAvailable` is `false` and a `hint` explains why.

### `eclipse_search_types`

Finds Java types by name across the workspace and everything on the project classpaths, jars included.
Use it to turn a simple name into a fully qualified one.

| Argument | Type | Default | Meaning |
|---|---|---|---|
| `pattern` | string, required | | Simple or qualified name, case insensitive, `*` and `?` allowed. |
| `project` | string | whole workspace | Project whose classpath is searched. |
| `maxResults` | integer, 1 to 2000 | 200 | |

```json
{"total":2,"truncated":false,"types":[
  {"fullyQualifiedName":"org.eclipse.jface.viewers.TreeViewer","simpleName":"TreeViewer",
   "packageName":"org.eclipse.jface.viewers","path":"/.../org.eclipse.jface_3.35.0.jar","binary":true}]}
```

### `eclipse_delete`

**Deletes a source file from the workspace.** Runs as a dry run unless `dryRun` is set to `false`.

| Argument | Type | Default | Meaning |
|---|---|---|---|
| `typeName` | string | | Fully qualified name of the type. Without `memberName`, its whole file is deleted. |
| `typeNames` | array of strings | | Delete several types in one call. |
| `memberName` | string | | A field, method or nested type to delete instead of the file. |
| `project` | string | every Java project | Project to resolve the name in. |
| `dryRun` | boolean | `true` | |
| `force` | boolean | `false` | Delete despite references, a registry position, or a public API package. |

The last step of a dead code sweep, after `eclipse_list_declarations` finds candidates and `eclipse_find_references` confirms them. It reports `references`, `registryEvidence` and `apiTier` on every call, and **refuses unless `force`** when any of the three says the type is still wanted: references remaining make a deletion a compile break rather than a cleanup, a registry position fails at runtime instead of at compile time, and a public API package can have consumers no search here can see.

A file declaring more than one top level type is refused outright when deleting a file.

`typeNames` deletes a batch, building the registry index **once** rather than per type, which matters because that index walks every project in the workspace. Each type is reported separately with its own refusal, so one that cannot be deleted does not stop the others.

**With `memberName` it deletes one member instead**, which is about half the edits of a real sweep: dead constants, private fields, unused methods, nested types. It goes through `IMember.delete`, whose source range includes the javadoc, so the comment goes with the declaration rather than being left behind describing something that no longer exists. An overloaded method name is refused, since the tool cannot tell which one you mean. References in the *same file* count here, unlike a file delete: the file keeps compiling around the hole.

**Read this limitation.** The deletion goes through LTK as a *resource* delete, and PDE's manifest participants are enabled on `IType` and `IPackageFragment` rather than on `IResource`, so **`plugin.xml` class attributes and `Export-Package` are not updated.** Whatever `registryEvidence` the answer reports is what will be left naming a class that no longer exists, and `danglingAfterDelete` says so on any call that would go through. The reason it works this way is that JDT's own delete refactoring, the one PDE's participants are written for, has no usable public API: `DeleteDescriptor` carries no setters and its processor is internal to `org.eclipse.jdt.ui`.

### `eclipse_rename`

**Modifies source files, and a rename can touch hundreds.**
Renames a Java type, method, field, package or compilation unit through the JDT refactoring engine.
Runs as a dry run unless `dryRun` is set to `false`.

| Argument | Type | Default | Meaning |
|---|---|---|---|
| `typeName` | string, required | | Fully qualified type. With `memberName`, the type declaring it. |
| `memberName` | string | the type itself | Method or field to rename. |
| `newName` | string, required | | The new simple name. |
| `project` | string | whole workspace | |
| `kind` | `auto` \| `type` \| `method` \| `field` \| `package` \| `compilationUnit` | `auto` | |
| `updateReferences` | boolean | `true` | |
| `updateQualifiedNames` | boolean | `false` | Also update qualified names in non-Java files, matched textually. |
| `renameGettersAndSetters` | boolean | `false` | For a field. |
| `dryRun` | boolean | `true` | |

```json
{"element":"sample.Target","newName":"Renamed","refactoring":"org.eclipse.jdt.ui.rename.type",
 "dryRun":true,"warnings":[],"affectedFileCount":2,
 "affectedFiles":["/app/src/sample/Target.java","/app/src/sample/User.java"],"applied":false}
```

Going through the refactoring engine rather than editing text is the whole point: overrides and implementations follow, and the refactoring participants that update non-Java references, such as `plugin.xml` class attributes, fire.

Preconditions are checked before anything is written. A rename that would collide with an existing type, or produce an invalid Java name, is **refused** with the reason rather than half applied. Warnings that do not block are reported in `warnings`.

An overloaded method is refused rather than guessed at, because a rename has to name exactly one member.

### `eclipse_clean_up`

**Modifies source files.** Runs as a dry run unless `dryRun` is `false`. Takes `cleanUps` (required), `path` or `project`, and `maxResults`.

Applies JDT's own clean-ups, the transformations behind *Source > Clean Up*, so the result is what Eclipse itself produces rather than a rewrite of our own. Currently offered, by their JDT option key:

| Key | Does |
|---|---|
| `cleanup.use_lambda` | anonymous class to lambda |
| `cleanup.instanceof` | pattern matching for `instanceof` |
| `cleanup.convert_to_enhanced_for_loop` | index loop to enhanced `for` |
| `cleanup.remove_unused_imports` | remove unused imports |
| `cleanup.make_variable_declarations_final` | add missing `final` |
| `cleanup.stringbuffer_to_stringbuilder` | `StringBuffer` to `StringBuilder` |

An unknown key is refused with the list. Each clean-up is a semantic transformation with conditions, so **a file reported with no edits is one where the pattern did not apply, not a failure.**

This is the one place in the server that takes a **discouraged dependency**: `CleanUpConstants` and the `*CleanUpCore` classes are `x-friends` to `org.eclipse.jdt.ui`, and JDT-LS is not on that list either despite using them. It was a deliberate decision rather than an oversight, because there is no public alternative and reimplementing JDT's transformations is not one. It can break in any JDT release with no compile-time signal. `CleanUpRefactoring` was rejected as the entry point because it lives in `org.eclipse.jdt.ui` and would pull the UI in.

`eclipse_remove_unused_imports` stays as it is, on public API, since a targeted tool with no such dependency is worth keeping.

### `eclipse_remove_unused_imports`

**Modifies source files.** Runs as a dry run unless `dryRun` is `false`. Takes `path` for one file or `project` for every file in one, plus `build` (`true`) and `maxResults`.

Removes the imports the compiler reports as unused, and nothing else. `eclipse_organize_imports` also sorts and regroups, so on a file where one import is dead it rewrites the whole block and the change hides among lines nobody meant to touch.

**The compiler decides what is unused**, which is what makes this safe: the project's settings govern, including whether a reference from javadoc keeps an import alive. A remover that reasons about code alone deletes those and leaves `Javadoc: X cannot be resolved` behind, which is exactly what happened to a client that wrote its own.

Markers are only as current as the last build, so it builds first unless told not to. Deleting an import flagged before your last edit would remove one that is now in use.

It exists because JDT's own clean-up machinery is not reachable: `CleanUpConstants`, which holds `REMOVE_UNUSED_CODE_IMPORTS`, is in `org.eclipse.jdt.internal.corext.fix`, x-friends to `org.eclipse.jdt.ui`. Problem markers plus `IImportDeclaration.delete` are public API and give a smaller diff than the clean-up would.

### `eclipse_organize_imports`

**Modifies the file.**
Organizes imports the way JDT does, with the project's own import order and on-demand thresholds, and saves.

| Argument | Type | Default | Meaning |
|---|---|---|---|
| `path` | string, required | | Workspace path of the Java file, e.g. `/app/src/com/example/Main.java`. |
| `resolveAmbiguous` | boolean | `false` | Take the first candidate when a simple name matches several types. |

```json
{"path":"/app/src/com/example/Main.java","importsAdded":2,"importsRemoved":1,
 "changed":true,"ambiguous":[]}
```

By default an ambiguous name, `List` matching both `java.util` and `java.awt` say, aborts the operation with an error naming the candidates rather than guessing, and the file is left untouched.

### `eclipse_format`

**Modifies the file.**
Formats a Java file with the formatter settings of its own project and saves it.

| Argument | Type | Default | Meaning |
|---|---|---|---|
| `path` | string, required | | Workspace path of the Java file. |

```json
{"path":"/app/src/com/example/Main.java","changed":true}
```

### `eclipse_read_file`

Reads a workspace file by workspace path, the same path form `eclipse_open` takes. Read-only.

| Argument | Type | Default | Meaning |
|---|---|---|---|
| `path` | string, required | | Workspace path of the file. |
| `offset` | integer | 1 | First line to return, 1 based. |
| `limit` | integer | rest of file | How many lines to return. |
| `maxBytes` | integer | 1000000 | Refuse rather than return more than this. |
| `refresh` | boolean | `true` | Read outside changes into the workspace first. |

This exists because a client is not always on the same machine as the IDE, and once the window is hidden there is no filesystem to fall back on. It is also the only way to look at the `plugin.xml` or `.exsd` that `eclipse_list_declarations` cites as evidence, in the same IDE the verdict came from rather than in your own copy of the tree, which may not even be the same revision.

The file is read through the workspace, so it uses the encoding Eclipse has for it. A naive read of the bytes gets that wrong silently for properties files and anything not UTF-8.

Binary files are reported as `binary` rather than returned as a mangled string.

### `eclipse_search_text`

Searches the text of workspace files, including the ones the Java model cannot see: `plugin.xml`, `.exsd`, `.project`, manifests, properties. Read-only.

| Argument | Type | Default | Meaning |
|---|---|---|---|
| `pattern` | string, required | | Text, or a regular expression when `isRegex`. |
| `isRegex` | boolean | `false` | |
| `isCaseSensitive` | boolean | `false` | |
| `projects` | array of strings | whole workspace | |
| `path` | string | | Restrict to a workspace folder or file. |
| `fileNamePattern` | string | every file | Glob over file names, e.g. `*.exsd`. |
| `excludePathPattern` | string | | Glob over the workspace path; matches are skipped. |
| `includeDerived` | boolean | `false` | Include resources Eclipse marks derived. |
| `maxResults` | integer, 1 to 5000 | 200 | |

For Java elements `eclipse_find_references` answers better, because it resolves overloads and inheritance and this does not. This is for everything that is not Java.

**Maven and Gradle output is not marked derived**, so `includeDerived` does not exclude it and a search of a built tree comes back mostly build output. `excludePathPattern` is the answer, for example `*/target/*`, and `excludedByPath` reports how many matches it dropped.

It runs through Eclipse's own `TextSearchEngine`, so **resources Eclipse marks derived are excluded by default**. That is the difference between this and a raw grep of the same tree, where every type comes back once per copy under a build output directory.

Each match reports the file, the line number and the line, capped at 500 characters.

**One file on disk counts once.** In a platform workspace almost every project is nested inside another, so a single file is reachable through several workspace paths and the same match arrives once per path. Matches are deduplicated by physical location and offset, the other paths come back as `alsoVisibleAs`, and `duplicatePathsCollapsed` says how many were folded away. Without this a count is inflated by an unpredictable factor that a client cannot detect without a filesystem, which is the thing this tool exists to do without.

### `eclipse_list_editors` and `eclipse_close_editor`

`eclipse_list_editors` lists the open editors in tab order, with the file each shows, which is active, which are pinned, and which have unsaved changes. Read-only, no arguments.

It is the tool that answers "is there unsaved work", which `eclipse_restart` refuses on and which nothing else reported on its own. It matters more once the IDE can be hidden, since nobody can look at a window they cannot see.

`eclipse_close_editor` **changes what the IDE shows**. It selects by `path`, by `title` substring, or `all`, and refuses to act on omission.

A clean editor closes with no ceremony, because closing it loses nothing. **A dirty editor is refused** unless `save` is passed, which saves it first, or `discardUnsaved`, which throws the changes away and is never a default. The save goes through the editor rather than through `closeEditors(refs, true)`, so no save prompt is ever raised: an unattended call cannot leave a dialog waiting for somebody who is not there.

### `eclipse_get_project_dependencies`

Reports the projects a project references and the open projects that reference it, as Eclipse resolves them.
This covers JDT build path project entries and the dynamic references PDE computes from `Require-Bundle`, so it answers what `.project` and `.classpath` cannot by inspection.

| Argument | Type | Default | Meaning |
|---|---|---|---|
| `project` | string | every open project | |
| `direction` | `references` \| `referencedBy` \| `both` | `both` | |
| `transitive` | boolean | `false` | Follow the graph instead of direct neighbours only. |
| `maxResults` | integer, 1 to 2000 | 200 | |

`referencedBy` only ever reports open projects, because that is all `IProject.getReferencingProjects()` sees, and it is also all the builder sees. Use it before closing a project, and to find the leaves of a graph.

### `eclipse_get_classpath`

Reports the build path of a Java project as JDT resolved it.

| Argument | Type | Default | Meaning |
|---|---|---|---|
| `project` | string, required | | |
| `resolved` | boolean | `true` | Expand containers and variables. |
| `maxResults` | integer, 1 to 5000 | 500 | |

`rawEntries` mirrors `.classpath`, but each container also carries its description and, for the JRE container, `boundJre` with the name, type and install location of the JDK actually bound to it.
That binding is the point of the tool. A `JavaSE-1.8` container says nothing about which JDK the IDE chose for it, and that choice decides whether a `--release` compile works. Nothing outside the IDE knows it.

`resolvedEntries` is the expansion: the jars behind each container, source attachments, access rules and classpath attributes.

### `eclipse_open`

**Changes what the IDE shows**, writes nothing.
Opens a workspace file in an editor and optionally reveals a line, so the person at the IDE is looking at what you are talking about instead of copying a path.

| Argument | Type | Default | Meaning |
|---|---|---|---|
| `path` | string, required | | Workspace path of the file. |
| `line` | integer | | Line to reveal, 1 based. |
| `activate` | boolean | `true` | Bring the editor to the front. |

`revealedLine` reports the line actually revealed, which is clamped to the end of the file.

### `eclipse_open_compare`

**Changes what the IDE shows**, writes nothing.
Opens Eclipse's compare editor on a workspace file, against another file, against content you supply, or against a Git revision.

| Argument | Type | Default | Meaning |
|---|---|---|---|
| `left` | string, required | | Workspace path of the file to compare. |
| `right` | string | | Workspace path to compare it against. |
| `content` | string | | Text to compare it against. |
| `revision` | string | | Git revision to compare it against, such as `HEAD`, `HEAD~1`, a branch, a tag or a commit id. |
| `leftLabel` | string | the workspace path | Label over the left side. |
| `rightLabel` | string | what the right side is | Label over the right side. |
| `activate` | boolean | `true` | Bring the compare editor to the front. |

Exactly one of `right`, `content` and `revision` is required.

`content` is the interesting one: it shows a proposed edit side by side with the file, with syntax colouring and the structural Java compare, before anything is written.
A diff pasted into a chat window is the same information in a form nobody reviews carefully.

Both sides are read-only. The editor is a view of a difference, not a merge tool, so no path through this tool can modify a file.

`identical` reports that the two sides are byte for byte the same.
The editor still opens and shows nothing, which is confusing enough to be worth saying in the answer rather than leaving the caller to wonder.
The compare input is also always built as a real difference node for the same reason the p2 tools install their own trust callback: an empty result makes the compare framework raise a modal "no differences" dialog, and a dialog on a path a client drives is a hang nobody is there to clear.

`revision` needs the `org.eclipse.jgit` bundle, which is an **optional** dependency: every Eclipse with EGit has it, a bare Platform SDK does not, and where it is missing this one argument is refused with an explanation while everything else keeps working.
The repository is found by walking up from the file, so it does not matter whether the project is shared in the IDE.

### `eclipse_get_editor_context`

Returns the file in the active editor, the cursor position and the current selection.
Use it to resolve vague references such as "this method" or "the file I am looking at".
No arguments.

```json
{"hasActiveEditor":true,"title":"Main.java","path":"/app/src/com/example/Main.java",
 "project":"app","dirty":false,"cursorLine":42,"cursorOffset":1187,
 "selectionLength":12,"selectedText":"doSomething()","selectedTextTruncated":false}
```

`openEditors` lists every open editor with its `dirty` flag. A file read from disk while its editor is dirty is not the file the user is looking at, and nothing outside the IDE can tell, so this is worth checking before drawing conclusions from file contents.

`selectedText` is capped at 2000 characters.
When there is no workbench, no window or no file-backed editor, the answer is `{"hasActiveEditor":false}`.

### `eclipse_start_sampling` and `eclipse_stop_sampling`

Samples thread stacks at a fixed interval, to profile an operation or diagnose a freeze.

`eclipse_start_sampling` takes `threads` (`ui` or `all`), `threadNames`, `intervalMillis` (100), `maxSamples` (300) and `maxDepth` (80), and returns a `sessionId`.
`eclipse_stop_sampling` takes that id, plus `topMethods`, `minSamples`, `includeRawSamples`, `keepRunning` and `frameFilter`.

`frameFilter` restricts the aggregate to stacks containing a package prefix or a class, and is applied when reading rather than when sampling, so one session can be read from several angles with `keepRunning`. It earns its place because the top of an unfiltered IDE profile is Jetty accept loops, the AWT event pump and the reference handler, none of which is ever the answer to the question being asked.

**Turn `includeIdleThreads` on to diagnose a freeze.** A frozen thread is usually parked, so the default, which exists to stop the pooled threads of an idle IDE dominating the result, drops exactly the samples that explain a stall. Profiling slow work and profiling a freeze want opposite settings.

Sampling runs on a daemon thread through `ThreadMXBean`, which needs neither the UI thread nor any workspace lock, so it keeps working while the IDE is frozen. That is the requirement, not a detail: a profiler that queues behind the freeze is useless for the case it exists for.

The result is **aggregated, not dumped**: the frames where time was actually spent (`topBySelfTime`), the frames most often on the stack (`topByPresence`), and the samples merged into one call tree. A hundred samples of seventy frames is seven thousand lines, so the raw samples only come back on request.

`ThreadMXBean` sampling is safepoint biased, so tight loops without safepoint polls are under-represented. Treat it as "where is the time going", not as an exact profiler.

### `eclipse_list_ui_targets`

Lists every open shell with its title, modality and bounds, and every workbench part with its id, title and visibility.
It is also the only way to answer "which dialog is open right now".

With `includeAvailableViews` it also lists the views registered in this IDE whether or not they are open, which is where `eclipse_show_view` gets its ids.
There are several hundred, so `filter` matches a substring of the id or the label and `maxResults` defaults to 100.

### `eclipse_set_ide_visibility`

**Changes what the user sees**, in the one way they cannot undo from the IDE.
Takes the Eclipse window off the screen, or brings it back, for using the IDE as a backend rather than as something to look at.

| Argument | Type | Default | Meaning |
|---|---|---|---|
| `visible` | boolean, required | | `false` takes it off the screen, `true` brings it back and focuses it. |
| `mode` | `hidden` \| `minimized` | `hidden` | How to take it off the screen. Ignored when showing. |

The IDE keeps running while hidden. Builds, searches, tests and every other tool here work unchanged, because the workbench event loop belongs to the display and not to the window.

`hidden` removes the window from the screen and the taskbar entirely; `minimized` leaves it reachable by hand, which is the safer choice when a person is at the machine.

Hiding a window is easy to make unrecoverable, and that is the whole risk here: a hidden window has no menu and no taskbar entry, so the only way back is this tool. Two things make it safe. Calling it with `visible: true` restores it, and the plug-in restores every window it hid when it stops, so disabling or uninstalling the server cannot leave an IDE nobody can see and nothing can bring back.

While hidden, dialogs are still raised and are still invisible. `eclipse_list_ui_targets` and `eclipse_dismiss_dialog` remain the way to see and answer them, and they are worth more than usual in this state.

### `eclipse_show_view` and `eclipse_hide_view`

**These change the perspective layout**, which Eclipse remembers across restarts. They write nothing to the workspace.

`eclipse_show_view` opens a view in the active perspective and `eclipse_hide_view` closes one.
Both take `view`, which is an id or the label a person reads, so `Problems` works as well as `org.eclipse.ui.views.ProblemView`.
`eclipse_show_view` also takes `activate` (`true`) and both take `secondaryId`, for views such as the Console that can be open more than once.

A name is resolved by exact id, then by exact label, then by substring, and stops at the first of those that matches anything.
Without the ordering an exact id that also occurs inside three other ids comes back ambiguous.
An ambiguous name is **refused with the candidates** rather than guessed, and a name that matches nothing open is refused with the list of what is open.

There is no tool for closing editors. An editor can hold unsaved work, and losing it is not something a client should be able to do by accident.

### `eclipse_screenshot`

Captures the IDE as a PNG, writes it to a file and returns the path.

| Argument | Type | Default | Meaning |
|---|---|---|---|
| `target` | `part` \| `shell` \| `display` | `part` | |
| `part` | string | | Part id, from `eclipse_list_ui_targets`. |
| `shellTitle` | string | active shell | Title or substring. |
| `activate` | boolean | `false` | Bring the part forward first. |
| `maxWidth` | integer, 100 to 4000 | 1200 | Downscale before writing. |
| `outputPath` | string | a temporary file | |
| `includeBase64` | boolean | `false` | Also return the image inline. |

Screenshots earn their place for UI work: layout, theming, dialog rendering, confirming a widget change looks right. For anything textual the other tools answer better and shorter, and a screenshot of the Problems view shows twenty rows of several thousand.

`display` captures whatever else is on the screen, mail and chat included, which is why it is not the default.

A part behind another tab is not rendered at all, so capturing it would produce an empty image. It is **refused** unless `activate` is passed, because activating visibly rearranges the user's IDE and should not be a silent side effect.

`method` in the answer says how the image was produced. `rootCapture` reads the real screen pixels for the area and crops. `widgetPrint` paints the widget hierarchy instead, which is the fallback used when the first attempt comes back uniform: under a compositing window manager such as mutter, a redirected window's contents live in an offscreen pixmap, so reading the X11 root drawable yields nothing at all. Printing has known GTK gaps, which is why it is the fallback and not the primary path, but a slightly wrong image beats no image.

There is no fallback for `display`, since there is no single widget to paint, so on such a display only `part` and `shell` can be captured.

The uniform-pixel check is what makes this safe rather than quietly wrong: SWT returns a blank image with no error at all on GTK4, and root capture returns blank under compositing, so a screenshot tool that trusts its own output writes an empty PNG and says it succeeded. Here a capture that is uniform after both attempts is refused and nothing is written.

### `eclipse_check_for_updates`, `eclipse_update`, `eclipse_install`, `eclipse_get_provisioning_status`

**These modify the installation.**
`eclipse_check_for_updates` changes nothing and reports which installed units have an update, from which version to which, and the configured repositories with the timestamp of the metadata behind each one.

Both take `units`. Naming the installed units you care about scopes the whole operation: `ProvisioningContext.getInstallableUnitSources` is asked which repositories can supply them, only those are refreshed, and the resolution is restricted to them with `setProvisioningContext`, so unlisted repositories are never loaded at all. On an IDE configured with a dozen update sites that is one network round trip instead of a dozen. Omitting `units` keeps the broad question broad, because narrowing it silently would be the same class of mistake as reporting a stale cache as up to date.

Only the metadata manager is ever refreshed, never the artifact manager. An update check does not read artifact metadata, and refreshing both is what makes a check cost twice what it needs to.

It re-reads that metadata first, by default. p2 caches repository metadata, and a cached miss comes back as "no updates found", which is exactly what a genuinely current IDE reports, so a stale cache is invisible and looks like success. That is worst in the self-update workflow these tools exist for: publish a build, check for updates, and be told there is nothing new. `refresh: false` gives the fast cached answer instead, and then says so in a `caveat` rather than letting the miss pass as a verdict. `eclipse_update` refreshes for the same reason, since otherwise it resolves against the cache and finds nothing to apply.
**Updating the server itself is refused unless `acknowledgeSelfUpdate` is passed.** The provisioning job runs inside the bundles being replaced, so a self update stops the bundle answering the request, and if anything then fails there is nothing left running to finish the update or to report why. The result is an IDE with no server, no way in, and no recovery except restarting Eclipse by hand at the machine. That was survivable while the IDE was something a person could see; it is not once the window can be hidden. The connection dropping is expected and fine, and a client can wait for it to come back. The bundle staying stopped is the failure, and it has no path back from outside.

`eclipse_update` applies updates to units that are already installed, from repositories already configured. `eclipse_install` adds a new unit.
Both run as jobs and return an `operationId` polled through `eclipse_get_provisioning_status`, because p2 resolution can take minutes on a slow mirror.

Both take `trustUnsigned`, which is **refused by default**. p2 asks whether to trust unsigned content or content signed by an untrusted certificate, and the IDE answers that with a modal dialog. During these calls that dialog is replaced by an answer, because a job blocked on a prompt is indistinguishable from a slow download and an unattended update would otherwise hang until the call timed out. The refusal is reported in `refusedTrust` and `blockedBy`, naming what would have to be trusted. Signing the artifacts on the update site removes the question for every consumer instead of teaching one client to click through it.

`eclipse_install` **refuses a repository the IDE is not already configured with**, and lists the ones that are. Installing fetches and runs code from the network, which is a larger step than any other tool here takes, and adding a new source is a decision for the person at the IDE. Add it under *Preferences > Install/Update > Available Software Sites* first.

This is self-updating machinery, and the descriptions say so: if a bad build lands, the tools that would fix it are the tools that just broke. Two things make that recoverable. `eclipse_restart` is in a different bundle and does not depend on the provisioning tools, so a half-applied update can still be restarted out of. And every result carries `previousConfiguration`, the timestamp to revert to from *Help > About > Installation Details > Installation History*, which works with no server at all.

### `eclipse_restart`

**Restarts the IDE. The connection will drop by design.**
The tool answers first and restarts two seconds later, so a dropped connection immediately after a successful result is the expected outcome rather than a failure. Reconnect with the same bearer token: it lives in the bundle state location, which is keyed by symbolic name and survives both restarts and p2 updates.

| Argument | Type | Default | Meaning |
|---|---|---|---|
| `save` | boolean | `false` | Save dirty editors first. |
| `force` | boolean | `false` | Restart anyway, discarding unsaved work. |

It refuses when editors have unsaved changes or a modal dialog is open, listing them, since restarting under an open dialog loses whatever is in it.

The answer names the `workspace` the IDE will return to. If it comes back asking which workspace to use, the relaunch lost its arguments, which is what `IWorkbench.restart()` does; `restart(true)` is what preserves `-data`.

## Contributing a tool

Tools are contributed through the `com.vogella.eclipse.mcp.core.tools` extension point:

```xml
<extension point="com.vogella.eclipse.mcp.core.tools">
   <tool class="com.example.MyTool"/>
</extension>
```

`com.example.MyTool` implements `com.vogella.eclipse.mcp.core.IMcpTool`.
The contract for an implementation:

* it is called on a worker thread, never on the UI thread, and never holding the workspace lock
* it must not modify the workspace and must not open a dialog
* to reach the UI, hand work to `Display.asyncExec` and wait on a future with a short timeout, the way `eclipse_get_editor_context` does
* the server aborts any call that has not finished after 30 seconds

## Bundles

| Bundle | Contains | Depends on |
|---|---|---|
| `com.vogella.eclipse.mcp.core` | `IMcpTool`, the registry, the extension point, and the two workspace tools | `org.eclipse.core.runtime`, `org.eclipse.core.resources` |
| `com.vogella.eclipse.mcp.server` | The MCP protocol handling, the embedded Jetty and the bearer token filter | MCP SDK, Jetty, core |
| `com.vogella.eclipse.mcp.jdt` | The two Java model tools | `org.eclipse.jdt.core`, core |
| `com.vogella.eclipse.mcp.ui` | The editor context tool, the preference page and the startup hook | `org.eclipse.ui`, core, server |

`com.vogella.eclipse.mcp.core` deliberately has no reference to the MCP SDK, to Jetty or to any UI bundle, so that the tool API stays a candidate for the Eclipse Platform.

## Not in this iteration

General file writing, refactorings such as rename; debugger inspection; MCP resources and prompts; a stdio transport.
