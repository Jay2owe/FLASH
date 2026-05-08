# Fluorescent Intensity Late-Stage Tweaks

## End goal

Remove intensity presets and helper UI, and fix threshold summary text so it reports real configured thresholds instead of saying they are not needed.

## Why we're doing this

Intensity presets are no longer wanted. The threshold summary is also misleading when a preset or configuration contains real threshold values.

## Architecture overview

- The analysis implementation is `src/main/java/flash/pipeline/analyses/IntensityAnalysisV2.java`.
- The primary dialog starts at `src/main/java/flash/pipeline/analyses/IntensityAnalysisV2.java:271`.
- Preset/helper controls are added by `addIntensitySetupControls` at `src/main/java/flash/pipeline/analyses/IntensityAnalysisV2.java:1470`.
- Threshold summary text is built by `buildFilterSummaryLine` at `src/main/java/flash/pipeline/analyses/IntensityAnalysisV2.java:1787`.
- Preset support uses `src/main/java/flash/pipeline/analyses/wizard/IntensityPreset.java`, `IntensityPresetIO.java`, and `IntensityWizard.java`.

## Stage map

1. `01_remove-presets-and-helper.md` - remove intensity preset and helper UI.
2. `02_fix-threshold-summary-text.md` - make threshold helper text describe actual configured thresholds.

## House rules

- Keep command-line and saved configuration behavior working.
- Do not remove threshold support itself.
- Be careful with dialog read order after removing the preset/helper controls.

## How to run a stage

From the repo root, run `/do-step docs/late-stage-tweaks/intensity-analysis/`.
