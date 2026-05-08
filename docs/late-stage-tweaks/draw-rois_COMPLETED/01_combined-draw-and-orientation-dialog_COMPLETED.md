# Combined Draw And Orientation Dialog

## Why this stage exists

The draw ROI prompt and orientation controls should be in the same dialog so the user sees both decisions together.

## Prerequisites

- Read `src/main/java/flash/pipeline/analyses/DrawAndSaveROIsAnalysis.java:295`.
- Read `src/main/java/flash/pipeline/ui/RoiOrientationPanel.java:27`.
- Read tests in `src/test/java/flash/pipeline/ui/RoiOrientationPanelTest.java`.

## Read first

- `DrawAndSaveROIsAnalysis` currently creates `RoiOrientationPanel` near the image, then shows a separate `WaitForUserDialog("Draw ROI", ...)`.
- `RoiOrientationPanel` applies orientation decisions by calling back into the analysis.
- The combined dialog must remain non-destructive: users still draw/edit ROI shapes in ImageJ before confirming.

## Scope

- Replace the separate orientation panel plus draw prompt with one modeless or modal-safe dialog that contains both the drawing instruction and orientation buttons.
- Keep the same orientation decisions and callback behavior.
- Make the final confirmation button clearly lock in the drawn ROI set.
- Ensure the dialog closes cleanly on cancel, skip, or image completion.
- Update tests for the new combined component.

## Out of scope

- Changing ROI naming rules.
- Changing zip or CSV save formats.
- Moving orientation manifest output paths; that belongs to `docs/late-stage-tweaks/outputs/`.

## Files touched

- `src/main/java/flash/pipeline/analyses/DrawAndSaveROIsAnalysis.java`
- `src/main/java/flash/pipeline/ui/RoiOrientationPanel.java`, or a replacement combined component
- `src/test/java/flash/pipeline/analyses/DrawAndSaveROIsAnalysisTest.java`
- `src/test/java/flash/pipeline/ui/RoiOrientationPanelTest.java`

## Implementation sketch

1. Extract the reusable orientation button panel from `RoiOrientationPanel` if useful.
2. Build a combined `JDialog` with instruction text, orientation buttons, and one confirmation action.
3. Keep it positioned near the active image like the current orientation panel.
4. Wire orientation button clicks to the same `saveOrientationDecision` path.
5. Replace the `WaitForUserDialog` call with the combined dialog result.
6. Update cleanup so the dialog is disposed in every exit path.

## Exit gate

- Users see draw ROI instructions and orientation controls in one dialog.
- Orientation choices are still saved.
- Existing ROI drawing and saving behavior still works.
- Tests cover at least one orientation decision through the combined UI path.

## Known risks

- A fully modal dialog can block ROI editing in ImageJ. If modal behavior blocks drawing, use a modeless dialog with a blocking wait mechanism similar to the current implementation.
