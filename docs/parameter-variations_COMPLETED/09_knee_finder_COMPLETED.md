# Step 09 — Knee-finder green border for 1D sweeps

## Goal

For sweeps where exactly one parameter has multiple values, compute the "knee" of the object-count curve and highlight that cell with a green border + a "suggested" tooltip/badge. Helps users zero in on the elbow without adding a custom chart in v1.

## Pre-conditions

- Steps 01–08 complete. Sweeps run end-to-end and tiles fill in with real label images.
- `VariationCellPanel` already supports a `setBorderHint(BorderHint hint)` API (or add it now).

## Deliverables

### `KneeDetector.java`

`flash.pipeline.ui.variations.analysis.KneeDetector`

```java
public final class KneeDetector {
    public static OptionalInt findKneeIndex(double[] x, double[] y);
}
```

Algorithm: Kneedle, simplified for the small-N case (N ≤ ~10):

1. If N < 4, return `OptionalInt.empty()` — no meaningful knee with so few points.
2. Sort indices by `x` ascending.
3. Min-max normalise both `x` and `y` to `[0, 1]`.
4. Detect monotonic direction (counts usually decrease with increasing threshold; both directions handled).
5. Compute the "difference curve" `d[i] = y_norm[i] - x_norm[i]` (or `x_norm[i] - y_norm[i]` for the other direction).
6. Return `argmax(d)`.
7. If the difference curve is nearly flat (max - min < 0.1), return empty — no clear knee, decide visually.

This is ~30 lines. Don't pull in a curve-fitting dependency.

### Cell border update

When all results have arrived (or when the user pauses dispatch), the dialog calls:

```java
OptionalInt kneeIdx = KneeDetector.findKneeIndex(xs, ys);
kneeIdx.ifPresent(i -> cells.get(i).setBorderHint(BorderHint.KNEE));
```

`BorderHint.KNEE` paints a 3px green border and adds "Suggested knee" to the badge/tooltip. Do not build a hand-painted `CountCurvePanel` for v1; the border hint is the useful part and is much cheaper to maintain.

## Acceptance

- `KneeDetectorTest`:
  - Synthetic curve `y = [100, 95, 80, 30, 10, 8, 7]` (clear elbow at index 3) returns index 3.
  - Linear curve returns empty.
  - Constant curve returns empty.
  - 3-point curve returns empty.
  - Reverse-direction curve (counts increase) still detects the elbow.
- Manual: classical threshold sweep with 7 values shows a green border on the elbow tile and a tooltip/badge explaining why.

## Tests location

`src/test/java/flash/pipeline/ui/variations/analysis/KneeDetectorTest.java`

## Notes / gotchas

- Don't crash on `NaN` or `Infinity` in `y` (a failed segmentation might produce these). Filter them and run on the remaining points; return empty if too few remain.
- For multi-parameter sweeps, the knee finder is skipped — there's no single-axis curve to analyse. IoU stability (step 10) is the analogue for 2D.
- The green border should never override the gold IoU-stability star (step 10). Define a precedence: gold > green > grey. If both apply, paint a half-and-half border or just use gold (gold wins).
- Counts can be the same across adjacent cells when the parameter has no effect locally — kneedle handles this fine as long as the overall curve has structure.
