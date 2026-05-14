# Step 13 — Tests, integration smoke, runtime status bar, deploy

## Goal

Final polish: a real-image integration test per engine, simple status-bar refinements, deployment check. End-of-step: the feature is shippable and the local Fiji install has it.

## Pre-conditions

- Steps 01–12 complete.

## Deliverables

### Integration tests with real images

Under `src/test/java/flash/pipeline/ui/variations/integration/`:

- `ClassicalSweepIntegrationTest.java` — load a small real test image from `src/test/resources/`, run a 9-cell threshold × min-size sweep, assert all 9 results have label images and non-zero object counts, total runtime < 5s.
- `StarDistFastNmsIntegrationTest.java` — only if step 05 parity is enabled. Same idea, 6-cell (probThresh × nmsThresh) sweep, assert fast-path was used (check strategy class) and total runtime < 2× single-inference time. Skip if no GPU available or fast path is disabled.
- `CellposeOneShotIntegrationTest.java` — 3-cell sweep via fallback path. Skip if cellpose Python module unavailable.
- `CellposePersistentIntegrationTest.java` — 3-cell sweep via persistent helper. Skip if cellpose Python module unavailable. Verify <50% overhead per cell after the first one.

Mark these `@Tag("integration")` or `@Ignore` with a doc comment explaining how to run them manually. They're slow; CI shouldn't block on them.

### Runtime status bar

In `VariationsDialog`, the status bar at the bottom shows:
- Before start: feasibility summary ("12 cells, crop 256x256x30").
- During run: "4/12 complete". If the strategy can cheaply report active tasks, append "2 running"; otherwise skip it.
- After completion: "12/12 complete in 2m 45s. Click a tile to accept, shift-click to compare, or Cancel to keep current settings."
- On error in any cell: append " - 1 cell failed (hover for details)".

Do not add `EtaEstimator.java` in v1. A rough ETA is often wrong for mixed cache hits, StarDist, and Cellpose; elapsed time plus completion count is enough.

### Strategy choice display

Beneath the status bar, in italic grey: "Using StarDist fast path (1 inference + parallel NMS for 6 cells)" or "Using Cellpose persistent helper" or "Using one-shot fallback".

### Failure surface

When a cell's `VariationResult.error` is non-null:
- Tile shows a red ⚠ badge instead of the count.
- Tooltip shows the error message and (for Cellpose) the captured stderr.
- The tile is unclickable for accept (clicking it does nothing). Shift-click is still allowed for compare-with-success-cells.

### Context note

There is no `CLAUDE.md` in the repo root. Add a one-paragraph entry to `CLAUDE_CONTEXT.md` (and mirror it in `CLAUDE_CONTEXT_CONDENSED.md` if that file is still maintained) under "Core Plugin Files":

```
- `src/main/java/flash/pipeline/ui/variations/VariationsDialog.java`
  - Parameter Variations dialog: user picks parameters + value lists, runs a sweep, clicks a tile to accept and write the chosen combo back to the parent stage. Reachable from Classical, StarDist, and Cellpose parameter stages.
```

And under the configuration discussion, note that `.bin/variations_cache/` and `.bin/variations_state.json` are managed by the dialog and safe to delete (everything regenerates).

### Deploy

Run the deploy commands described in `CLAUDE_CONTEXT.md`. Verify the JAR lands in both Fiji `plugins/` AND the shared lab folder. Launch Fiji. Verify the Parameter Variations button works for at least one channel on a real dataset.

Do not push to public GitHub in this step. Public release is a separate concern.

## Acceptance

- `.\mvnw.cmd clean package -Denforcer.skip=true` passes with no new warnings on Windows PowerShell (or `./mvnw clean package -Denforcer.skip=true` in Git Bash).
- `.\mvnw.cmd test` passes (integration tests excluded by default).
- Manually-invoked integration tests pass on a developer machine with cellpose and a GPU available.
- Deployed JAR launches in Fiji. Parameter Variations button visible on all three stages. A small sweep on each method produces real results.
- Cancellation during a Cellpose persistent-helper sweep does not leave orphaned `python.exe` processes. Verify with task manager. If the optional one-shot cancellation hook was not implemented, document that one-shot cancellation stops between cells only.
- Resource-guard refusal works: configure a sweep large enough to exceed `HeapBudget` (e.g. cellCount=200 on a full 1024×1024×30 stack) and confirm the dialog refuses with a clear message before any segmentation runs.

## Tests location

`src/test/java/flash/pipeline/ui/variations/integration/`

## Notes / gotchas

- The integration tests need small test images committed under `src/test/resources/flash/pipeline/ui/variations/`. ~256×256 single-channel TIFFs with synthetic blobs are fine. Don't commit microscopy data.
- Do not add ETA in v1. Mixed cache hits and deep-learning startup costs make it noisy.
- Don't add a "Save report" button in v1. Users who want to publish the grid can screenshot it. (If users ask, that's a v1.x add.)
- The `CLAUDE_CONTEXT.md` change is small but important — agents in future sessions will look there first.
- Do not change `BinConfigIO` or the `Channel_Data.txt` schema. The variations dialog must only write through the existing stage fields, which already drive the existing persistence path.
- Verify no regressions in existing tests by running the full suite locally before signing off.
