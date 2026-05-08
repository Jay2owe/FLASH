# Fix Threshold Summary Text

## Why this stage exists

The filter summary can say a threshold is not needed even when a real threshold is configured. Users need to see that configured threshold instead.

## Prerequisites

- Read `src/main/java/flash/pipeline/analyses/IntensityAnalysisV2.java:241`.
- Read `src/main/java/flash/pipeline/analyses/IntensityAnalysisV2.java:316`.
- Read `src/main/java/flash/pipeline/analyses/IntensityAnalysisV2.java:1787`.

## Read first

- Threshold defaults are loaded from config around `src/main/java/flash/pipeline/analyses/IntensityAnalysisV2.java:241`.
- `buildFilterSummaryLine` currently reports `not needed unless Binarise is enabled` when binarisation is off.
- Some presets/configs may still carry real threshold values even when the UI says they are not needed.

## Scope

- Update threshold summary text to show a real configured threshold when one exists.
- Keep "not needed" only for channels with no real configured threshold and no binarisation need.
- Make the text clear when threshold will be used later because binarisation is enabled.
- Add tests for default/no-threshold, configured numeric threshold, and `default`/automatic threshold cases.

## Out of scope

- Changing how thresholds are applied during measurement.
- Reintroducing intensity presets.

## Files touched

- `src/main/java/flash/pipeline/analyses/IntensityAnalysisV2.java`
- `src/test/java/flash/pipeline/analyses/IntensityAnalysisV2Test.java`

## Implementation sketch

1. Add a helper such as `describeConfiguredThreshold(...)`.
2. Treat numeric threshold strings as real configured thresholds.
3. Treat `default`, blank, and unset values according to current runtime behavior.
4. Use that helper inside `buildFilterSummaryLine`.
5. If binarisation is enabled, preserve the next-dialog threshold prompt wording.
6. Add tests that assert the summary does not say "not needed" when a numeric threshold exists.

## Exit gate

- Numeric configured thresholds appear in the channel summary.
- "Not needed" appears only when it is actually true.
- Tests cover the misleading-text regression.

## Known risks

- A threshold value of `0` may mean either a real threshold or an automatic/default placeholder in different paths. Inspect existing config semantics before deciding how to display it.
