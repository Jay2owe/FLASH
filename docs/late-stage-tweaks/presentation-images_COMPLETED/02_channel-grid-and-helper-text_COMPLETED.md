# Channel Grid And Helper Text

## Why this stage exists

The current dialog repeats Processing Method, Saturation, and Display Range under each channel. The requested layout is three rows by setting, with columns for each channel, in this order: Processing Method, Display Ranges, Saturation.

## Prerequisites

- Complete `01_remove-presets-and-rename.md`.
- Read `src/main/java/flash/pipeline/analyses/SplitAndMergeImageChannelsAnalysis.java:817`.
- Read `src/main/java/flash/pipeline/ui/PipelineDialog.java:462`.

## Read first

- The current per-channel controls are created in a loop from `src/main/java/flash/pipeline/analyses/SplitAndMergeImageChannelsAnalysis.java:817` to `src/main/java/flash/pipeline/analyses/SplitAndMergeImageChannelsAnalysis.java:851`.
- The dialog currently reads choices, numbers, and strings through `PipelineDialog` counters after `showDialog`.
- A table-like custom panel may be easier than forcing `PipelineDialog` field APIs into columns.

## Scope

- Replace per-channel repeated sections with a compact grid:
  - row 1: Processing Method
  - row 2: Display Ranges
  - row 3: Saturation
  - columns: each channel
- Add grey helper text beneath each input.
- Keep defaults and saved values identical to the current dialog.
- Keep validation for display range strings and saturation values.
- Update tests to match the new control bindings.

## Out of scope

- Changing processing method options.
- Changing merge/export output behavior.

## Files touched

- `src/main/java/flash/pipeline/analyses/SplitAndMergeImageChannelsAnalysis.java`
- `src/main/java/flash/pipeline/ui/PipelineDialog.java` only if a reusable grid helper is worth adding
- `src/test/java/flash/pipeline/analyses/SplitAndMergeWizardBehaviorTest.java`
- `src/test/java/flash/pipeline/analyses/SplitAndMergeImageChannelsAnalysisTest.java`

## Implementation sketch

1. Build arrays of Swing controls for processing method, display range, and saturation, keyed by channel index.
2. Add the grid to `PipelineDialog` through `addComponent(...)`.
3. Put helper labels under each input in grey, using consistent small text.
4. After `showDialog`, read directly from the arrays if the grid is custom.
5. Keep non-grid `PipelineDialog` read order stable for the remaining options.
6. Update tests to validate the new ordering and that presets are absent.

## Exit gate

- The dialog shows three setting rows and channel columns.
- Processing Method appears before Display Ranges, which appears before Saturation.
- Every input has grey helper text beneath it.
- Current defaults and validation still work.

## Known risks

- Custom Swing controls are not automatically included in `PipelineDialog` read counters. Direct bindings are safer for this grid.
