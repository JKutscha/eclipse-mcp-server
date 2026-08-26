# Report: reach the workbench command framework from the MCP server

Branch `feat/command-tools`, three commits, not pushed.

## What was implemented

### `eclipse_log_status` (core, commit "Write one entry into the Error Log")

Nested as `LogStateTools.Write` beside Mark and Clear, contributed through the core `plugin.xml`.
Writes one entry through `ILog` with the requested severity, plugin id (defaulting to this bundle's symbolic name via `FrameworkUtil`) and an optional throwable, then reads the log file back through `PlatformLogFile` and reports `verified`, matching message, plugin and severity.
The verification is stronger than the clear tool's substring probe because the brief's own test requires the severity to come back out of the file.
An unknown severity and a missing message are refused before anything is written.

### `eclipse_list_commands` (ui)

`CommandTools.ListCommands`, read only, run on the UI thread.
Filters by case insensitive substring over id, name and category, honours `handledOnly`, `includeParameters` and `maxResults` (1 to 500, default 100), and reports `total` and `truncated`.
Per command: id, name, category, description, handled, enabled and the keybinding from `IBindingService.getBestActiveBindingFormattedFor`.
Commands that throw `NotDefinedException` from `getName()`, which is what a defined-then-undefined command does, are skipped rather than failing the listing.
Output is sorted case insensitively by name then id so truncation keeps a stable page.

### `eclipse_run_workbench_command` (ui)

`CommandTools.Run`.
Resolves by exact id, then exact name, then substring, stopping at the first step that matches anything, following `ViewTools.match`; ambiguity is refused with up to 20 candidates.
Executes via `IHandlerService.executeCommand(ParameterizedCommand.generateCommand(command, parameters), null)` on the UI thread through an `asyncExec` future capped at `timeoutSeconds` (default 10, maximum 25).
`NotHandledException`, `NotEnabledException` and `ExecutionException` are folded into the JSON answer (`handled: false`, `enabled: false`, or the cause's own message) rather than returned as errors; the answer carries executed, id, name, handled, enabled, elapsedMillis and the return value capped at 500 characters.
On timeout it answers `executed: false, timedOut: true` with pointers to `eclipse_list_ui_targets` and `eclipse_dismiss_dialog`; the future is deliberately not cancelled and a completion after the timeout is written to the Error Log instead of being dropped.
`org.eclipse.ui.file.exit` and `org.eclipse.ui.file.restartWorkbench` are refused outright, checked both on the raw argument and on the resolved id, because a label can resolve to Exit; the guard sits before the workbench check so it holds headless.

### `eclipse_manage_window` (ui)

`WindowTools`.
`open` uses `IWorkbench.openWorkbenchWindow(perspectiveId, workspaceRoot)`, resolving an optional perspective through the same match order as `PerspectiveTools` and falling back to the registry's default perspective; `close` selects by exact title then substring, or the active window, and refuses an ambiguous title with candidates.
Closing the last window is refused unconditionally with an explanation of what would have happened.
Both actions answer with every window afterwards: title, whether active, bounds.
The wait is capped at 15 seconds and running out answers `timedOut` naming the save prompt and `eclipse_dismiss_dialog`.

Supporting change: `UiThread.timed`, the variant whose timeout is part of the answer rather than an error, used by the listing and window tools; `Run` manages its own future because its late-completion logging has to be attached to that future alone.

## Verification

Final `mvn clean verify` from the repository root:

```
[INFO] Reactor Summary for Eclipse MCP Server 0.2.0-SNAPSHOT:
[INFO]
[INFO] Eclipse MCP Server ................................. SUCCESS [  0.085 s]
[INFO] [aggregator] plugins ............................... SUCCESS [  0.002 s]
[INFO] [bundle] Eclipse MCP Core .......................... SUCCESS [ 15.327 s]
[INFO] [bundle] Eclipse MCP Git Tools ..................... SUCCESS [  1.351 s]
[INFO] [bundle] Eclipse MCP Java Model Tools .............. SUCCESS [  1.784 s]
[INFO] [bundle] Eclipse MCP Provisioning Tools ............ SUCCESS [  0.609 s]
[INFO] [bundle] Eclipse MCP PDE Tools ..................... SUCCESS [  0.722 s]
[INFO] [bundle] Eclipse MCP Server ........................ SUCCESS [  0.607 s]
[INFO] [bundle] Eclipse MCP UI ............................ SUCCESS [  1.575 s]
[INFO] [aggregator] features .............................. SUCCESS [  0.001 s]
[INFO] [feature] Eclipse MCP Server ....................... SUCCESS [  0.331 s]
[INFO] [aggregator] tests ................................. SUCCESS [  0.001 s]
[INFO] [test-bundle] Eclipse MCP Core Tests ............... SUCCESS [03:36 min]
[INFO] [test-bundle] Eclipse MCP Server Tests ............. SUCCESS [ 29.507 s]
[INFO] [aggregator] update-site ........................... SUCCESS [  0.006 s]
[INFO] [updatesite] com.vogella.eclipse.mcp.repository.eclipse-repository SUCCESS [  6.499 s]
[INFO] ------------------------------------------------------------------------
[INFO] BUILD SUCCESS
```

Test counts: `com.vogella.eclipse.mcp.core.tests` 255 tests, 0 failures, 0 errors, 0 skipped; `com.vogella.eclipse.mcp.server.tests` 11 tests, 0 failures, 0 errors, 0 skipped.
New in this change: `LogStatusToolTest` (5 tests), `CommandToolsTest` (6 tests).
No existing test was weakened or deleted; the only edit to existing tests is one new case in `McpServerServiceTest.arguments`, giving the smoke call of `eclipse_log_status` its required argument the way every other tool already gets one.

## Decisions this brief did not make

- **Where shared command helpers live.** Name, category, parameter and match helpers sit on the outer `CommandTools` class so both nested tools use one copy; `UiThread.timed` became a general variant rather than tool-local code, while `Run` keeps its own future for late-completion logging.
- **Verification depth of the log write.** The clear tool verifies with a substring probe; here the readback matches message, plugin id and severity through `PlatformLogFile`, since the brief's test asserts the severity arrives.
- **Refusal re-check after resolution.** A label such as "Exit" resolves to `org.eclipse.ui.file.exit`, so the refusal runs twice: once on the raw argument before any workbench access, once on the resolved id inside the UI-thread work. The first is what makes the headless test meaningful, the second is what makes the refusal actually unconditional.
- **Not-found and ambiguous commands are JSON answers, not errors.** Missing required arguments and the two refused ids remain `McpToolResult.error`, but resolution outcomes follow the brief's "framework failures are answers" principle with `executed: false` plus reason and candidates.
- **Fixed cap for window operations.** The brief prescribed no `timeoutSeconds` there, so both actions share a 15 second constant under the 30 second call timeout, reported as `timedOut` when spent.
- **Smoke-test coverage stays minimal.** Only `eclipse_log_status` got smoke arguments because the server test bundle registers only core, server and jdt tools; the three UI tools are absent from that run by design, which AGENTS.md already documents.
- **Default plugin id computed, not hard coded**, from `FrameworkUtil.getBundle(LogStateTools.class).getSymbolicName()`.

## Manual execution

None against a live IDE.
This machine has the developer's running Eclipse on port 8642 serving a released build, and exercising these tools against it would write into their log and open windows in their session, which is exactly what the descriptions tell clients not to do casually.
Everything was verified through the two headless suites, including the full round trip of `eclipse_log_status`.

One transient failure worth recording: an intermediate run failed with `Failed to bind to /127.0.0.1:18642`, a leftover JVM from the previous aborted run still holding the test port.
A clean rerun passed; nothing in the code was involved.

## What is missing

Nothing functional from the brief.
The honest caveat: the paths that need a real workbench, meaning enablement values and keybindings in the listing, every execute outcome including the three framework failures and the dialog timeout, and both window actions, were never exercised against a running IDE.
They rest on the platform API contracts, on `javap` checks against the target platform's jars where signatures surprised me (`IParameter.getName` throws nothing, `IHandlerService.executeCommand` declares no `ParameterValuesException`, `IBindingService` lives in `org.eclipse.ui.keys`), and on the patterns this repository has already proven in a real IDE.
No platform defect turned up that would earn an entry in `docs/platform-bugs.md`.

## Follow-up: say whether the handler actually ran (commit on top of the verified 82722a3)

Source was the usage-data analysis: the timeout answer could not tell "the command executed" from "it never got that far", which Eclipse's own `CommandUsageMonitor` distinguishes through `postExecuteSuccess`, `postExecuteFailure` and `notHandled`.

`eclipse_run_workbench_command` now registers an `ExecutionRecorder`, an `IExecutionListener`, on the resolved `Command` for the duration of the execute call and removes it in a `finally`.
Every answer, timeout included, carries `handlerFinished` (true once success or failure fired) and the `outcome` verb (`success`, `failure`, `notHandled`, or null when nothing reached a verdict); `timedOut` is unchanged, so the two read side by side.
A dialog still holding the handler answers `timedOut: true, handlerFinished: false`; a slow handler whose verdict already came back answers `handlerFinished: true`.
Because the listener fires before the corresponding exception propagates out of `executeCommand`, all three non-timeout outcomes report their verdict too.
The recorder is created outside the `asyncExec` so the Jetty thread can read it when building the timeout answer, callbacks land on the UI thread into a volatile field.

Decisions:
- **Listener goes on the `Command`, not on `ICommandService`.** The command is resolved anyway; command-level registration needs no id filtering and hears exactly this run.
- **The recorder is its own class** (`com.vogella.eclipse.mcp.ui.internal.ExecutionRecorder`) rather than a nested private one, so the verb mapping is testable.
- **`preExecute` is heard but ignored**: dispatched is not a verdict, and the answer reports only what the brief named.
- **Not-enabled reports no verdict.** The plain `IExecutionListener` has no enabled callback; if the platform routes not-enabled through `notHandled` it shows as such, otherwise `outcome` stays null beside `enabled: false`, both honest.
- **Headless testability needed one export.** The mapping lives where the change lives, so `com.vogella.eclipse.mcp.ui.internal` is now exported `x-friends:="com.vogella.eclipse.mcp.core.tests"`, mirroring what core does for its own internals; `ExecutionRecorderTest` covers the four states the answer can report.

Verification after the follow-up, from the repository root: `mvn clean verify`, BUILD SUCCESS in 03:28 min, same reactor summary as above with every module SUCCESS.
Core tests now 259 run, 0 failures, 0 errors (the four new `ExecutionRecorderTest` tests included), server tests still 11, 0 failures.
No existing test changed.

