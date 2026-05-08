# Dialog Defaults And Tests

## Why this stage exists

After the preset files are fixed, the analysis dialog and wizard must still default ROI filtering to on in every path.

## Prerequisites

- Complete `01_preset-library-order-and-roi-filter.md`.
- Read `src/main/java/flash/pipeline/analyses/ThreeDObjectAnalysis.java:613`.
- Read `src/main/java/flash/pipeline/analyses/wizard/ThreeDObjectWizard.java:80`.
- Read `src/main/java/flash/pipeline/analyses/wizard/ThreeDObjectWizard.java:429`.

## Read first

- `ThreeDObjectAnalysis` field `classicalCentroidFilter` already defaults to true.
- `ThreeDObjectWizard.deriveConfig` defaults centroid filtering to true.
- `ThreeDObjectWizard.fromPreset` currently has a fallback preset with centroid filtering false.

## Scope

- Make every dialog path default ROI filtering to on.
- Make wizard fallback config default ROI filtering to on.
- Ensure loading a stock preset does not switch ROI filtering off.
- Update tests for dialog defaults and preset-derived config.

## Out of scope

- Removing the advanced placement of the ROI filtering toggle unless requested separately.
- Changing actual ROI filtering calculations.

## Files touched

- `src/main/java/flash/pipeline/analyses/ThreeDObjectAnalysis.java`
- `src/main/java/flash/pipeline/analyses/wizard/ThreeDObjectWizard.java`
- `src/test/java/flash/pipeline/analyses/wizard/ThreeDObjectWizardTest.java`
- `src/test/java/flash/pipeline/analyses/ThreeDObjectAnalysisTest.java`

## Implementation sketch

1. Update `ThreeDObjectWizard.fromPreset` fallback to true.
2. Verify the dialog toggle default is true before and after preset load.
3. If the preset load listener applies false from old user presets, leave user presets respected but ensure stock presets are true.
4. Add tests for no preset, each stock preset, and fallback preset load.
5. Run the 3D object wizard and analysis test classes.

## Exit gate

- ROI filtering defaults to on without a preset.
- ROI filtering defaults to on for every stock preset.
- Tests catch any future stock preset with filtering off.

## Known risks

- Respecting old user presets may still allow filtering off by explicit user choice. That is acceptable unless the product requirement changes from default-on to always-on.
