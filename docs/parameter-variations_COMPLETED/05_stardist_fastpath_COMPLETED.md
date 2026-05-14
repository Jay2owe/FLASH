# Step 05 — StarDist fast path: one inference + parallel NMS

## Goal

Implement the optional high-leverage StarDist strategy: run StarDist 2D inference **once** per Z-slice, cache the `prob` and `dist` outputs, then run `StarDist2DNMS` in parallel for every `(probThresh, nmsThresh)` combo. If parity is proven, a 5x5 prob/nms sweep costs roughly the time of one regular StarDist run plus NMS/linking overhead.

This step is the most technically novel piece of the project and must remain optional until parity is proven. De-risk first by verifying `StarDist2DNMS` is reachable from `StarDist3DRunner`'s existing classpath and that the reconstructed 3D labels match the current TrackMate-linked behaviour closely enough to trust.

## Pre-conditions

- Steps 01–04 complete.
- StarDist plugin is on the Fiji classpath (it already is — `StarDist3DRunner` imports `de.csbdresden.stardist.StarDist2DModel`).
- `pom.xml` declares `sc.fiji:TrackMate-StarDist:1.2.1` (`provided`), whose POM depends on `de.csbdresden:StarDist_:0.3.0-scijava`. The local Maven jar contains:
  - `de/csbdresden/stardist/StarDist2D.class`
  - `de/csbdresden/stardist/StarDist2DNMS.class`
- Existing TrackMate detector settings are in `StarDist3DRunner.configureStarDistDetector(...)` at `src/main/java/flash/pipeline/stardist/StarDist3DRunner.java:414`; tracker settings are at lines 232-236.

## De-risk task (do first)

Before writing the strategy, write a small standalone test that:
1. Loads a synthetic ImagePlus.
2. Invokes `StarDist2D` via the SciJava `CommandService` with `showProbAndDist = true`.
3. Captures `prob` and `dist` outputs from the resulting `CommandModule` (`StarDist2D.class` exposes both as `Dataset` outputs when `showProbAndDist` is true).
4. Invokes `StarDist2DNMS` with the captured `prob`, `dist`, and chosen thresholds.
5. Captures the NMS output. The class metadata shows the output field as `labelImage` and type `ImagePlus` via `StarDist2DBase`; confirm the exact `CommandModule.getOutput(...)` key in the test.
6. Asserts the output is a valid label image with at least one object.
7. Adds a parity test against `StarDist3DRunner.run(...)` on a tiny stack. If the fast path cannot preserve current 3D linking/object-count semantics, disable it for v1.

If reachability or parity fails, the entire fast path is disabled and we fall back to per-cell (step 06). Write this test as `StarDist2DNMSReachableTest` and mark it `@Ignore` if it requires GPU.

## Deliverables (assuming de-risk passes)

### Optional `StarDistVariationRunner` / `StarDist3DRunner.runVariations(...)`

Prefer a sibling class in `flash.pipeline.stardist` if the code grows beyond a small method. Do not make `flash.pipeline.stardist` depend on UI classes such as `ParameterCombo`; the strategy can map combos to engine-native threshold pairs.

`ThresholdPair` is a tiny Java 8 value class local to the StarDist variation runner (`double probThresh`, `double nmsThresh`, stable `equals`/`hashCode`).

```java
public static Map<ThresholdPair, ImagePlus> runVariations(
    ImagePlus input,
    String channelName,
    List<Double> probThreshes,
    List<Double> nmsThreshes,
    BooleanSupplier cancelCheck);
```

Internals:
1. **Input preparation**: preserve calibration/dimensions and mirror the existing runner's channel padding where needed. Direct per-slice `StarDist2D` command calls do not automatically get TrackMate's current Z->T linking behaviour.
2. **Per-slice inference loop** (sequential, gated on `GpuConcurrency.gpuSemaphore`):
   - For slice z, invoke `StarDist2D` with `showProbAndDist=true`. Capture `prob[z]` and `dist[z]` datasets in a `Map<Integer, ProbDistPair>`.
3. **Parallel NMS sweep** (`ForkJoinPool`, parallelism `cores - 1`):
   - For each `(probT, nmsT)`:
     - For each z, invoke `StarDist2DNMS(prob[z], dist[z], probT, nmsT, ...)` → 2D label.
     - Reconstruct 3D labels with the same semantics as the current `StarDist3DRunner.run(...)`. This is the hard part: current production code uses TrackMate `SparseLAPTrackerFactory` to link 2D detections into 3D objects and exports `LABEL_IS_TRACK_ID`. Do not enable fast path until a parity test proves object counts/labels are acceptable.
     - Emit `(thresholdPair, label3D)` into the result map.
4. **Cancellation**: each task checks `cancelCheck` before NMS dispatch and between slices. On cancel, return partial results.

### `StarDistFastNms.java` (strategy)

`flash.pipeline.ui.variations.strategy.StarDistFastNms implements VariationStrategy`

Constructor: `(ImagePlus filteredSource, CropSpec crop, VariationCache cache, StarDistParameterStage.PreviewAdapter previewAdapter, StarDistParameterStage.Parameters baseParams)`.

`dispatch(sweep, publisher, cancelCheck)`:
1. Validate that `sweep.parameterIds() ⊆ {PROB_THRESH, NMS_THRESH}` and the fast-path parity flag is enabled. Linking (`LINKING_MAX`, `GAP_CLOSING_MAX`, `FRAME_GAP`) changes 3D TrackMate linking and is not fast-path eligible. Post-filter-only optimisation is separate and should reuse/extract the stage's filter-classification helpers, not `StarDist2DNMS`.
2. Crop the input.
3. Cache lookup per combo; cache hits publish immediately.
4. For cache misses, call `StarDist3DRunner.runVariations(...)` / `StarDistVariationRunner.runVariations(...)` with the union of swept thresholds. Receive a `Map<ThresholdPair, ImagePlus>` and map threshold pairs back to combos in the strategy.
5. For each result, publish. If post-filter IDs are later made fast, first extract `StarDistParameterStage` helpers at lines 706-788 into a shared utility and apply the same classified-LUT semantics as the stage.

### Eligibility predicate

`StarDistFastNms.canHandle(sweep)`:
- Method must be StarDist.
- Every swept parameter must be `PROB_THRESH` or `NMS_THRESH`.
- Any linking or post-filter parameter forces the slow path in v1 unless a separate, tested optimisation is added.

## Acceptance

- `StarDist2DNMSReachableTest` passes (or is documented as GPU-only and verified manually).
- If parity is enabled, `StarDistFastNmsTest`: with a real test image + bundled model, a 3×3 (probThresh × nmsThresh) sweep produces 9 results and runs in <2× the time of one regular `StarDist3DRunner.run()`.
- `StarDistFastNmsEligibilityTest`: sweeps including linking fields, post-filter fields, or any unknown future StarDist parameter are rejected; sweeps with only `PROB_THRESH` / `NMS_THRESH` are accepted only when parity is enabled.
- If parity is enabled, manual: launch dialog from StarDist stage, sweep prob in `[0.3, 0.5, 0.7]`, nms in `[0.3, 0.5]`. Confirm 6 tiles arrive within roughly one inference's worth of time.

## Tests location

`src/test/java/flash/pipeline/stardist/StarDist3DRunnerVariationsTest.java`
`src/test/java/flash/pipeline/ui/variations/strategy/StarDistFastNmsTest.java`

## Notes / gotchas

- The SciJava `CommandService` is available in Fiji at `ij().command()`; in pure-headless tests you'll need to bootstrap a SciJava `Context`. Existing repo tests do not currently exercise direct `StarDist2D` command output capture, so this test owns that setup.
- Each `StarDist2D` invocation may print to ImageJ's log; suppress with `IJ.redirectErrorMessages(true)` if it spams.
- Re-linking with TrackMate per variation is the slowest non-inference step. Profile early — if linking dominates, consider caching spot-detection results and only re-running the linker.
- `prob` and `dist` outputs are `Dataset`-typed, not `ImagePlus`. Pass them as `Dataset` to `StarDist2DNMS`.
- Memory: caching `prob`/`dist` per slice is large (float images, same dims as input). For a 256×256×30 crop, ~30 × 256×256×4 bytes × 2 (prob + 33-channel-dist) ≈ 60 MB. Within budget. For a full 1024×1024×30 (when crop = FULL), 1 GB — `ResourceGuard` should refuse this anyway, but add a defensive check inside `runVariations`.
- StarDist's distance output has 32 or 33 channels depending on the model. Don't assume a fixed channel count — read it from the dataset.
