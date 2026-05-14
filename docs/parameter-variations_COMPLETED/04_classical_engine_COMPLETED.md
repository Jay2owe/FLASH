# Step 04 — Classical sweep strategy (the fast easy one)

## Goal

Implement the first real `VariationStrategy`: `ClassicalSweep`. Sweeps threshold / min-size / max-size on a bounded `ForkJoinPool`. End-of-step: clicking [Start] in the Variations dialog from the Classical stage actually produces a grid of real label images.

## Pre-conditions

- Steps 01, 02, 03 complete.
- Classical segmentation logic is exposed by the existing public nested interface `flash.pipeline.ui.config.ClassicalSegmentationStage.PreviewAdapter`:

```java
ObjectsCounter3DWrapper.Result runPreview(ImagePlus filteredSource,
                                          int threshold,
                                          int minSize,
                                          int maxSize) throws Exception;
int countObjects(ObjectsCounter3DWrapper.Result result);
void close(ImagePlus image);
```

## Deliverables

### `ClassicalSweep.java`

`flash.pipeline.ui.variations.strategy.ClassicalSweep implements VariationStrategy`

```java
public ClassicalSweep(ImagePlus filteredSource, CropSpec crop, VariationCache cache,
                      ClassicalSegmentationStage.PreviewAdapter previewAdapter,
                      int parallelism);

@Override
public void dispatch(ParameterSweep sweep,
                     Consumer<VariationResult> publisher,
                     BooleanSupplier cancelCheck) throws Exception;
```

Behaviour:
1. `ImagePlus cropped = sweep.cropSpec().apply(filteredSource)` — done once.
2. Build a `ForkJoinPool` with parallelism = `min(parallelism, cores - 1)`. **Do not use commonPool** — we need `shutdownNow()` for cancel.
3. For each `ParameterCombo c` in `sweep.combos()`:
   - Cache-key lookup: if hit, publish immediately.
   - Else submit task to the pool: run `previewAdapter.runPreview(cropped, threshold, minSize, maxSize)`, collect `result.getObjectsMap()` and `result.getStatistics()`, compute count with `previewAdapter.countObjects(result)`, write to cache, publish.
4. Wait for completion. Poll `cancelCheck` between dispatches; on cancel call `pool.shutdownNow()` and bail.
5. `pool.shutdown()` + `awaitTermination(60s)` at exit.

### Priority dispatch (centre-first)

Submit combos in a priority order: the centre of the sweep first, then radiating outward by Chebyshev distance from the centre cell. This is the "centre-first" UX from idea #11 of the brainstorm — gives the user something useful to look at within the first second.

Implementation: pre-compute the order as `List<ParameterCombo>` sorted by Chebyshev distance from the median value index along each axis. Helper `SweepDispatchOrder.order(sweep)`.

### Reuse the existing PreviewAdapter

Don't reimplement segmentation. `ClassicalSegmentationStage.PreviewAdapter` already encapsulates the current preview path. The stage passes its existing `previewAdapter` into `VariationEngineContext.forClassical(...)`; the dialog passes it to `ClassicalSweep`.

Do not lift the adapter to a new segmentation package in v1. The nested interface is already public and is the smallest stable seam.

### Strategy chooser (initial cut)

Add `flash.pipeline.ui.variations.strategy.VariationStrategyChooser`:

```java
public static VariationStrategy choose(
    ParameterSweep sweep,
    VariationEngineContext context,
    VariationCache cache);
```

For this step it only handles `Classical`. Step 06 extends it. Step 07 adds Cellpose branches.

## Acceptance

- Unit test `ClassicalSweepTest`: feed a 256×256×3 synthetic ImagePlus with three blobs of known intensity; sweep `THRESHOLD = [low, medium, high]`; assert that low yields more objects than high.
- Integration smoke test in headless mode: 4-cell sweep completes in <2s on a developer machine.
- Manual: launch the dialog from the demo harness or temporary Classical-stage hook, sweep threshold across 5 values, watch tiles fill in centre-first within ~1s total. Click one, confirm the callback receives the right combo. The permanent stage buttons land in step 08.

## Tests location

`src/test/java/flash/pipeline/ui/variations/strategy/ClassicalSweepTest.java`
`src/test/java/flash/pipeline/ui/variations/strategy/SweepDispatchOrderTest.java`

## Notes / gotchas

- The existing `CreateBinFileAnalysis` adapter creates a fresh `ObjectsCounter3DWrapper` per call and falls back to `WindowManagerLock` only when mcib3d is unavailable. Start parallel, but if tests expose thread-safety trouble, cap Classical to one worker rather than inventing another segmentation path.
- ImageJ macro calls (`IJ.run(imp, "Auto Threshold", "...")`) are NEVER safe off the EDT in parallel — they manipulate `WindowManager`. Verify the adapter doesn't go through macros. If it does, refactor that path to direct `ImageProcessor` / `Auto_Threshold` API calls.
- `ImageProcessor.crop()` is thread-safe (immutable input -> new processor). `Duplicator` is NOT. The codebase already uses `ImageOps.duplicateThreadSafe`; follow that pattern when duplicating stacks outside the existing adapters.
- Memory: each task allocates a label image. With parallelism = 7 and 25 cells, peak in-flight = 7 label images. Crop to 256×256×Z keeps this comfortably bounded.
- Don't forget to mark cells as `pending` → `running` → `done` via the publisher contract — UI feedback matters.
