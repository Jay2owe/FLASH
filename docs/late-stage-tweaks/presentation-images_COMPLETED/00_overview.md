# Presentation Images Late-Stage Tweaks

## End goal

Rename Split and Merge Images to Make Presentation-Ready Images, remove presets, add grey helper text under each input, and reorganize the channel settings into three row groups with channel columns.

## Why we're doing this

The current dialog repeats the same controls per channel and includes presets the user no longer wants. A row-by-setting layout will make comparison across channels easier.

## Architecture overview

- Main UI label and description live in `src/main/java/flash/pipeline/FLASH_Pipeline.java:115`.
- The analysis implementation is `src/main/java/flash/pipeline/analyses/SplitAndMergeImageChannelsAnalysis.java`.
- The main dialog is built in `showMainDialog` starting at `src/main/java/flash/pipeline/analyses/SplitAndMergeImageChannelsAnalysis.java:783`.
- Preset support is in `src/main/java/flash/pipeline/analyses/wizard/SplitMergePreset.java`, `src/main/java/flash/pipeline/analyses/wizard/SplitMergePresetIO.java`, and `src/main/resources/split_merge_presets/`.

## Stage map

1. `01_remove-presets-and-rename.md` - remove presentation-image presets and update the main UI name/description.
2. `02_channel-grid-and-helper-text.md` - rebuild channel settings as Processing Method, Display Ranges, Saturation rows with grey helper text.

## House rules

- Preserve existing output behavior unless a control is explicitly removed.
- Do not shift `PipelineDialog` read order accidentally. If the new grid uses custom Swing controls, read values directly from those bindings.
- Keep helper text grey and subordinate, not a new instruction-heavy page.

## How to run a stage

From the repo root, run `/do-step docs/late-stage-tweaks/presentation-images/`.
