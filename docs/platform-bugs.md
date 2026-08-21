# Eclipse platform bugs and API gaps

Defects and gaps in Eclipse itself that this project ran into, so that they are
not rediscovered or quietly worked around forever.
Each entry says what was observed, where it is, and whether anything is filed.

Add an entry whenever a workaround here exists because the platform is wrong,
rather than because we are.

## Open

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
every check through that path costs twice what it needs to.

Not filed. Avoided here by calling `IMetadataRepositoryManager.refreshRepository`
directly and never using `RepositoryTracker`.

### p2: suspected stale child metadata after a composite refresh

Reported by the p2 session, **unverified**: a parent composite keeps stale child
instances loaded during its own construction, so a refreshed composite may serve
the previous generation of child metadata.

Directly relevant here, because the update site is a composite whose single child
location changes on every release. The natural test is to check twice after a
publish and see whether the new unit appears only on the second refresh.

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
