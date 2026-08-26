# Report: debugger tools for the Eclipse MCP server

Branch `feat/debug-tools`, five commits, final `mvn clean verify` green.
Nothing was pushed and the `main` worktree was not touched.

## What was implemented

### The bundle

`plugins/com.vogella.eclipse.mcp.debug`, shaped after `com.vogella.eclipse.mcp.p2`:
manifest, `build.properties`, `.project`, `.classpath`, `plugin.xml`, `src/`.
`Bundle-SymbolicName: com.vogella.eclipse.mcp.debug;singleton:=true`, version `0.2.0.qualifier`,
vendor `vogella GmbH`, BREE `JavaSE-25`, lazy activation, activator `McpDebugPlugin`.
Everything lives in `com.vogella.eclipse.mcp.debug.internal`; nothing is exported.
No dependency on any UI bundle, on the MCP SDK or on Jetty.
Registered in `feature.xml`, in the test bundle's `Require-Bundle`, in the AGENTS.md layout
block and in the README bundle table.
The pomless aggregator picked the new directory up without a pom, as claimed; the build verifies that rather than assuming it.

### `eclipse_list_breakpoints`

Read only. Lists Java line and exception breakpoints with an id derived from the marker id,
kind, type name, line, enabled, installed, hit count, condition, suspend policy,
caught/uncaught for exceptions, and the workspace path of the attached resource.
Honours `filter` (case insensitive substring of the type name) and `maxResults`,
reports `total` and `truncated`.

### `eclipse_set_breakpoint`

Creates, updates or removes line and exception breakpoints through `JDIDebugModel`.
The description says CHANGES THE IDE'S BREAKPOINT LIST in capitals.
The id argument addresses a breakpoint directly; otherwise an existing breakpoint with the
same type and line (or the same exception type) is updated rather than duplicated,
and the answer says `created` or `updated`.
Moving an existing line breakpoint to another line is refused with the old position named.
The type resolves to its source file through `IJavaProject.findType` across every open
project, with the workspace root as the fallback for jar-resident types.
Both honesty requirements are in: the answer reports `installed`, and when it is false a
note says what that means, distinguishing "no session running yet" from "a session is
running and it still did not install, so the line almost certainly has no executable code".
Exception breakpoints default to `caught: false, uncaught: true`.

### `eclipse_debug_launch`

STARTS A PROCESS, says so in capitals.
Launches a saved configuration by name (unknown names refused with the list) or builds a
transient Java Application launch from `project` plus `mainType` via
`ILaunchConfigurationType.newInstance(null, name)`, which never reaches the user's saved
configurations. Launching happens in a `Job` in the pattern of `RunTestsTool`;
the answer waits for registration first and reports launch failures with their message.
Returns a `sessionId` and the full session state.
`stopInMain`, program arguments, VM arguments, `autoTerminateAfterSeconds` (default 900)
and `waitForSuspendSeconds` (default 20, maximum 25) are honoured.

### `eclipse_debug_status`

Read only. Lists all sessions this IDE knows, not only ours: per session the configuration
name, `startedByMcp`, `terminated`, `suspended`, and per thread name, suspended state and,
when suspended, the breakpoint that stopped it and the top frame as
`declaringType.method(File.java:123)`. The breakpoint comes live from
`IThread.getBreakpoints()` where JDT reports it, falling back to what the suspend event recorded.
With `waitForSuspendSeconds` it blocks until any session suspends next and reports
`timedOut` when the wait ran out.

### `eclipse_debug_get_frames`

Read only. The stack of one suspended thread (index, declaring type, method with argument
types, line, source file, native flag) and the variables of one frame (name, declared type,
value through `IJavaValue.getValueString()`, `hasChildren`, runtime type when it differs).
`variablePath` descends one level into objects and arrays (`3` or `[3]` for elements) and
returns only that level's children. Values truncate at `maxValueLength` with `valueTruncated`.
An ambiguous thread is refused with the suspended candidates.

### `eclipse_debug_evaluate`

RUNS CODE INSIDE THE DEBUGGED PROGRAM, says so in capitals.
Evaluates against one frame of a suspended thread through
`EvaluationManager.newAstEvaluationEngine` and `IEvaluationEngine.evaluate`, waiting on a
latch with `timeoutSeconds` (default 10, maximum 20) and reporting `timedOut` instead of
blocking a Jetty worker. Compilation problems come back in `problems`; a runtime exception
in the evaluated code comes back in `exception`; success reports `value`, `declaredType`
and `valueTruncated`.

### `eclipse_debug_control`

CHANGES THE STATE OF THE DEBUGGED PROGRAM, terminate kills the process.
Seven actions with the exact prescribed names, each validated before anything moves;
an unknown action is refused with the list of valid ones.
After `resume` and the three steps it registers a suspend signal before acting, then waits
`waitForSuspendSeconds` (default 20 for those actions, 0 for the rest, maximum 25) and
reports the new location in the same answer, with `timedOut` saying plainly that a resume
normally just means the program kept running.

### The session registry

`DebugSessionRegistry`, written after reading `TestRunRegistry`.
Ids are assigned by an `ILaunchListener` registered lazily on first use and filtered to
debug launches, so sessions started by hand in the IDE or by `eclipse_run_tests` get ids too.
Sessions created by `eclipse_debug_launch` are pre-registered under their configuration
name, so no launch event can arrive unassigned.
`startedByMcp` distinguishes ours from theirs.
Terminated sessions stay listed for five minutes with `terminated: true`, capped at fifty.
Auto-termination runs on a daemon scheduler and touches only MCP-started sessions;
bundle stop terminates exactly those and no others.
Suspend events are recorded and signalled on the debug event dispatcher thread and worked
on nowhere else; waits register their signal before acting, which makes them race free.

### `eclipse_run_tests`

Gained a `debug` boolean argument, default false, which launches in `DEBUG_MODE`.
The answer carries `debug: true` and a note that the run appears as a debug session
addressable by its sessionId through the debug tools. No second copy of the launching logic exists.

## Verification

Final `mvn clean verify` from the repository root, exit code 0:

```
[INFO] Reactor Summary for Eclipse MCP Server 0.2.0-SNAPSHOT:
[INFO]
[INFO] Eclipse MCP Server ................................. SUCCESS [  0.059 s]
[INFO] [aggregator] plugins ............................... SUCCESS [  0.002 s]
[INFO] [bundle] Eclipse MCP Core .......................... SUCCESS [ 11.667 s]
[INFO] [bundle] Eclipse MCP Debugger Tools ................ SUCCESS [  0.562 s]
[INFO] [bundle] Eclipse MCP Git Tools ..................... SUCCESS [  0.772 s]
[INFO] [bundle] Eclipse MCP Java Model Tools .............. SUCCESS [  1.064 s]
[INFO] [bundle] Eclipse MCP Provisioning Tools ............ SUCCESS [  0.435 s]
[INFO] [bundle] Eclipse MCP PDE Tools ..................... SUCCESS [  0.482 s]
[INFO] [bundle] Eclipse MCP Server ........................ SUCCESS [  0.402 s]
[INFO] [bundle] Eclipse MCP UI ............................ SUCCESS [  1.083 s]
[INFO] [aggregator] features .............................. SUCCESS [  0.001 s]
[INFO] [feature] Eclipse MCP Server ....................... SUCCESS [  0.257 s]
[INFO] [aggregator] tests ................................. SUCCESS [  0.001 s]
[INFO] [test-bundle] Eclipse MCP Core Tests ............... SUCCESS [03:16 min]
[INFO] [test-bundle] Eclipse MCP Server Tests ............. SUCCESS [ 17.417 s]
[INFO] [aggregator] update-site ........................... SUCCESS [  0.001 s]
[INFO] [updatesite] com.vogella.eclipse.mcp.repository.eclipse-repository SUCCESS [  1.554 s]
[INFO] BUILD SUCCESS
```

Test counts: `com.vogella.eclipse.mcp.core.tests`: **Tests run: 258, Failures: 0, Errors: 0, Skipped: 0**
(244 existing plus 14 new: 8 in `BreakpointToolsTest`, 5 in `DebugSessionToolsTest`, 1 in `DebugLaunchEndToEndTest`).
`com.vogella.eclipse.mcp.server.tests`: **Tests run: 11, Failures: 0, Errors: 0, Skipped: 0**.
No existing test was weakened, changed or deleted.

The end-to-end test ran rather than skipped: it launched a real JVM, hit a real breakpoint,
read `answer = 42` out of a frame, evaluated `answer * 2` to 84 inside the debugged program,
stepped over onto the next statement, resumed into completion and watched the session go
`terminated`, in about six and a half seconds.

## Decisions the brief did not decide

**ClientSessions is deliberately not consulted.**
The four registries that refuse implicit defaults while a second client connects do so
because "the most recent" cannot be attributed. Here the tools refuse whenever more than
one session is live, regardless of how many clients exist, and name the ids; with exactly
one live session there is nothing to misattribute even with ten clients connected.
The refusal covers the trap more directly than counting clients would.

**Update leaves absent arguments alone; create applies defaults.**
The brief fixes defaults for creation but says nothing about updates. Resetting a
breakpoint's condition because the caller sent only `enabled: true` would be surprising,
so an update touches only what was sent. This is stated in the schema descriptions.

**A line move is a refusal, not a recreate.**
Updating a breakpoint named by id to a different line could be implemented as remove plus
create, but that silently reorders operations around conditions and hit counts. The tool
refuses and says to remove and set, which is two calls the caller can see.

**Type resolution iterates `IJavaProject.findType`, not `SearchEngine`.**
The brief allows either. Iterating projects and taking the first source compilation unit
also sidesteps the documented gotcha that unscoped resolution finds build output before
source, since a binary hit is skipped and the workspace root fallback used instead.

**The evaluation detail constant had to change.**
`IAstEvaluationEngine.EVALUATION_DETAIL_WITH_EDITOR/WITHOUT_EDITOR`, cited in the brief,
no longer exist in the target platform's `org.eclipse.jdt.debug` 3.26.0; they were already
gone in 3.23.0 from 2025-06. Verified with `javap` against the cached target platform jars.
The detail parameter now takes `DebugEvent.EVALUATION` (explicit, user visible) versus
`DebugEvent.EVALUATION_IMPLICIT` (watch expressions), so the tool passes
`DebugEvent.EVALUATION`. Similarly, `getValueString()` is inherited from
`org.eclipse.debug.core.model.IValue` rather than declared on `IJavaValue`; the call is the
one the brief meant. Neither went into `docs/platform-bugs.md`, because both are API shape
rather than Eclipse being wrong.

**The e2e test registers a VM before assuming.**
`JavaRuntime.getDefaultVMInstall()` returning null skips the test cleanly, but first the
test tries registering the platform's own JVM (`java.home`) as a standard VM install and
making it default, all public API of `org.eclipse.jdt.launching`. On this machine the
assumption never fires; on a machine without any JVM the skip stays honest.

**Retention and caps.**
Five minutes of retention for terminated sessions and a fifty session cap are choices;
the brief required only "briefly" and "then dropped".

## What is missing

Nothing from the brief. Scope boundaries worth stating:

Method breakpoints and class-prepare breakpoints are not offered; the tools cover the two
kinds the brief specified, and `eclipse_list_breakpoints` would show any other kind as
`kind: "other"` rather than misclassifying it.
Thread filters, instance filters and conditional suspend-on-recurrence strategies for
exception breakpoints are not exposed; the common cases (condition, hit count, suspend
policy, caught/uncaught) are.
Only `stepOver` is covered by the end-to-end test; `stepInto` and `stepReturn` share the
same wait-and-report path and are exercised only at the refusal level.
