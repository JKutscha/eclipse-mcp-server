# Plan: several Eclipse instances, each with its own MCP server

Status: proposal, nothing here is implemented.
The purpose is to weigh the variants before code is written, and to record why the chosen one was chosen.

## The problem

A user runs more than one Eclipse at a time, different workspaces or different installations, and wants a client to reach each of them without configuring anything by hand each time.

Today that fails at one point and is awkward at three more.

1. **One static port.**
   `McpPreferences.getPort()` is a single number, `8642` by default, and `McpServerService.start` binds exactly that.
   The second IDE fails to bind, stays down, and records the reason in `getLastError()`.
   That was a deliberate choice, recorded in `AGENTS.md`: falling back silently would break every configured client.
2. **The server is off in every new workspace.**
   `enabled` is an instance preference, so a workspace nobody has visited the preference page in comes up with nothing listening.
   `releng/mcp-test-ide.sh` and `RestartTool.carryTheServerOver` both work around this by writing the `.prefs` file before the IDE starts.
3. **Discovery is per workspace and by path.**
   `endpoint.json` is written under `<workspace>/.metadata/.plugins/com.vogella.eclipse.mcp.server/`.
   A client can read it only if it already knows which workspace it wants, and nothing lists the instances that are up.
4. **Clients are configured statically.**
   Claude Code, Cursor, Copilot and Codex take either an HTTP URL plus headers or a stdio command.
   None of them re-reads a discovery file, so a port that varies has to be resolved by something the client starts.

Two things are already right for this and must stay.
The bearer token is user scoped, `~/.eclipse/com.vogella.eclipse.mcp.server/token`, so one secret serves every instance this user starts; with several servers that is the only workable shape.
And a workspace is locked by the IDE that opened it, so "one workspace" and "one running server" are the same thing, which makes the workspace path a natural instance id.

## What works today with no code

Worth stating, because part of the request is met by documentation alone.

**Enabling the server for every workspace of an installation.**
`McpPreferences.isEnabled()` reads through `Platform.getPreferencesService()` with the default lookup order, which is instance, then configuration, then default scope.
Writing `enabled=true` into `<installation>/configuration/.settings/com.vogella.eclipse.mcp.server.prefs`, or into a `plugin_customization.ini` named by `-pluginCustomization` in `eclipse.ini`, therefore switches the server on in every workspace that has not set the key itself.
The same holds for `port` and `callTimeoutSeconds`.
This is undocumented and should be documented whatever else is done.

**Two instances on different ports, by hand.**
`port` is an instance preference, so two workspaces can be given two ports on their preference pages and both servers run.
Each writes its own `endpoint.json`, and `releng/mcp-script.py --workspace <dir>` already reads it.
The cost is a manual step per workspace and a client configuration per workspace, which is exactly what the request wants to remove.

**Two instances on different ports, from outside.**
`releng/mcp-eclipse-ini.py` removes the manual step on the IDE side without touching the plug-in: it picks a free port before the IDE starts, writes a `-pluginCustomization` file that switches the server on and names the port, and keeps a registry so the port stays with its workspace and is given back when the file is deleted.
That is the A2 sticky range below, done by a launcher script instead of by the server, and it is what a user can run today.
What it cannot do is register the running instances for a client, which is B and C.

## The sub-problems

The feature decomposes into four questions, and the variants below are per question so they can be combined.

- **A. Port allocation.** How does each instance get a port of its own without a person choosing it?
- **B. Registration.** How does anything outside the IDE learn which instances are up, and where?
- **C. Client connection.** How does a statically configured client end up talking to the right instance?
- **D. Turning it on.** What does the user do once, and what must they never have to do again?

## A. Port allocation

### A1. Fallback scan over a range

`port` stays the first candidate.
When binding fails and the multi-instance mode is on, `start()` tries `port+1`, `port+2`, up to a bounded count, say 20.

Touches `McpServerService.start` and `createJetty`, and `McpServerJob.reconcile`, which currently restarts the server whenever `getPort() != McpPreferences.getPort()` and would otherwise bounce a server that legitimately landed on `port+3`.

Pro: predictable set of candidates, a client could even probe them.
Con: which workspace gets which port depends on startup order, so a URL a client learned yesterday points at a different IDE today.
That is the exact hazard the "never fall back" rule exists for, and A1 alone reintroduces it.

### A2. Sticky range: A1 plus a remembered port per workspace

As A1, but the port actually bound is written back into the instance scope as `lastPort`, and the next start tries `lastPort` first, then the range from `port`.

Since a workspace is opened by one IDE at a time, `lastPort` is a per-instance value with the right lifetime.
Once every workspace a user opens regularly has landed once, the assignment is stable unless something else takes a port, and a client configured with a workspace's URL keeps working.

Touches the same places as A1 plus one preference key, and `RestartTool.carryTheServerOver`, which copies `port` into a fresh target workspace and should copy nothing for `lastPort`, so the new workspace allocates rather than colliding with the one it left.

Pro: stable per workspace in practice, still bounded and human readable.
Con: stability is best effort, not a guarantee; registration in B is still needed for the first start and for the cases where the sticky port was taken.

### A3. Ephemeral port

`port=0` hands the choice to the operating system.
Jetty's `ServerConnector.setPort(0)` plus `getLocalPort()` after `start()` is the whole change in the service, and `McpEndpoint` is built from the read-back port.

Pro: the smallest possible change and it never collides.
Con: the port changes on every start, so nothing can be configured by URL at all and every connection goes through discovery.
That makes B and C mandatory rather than helpful.
It also removes the one thing a person can read off the preference page and type into a client.

### A4. Port derived from the workspace path

`base + hash(workspacePath) mod range`, with a scan fallback on collision.

Pro: stable per workspace across machines and reinstalls, no state at all.
Con: collisions are silent until two workspaces happen to hash together, and a port the hash chose may belong to another program, so the fallback is still needed and the promise of stability is weaker than it looks.
A2 gives the same stability with a mechanism a person can follow.

### Rejected: a broker on the well known port

One process, or the first IDE, owns `8642` and routes to the others by workspace.
It changes the transport layer, adds a routing hop to every call, and the IDE that owns the port going away takes every other instance offline.
Too large and too fragile for an optional feature.

**Choice for A: A2.**
A3 stays available as a configuration, since `port=0` falls out of the same code path, for users who never configure a client by URL.

## B. Registration

### B1. Per-workspace `endpoint.json` only

What exists.
Sufficient when the client knows the workspace path, insufficient for "list what is running" and for a client started in a project directory that does not know which workspace holds it.

### B2. A user scoped registry directory

Every server writes `~/.eclipse/com.vogella.eclipse.mcp.server/instances/<id>.json` on start and removes it on stop, beside the token, through the same `PrivateFiles` atomic write.
`<id>` is a stable hash of the workspace path, so an instance restarting into the same workspace replaces its own entry rather than adding one.

Contents, a superset of `endpoint.json`:

```json
{
  "url": "http://127.0.0.1:8643/mcp",
  "workspace": "/home/me/workspace/swt",
  "installation": "/opt/eclipse-sdk",
  "pid": 41230,
  "startedAt": 1787300000000,
  "application": "org.eclipse.ui.ide.workbench",
  "projects": ["/home/me/git/swt", "/home/me/git/swt/bundles/org.eclipse.swt"]
}
```

`projects` are the locations of the open projects, which is what lets a launcher pick the instance whose workspace contains the directory the client was started in.
It can go stale as projects are imported, so a reader treats it as a hint and can fall back to asking the instance through `eclipse_list_projects`.

Stale entries are the hazard: an IDE killed hard removes nothing.
Readers check `ProcessHandle.of(pid).isPresent()` from Java and `os.kill(pid, 0)` or the equivalent from Python, and a server starting up sweeps entries whose process is gone.
A pid is reused eventually, so the check is pid and `startedAt` against the process start time where available, and a reachability probe of `url` settles the rest.

The token is deliberately not repeated here.
It is one file away in the same directory, and a registry that is safe to `cat` while diagnosing is worth the one extra read.

Touches `EndpointFile`, or a sibling `InstanceRegistry` in the same package, and `McpServerService.start` and `stopQuietly`.
`markStopped` keeps its current meaning for the per-workspace file; the registry entry is removed rather than marked, because "listening now" is its only question.

### B3. Probing the port range

With A1 or A2 and no registry, a client can probe `port` to `port+N`.
The `401` body already names the workspace a server serves, so an unauthenticated `GET` answers "who is here" without a new endpoint.

Pro: no new file.
Con: N connection attempts per lookup, no answer for A3, and reading a workspace path out of an error message is a contract nobody wrote down.
Fine as a fallback in the launcher, wrong as the primary mechanism.

**Choice for B: B2, keeping B1 as it is.**

## C. Client connection

### C1. A stdio launcher that resolves and forwards

A small executable, Python with the standard library only like `releng/mcp-script.py`, registered once in the client as a stdio MCP server.
On start it reads the registry, picks an instance, and forwards JSON-RPC between its stdin and stdout and the instance's Streamable HTTP endpoint, adding the bearer token and the session header itself.

Selection order:

1. `--workspace <dir>` names it.
2. `--url` names it.
3. The client's working directory lies under one entry's `projects` or `workspace`.
4. Exactly one instance is up.
5. Otherwise it refuses with the list, so nothing is guessed.

Registering it once:

```bash
claude mcp add eclipse -- eclipse-mcp
```

and a Cursor `mcp.json` entry with `"command": "eclipse-mcp"` does the same.

Forwarding is about 200 lines: `initialize` and every request are `POST`ed, a `text/event-stream` answer is unwrapped the way `mcp-script.py` already does, the server to client `GET` stream is optional because this server declares no notifications.

Where the script lives is the one open design point.
Shipping it inside the server bundle and extracting it to `~/.eclipse/com.vogella.eclipse.mcp.server/bin/` on start, with the preference page showing the exact `claude mcp add` line and a copy button, is what makes "easy to turn on" true without a checkout of this repository.

Pro: works with every client, with A3 as well as A2, and the client configuration never changes again.
Con: a process per client session, Python required on the client machine, and one more moving part between client and IDE.
A Java version run by the IDE's own JRE is possible but the bundled JustJ runtime has no `jdk.compiler`, so single-file source launch is out and it would have to be a jar; Python is the smaller ask.

### C2. Stable URL per workspace, configured once per workspace

With A2 the URL settles per workspace, so a user can register `http://127.0.0.1:8643/mcp` for the SWT workspace and `8644` for the platform one, reading both off their preference pages.
Claude Code's `.mcp.json` expands `${VAR}` in `headers`, so the token can come from an environment variable set once in the shell profile rather than being pasted per project.

Pro: no extra process, pure documentation once A2 exists.
Con: one manual step per workspace, and a sticky port that moves breaks it silently, which is the original hazard again with a smaller blast radius.
Right as the documented fallback for people who dislike the launcher, not as the default.

### C3. Relay tools inside the IDE

Every instance gains `eclipse_list_instances`, reading the registry, and `eclipse_call_instance`, which forwards one tool call to a sibling with the shared token.
The client stays configured against whichever instance it already reaches and gets at the others through it.

Pro: zero client side change.
Con: the forwarded tools are invisible to the model as tools, so it works for a model that already knows the tool names and not for one discovering them; and the instance the client is configured against has to be up for any of it to work.
`eclipse_list_instances` alone is cheap and useful for diagnosis whatever else is chosen.
`eclipse_call_instance` is not recommended.

### Rejected: the IDE writes the client's configuration

Writing `.mcp.json` or `.cursor/mcp.json` into project roots puts a URL and possibly a token into directories that get committed, and writing into `~/.claude.json` or Cursor's user settings means owning another product's file format.
Neither is acceptable for a feature that is supposed to be a small, optional addition.

**Choice for C: C1, with C2 documented and `eclipse_list_instances` from C3.**

## D. Turning it on

One switch, off by default, in the **configuration** scope like `replaceSplash`, because it is a property of "how this installation behaves when several of it run" and not of one workspace.
Working name `multipleInstances`.

When it is off, nothing changes: exact port, refusal on collision, `endpoint.json` alone.
When it is on:

- `start()` allocates through A2 and writes the registry entry of B2;
- `McpServerJob.reconcile` treats a running server as in line with the preferences when its port is within the range, not only when it equals `port`;
- the preference page shows the port actually bound, the range, the registry path, and the launcher command with a copy button;
- the startup hook extracts the launcher script if it is shipped.

The other half of "no user interaction" is `enabled`.
Two options, and they are not exclusive.

- Document the configuration scope route above, which already works.
- Have the `multipleInstances` checkbox offer, or simply perform, writing `enabled=true` into the configuration scope, so one checkbox in one workspace switches the server on for every workspace of that installation.
  The page should say that it did, because an instance preference that is on in a workspace nobody enabled it in is otherwise a surprise.

A `-D` system property as an override for both, `-Dcom.vogella.eclipse.mcp.enabled=true` and `-Dcom.vogella.eclipse.mcp.multipleInstances=true`, would let a launcher script or an `eclipse.ini` turn it on without a preference file, the way `tokenDirectory` already works for the tests.
Cheap to add in `McpPreferences`, and it is how `mcp-test-ide.sh` would stop writing a `.prefs` file by hand.

## Recommended combination and the resulting flow

A2 sticky range, B2 user scoped registry, C1 stdio launcher, `eclipse_list_instances`, D as one configuration scoped switch.

The user does this once:

1. Install the feature into the installation.
2. Open *Preferences > General > MCP Server* in any workspace, tick *Enable*, tick *Several IDEs*, press Apply.
3. Copy the launcher line from the page into the client, once.

From then on every Eclipse of that installation starts a server on a free port, registers itself, and the client, started in any project directory, reaches the IDE holding that project.
Nothing is typed again when a second, third or tenth IDE is started.

## Changes, by file

Server bundle:

- `McpPreferences`: `KEY_MULTIPLE_INSTANCES` in configuration scope, `KEY_LAST_PORT` in instance scope, `PORT_RANGE` constant, system property overrides.
- `McpServerService.start`: allocation loop over candidates, port read back from the connector, `lastPort` written on success, registry write; `stopQuietly`: registry remove.
- `EndpointFile` or a new `InstanceRegistry`: location under `UserScope`, write, remove, sweep of stale entries, honouring `tokenDirectory` so the tests never write the real registry.
- `McpEndpoint`: unchanged.
- `plugin.xml`, new: contributes `eclipse_list_instances`, the server bundle's first tool contribution; alternatively the tool lives in core with the registry directory passed in.

UI bundle:

- `McpServerJob.reconcile`: in-range check instead of equality.
- `McpPreferencePage`: the switch, the actual port, the registry path, the launcher command.
- `RestartTool.carryTheServerOver`: never copy `lastPort`; its answer says the target will allocate.
- `McpStartup`: extract the launcher if shipped.

Releng:

- `releng/eclipse-mcp` launcher, or the same file under the server bundle's resources if it is shipped.
- `releng/mcp-test-ide.sh`: use the system properties instead of writing `.prefs` by hand, and `--port` can become optional.
- `releng/mcp-script.py`: learn to read the registry when neither `--url` nor `--workspace` is given.

Documentation:

- `README.md`: the configuration scope route, the switch, the launcher, and the "which workspace answered" section rewritten for several servers.
- `AGENTS.md`: the "never falls back" rule gets its exception stated: fallback only under the switch, always reported, never silent.

Tests, headless in `com.vogella.eclipse.mcp.server.tests`:

- two services on one range bind two different ports;
- `lastPort` is retried first and yields when taken;
- registry entry written on start, removed on stop, stale entry swept;
- with the switch off, behaviour is byte for byte what it is now, including the refusal;
- the tests redirect the registry the way `TokenStore` is redirected, and a test fails if that redirect is lost.

The launcher gets a test of its selection rules against a fixture directory, run by plain `python3`, since the forwarding itself is what `mcp-script.py` already exercises.

## Hazards to design around

**The "never fall back" rule.**
It stays the default.
Under the switch the fallback is reported in three places, the registry, the preference page and the Error Log, and `lastPort` makes it rare after the first start.
What the rule was protecting against is a client configured by URL breaking silently; a client configured with the launcher cannot break that way, and one configured by URL is told on the page that the URL may move.

**The plug-in test IDE.**
`eclipse_run_tests` launches a second Eclipse with this feature in it.
With `enabled` in the configuration scope that child will start a server too, allocate a port, and register.
The registry entry carries `application`, so the launcher can skip anything that is not `org.eclipse.ui.ide.workbench`, and the test launch could pass `-Dcom.vogella.eclipse.mcp.enabled=false` explicitly, which is the better fix.

**Two IDEs racing for one port.**
Bind is atomic at the socket, so a race costs one of them a retry, not a shared port.
`lastPort` written after a successful bind cannot describe a port it does not hold.

**`eclipse_restart` into another workspace.**
The new workspace has no `lastPort` and allocates; the registry entry of the old workspace is removed by the dying process's `stopQuietly`.
If the old process is killed before that runs, the sweep on the next start of any instance removes it.

**Windows.**
The registry uses `PrivateFiles`, which already handles the ACL, and the launcher has to find `python3` there; `py -3` is the usual spelling.
`ProcessHandle` liveness works on every platform; the Python side needs a Windows branch for the pid check.

**Token regeneration.**
Unchanged, and now with a wider blast radius: regenerating in one IDE invalidates the clients of every IDE.
The page already says so; the wording should say "every IDE" rather than "every client".

## Open questions

1. Should the launcher ship inside the bundle and be extracted, or stay a file in `releng/` that a user copies?
   Shipping it is what makes the setup a copy-paste from the preference page; it also means Python on the user's machine becomes an implicit requirement of the feature.
2. Is the client side Claude Code, Cursor, both, or something else?
   It decides which configuration snippets the preference page offers and which selection rule matters most.
3. Should ticking *Several IDEs* also write `enabled=true` into the configuration scope, or should the two stay separate switches?
4. Is A3, the ephemeral port, wanted as a selectable mode, or is `port=0` as an undocumented consequence of the same code enough?
5. Are different installations on one machine in scope, or only several workspaces of one installation?
   The registry in user scope covers both; the configuration scoped switch has to be set once per installation.
