# Step 10 — IoU stability gold-star pick

## Goal

After all results arrive, compute mean-neighbour IoU per cell and gold-star the most stable one. Stable = "small changes to the parameters don't change the segmentation much" — usually the right answer. Works for both 1D and 2D sweeps.

## Pre-conditions

- Steps 01–09 complete.
- `VariationCellPanel.setBorderHint(BorderHint.STABLE)` available.

## Deliverables

### `IouStability.java`

`flash.pipeline.ui.variations.analysis.IouStability`

```java
public final class IouStability {
    public static OptionalInt findMostStable(List<ParameterCombo> combos, List<ImagePlus> labels);
}
```

Algorithm:
1. Determine sweep topology from `combos`: 1D (one parameter has >1 value) or 2D (two parameters have >1 value).
2. For each cell `i`, compute mean IoU vs its neighbours (Chebyshev distance 1 in the sweep grid).
3. Return `argmax(mean_neighbour_iou)`.
4. If fewer than 3 cells, return empty (no meaningful stability).
5. If all IoUs are zero (every variation produced nothing), return empty.

### IoU of two label images

`flash.pipeline.ui.variations.analysis.LabelIou`

```java
public static double iou(ImagePlus a, ImagePlus b);
```

Computes pixel-level binary IoU: `|A>0 ∩ B>0| / |A>0 ∪ B>0|`. Not per-object — too expensive for live UI feedback. Pixel IoU is a good-enough proxy for "the masks look similar."

For 3D labels, iterate every voxel and accumulate counters. On a 256×256×30 crop that's ~2M voxels per pair, ~20ms per IoU. With N=25 cells and ~4 neighbours each, ~50 IoU calls = ~1s. Acceptable.

Use `ImageStack.getProcessor(z).get(x, y)` in tight loops; cache `getPixels()` arrays to avoid the overhead of per-voxel method dispatch.

### Dialog integration

After the last cell arrives (or after a 2-second debounce of completion events), the dialog runs:

```java
OptionalInt stableIdx = IouStability.findMostStable(combos, labels);
stableIdx.ifPresent(i -> cells.get(i).setBorderHint(BorderHint.STABLE));
```

`BorderHint.STABLE` paints a 4px gold border. The cell's tooltip gains a line: "Mean IoU with neighbours: 0.87 (most stable)".

## Acceptance

- `LabelIouTest`:
  - Identical images → IoU = 1.0.
  - Disjoint images → IoU = 0.0.
  - One image is a subset of the other (50% overlap by area) → IoU matches manual calculation.
- `IouStabilityTest`:
  - 1D sweep of 5 cells where the middle three are nearly identical and the ends are wildly different → returns index 2.
  - 2D 3×3 sweep with a stable plateau in the bottom-right → returns the centre of the plateau.
  - All-empty sweep → returns empty.
- Manual: run a StarDist sweep over `(probThresh × nmsThresh)`, observe a gold-star cell where adjacent variations agree.

## Tests location

`src/test/java/flash/pipeline/ui/variations/analysis/LabelIouTest.java`
`src/test/java/flash/pipeline/ui/variations/analysis/IouStabilityTest.java`

## Notes / gotchas

- For huge crops (full image, large Z), IoU may be slow. Add an early-out: if the IoU computation exceeds 5 seconds total, abort and skip the gold star. Logged, not surfaced to the user.
- Edge cells in 2D grids have fewer neighbours — average over what exists, don't demand 4.
- Pixel IoU treats all objects as one mask. A variation that splits an object into two will score the same as one that doesn't, as long as the union footprint is the same. Acceptable for "stability" as defined here; if it bites in practice, upgrade to per-object IoU via centroid matching.
- Precedence with knee (step 09): gold (stable) > green (knee). Both can be present in a sweep, just on different cells. If they coincide on the same cell, paint gold.
