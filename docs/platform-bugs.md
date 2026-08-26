# Eclipse platform bugs and API gaps

Defects and gaps in Eclipse itself that this project ran into, so that they are
not rediscovered or quietly worked around forever.
Each entry says what was observed, where it is, and whether anything is filed.

Add an entry whenever a workaround here exists because the platform is wrong,
rather than because we are.

## Open

### SWT: `Control.print` does not paint a `CTabFolder`'s `topRight` control

A print of a `CTabFolder` omits its `topRight` children, which in the workbench
are the view toolbar, the view menu and the minimise and maximise buttons.
Rooting the print at the folder, at its parent and at the window's content
composite all miss them, so no ancestor supplies them.

The tab row is also laid out as though the control were absent: for a stack
whose model reports `ToolbarComposite` at x=369, the printed tab label runs to
about x=460, which cannot happen on screen. So the folder paints itself without
its `topRight` rather than merely skipping a child.

Observed on GTK at zoom 200 through `eclipse_screenshot` with `includeToolbar`.
Nothing filed yet. The consequence for this project is that `includeToolbar`
cannot show a view toolbar; capture the shell and crop to bounds from
`eclipse_get_widget_tree` instead.

### p2: a failed resolution reports only "Operation details"

`ProfileChangeOperation.resolveModal` returns, for every planner failure, a
status whose top level message is the literal string `Operation details`; the
actual conflicts, one per unit, live only in the children of that multi status.
p2 also logs nothing itself, so the failure does not reach the platform log and
`eclipse_get_log_entries` shows nothing.

Observed when a feature installed at `1.0.0.202608261121` was rebuilt as
`1.0.0.202608261142`: every later install and update failed with no usable
diagnosis, and the pin was found by unzipping the installed feature by hand.

Worked around in `ResolutionStatuses`, which flattens the status tree into the
tool answer and logs the whole status once at warning level.
Not filed upstream as of 2026-08-26.

### PDE: `NullPointerException` in `DependencyManager.findRequirementsClosure`

`ui/org.eclipse.pde.core/.../DependencyManager.java:266`

```java
: namespaces.stream().map(wiring::getRequiredWires).flatMap(List::stream)::iterator;
```

`BundleWiring.getRequiredWires(String)` returns `null` when the wiring is no
longer in use, and `flatMap(List::stream)` over that null throws.
Line 254 has the same exposure for `HOST_NAMESPACE`, and the
`namespaces.isEmpty()` branch would fail at the loop instead of in the stream.

Reached from `IProject.getReferencedProjects()` through
`DynamicPluginProjectReferences`, so it fires from the IDE's own
`ProjectReferenceGraph` job and, more seriously, from build order computation.
Observed after a p2 update plus bulk project close and open in a 755 project
workspace.

Not filed upstream as of 2026-08-21; no matching issue in `eclipse-pde/eclipse.pde`.
Worked around in `GetProjectDependenciesTool` by catching and skipping, which
protects our call only.
`DynamicPluginProjectReferences` is registered unconditionally in `plugin.xml`,
so there is no preference to disable it.

### SWT: `Control.print` paints at the monitor's device scale

On a 200% monitor, `print` draws the widget at device resolution whatever the
target image is sized in, so drawing into an image created from the widget's
size in points writes a 2x picture into a 1x canvas and keeps the top left
quarter. Nothing reports it: the image is exactly the size a correct capture
would be.

Correcting it with a `GC` transform of `1/zoom` does not work either. It scales
the paint down into a canvas that is still device sized, so three quarters of
the image is then empty rather than three quarters of the widget missing. Both
states were shipped from here before the right one was found.

What works is `new Image(Device, ImageGcDrawer, int, int)` and reading the
result with `getImageData(zoom)`: the drawer receives a `GC` SWT has already set
up for the target zoom. Used in `ScreenshotTools`.

Not filed. Arguably intended behaviour of a low level API, but the combination
of "no way to ask what scale it will paint at" and "silent clipping" is what
makes it a trap.

### SWT: window capture silently returns a blank image

`bundles/org.eclipse.swt/Eclipse SWT/gtk/.../GC.java`, around line 489

```java
} else if (data.drawable != 0) {
    if (!GTK.GTK4) GDK.gdk_cairo_set_source_window(cairo, data.drawable, 0, 0);
}
```

On GTK4 the branch does nothing, so the caller gets a valid but empty image with
no error at all. Separately, under a compositing window manager the window
contents live in an offscreen pixmap, so reading the X11 root drawable through
XWayland also yields uniform pixels.

The failure is silent in both cases, which makes it the worst shape of bug for a
screenshot tool. `ScreenshotTools` detects it by rejecting a uniform capture and
falls back to `Control.print`.

### SWT: `Display.getBounds()` zoom is not the primary monitor's

[eclipse-platform/eclipse.platform.swt#3530](https://github.com/eclipse-platform/eclipse.platform.swt/issues/3530),
open, targeting 4.41, with PR #3532.

`Display.getBounds()` derives its zoom from `DPIUtil.getDeviceZoom()`, which
reports the zoom of whichever shell last changed zoom rather than the primary
monitor, so full display capture is non-deterministic on Windows with multiple
monitors at different DPI. Not reproducible on Linux; relevant when
`eclipse_screenshot` runs on Windows.

### p2: `ColocatedRepositoryTracker` refreshes the artifact manager too

`ColocatedRepositoryTracker.java:96-97` refreshes both the metadata and the
artifact repository manager. An update check never reads artifact metadata, so
a check through that path costs twice what it needs to.

Not filed. **This entry led this project astray and the conclusion below has
been corrected.** Refreshing metadata alone was adopted here on the strength of
it, and that is wrong for a composite whose release replaces the child rather
than adding one: see the next entry. The waste is real for a plain update check
and the saving is not worth having for an install.

### p2: a composite artifact repository is not refreshed with its metadata

**Observed, twice, self updating this server against its own update site.**
`eclipse_check_for_updates` refreshed only `IMetadataRepositoryManager`, saw the
newly published version and resolved against it. The install then failed in the
download phase:

```
No repository found containing: osgi.bundle,com.vogella.eclipse.mcp.core,0.2.0.202608231851
```

The cached composite ARTIFACT repository still pointed at `releases/<previous>/`,
which the publish had deleted, so no repository could supply the bundles. The
message names neither the cache nor the site, and the resolution succeeding
first makes it read as a broken publish.

Fixed here in `Provisioning.refreshArtifacts` by refreshing both managers. This
is the concrete case behind the entry above: skipping the artifact side is only
a saving for a check that never installs.

### p2: suspected stale child metadata after a composite refresh

Reported by the p2 session, **still unverified**, and distinct from the entry
above, which was the artifact side rather than the metadata side: a parent
composite may keep child instances loaded during its own construction and serve
the previous generation of child metadata.

Not seen here once both managers are refreshed.

## API gaps rather than defects

### JDT: no headless quick fix API

The concrete fixes live in `org.eclipse.jdt.internal.corext.fix`, exported
`x-friends:="org.eclipse.jdt.ui,..."`, so they are a compile error outside JDT UI.
`org.eclipse.jdt.core.manipulation` exports only the framework types
(`ICleanUpFixCore`, `CleanUpContextCore`, `CUCorrectionProposalCore`) and nothing
that maps a marker to proposals.

Consequence: `eclipse_get_quick_fixes` cannot be built headlessly. It would need
`org.eclipse.jdt.ui` and therefore the UI thread, which is wrong for bulk
application.

### JDT: `SearchMatch.getResource()` attributes a binary match to the wrong project

For a match inside a jar it returns the project that owns the classpath entry, so
the path is a bare project name with no file component. Arguably as designed, but
it means a naive caller reports references found in `org.eclipse.jdt.ui.jar` as
source references in whichever project depends on that jar.

Handled in `JavaModelSupport.describeLocation`, which reports an explicit
`origin` and puts the jar in `library`.

### JDT: manipulation layer needs a preference node id set by hand

`JavaManipulation.getPreference` calls `ProjectScope.getNode` with a null node id
when nothing set it, throwing `IllegalArgumentException` rather than returning a
default. Hit headlessly by both `OrganizeImportsTool` and `RenameTool`, the
latter only for field renames, through `GetterSetterUtil`.

Worked around by setting `JavaManipulation.setPreferenceNodeId("org.eclipse.jdt.ui")`.

### Error Log view: no API and no command to clear what it shows

The view keeps the parsed log in memory and does not watch the file, so deleting
the file underneath it leaves it showing entries that are gone. Its own delete
action handles that by calling `LogView.handleClear()` right after
`fInputFile.delete()` (`LogView.doDeleteLog`), but there is no way for anyone
else to reach that: `LogView` lives in `org.eclipse.ui.internal.views.log`,
exported `x-friends:="org.eclipse.pde.ui"`, the clear is an anonymous `Action`
built in `createClearAction` rather than a command, and nothing is contributed
to the command framework. `IViewPart` offers nothing either.

`ErrorLogRefresh` calls `handleClear()` reflectively on the open view, which
works because the class and the method are public, and reports what it came to
rather than assuming it worked. A command id for "clear the Error Log view", or
a `handleClear` on a published interface, would remove the need.

### PDE: the UI test application cannot start in a workspace of platform bundles

A JUnit Plug-in Test with the UI test application, meaning application
`org.eclipse.ui.ide.workbench` rather than the headless one, never reaches its
tests in a workspace that holds the platform's own bundles as projects. The
workbench start fails with three errors, always these and always in this order:

```
Command manager was null in org.eclipse.ui.internal.BindingToModelProcessor
Context manager was null in org.eclipse.ui.internal.BindingToModelProcessor
InjectionException: Unable to process "BindingService.manager": no actual value
  was found for the argument "BindingManager"
```

The last one comes from `Workbench.java:2445`, `ContextInjectionFactory.make` on
`BindingService`. `CommandManager` and `ContextManager` are put into the
application context by the workbench's own startup rather than contributed by a
bundle, which is why nothing about the bundle set moves them.

What has been excluded, each by measurement in `eclipse.platform.releng.aggregator`
with 756 projects:

- Not the launch configuration or the tool that wrote it. It fails identically
  from `eclipse_run_tests` and from the IDE's own Run Configurations dialog.
- Not the bundle set. It fails with 632 bundles and with 133; narrowing removed
  the `org.eclipse.ui.tests` contributions entirely and changed nothing.
- Not missing or unbuilt bundles. `org.eclipse.core.commands`,
  `org.eclipse.e4.core.commands`, `org.eclipse.e4.core.contexts`,
  `org.eclipse.e4.ui.bindings` and `org.eclipse.e4.ui.workbench` are all present,
  and `org.eclipse.ui.workbench` carries 1870 compiled classes including
  `BindingToModelProcessor` and `BindingService`.
- Not the missing pre-launch build. A dialog launch that built the workspace
  first failed the same way.
- Not the JDK. Identical failure on 25.0.3 and on 21.0.8.

The headless path is unaffected: the same bundle passes 17 of 17 under the core
test application, and `org.eclipse.jface.tests.AllTests` passes 1281 with 6
legitimate assumption skips.

Nothing filed yet. The consequence for this project is that `eclipse_run_tests`
with `ui` true cannot run tests in such a workspace, which its own description
now says, and that a run reporting no tests reports the launched platform's
errors so the cause is visible rather than looking like an empty test bundle.

### e4 CSS: a snippet cannot be applied through `IThemeEngine`

Applying an ad-hoc stylesheet needs `resetCurrentTheme()` and `getCSSEngines()`,
and both are on `org.eclipse.e4.ui.css.swt.internal.theme.ThemeEngine` rather
than on `IThemeEngine`, whose package is exported `x-friends` to the workbench
bundles.
PDE's own CSS scratch pad casts to the implementation and carries the comment
`FIXME: expose these new protocols: resetCurrentTheme() and getCSSEngines()`
(`ui/org.eclipse.pde.spy.css/.../CSSScratchPadPart.java`), so the gap is known
inside the platform and unfiled.

`CssStyling` reaches both reflectively and looks the engine up by name through
`IEclipseContext`, which keeps this bundle off the friends list entirely.

### e4 CSS: `CSSEngine.parseStyleSheet` changed its return type

`org.w3c.dom.stylesheets.StyleSheet` in 4.40, `CSSStyleSheetImpl` in 4.41. That
is source compatible and binary incompatible: a call site compiled against the
older interface fails with `NoSuchMethodError` on the newer engine, which is
exactly the situation of a plug-in built against a release target platform and
installed into a newer IDE.

The same change dropped `org.w3c.dom.css.CSSStyleSheet` from the returned type,
so a rule count has to be read as `getCssRules()` or as `getRules()` depending
on which engine is running.

Not filed; the package is `x-friends` and therefore provisional by convention,
though it is what every theme in the wild is styled by. `CssStyling.parse` calls
it reflectively and `CssStyling.rules` handles both shapes.

### e4 CSS: styling a preference block does not say what it wrote

`org.eclipse.e4.ui.css.swt.properties.preference.EclipsePreferencesHandler.overrideProperty`
writes the value only when the key is unset or when
`EclipsePreferencesHelper.isThemeChanged()` answers true, and that flag is false
after any start-up that restored the theme it saved, because then previous and
current theme id are equal. A block applied with no theme change in between
therefore overrides only the `DefaultScope` value while an existing instance
value keeps winning, `applyCSSProperty` still returns true either way, and
nothing in the return value or the engine distinguishes the two outcomes.

Not filed; refusing to clobber a user preference until a real theme change has
happened is arguably intended, but the silence is not.
`CssStyling.stylePreferences` works around it by reading every declared key
back after the call and reporting the ones that kept their value as unchanged,
rather than trusting the return.

The selector syntax has the same shape of problem on the way in:
`ThemeElementDefinitionHelper.escapeId` replaces dots with dashes and there is
no inverse that survives a qualifier which legitimately contains a dash.
A wrongly unescaped qualifier matches no rules at all, so the read-back decides
here too.

### e4 CSS: themes contributed at runtime never reach the engine

`ThemeEngine`'s constructor walks the extension registry once and stores the
result in a private final list. There is no registry field, no listener field
and no `IRegistryEventListener` on the class, so a theme bundle installed into a
running framework contributes to `org.eclipse.e4.ui.css.swt.theme` and
`getThemes()` still does not see it until the next start.

Verified against `org.eclipse.e4.ui.css.swt.theme_0.15.100.v20260422-0926.jar`,
the version the IDE on this machine runs, with `javap`.

Not filed; the engine has always worked this way rather than regressed, but the
gap is invisible: p2 reports the install as successful and the theme is simply
absent until a restart.
`eclipse_register_theme` works around it by registering the stylesheet through
`IThemeEngine.registerTheme(String, String, String)`, which the public interface
declares; the four argument overload with the os version match exists only on
the internal class and is deliberately not used.

Related, and measured at the same time: `setTheme` handed an id that matches
nothing loops the themes, falls through, logs a warning through `ILog` and
leaves the current theme alone. It does not throw and corrupts nothing, but the
warning goes where an MCP caller cannot read it, which is why
`eclipse_set_theme` refuses unregistered ids itself instead of forwarding them.
