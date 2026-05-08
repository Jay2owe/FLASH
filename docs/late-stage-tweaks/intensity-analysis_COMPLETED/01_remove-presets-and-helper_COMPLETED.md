# Remove Presets And Helper

## Why this stage exists

The Fluorescent Intensity Analysis UI should no longer expose preset selection or the setup helper.

## Prerequisites

- Read `src/main/java/flash/pipeline/analyses/IntensityAnalysisV2.java:271`.
- Read `src/main/java/flash/pipeline/analyses/IntensityAnalysisV2.java:1470`.
- Read `src/main/java/flash/pipeline/analyses/wizard/IntensityPresetIO.java`.

## Read first

- The primary dialog calls `addIntensitySetupControls` before showing filter-source and analysis options.
- Preset save/load helpers continue later in the file.
- Tests currently expect stock intensity presets.

## Scope

- Remove preset combo and save preset button from the intensity UI.
- Remove the setup helper button from the intensity UI.
- Remove now-unused preset IO/resources if no command-line or compatibility path still needs them.
- Update or delete tests that only validate removed preset UI.

## Out of scope

- Removing intensity threshold fields.
- Changing intensity measurement outputs.
- Removing config-derived defaults.

## Files touched

- `src/main/java/flash/pipeline/analyses/IntensityAnalysisV2.java`
- `src/main/java/flash/pipeline/analyses/wizard/IntensityPreset.java`
- `src/main/java/flash/pipeline/analyses/wizard/IntensityPresetIO.java`
- `src/main/java/flash/pipeline/analyses/wizard/IntensityWizard.java` only if now unused
- `src/main/resources/intensity_presets/`
- `src/test/java/flash/pipeline/analyses/wizard/IntensityPresetIOTest.java`
- `src/test/java/flash/pipeline/analyses/wizard/IntensityWizardTest.java`
- `src/test/java/flash/pipeline/analyses/IntensityAnalysisV2Test.java`

## Implementation sketch

1. Remove the `addIntensitySetupControls` call from the primary dialog.
2. Remove the method and related preset save/list/load helpers if no longer used.
3. Remove `SetupHelperButton`, preset, and wizard imports if unused.
4. Search for `IntensityPreset`, `IntensityPresetIO`, and `IntensityWizard`.
5. Delete resource files and tests for removed preset functionality, or narrow tests to retained non-UI compatibility.
6. Run compile/tests to catch leftover references.

## Exit gate

- The intensity UI has no preset selector.
- The intensity UI has no setup helper button.
- No dead preset imports remain.
- Tests reflect the removed preset feature.

## Known risks

- If command-line workflows still depend on preset JSON names, do not delete the backend until that dependency is removed or explicitly migrated.
