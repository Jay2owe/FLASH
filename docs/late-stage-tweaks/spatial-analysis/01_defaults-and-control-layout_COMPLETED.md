# Defaults And Control Layout

## Why this stage exists

Ripley's K should not run by default, and 3D features plus complex shape analysis should be visible without opening advanced options.

## Prerequisites

- Read `src/main/java/flash/pipeline/analyses/SpatialAnalysis.java:240`.
- Read `src/main/java/flash/pipeline/analyses/SpatialAnalysis.java:285`.
- Read `src/main/java/flash/pipeline/analyses/wizard/SpatialAnalysisWizard.java:180`.

## Read first

- `doSpatialStats` currently defaults to true in the manual dialog path.
- Ripley's K is controlled by `doSpatialStats`.
- 3D shape and complex shape toggles are currently inside an advanced section around `src/main/java/flash/pipeline/analyses/SpatialAnalysis.java:351`.

## Scope

- Set Ripley's K / `doSpatialStats` default to off in the manual dialog path.
- Review wizard/preset defaults so "default" does not unexpectedly mean Ripley's K on.
- Move 3D shape features and complex shape analysis controls out of advanced options.
- Keep population-level and spatial-morphometric controls advanced unless product requirements say otherwise.
- Update dialog readback order and tests.

## Out of scope

- Removing Ripley's K from the product.
- Changing the math used for any spatial metric.

## Files touched

- `src/main/java/flash/pipeline/analyses/SpatialAnalysis.java`
- `src/main/java/flash/pipeline/analyses/wizard/SpatialAnalysisWizard.java`
- `src/test/java/flash/pipeline/analyses/wizard/SpatialAnalysisWizardTest.java`
- `src/test/java/flash/pipeline/analyses/SpatialAnalysisTest.java`

## Implementation sketch

1. Change the manual default for `doSpatialStats` to false.
2. Check wizard choices that imply "all" or "clustered" and keep explicit Ripley's K choices working.
3. Move the 3D shape and complex shape toggle creation before the advanced section.
4. Keep dependency enable/disable behavior in `updateMorphometricDependencyControls`.
5. Update readback order if any toggles moved across `PipelineDialog` boolean counters.
6. Add tests that no manual default enables Ripley's K.

## Exit gate

- Ripley's K is off by default.
- 3D shape and complex shape controls are visible outside advanced options.
- Explicit Ripley's K choices still work.
- Tests cover default off.

## Known risks

- Moving toggles without changing boolean readback can silently swap option values. Update tests around selected options, not only rendered labels.
