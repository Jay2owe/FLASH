# Preset Names And Order

## Why this stage exists

Spatial presets need clearer names and the exploratory preset should be first.

## Prerequisites

- Read `src/main/java/flash/pipeline/analyses/wizard/SpatialPresetIO.java:22`.
- Inspect `src/main/resources/spatial_presets/`.
- Read `src/test/java/flash/pipeline/analyses/wizard/SpatialPresetIOTest.java`.

## Read first

- Existing stock order starts with microglia morphology and microglia-plaque contact.
- Requested changes:
  - `microglia morphology` becomes `Cell-level morphology`
  - `microglia + plaque` becomes `Cell morphology + contact`
  - exploratory preset is first

## Scope

- Reorder stock preset loading so exploratory appears first.
- Rename the two requested preset display names.
- Keep filenames stable if that avoids breaking old saved references, unless tests and bootstrap behavior prefer resource renames.
- Update tests for order and display names.

## Out of scope

- Changing preset calculations except where needed for Ripley's K default policy.
- Removing stock preset count.

## Files touched

- `src/main/java/flash/pipeline/analyses/wizard/SpatialPresetIO.java`
- `src/main/resources/spatial_presets/exploratory_all.json`
- `src/main/resources/spatial_presets/microglia_morphology.json`
- `src/main/resources/spatial_presets/microglia_plaque_contact.json`
- `src/test/java/flash/pipeline/analyses/wizard/SpatialPresetIOTest.java`

## Implementation sketch

1. Move `exploratory_all.json` to the first entry in the stock preset list.
2. Update JSON `name` fields for the two renamed presets.
3. Decide whether exploratory should keep Ripley's K on because it is explicitly exploratory; document that choice in the test.
4. Update tests to assert first preset and renamed labels.
5. Keep bootstrap refresh behavior intact.

## Exit gate

- Exploratory appears first.
- The renamed presets display exactly as requested.
- Stock preset tests pass.

## Known risks

- If user preset files are bootstrapped from stock resources, old display names may persist until refresh. Follow the existing bootstrap refresh policy.
