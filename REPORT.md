# Report: install a single bundle jar into the running IDE

Branch `feat/install-bundle`, two commits, not pushed.
`mvn clean verify` from the repository root reports `BUILD SUCCESS`.

## What was implemented

### `eclipse_install_bundle` (`InstallBundleTool`, core)

Contributed through the core `plugin.xml`.
Reads `Bundle-SymbolicName` and `Bundle-Version` out of the jar with `java.util.jar.JarFile`, strips directives from the symbolic name, and refuses a jar without a symbolic name by name, before any framework call.
Looks up installed bundles by symbolic name, targets the highest versioned one when several exist, and reports both versions.

For `hot`, it computes the dependency closure of the affected bundle with `FrameworkWiring.getDependencyClosure` before anything changes, refuses when that closure or the jar's own symbolic name contains a bundle starting with `com.vogella.eclipse.mcp`, and otherwise installs through `BundleContext.installBundle("file:" + path)` or replaces content with `Bundle.update(InputStream)`.
Identical version over identical content is refused as the duplicate the framework would reject anyway; identical version over different content updates and says explicitly that the version did not change.
The refresh waits on a `FrameworkListener` countdown latch bounded through `CallBudget`, then starts the bundle unless it is a fragment or `start` is false, reporting fragments rather than throwing at them.
Unresolved bundles are reported with their state name, a `resolved` flag checked against both state and `BundleWiring`, and the resolver's own text extracted from a failed `start()` where one was attempted.

For `dropins`, it resolves `Platform.getInstallLocation()` to `<installation>/dropins`, creates the directory when missing, checks writability up front and refuses with a reason instead of failing mid-copy, and answers that this variant survives a restart and needs one, picked up by p2's reconciler.

Every answer carries `symbolicName`, `previousVersion` (null on a fresh install), `version`, the outcome verb, post-operation state, and the `refreshed` list capped by `maxResults` with `total` and `truncated`.
Every successful hot answer carries the p2 divergence note in its own payload, and every update answer carries the note that already loaded classes keep running until closed and reopened.

With `allowSelf: true` the install or update happens immediately inside the call, the refresh does not: the answer names what is scheduled, and a `Job` two seconds later performs the refresh and the start, logging its outcome to the Error Log because no caller will be connected to receive it.

### Tests

`InstallBundleToolTest` in `com.vogella.eclipse.mcp.core.tests`, six tests against the real framework the suite runs in:

* builds a minimal bundle jar into `@TempDir` at test time, installs it hot, asserts `ACTIVE` and `resolved`, then updates from a second jar with a higher version and asserts exactly one copy remains, at the new version;
* cleans up by uninstalling directly through the OSGi API before and after each test, so a leftover from an earlier run cannot poison the next one;
* a jar without `Bundle-SymbolicName`, a missing path and a non-jar file are each refused cleanly;
* a jar claiming `com.vogella.eclipse.mcp.core` is refused with `dryRun` left at its default, naming the bundle and `allowSelf`, leaving the real core bundle untouched;
* a dry run changes nothing and predicts `installed`;
* a `dropins` dry run plans a copy, writes none, and does not create the directory.

One existing test grew a case, following the file's own convention: `McpServerServiceTest.arguments` now hands the smoke call of `eclipse_install_bundle` a throwaway temp-dir jar with `dryRun` true, because that smoke test calls every registered tool and needs a valid answer from this one too.

## Verification

Final `mvn clean verify` output, verbatim reactor summary:

```
[INFO] Reactor Summary for Eclipse MCP Server 0.2.0-SNAPSHOT:
[INFO]
[INFO] Eclipse MCP Server ................................. SUCCESS [  0.033 s]
[INFO] [aggregator] plugins ............................... SUCCESS [  0.001 s]
[INFO] [bundle] Eclipse MCP Core .......................... SUCCESS [  9.353 s]
[INFO] [bundle] Eclipse MCP Debugger Tools ................ SUCCESS [  0.376 s]
[INFO] [bundle] Eclipse MCP Git Tools ..................... SUCCESS [  0.469 s]
[INFO] [bundle] Eclipse MCP Java Model Tools .............. SUCCESS [  0.768 s]
[INFO] [bundle] Eclipse MCP Provisioning Tools ............ SUCCESS [  0.345 s]
[INFO] [bundle] Eclipse MCP PDE Tools ..................... SUCCESS [  0.466 s]
[INFO] [bundle] Eclipse MCP Server ........................ SUCCESS [  0.325 s]
[INFO] [bundle] Eclipse MCP UI ............................ SUCCESS [  0.787 s]
[INFO] [aggregator] features .............................. SUCCESS [  0.002 s]
[INFO] [feature] Eclipse MCP Server ....................... SUCCESS [  0.189 s]
[INFO] [aggregator] tests ................................. SUCCESS [  0.001 s]
[INFO] [test-bundle] Eclipse MCP Core Tests ............... SUCCESS [02:42 min]
[INFO] [test-bundle] Eclipse MCP Server Tests ............. SUCCESS [ 12.667 s]
[INFO] [aggregator] update-site ........................... SUCCESS [  0.001 s]
[INFO] [updatesite] com.vogella.eclipse.mcp.repository.eclipse-repository SUCCESS [  1.281 s]
[INFO] ------------------------------------------------------------------------
[INFO] BUILD SUCCESS
```

Test counts: `com.vogella.eclipse.mcp.core.tests` 279 tests, 0 failures, 0 errors, 0 skipped; `com.vogella.eclipse.mcp.server.tests` 11 tests, 0 failures, 0 errors, 0 skipped.
The OSGi install test really ran: `InstallBundleToolTest` reports 6 tests, 0 skipped, and its central assertions read `Bundle.ACTIVE` off the live framework and require exactly one copy of the test bundle after the update, which cannot pass unless a real install and a real update happened in the surefire Equinox.
No existing test was weakened or deleted.
No `Import-Package` addition was needed: the build did not ask for one, matching the AGENTS.md expectation.

Two intermediate runs surfaced failures worth recording, both fixed rather than worked around: a multi-catch of `IllegalArgumentException | RuntimeException` rejected by the compiler, and four failures from `FrameworkUtil.getBundle(...).getBundleContext()` returning null under the headless harness, where the core bundle loads but is never started.

## Decisions this brief did not make

* **How to detect "the file has not changed".** There is no public API to hash an installed bundle's stored content, so the tool resolves the installed bundle's location to a local file, treats the same path as unchanged, and otherwise compares SHA-256 digests. A bundle whose old content cannot be addressed this way is treated as changed, which errs towards updating rather than refusing.
* **What `refreshed` means.** `refreshBundles` returns nothing, so the list reported is the closure handed to it, computed fresh from the affected bundle. That is the definition the brief works with, not an observation of which bundles actually stopped and started.
* **Where the resolver error comes from.** Equinox's resolution diagnostics are internal API, so the only portable source is the message of the `BundleException` a failed `start()` carries. It is captured verbatim when start was requested, and absent otherwise, with `state` and `resolved` still telling the story.
* **The self guard fires on dry runs.** The refusal describes a refresh that would kill the server mid-answer, which is worth knowing before committing to anything, and it is what makes the guard testable without touching a thing, exactly as the brief's test asks.
* **Starting our own bundle for its context.** Under surefire the core bundle is loaded but merely resolved, so `getBundleContext()` is null; the tool starts itself with `START_TRANSIENT` before giving up. This is the same class of problem AGENTS.md records for the PDE bundle, solved the same way rather than by changing activation policy.
* **Dropins dry run reports rather than refuses.** An unwritable location is a hard error on a real copy but a field (`writable`) in a plan, so a client can learn what would happen without needing permission to do it.
* **Smoke arguments use a generated jar.** The smoke call needed arguments that succeed headlessly; a throwaway manifest-only jar with an explicit dry run exercises manifest parsing and the planning half without installing anything.

## Manual execution

None.
The developer's IDE on port 8642 was not contacted, per the brief.
Everything above was verified through the two headless suites.

## What is missing

Nothing functional from the brief.
The honest caveats: the `allowSelf` deferred refresh was never executed anywhere, since exercising it would have stopped the very framework the tests run in; the `dropins` real copy was never executed, since it would write into the test IDE's own installation; and the same-version-different-content patch path is guarded by code but reached by no test, whose jars always differ in version.
Those three rest on the OSGi API contracts and on the pattern `eclipse_restart` has already proven in a real IDE.
No defect or API gap in Eclipse itself turned up, so `docs/platform-bugs.md` gained no entry.
