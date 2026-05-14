# Step 06 — StarDist per-cell fallback + strategy chooser

## Goal

Add the safe slow path for StarDist sweeps that include parameters the fast path can't handle (model choice, normalisation, tile count). Plumb the strategy chooser so the executor picks fast-path-when-possible, fallback-otherwise. End-of-step: any StarDist sweep produces correct results; eligible ones are fast, others "merely work."

## Pre-conditions

- Steps 01–05 complete.
- `StarDistParameterStage.PreviewAdapter.runPreview(ImagePlus, StarDistParameterStage.Parameters)` exists and calls `StarDist3DRunner.run(...)`. GPU semaphore gating happens inside `StarDist3DRunner.run(...)`, so strategies using the adapter must not acquire it again.

## Deliverables

### `StarDistPerCell.java` (strategy)

`flash.pipeline.ui.variations.strategy.StarDistPerCell implements VariationStrategy`

```java
public StarDistPerCell(ImagePlus filteredSource, CropSpec crop, VariationCache cache,
                       StarDistParameterStage.PreviewAdapter previewAdapter,
                       StarDistParameterStage.Parameters baseParams);
```

`dispatch(sweep, publisher, cancelCheck)`:
1. Crop the input once.
2. For each combo (in centre-first dispatch order from step 04):
   - Cancel check; abort if cancelled.
   - Cache check; publish on hit.
   - Otherwise build a `StarDistParameterStage.Parameters` by overlaying combo values onto `baseParams`.
   - Call `previewAdapter.runPreview(cropped, runParameters)` using the same semantics as `StarDistParameterStage`: detection/linking values drive the run; post-filter fields are applied/classified after the label image exists. The stage's current `previewRunParameters(...)` helper is private at lines 844-856, so either duplicate that exact small transformation in the strategy or extract it to a package-visible/public utility.
   - GPU semaphore is already inside `StarDist3DRunner.run(...)`. Don't double-acquire.
   - Cache and publish.
3. Sequential dispatch — only one GPU inference at a time anyway.

### `VariationStrategyChooser` (extended)

Extend the chooser from step 04:

```java
public static VariationStrategy choose(ParameterSweep sweep, VariationEngineContext ctx, VariationCache cache) {
    switch (sweep.method()) {
        case CLASSICAL:
            return new ClassicalSweep(ctx.filteredSource(), ctx.crop(), cache, ctx.classicalAdapter(), parallelism());
        case STARDIST:
            if (StarDistFastNms.canHandle(sweep)) {
                return new StarDistFastNms(ctx.filteredSource(), ctx.crop(), cache, ctx.starDistAdapter(), ctx.starDistParams());
            }
            return new StarDistPerCell(ctx.filteredSource(), ctx.crop(), cache, ctx.starDistAdapter(), ctx.starDistParams());
        case CELLPOSE:
            // step 07
            throw new UnsupportedOperationException("Cellpose strategy lands in step 07");
    }
}
```

`VariationEngineContext` is a small Java 8 immutable class with static factories, not a builder:

```java
VariationEngineContext.forClassical(..., ClassicalSegmentationStage.PreviewAdapter adapter, ...)
VariationEngineContext.forStarDist(..., StarDistParameterStage.PreviewAdapter adapter,
                                   StarDistParameterStage.Parameters params, ...)
VariationEngineContext.forCellpose(..., CellposeParameterStage.PreviewAdapter adapter,
                                  CellposeParameterStage.Parameters params, ...)
```

Keep it as a transport object for the dialog/chooser only. Avoid a large builder unless later steps add enough optional fields to justify it.

### Strategy-pick UI feedback

Keep this minimal: after the chooser picks a strategy, put `strategy.getClass().getSimpleName()` plus cell count in the status bar or log. Do not add a separate per-strategy status-string API in v1.

## Acceptance

- `StarDistPerCellTest`: 4-cell sweep including a non-NMS parameter (e.g. `LINKING_MAX` or `AREA_MIN`) routes to `StarDistPerCell`. In v1, post-filter fields are not part of the NMS fast path unless a separate tested optimisation is added.
- `VariationStrategyChooserTest`:
  - Classical method → `ClassicalSweep`.
  - StarDist with `{PROB_THRESH, NMS_THRESH}` -> `StarDistFastNms` only when the step 05 parity flag is enabled; otherwise `StarDistPerCell`.
  - StarDist with `{PROB_THRESH, LINKING_MAX}` -> `StarDistPerCell`.
- Integration: if fast path is enabled, a real StarDist sweep that mixes prob/nms runs faster than the same sweep with the strategy forced to `StarDistPerCell`. If fast path is disabled, document that in the test comment and verify per-cell still works.

## Tests location

`src/test/java/flash/pipeline/ui/variations/strategy/StarDistPerCellTest.java`
`src/test/java/flash/pipeline/ui/variations/strategy/VariationStrategyChooserTest.java`

## Notes / gotchas

- The chooser is the right place for any future heuristics ("if cellCount > 50 and method == Cellpose without persistent helper, refuse"). Keep its logic centralised.
- Strategy wording should stay incidental. The important status is completion/failure count; step 13 can format that without a separate estimator class.
- Don't combine fast-path and per-cell within one sweep. If any combo is ineligible, the whole sweep uses per-cell. Mixing makes timing and cache keys complicated for no real benefit.
