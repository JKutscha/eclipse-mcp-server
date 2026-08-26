# Task: restart without the splash screen

Add a `splash` argument to `eclipse_restart` in `plugins/com.vogella.eclipse.mcp.ui`.

Read `AGENTS.md` first and follow every rule in it.
It is the contract for this repository and it overrides any habit you have from other projects.

You are working in a git worktree on the branch `feat/restart-nosplash`.
Commit here. Do **not** push, do **not** open a pull request, and do **not** touch any other worktree of this repository. Other agents are working in some of them at the same time.

**Do not touch the running Eclipse IDE.** There is one on this machine, at `/home/vogella/workspace/all`. Do not call it and above all do not restart it. Your verification is `mvn clean verify`.

## What is wanted

`eclipse_restart` gains `splash`, default `true`, which keeps exactly today's behaviour. With `splash: false` the IDE comes back without the splash screen.

## What you are working with, measured

The IDE on this machine was launched by the native launcher, and its Java command line contains, in this order:

```
-os linux -ws gtk -arch x86_64 -showsplash -launcher /home/vogella/dev/eclipse-.../eclipse -name Eclipse
--launcher.library ... -startup ... --launcher.appendVmargs -exitdata ae6803a -data file:/home/vogella/workspace/all/ -vm /usr/bin/java -vmargs ...
```

Note that here `-showsplash` carries **no value**: the next token is `-launcher`. Other installations write `-showsplash <something>`, so a filter has to handle both, dropping `-showsplash` and dropping the token after it only when that token does not itself start with a dash.

`RestartTool` today ends at `PlatformUI.getWorkbench().restart(true)`. The `true` form is deliberate and there is a comment saying why: the no argument form relaunches without `-data`.

## What to find out before you write anything

Do not guess the mechanism. Read the sources:

- `Workbench.restart(boolean)` and whatever it uses to build the relaunch command line, in the `org.eclipse.ui.workbench` source jar.
- `IDEApplication` in `org.eclipse.ui.ide`, which is what maps the workbench's return code onto the launcher's relaunch protocol and sets `eclipse.exitdata`.

Source jars are in the local Maven repository under `~/.m2/repository/p2/osgi/bundle/`, and `unzip -p <jar> <path/to/Class.java>` reads one without extracting anything. `javap` against the binary jar answers signature questions.

What you are looking for is the exact point at which the command line for the next start is assembled from `eclipse.vm`, `eclipse.vmargs` and `eclipse.commands`, and how it reaches the launcher. That is the only place where a splash argument can be removed.

## What to build

- `splash` absent or `true`: **the existing code path, untouched**. Do not restructure it, do not route it through the new code. `eclipse_restart` is how an IDE gets recovered, including after a half applied update, and it must not become less reliable because of an option nobody asked for in that moment.
- `splash: false`: assemble the relaunch command line yourself, the same way the platform does, with the splash arguments filtered out, publish it the way the platform publishes it, and trigger the relaunch. Closing the workbench with a specific return code is internal API; reach it reflectively, the way `CssStyling` reaches the theme engine, rather than putting this bundle on somebody's friends list.
- **If the command line cannot be assembled**, because a property is missing or the reflective call is not there in this platform version, do not fail the call and do not refuse. Restart normally and answer `splashSuppressed: false` with the reason. A restart that happens with a splash is a far better outcome than no restart at all.
- The answer says what actually happened: `splashSuppressed` true or false, and when true, which arguments were removed.

## Be honest about what is unproven

The splash is painted by the native launcher before the JVM exists. Whether the launcher honours a relaunch command line that no longer carries `-showsplash`, rather than deciding from its own arguments, is **not established** and this repository has no way to test it. Reason about it from the launcher's protocol as far as the sources let you, then say plainly in `REPORT.md` what you could establish and what remains an expectation.

The tool description must carry the same honesty: say that suppression depends on the launcher honouring the relaunch command line, and that `splashSuppressed` reports what the tool removed rather than what the launcher then did.

Underclaiming here is cheap. A tool that says it suppressed the splash and did not is the failure mode this repository keeps writing rules against.

## Rules that will break this if you miss them

- **Threading.** The existing tool answers first and restarts a couple of seconds later, through a `timerExec`, because the server dies with the IDE and an answer sent afterwards never arrives. Keep that shape exactly. Whatever you add happens inside that delayed runnable or before the answer, never in a way that could stop the answer going out.
- Never call `Display.syncExec` from a tool call.
- Reflection failures are caught and reported, never thrown out of a tool call.
- **A tool that changes something says so in its own description.** This one already does; keep it accurate.

## Tests

Add tests to `tests/com.vogella.eclipse.mcp.core.tests`, in the style of the tests already there. The suite is headless, so nothing here can restart anything, and the restart itself must not be exercised.

What is testable, and what the tests must cover:

- The argument filter is pure string handling. Extract it as a static method that takes the command line as a list and returns the filtered list, and test it hard: `-showsplash` with no value followed by another option, `-showsplash` with a value, `-showsplash` as the last token, a command line with no splash argument at all, and one where a *value* elsewhere happens to be the string `-showsplash`.
- `eclipse_restart` with `splash: false` and no workbench refuses cleanly, the way its existing headless test does.
- Never weaken or delete an existing test to make something pass. The existing restart tests are the safety net for the path you must not break.

## Documentation

- `README.md`: the `eclipse_restart` section gains the argument and one short paragraph on what is and is not guaranteed.
- If the platform turns out to be at fault for something here, `docs/platform-bugs.md` takes the entry, in the format used there. Only if it is Eclipse that is wrong.

## House style

- Never use em dashes anywhere, including code comments and commit messages.
- Javadoc is one or two sentences. Inline comments only for a non-obvious why.
- Markdown is one sentence per line.
- Commit messages: an imperative subject line in the voice of `git log` here, a blank line, then what and why in prose. Never add a `Co-Authored-By` trailer.
- All user visible strings carry `//$NON-NLS-1$`.

## Check your own work

From the repository root:

```bash
mvn clean verify
```

Your work is not finished until it reports `BUILD SUCCESS`.

If the server tests fail with `Failed to bind to /127.0.0.1:18642`, that is a port collision with another build on this machine and not your code. Rerun rather than changing anything.

Never edit a test, a version floor or the target platform to make a failure go away.

## Report

Write `REPORT.md` in this directory with what you implemented, what the platform sources actually said about the relaunch protocol, what remains an expectation rather than a finding, the exact reactor summary and test counts, and what you decided that this brief did not decide.
