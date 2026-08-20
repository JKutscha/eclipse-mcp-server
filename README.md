# Eclipse MCP Server

Turns a running Eclipse IDE into an [MCP](https://modelcontextprotocol.io) server, so that external LLM clients (Claude Code, Cursor, any MCP-capable agent) can ask the IDE for information they cannot cheaply reconstruct from files alone.

An agent with a shell already has files, grep and git.
What it does not have is the resolved Java model, the incremental builder's problem markers and the user's current editor context.
Those are the capabilities exposed here.

Most tools are read-only. Two of them, `eclipse_organize_imports` and `eclipse_format`, modify the file they are pointed at; they are marked as such below.
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
To pin a version, point at its own site instead, for example `https://vogellacompany.github.io/eclipse-mcp-server/releases/0.2.0/`.

Each release also carries the repository as a zip, for offline installs through *Add > Archive*.
When building from source, the same repository is produced under `update-site/com.vogella.eclipse.mcp.repository/target/repository` and can be added as a local site.

## Releasing

Pushing a `v<version>` tag runs `.github/workflows/release.yml`, which builds the tag, copies the p2 repository into `releases/<version>/` on the `gh-pages` branch, regenerates the composite metadata with `releng/update-composite-site.sh` and attaches the repository archive to the GitHub release.

## Developing in the IDE

1. Import the projects with *File > Import > Existing Projects into Workspace*, pointing at the repository root and enabling *Search for nested projects*.
2. Open `target-platform/com.vogella.eclipse.mcp.target/com.vogella.eclipse.mcp.target.target` and click *Set as Active Target Platform*. Resolving it downloads the Eclipse SDK, so the first run takes a while.

## Enabling the server

*Preferences > General > MCP Server*:

* **Enable MCP server**, off by default
* **Port**, `8642` by default

The setting takes effect immediately, and the server also starts on the next IDE startup while it stays enabled.

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
  "token": "0f0f2a2e-1f9c-4c4a-9a0e-6d0f8f0f1e2b"
}
```

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
| `tools/list` | the nine tools below |
| `tools/call` | arguments are validated against the tool's input schema before the tool runs |
| `notifications/initialized`, `notifications/roots/list_changed` | accepted and ignored |

Everything else, `resources/list`, `prompts/list`, `logging/setLevel` and `completion/complete` among them, is answered with method not found.
Sessions are carried in the `mcp-session-id` header, a `GET` opens the server-to-client SSE stream and a `DELETE` ends the session.

## Tools

Every tool returns a single text block containing pretty-printed JSON.
Every list-returning tool honours `maxResults` and reports `total` and `truncated`, so the model can tell when it is seeing a partial answer.
All tools are read-only except `eclipse_organize_imports` and `eclipse_format`.

### `eclipse_list_projects`

Lists the projects in the workspace, with their natures and open/closed state.
No arguments.

```json
{"projects":[{"name":"com.example.app","open":true,
              "natures":["org.eclipse.jdt.core.javanature"],
              "location":"/home/user/git/app"}]}
```

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

### `eclipse_find_references`

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
 "total":17,"truncated":false,
 "matches":[{"path":"/app/src/com/example/View.java","project":"app","line":88,
             "offset":2451,"length":8,"kind":null,
             "enclosingElement":"com.example.View.createPartControl(Composite)"}]}
```

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

### `eclipse_get_editor_context`

Returns the file in the active editor, the cursor position and the current selection.
Use it to resolve vague references such as "this method" or "the file I am looking at".
No arguments.

```json
{"hasActiveEditor":true,"title":"Main.java","path":"/app/src/com/example/Main.java",
 "project":"app","dirty":false,"cursorLine":42,"cursorOffset":1187,
 "selectionLength":12,"selectedText":"doSomething()","selectedTextTruncated":false}
```

`selectedText` is capped at 2000 characters.
When there is no workbench, no window or no file-backed editor, the answer is `{"hasActiveEditor":false}`.

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
