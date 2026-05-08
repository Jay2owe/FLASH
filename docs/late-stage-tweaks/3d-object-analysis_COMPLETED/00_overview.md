# 3D Object Late-Stage Tweaks

## End goal

Clean up 3D object presets, remove the amyloid-specific preset, enforce the requested preset order, and make ROI filtering default to on everywhere, including presets.

## Why we're doing this

The current preset list includes redundant/special-case entries and presets turn off ROI filtering even though the dialog default is already on. That creates inconsistent behavior.

## Architecture overview

- The analysis implementation is `src/main/java/flash/pipeline/analyses/ThreeDObjectAnalysis.java`.
- The main dialog starts at `src/main/java/flash/pipeline/analyses/ThreeDObjectAnalysis.java:613`.
- Preset loading is in `src/main/java/flash/pipeline/analyses/wizard/ThreeDObjectPresetIO.java`.
- Preset-to-config mapping is in `src/main/java/flash/pipeline/analyses/wizard/ThreeDObjectWizard.java:121`.
- Stock presets live in `src/main/resources/three_d_object_presets/`.

## Stage map

1. `01_preset-library-order-and-roi-filter.md` - replace and reorder stock presets, with ROI filtering on.
2. `02_dialog-defaults-and-tests.md` - verify dialog and wizard defaults keep ROI filtering on.

## House rules

- Keep the requested preset names and order exactly:
  - Full workflow
  - Count Only
  - Count + Coloc Standard
  - Count + Coloc Strict
  - Count + Coloc Loose
  - Count + Process Length
- ROI filtering should default to on in the dialog, wizard, and every stock preset.
- Do not remove amyloid detection logic unless it is only used by the removed preset.

## How to run a stage

From the repo root, run `/do-step docs/late-stage-tweaks/3d-object-analysis/`.
