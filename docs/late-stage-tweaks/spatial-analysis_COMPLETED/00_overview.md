# Spatial Analysis Late-Stage Tweaks

## End goal

Make Ripley's K default off, expose 3D and complex shape analysis outside advanced options, update preset names/order, and reuse existing 3D object analysis data instead of repeating work.

## Why we're doing this

Spatial analysis currently hides useful morphology controls, over-enables Ripley's K by default, and can repeat calculations already produced by 3D Object Analysis. Users need clearer choices and less duplicate processing.

## Architecture overview

- The analysis implementation is `src/main/java/flash/pipeline/analyses/SpatialAnalysis.java`.
- Dialog defaults and controls start at `src/main/java/flash/pipeline/analyses/SpatialAnalysis.java:240`.
- Existing CPC (cell-plaque contact) reuse logic is in `runCpcIfNeeded` around `src/main/java/flash/pipeline/analyses/SpatialAnalysis.java:1377`.
- Presets are loaded by `src/main/java/flash/pipeline/analyses/wizard/SpatialPresetIO.java`.
- Wizard defaults and preset mapping are in `src/main/java/flash/pipeline/analyses/wizard/SpatialAnalysisWizard.java`.

## Stage map

1. `01_defaults-and-control-layout.md` - set Ripley's K default off and move 3D/complex shape options out of advanced.
2. `02_preset-names-and-order.md` - put exploratory first and rename the two cell morphology presets.
3. `03_detect-and-reuse-3d-object-data.md` - detect existing 3D object outputs, skip repeated work, and explain reuse in grey helper text.

## House rules

- Keep dependency checks: complex shape still depends on appropriate shape outputs, even if the toggle is no longer advanced.
- Do not delete existing skip/reuse behavior; extend it.
- Make helper text factual and grey, not a blocking warning.

## How to run a stage

From the repo root, run `/do-step docs/late-stage-tweaks/spatial-analysis/`.
