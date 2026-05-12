# Inline Legacy Add Probe

## Why this stage exists

This is the highest-risk implementation stage. Once setup shows arbitrary Fiji/plugin commands, selecting one must not silently add a broken filter step or mutate the user's preview image. This stage wires legacy command selection through the existing ImageJ Recorder probe, stores the recorded macro options in the DAG, and only then enables legacy commands in the production setup picker.

## Prerequisites

- `01_command-registry.md` must be completed and renamed with `_COMPLETED`.
- `02_catalog-merge-dedupe.md` must be completed and renamed with `_COMPLETED`.
- `03_setup-picker-legacy-visibility.md` must be completed and renamed with `_COMPLETED`.

## Read first

- `docs/dynamic-filter-command-catalog/00_overview.md`
- `docs/dynamic-filter-command-catalog/03_setup-picker-legacy-visibility_COMPLETED.md`
- `src/main/java/flash/pipeline/ui/config/FilterParameterStage.java` lines 63-64, 135, 262-264, 1198-1213, 1347-1369, and 1569-1572.
- `src/main/java/flash/pipeline/ui/sandbox/FilterBuilderPanel.java` lines 43-50, 218-226, and 668-708.
- `src/main/java/flash/pipeline/ui/sandbox/RecorderParameterProbe.java` lines 16-64 and 161-180.
- `src/main/java/flash/pipeline/image/dag/DagToIjmEmitter.java` lines 68-88.
- `src/main/java/flash/pipeline/image/FilterExecutor.java` lines 431-456.
- `src/test/java/flash/pipeline/ui/sandbox/RecorderParameterProbeTest.java` lines 11-37.
- `src/test/java/flash/pipeline/image/dag/DagToIjmEmitterTest.java` lines 93-126.

## Scope

- Add a `FilterBuilderPanel.appendNode(FilterCatalog.Entry entry, String args)` overload for callers that already captured options.
- Update setup's `FilterParameterStage.applyAddFilter` to branch on `entry.legacy`.
- For fast entries, keep current behavior.
- For legacy entries, duplicate or freshly create a disposable source image, call `RecorderParameterProbe.probe(...)`, and append the node only when probing succeeds.
- Close the disposable probe image through `previewAdapter.close(...)`.
- Keep the current filter unchanged if probing fails, the user cancels, the app is headless, or no preview source is available.
- Flip `AddFilterPopover.show(...)` from fast-only to include legacy entries only after the safe add path exists.

## Out of scope

- Do not rewrite `RecorderParameterProbe` into a full sandbox. Use the existing probe behavior unless a focused test proves a bug in this path.
- Do not promise that every Fiji command is a valid filter. Stage 05 manual validation must confirm failure messaging for unsuitable commands.
- Do not change legacy DAG execution. Existing `FilterExecutor.runLegacyDagSandboxed(...)` owns execution.
- Do not add a command allowlist or blocklist beyond Stage 02's basic skip list.

## Files touched

| path | action | reason |
| --- | --- | --- |
| `src/main/java/flash/pipeline/ui/sandbox/FilterBuilderPanel.java` | MODIFY | Add an append overload that accepts recorded legacy options. |
| `src/main/java/flash/pipeline/ui/config/FilterParameterStage.java` | MODIFY | Probe legacy commands before appending and enable legacy picker entries. |
| `src/test/java/flash/pipeline/ui/sandbox/FilterBuilderPanelTest.java` | MODIFY | Verify appending a legacy command with args emits a legacy IJM/DAG. |
| `src/test/java/flash/pipeline/ui/config/FilterParameterStageTest.java` | MODIFY | Verify legacy add failure leaves current macro unchanged and fast add still works. |

## Implementation sketch

Add the overload in `FilterBuilderPanel` near `appendNode(FilterCatalog.Entry entry)`:

```java
public void appendNode(FilterCatalog.Entry entry, String args) {
    if (entry == null || entry.stub) return;
    SandboxModel.Line line = singleLineOrThrow();
    model.addNode(line, entry, args);
    canvas.rebuild();
    notifyListeners();
}
```

Update `FilterParameterStage.onAddFilterClicked()` after the safe path is implemented:

```java
AddFilterPopover.show(addFilterButton, popoverCatalog(), new AddFilterPopover.Selection() {
    @Override public void onSelected(FilterCatalog.Entry entry) {
        applyAddFilter(entry);
    }
}, true);
```

Change `applyAddFilter`:

```java
private void applyAddFilter(FilterCatalog.Entry entry) {
    if (entry == null || !linear) return;
    ensureHiddenBuilderInSyncWithCurrentMacro();
    try {
        if (entry.legacy) {
            if (!appendLegacyFilter(entry)) return;
        } else {
            hiddenBuilder.appendNode(entry);
        }
    } catch (RuntimeException ex) {
        setError("Could not add filter: " + ex.getMessage());
        return;
    }
    afterStructuralMutation(true);
}
```

Add helper:

```java
private boolean appendLegacyFilter(FilterCatalog.Entry entry) {
    if (GraphicsEnvironment.isHeadless()) {
        setError("Fiji command parameter capture is not available in headless mode.");
        return false;
    }
    if (sourceImage == null) {
        setError("No preview image is available for Fiji's parameter dialog.");
        return false;
    }

    ImagePlus probeSource = null;
    try {
        probeSource = sourceImage.duplicate();
        RecorderParameterProbe.ProbeResult probe =
                RecorderParameterProbe.probe(probeSource, entry.commandName);
        if (probe.userCancelled) {
            if (probe.errorMessage.length() > 0) {
                setError("Command was not added: " + probe.errorMessage);
            } else {
                setStatus("Command was not added.");
            }
            return false;
        }
        hiddenBuilder.appendNode(entry, probe.optionsString);
        return true;
    } catch (RuntimeException ex) {
        setError("Command was not added: " + ex.getMessage());
        return false;
    } finally {
        if (probeSource != null) previewAdapter.close(probeSource);
    }
}
```

If `ImagePlus.duplicate()` is unsuitable for some source types, use `previewAdapter.createSource(activeContext)` instead, but still close the returned image.

Expected legacy IJM output after a successful append:

```ijm
// @ihf-dag v1 executionTier=legacy
// {"version":1,...,"commandName":"Plugin Filter","menuPath":"Fiji commands > Plugin Filter"}
source_id = getImageID();
...
run("Plugin Filter", "radius=5 stack");
```

Test ideas:

- `FilterBuilderPanel.appendNode(entry, "radius=5 stack")` with `Entry.legacy(...)` emits `executionTier=legacy` and `run("Plugin Filter", "radius=5 stack")`.
- In headless `FilterParameterStage`, simulating a legacy add should leave `currentMacro` unchanged and set an error/status rather than appending a blank `run("Plugin Filter")`.
- Existing `simulateAddFilterForTest("Median")` still appends a fast native command.

## Exit gate

1. Fast add behavior remains unchanged.
2. Legacy add never appends a node when probing fails or is unavailable.
3. Successful legacy append stores recorded options and emits a legacy DAG/IJM.
4. The production setup picker passes `includeLegacy=true` only after the above behavior exists.
5. Run:

```powershell
.\mvnw.cmd "-Dtest=FilterBuilderPanelTest,FilterParameterStageTest,RecorderParameterProbeTest,DagToIjmEmitterTest" "-Denforcer.skip=true" test
```

## Known risks

- Risk level: very high. The selected command can be any Fiji/plugin command, not necessarily a filter. It might open dialogs, create windows, fail on the current image type, or record no macro line. The filter must remain unchanged on any failed or cancelled probe.
- Risk level: very high. Do not run the probe on the real `sourceImage`; use a disposable duplicate or fresh preview source. Running on the real preview source could corrupt the setup preview before the user has accepted the filter.
- Risk level: high. Some commands record a different macro command name than the selected menu label. Start by preserving existing `RecorderParameterProbe` behavior; only switch to `probe.commandName` if tests/manual validation show the selected label is not replayable.
- Risk level: high. Legacy execution is global-state heavy. Do not bypass the existing locked legacy executor in `FilterExecutor`.
- Risk level: medium. Probe cleanup may not close every window a plugin opens. Stage 05 must include manual Fiji checks for stray windows after cancelled and failed plugin commands.
