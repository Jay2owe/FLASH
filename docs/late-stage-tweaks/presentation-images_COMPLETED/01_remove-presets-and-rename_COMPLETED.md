# Remove Presets And Rename

## Why this stage exists

The presentation-image workflow should not expose presets, and the main UI needs the new user-facing name and description.

## Prerequisites

- Read `src/main/java/flash/pipeline/FLASH_Pipeline.java:115`.
- Read `src/main/java/flash/pipeline/analyses/SplitAndMergeImageChannelsAnalysis.java:783`.
- Read `src/main/java/flash/pipeline/analyses/wizard/SplitMergePresetIO.java`.

## Read first

- The current main UI label is `Split and Merge Image Channels`.
- The requested main UI label is `Make Presentation-Ready Images`.
- The requested description is `Split image channels, apply LUTs and display ranges, then create composite PNGs and/or Tifs for presentation.`
- Preset controls are added near `src/main/java/flash/pipeline/analyses/SplitAndMergeImageChannelsAnalysis.java:805`.

## Scope

- Rename the main analysis label to `Make Presentation-Ready Images`.
- Replace the main UI description with the requested text.
- Remove preset combo, save preset button, preset load listener, preset bootstrap, and preset helper methods from this analysis.
- Delete unused split/merge preset classes/resources if no other code uses them.
- Update tests that expect stock split/merge presets.

## Out of scope

- Renaming Java class names unless required by tests or public UI.
- Changing generated image filenames unless covered by the outputs plan.

## Files touched

- `src/main/java/flash/pipeline/FLASH_Pipeline.java`
- `src/main/java/flash/pipeline/analyses/SplitAndMergeImageChannelsAnalysis.java`
- `src/main/java/flash/pipeline/analyses/wizard/SplitMergePreset.java`
- `src/main/java/flash/pipeline/analyses/wizard/SplitMergePresetIO.java`
- `src/main/resources/split_merge_presets/`
- `src/test/java/flash/pipeline/analyses/wizard/SplitMergePresetIOTest.java`
- split/merge behavior tests that assert preset UI exists

## Implementation sketch

1. Update the analysis label and description arrays in `FLASH_Pipeline`.
2. Remove imports and fields for `SplitMergePreset` and `SplitMergePresetIO`.
3. Remove the preset controls and listeners from `showMainDialog`.
4. Remove preset helper methods if they are now unused.
5. Delete stock preset JSON resources and preset IO tests, or rewrite tests to assert presets are absent.
6. Run a search for `SplitMergePreset` and remove all dead references.

## Exit gate

- The main UI shows `Make Presentation-Ready Images`.
- No preset controls appear in the presentation-image dialog.
- `rg "SplitMergePreset"` finds no live production references, or only intentionally retained compatibility code.

## Known risks

- Tests may instantiate preset IO directly. Remove or rewrite those tests in the same stage so the codebase stays coherent.
