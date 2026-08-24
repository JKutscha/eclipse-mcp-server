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
