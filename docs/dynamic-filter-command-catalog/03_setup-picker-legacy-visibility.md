# Setup Picker Legacy Visibility

## Why this stage exists

After Stage 02, the catalog can contain legacy Fiji/plugin commands, but setup's inline picker currently removes them before display. This stage prepares the picker to render and search legacy commands with clear labels. Because adding arbitrary legacy commands is risky until Recorder probing is wired in, production setup should keep legacy visibility gated until Stage 04.

## Prerequisites

- `01_command-registry.md` must be completed and renamed with `_COMPLETED`.
- `02_catalog-merge-dedupe.md` must be completed and renamed with `_COMPLETED`.

## Read first

- `docs/dynamic-filter-command-catalog/00_overview.md`
- `docs/dynamic-filter-command-catalog/02_catalog-merge-dedupe_COMPLETED.md`
- `src/main/java/flash/pipeline/ui/config/AddFilterPopover.java` lines 46-145.
- `src/main/java/flash/pipeline/ui/config/FilterParameterStage.java` lines 1198-1213 and 1419-1423.
- `src/main/java/flash/pipeline/ui/sandbox/FilterCatalog.java` lines 91-121 and 295-326.

## Scope

- Refactor `AddFilterPopover` so it can build its picker list either with fast entries only or with fast plus legacy entries.
- Keep the current public production behavior fast-only until Stage 04 flips the call site.
- Update popover rendering to show `entry.badge()` and source path/category so users can distinguish `[fast]` from `[legacy]`.
- Ensure search matches label, category, menu path, and badge.
- Add a testable helper for picker-entry filtering without requiring a visible Swing popup.

## Out of scope

- Do not enable legacy commands in the production setup picker yet. Stage 04 owns the safe flip after add/probe behavior exists.
- Do not change how legacy commands are added to the DAG. Stage 04 owns `FilterParameterStage.applyAddFilter` and `FilterBuilderPanel`.
- Do not add grouped UI or major layout redesign; this is a minimal popover refactor.
- Do not add command safety filtering here. Stage 04 handles add-time failures.

## Files touched

| path | action | reason |
| --- | --- | --- |
| `src/main/java/flash/pipeline/ui/config/AddFilterPopover.java` | MODIFY | Add legacy-capable picker list and clearer rendering while preserving fast-only production behavior. |
| `src/test/java/flash/pipeline/ui/config/AddFilterPopoverTest.java` | NEW | Cover fast-only versus include-legacy entry filtering and search metadata. |

## Implementation sketch

Keep the existing public method, but route it through an overload:

```java
public static void show(JComponent anchor, FilterCatalog catalog, Selection callback) {
    show(anchor, catalog, callback, false);
}

public static void show(JComponent anchor, FilterCatalog catalog,
                        Selection callback, boolean includeLegacy) {
    if (anchor == null || catalog == null || callback == null) return;
    if (GraphicsEnvironment.isHeadless()) return;

    final List<FilterCatalog.Entry> all = pickerEntries(catalog.getAllEntries(), includeLegacy);
    ...
}
```

Add a package-private helper for tests:

```java
static List<FilterCatalog.Entry> pickerEntries(List<FilterCatalog.Entry> in,
                                               boolean includeLegacy) {
    List<FilterCatalog.Entry> out = new ArrayList<FilterCatalog.Entry>();
    if (in == null) return out;
    for (int i = 0; i < in.size(); i++) {
        FilterCatalog.Entry e = in.get(i);
        if (e == null || e.stub) continue;
        if (!includeLegacy && e.legacy) continue;
        out.add(e);
    }
    return out;
}
```

Replace `filterFastEntries(...)` with `pickerEntries(..., includeLegacy)`.

Update matching:

```java
private static boolean matches(FilterCatalog.Entry e, String q) {
    if (e.label.toLowerCase(Locale.ROOT).contains(q)) return true;
    if (e.category.toLowerCase(Locale.ROOT).contains(q)) return true;
    if (e.menuPath.toLowerCase(Locale.ROOT).contains(q)) return true;
    if (e.badge().toLowerCase(Locale.ROOT).contains(q)) return true;
    return false;
}
```

Update renderer text:

```java
label.setText(e.label + " " + e.badge() + "   ("
        + (e.menuPath.length() > 0 ? e.menuPath : e.category) + ")");
```

Test ideas:

```java
@Test
public void pickerEntriesAreFastOnlyByDefault() {
    List<FilterCatalog.Entry> entries = Arrays.asList(
            FilterCatalog.Entry.fast("Smoothing", "Median", OpType.MEDIAN, "radius=2"),
            FilterCatalog.Entry.legacy("Fiji commands", "Plugin Filter",
                    "Fiji commands > Plugin Filter"));

    List<FilterCatalog.Entry> visible = AddFilterPopover.pickerEntries(entries, false);

    assertEquals(1, visible.size());
    assertEquals("Median", visible.get(0).label);
}

@Test
public void pickerEntriesCanIncludeLegacy() {
    ...
    List<FilterCatalog.Entry> visible = AddFilterPopover.pickerEntries(entries, true);
    assertEquals(2, visible.size());
}
```

## Exit gate

1. Existing setup picker behavior remains fast-only through the current `show(anchor, catalog, callback)` call.
2. Tests prove the new helper can include legacy entries when requested.
3. Renderer includes `[fast]` or `[legacy]` so risky plugin commands are visible as legacy.
4. Run:

```powershell
.\mvnw.cmd "-Dtest=AddFilterPopoverTest,FilterCatalogTest,FilterParameterStageTest" "-Denforcer.skip=true" test
```

## Known risks

- Risk level: high if production is flipped too early. Do not pass `includeLegacy=true` from `FilterParameterStage` in this stage, because legacy commands would be addable before safe Recorder probing is implemented.
- Risk level: medium. The popover is compact; long plugin command names or generic paths may render poorly. Keep text concise but include enough source information to prevent users mistaking legacy commands for fast/native filters.
- Risk level: medium. Search by `[legacy]` is useful but can expose many commands at once. That is acceptable because Stage 04 must still handle failures safely.
