# Preset Library Order And ROI Filter

## Why this stage exists

The stock 3D object preset list should match the requested names/order, remove the amyloid preset, and enable ROI filtering in every preset.

## Prerequisites

- Read `src/main/java/flash/pipeline/analyses/wizard/ThreeDObjectPresetIO.java:19`.
- Inspect `src/main/resources/three_d_object_presets/`.
- Read `src/main/java/flash/pipeline/analyses/wizard/ThreeDObjectWizard.java:121`.

## Read first

- Existing stock files include `amyloid_loose.json` and `microglia_processes.json`.
- Existing stock JSON sets `classicalCentroidFiltering` to false.
- The requested "Count + Coloc Loose" replaces the amyloid-specific loose preset at the UI level.

## Scope

- Remove the amyloid-specific preset from the stock list.
- Add or rename a generic loose colocalisation preset named `Count + Coloc Loose`.
- Rename/present process-length preset as `Count + Process Length`.
- Set `classicalCentroidFiltering` to true in all stock presets.
- Reorder `ThreeDObjectPresetIO` stock files to match the requested UI order.
- Update stock preset tests.

## Out of scope

- Removing amyloid-specific channel detection used for defaults elsewhere.
- Changing object counting algorithms.

## Files touched

- `src/main/java/flash/pipeline/analyses/wizard/ThreeDObjectPresetIO.java`
- `src/main/resources/three_d_object_presets/full_workflow.json`
- `src/main/resources/three_d_object_presets/count_only.json`
- `src/main/resources/three_d_object_presets/count_coloc_standard.json`
- `src/main/resources/three_d_object_presets/count_coloc_strict.json`
- `src/main/resources/three_d_object_presets/amyloid_loose.json`
- `src/main/resources/three_d_object_presets/microglia_processes.json`
- `src/test/java/flash/pipeline/analyses/wizard/ThreeDObjectPresetIOTest.java`

## Implementation sketch

1. Create or rename the loose preset file to a generic name such as `count_coloc_loose.json`.
2. Create or rename the process-length preset file to a generic name such as `count_process_length.json`.
3. Update display names to the exact requested names.
4. Set `classicalCentroidFiltering` to true in every stock preset.
5. Update `STOCK_PRESET_FILES` order in `ThreeDObjectPresetIO`.
6. Update tests to assert order, names, count, and ROI filtering defaults.
7. Remove the old amyloid preset resource if no code references it.

## Exit gate

- The preset list appears in the requested order.
- No amyloid-specific preset is shown.
- Every stock preset enables ROI filtering.
- Tests cover order and ROI filtering.

## Known risks

- Existing user preset files may still contain old names or ROI filtering off. The stage should change stock defaults, not forcibly rewrite user presets unless there is an existing bootstrap refresh policy.
