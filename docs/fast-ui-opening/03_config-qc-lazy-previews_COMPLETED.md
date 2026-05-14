# Stage 03: Config QC Lazy Preview Init

## Why

`ConfigQcDialog` constructs a horizontal `PreviewPairPanel` in its constructor before the dialog is shown (`src/main/java/flash/pipeline/ui/config/ConfigQcDialog.java:121-132`). That pair constructs two `ImagePreviewPanel`s (`src/main/java/flash/pipeline/ui/preview/PreviewPairPanel.java:139-146`), and each preview currently calls `refresh()` at the end of its constructor (`src/main/java/flash/pipeline/ui/preview/ImagePreviewPanel.java:63-104`).

Source review shows the original worry is partly overblown. When no image is set, `refresh()` only resets labels/slider state and queues `canvas.repaint()` (`src/main/java/flash/pipeline/ui/preview/ImagePreviewPanel.java:217-225`). The expensive image path is later, in `CanvasPanel.paintComponent()` calling `currentProcessor()` and then `processor.createImage()` (`src/main/java/flash/pipeline/ui/preview/ImagePreviewPanel.java:382-437`, `src/main/java/flash/pipeline/ui/preview/ImagePreviewPanel.java:533-542`). This stage should therefore remove constructor-time refresh/repaint churn and make first real render image-driven, not treat empty `refresh()` as a major processing blocker.

## Prerequisites

- Read `docs/fast-ui-opening/00_overview.md`.
- Skim Stage 01 if not already done for the "paint immediately, defer expensive work" mindset. Do not add background threading in this stage.
- Keep Java 8 compatibility; use existing Swing patterns already in these classes.

## Read First

- `src/main/java/flash/pipeline/ui/config/ConfigQcDialog.java:121-153` - constructor order: preview pair construction, `buildContent()`, then `enterFirstApplicableStage()`.
- `src/main/java/flash/pipeline/ui/config/ConfigQcDialog.java:210-263` - `buildContent()` / `buildMain()` layout; no paint-time sizing dependency is visible here.
- `src/main/java/flash/pipeline/ui/config/ConfigQcDialog.java:350-389` - `rebuildCurrentStage()` assigns the original image before `showDialog()` packs and shows the dialog.
- `src/main/java/flash/pipeline/ui/config/ConfigQcDialog.java:415-495` - layout refresh and expandable split sizing; useful for spotting flicker/regression risk.
- `src/main/java/flash/pipeline/ui/preview/ImagePreviewPanel.java:63-104` - constructor, listener setup, trailing `refresh()`.
- `src/main/java/flash/pipeline/ui/preview/ImagePreviewPanel.java:151-164` - `setImage(...)` is the correct first real render trigger.
- `src/main/java/flash/pipeline/ui/preview/ImagePreviewPanel.java:217-249` - `refresh()` no-image branch versus image branch.
- `src/main/java/flash/pipeline/ui/preview/ImagePreviewPanel.java:299-311` - existing `hasUsableImage(...)`; do not add a duplicate image field.
- `src/main/java/flash/pipeline/ui/preview/ImagePreviewPanel.java:526-542` - paint path; no-image paint is already cheap, image paint is where processor work starts.
- `src/main/java/flash/pipeline/ui/preview/PreviewPairPanel.java:139-161` - constructor pair-init, slim mode, listener wiring, and initial adjusted-state repaint.
- `src/main/java/flash/pipeline/ui/preview/PreviewPairPanel.java:163-184` - `setOriginal(...)` / `setAdjusted(...)` image assignment paths.
- `src/main/java/flash/pipeline/ui/preview/PreviewPairPanel.java:426-442` - shared Z row listener is guarded while programmatic values are applied.
- `src/main/java/flash/pipeline/ui/preview/PreviewPairPanel.java:795-965` - object overlay/source/display listeners; check for programmatic init events before adding broad guards.
- `src/main/java/flash/pipeline/ui/preview/PreviewPairPanel.java:1097-1134` - shared Z application and slider refresh.
- `src/main/java/flash/pipeline/ui/preview/PreviewPairPanel.java:1259-1276` - adjusted preview image selection, including overlay generation.
- `src/main/java/flash/pipeline/ui/preview/LargePreviewDialog.java:39-83` - three `ImagePreviewPanel` fields are built and the dialog packs before images are applied.
- `src/main/java/flash/pipeline/ui/preview/LargePreviewDialog.java:139-150` - `setImages(...)` is the large-dialog first real image path.
- `src/main/java/flash/pipeline/ui/preview/LargePreviewDialog.java:281-299` - extra preview is cleared with `setImage(null)`, so no-image clear behavior must stay correct.
- `src/main/java/flash/pipeline/ui/preview/ObjectSizeFilterPreview.java:97-148`, `src/main/java/flash/pipeline/ui/preview/ObjectSizeFilterPreview.java:150-180`, `src/main/java/flash/pipeline/ui/preview/ObjectSizeFilterPreview.java:183-227` - static object-map helpers; they are not preview-panel construction, but they can still be reached after lazy changes.
- `src/test/java/flash/pipeline/ui/preview/ImagePreviewPanelTest.java:32-41` - existing empty-panel default assertions.
- `src/test/java/flash/pipeline/ui/preview/PreviewPairPanelTest.java:30-51`, `src/test/java/flash/pipeline/ui/preview/PreviewPairPanelTest.java:53-99` - slim layout and shared-Z regression coverage.

## Scope

- Remove the eager constructor `refresh()` from `ImagePreviewPanel` (`src/main/java/flash/pipeline/ui/preview/ImagePreviewPanel.java:103`), but leave the constructor in a correct empty state: title text, blank detail/status/slice labels, slider range `1..1`, and Z slider disabled.
- Use the existing `private ImagePlus image` and `hasUsableImage(...)` (`src/main/java/flash/pipeline/ui/preview/ImagePreviewPanel.java:49`, `src/main/java/flash/pipeline/ui/preview/ImagePreviewPanel.java:299-311`). Do not add a parallel `currentImage` field.
- Keep `refresh()` meaningful for `setImage(null)`. `PreviewPairPanel.clearImages()` calls `originalPreview.setImage(null)` and `adjustedPreview.setImage(null)` (`src/main/java/flash/pipeline/ui/preview/PreviewPairPanel.java:186-222`); suppressing all no-image refresh work would leave stale labels, slider state, or pixels after a clear.
- Confirm that the first usable image still triggers a full preview update through `ImagePreviewPanel.setImage(...)` (`src/main/java/flash/pipeline/ui/preview/ImagePreviewPanel.java:151-164`) and `PreviewPairPanel.setOriginal(...)` / `setAdjusted(...)` (`src/main/java/flash/pipeline/ui/preview/PreviewPairPanel.java:163-184`).
- Treat listener races as a verification item, not an assumed bug. The currently visible programmatic slider update is guarded (`src/main/java/flash/pipeline/ui/preview/PreviewPairPanel.java:436-441`, `src/main/java/flash/pipeline/ui/preview/PreviewPairPanel.java:1120-1133`), and display-control listeners are wired after initial combo-box selection (`src/main/java/flash/pipeline/ui/preview/PreviewPairPanel.java:867-874`, `src/main/java/flash/pipeline/ui/preview/PreviewPairPanel.java:930-955`).
- Verify Config QC layout stability. `buildContent()` does not rely on the preview being painted, and `rebuildCurrentStage()` assigns the original image before `showDialog()` calls `pack()` (`src/main/java/flash/pipeline/ui/config/ConfigQcDialog.java:350-389`, `src/main/java/flash/pipeline/ui/config/ConfigQcDialog.java:182-189`).

## Out Of Scope

- Threading the actual image load. If `setImage` is being called with an already-loaded `ImagePlus` reference, it is near-free; only background it if profiling shows it dominates.
- Redesigning the QC preview layout.
- Changing `ObjectSizeFilterPreview`; it is a static helper, not a Swing preview panel (`src/main/java/flash/pipeline/ui/preview/ObjectSizeFilterPreview.java:17-227`).
- Changing `LargePreviewDialog` in this stage. It has a similar eager `ImagePreviewPanel` construction pattern (`src/main/java/flash/pipeline/ui/preview/LargePreviewDialog.java:39-83`), but it is opened by the "Large view" button after Config QC is already visible (`src/main/java/flash/pipeline/ui/preview/PreviewPairPanel.java:967-984`). Note it as a follow-up if the shared `ImagePreviewPanel` change proves useful and safe.

## Files Touched

- `src/main/java/flash/pipeline/ui/preview/ImagePreviewPanel.java`
- `src/main/java/flash/pipeline/ui/preview/PreviewPairPanel.java` only if verification finds a real constructor-time listener race or a narrow test hook is needed.
- `src/main/java/flash/pipeline/ui/config/ConfigQcDialog.java` only if visual smoke reveals a layout dependency; source review suggests no production edit is needed.
- Test update: extend `ImagePreviewPanelTest` first, and add `PreviewPairPanelTest` coverage only for pair-level listener/layout behavior.

## Implementation Sketch

1. In `ImagePreviewPanel`, remove the constructor's trailing `refresh()` call (`src/main/java/flash/pipeline/ui/preview/ImagePreviewPanel.java:103`).
2. Replace it with cheap empty-state initialization that does not queue a canvas repaint. At minimum, ensure the Z slider is disabled and clamped to `1..1`; the labels already start with safe empty text (`src/main/java/flash/pipeline/ui/preview/ImagePreviewPanel.java:40-45`).
3. Do not make `refresh()` a blanket no-op when no image exists. Its no-image branch currently clears title/detail/slice state and disables the slider (`src/main/java/flash/pipeline/ui/preview/ImagePreviewPanel.java:217-225`), which is required when callers clear a previously displayed image.
4. If repaint suppression needs state, track whether a usable image has ever been displayed or pass an internal `repaintCanvas` flag. The constructor path may skip the repaint; `setImage(null)` after a real image must repaint so stale pixels disappear.
5. Keep the paint guard simple. `CanvasPanel.paintComponent()` already reaches `currentProcessor()`, which returns `null` for no usable image (`src/main/java/flash/pipeline/ui/preview/ImagePreviewPanel.java:382-385`, `src/main/java/flash/pipeline/ui/preview/ImagePreviewPanel.java:533-540`). An explicit `hasUsableImage()` check at the top of paint is fine for clarity, but the main guard belongs around constructor-time repaint scheduling, not around `refresh()` as a whole.
6. In `PreviewPairPanel`, verify before editing: constructor wiring currently creates controls, then wires listeners, then sets adjusted state (`src/main/java/flash/pipeline/ui/preview/PreviewPairPanel.java:139-161`). Add a `ready` flag only if a test or debugger confirms a specific listener fires during initialization; otherwise leave listener code alone.
7. Keep `ConfigQcDialog` unchanged unless smoke testing proves otherwise. Preferred sizes are set on the preview canvas before paint (`src/main/java/flash/pipeline/ui/preview/ImagePreviewPanel.java:82`, `src/main/java/flash/pipeline/ui/preview/ImagePreviewPanel.java:132-149`), and Config QC assigns the original image before showing (`src/main/java/flash/pipeline/ui/config/ConfigQcDialog.java:350-389`).
8. Tests:
   - Extend `ImagePreviewPanelTest.emptyPanelUsesSafeDefaults()` (`src/test/java/flash/pipeline/ui/preview/ImagePreviewPanelTest.java:32-41`) so construction still leaves a disabled Z slider and no usable image after removing `refresh()`.
   - Add a clear-after-image assertion: set a real image, then `setImage(null)`, and assert title/slider/rendered processor return to empty state.
   - Avoid exact Swing paint-count assertions. They are brittle across look and feels (Swing themes), headless mode, and event-dispatch timing. If a hook is needed, count deterministic render/repaint requests inside `ImagePreviewPanel`, not actual OS paint calls.
   - Run pair-level tests that cover slim layout and shared Z after the change (`src/test/java/flash/pipeline/ui/preview/PreviewPairPanelTest.java:30-99`).

## Exit Gate

- `ImagePreviewPanel` construction no longer calls `refresh()` or queues a constructor-time canvas repaint.
- Empty construction still reports no image, current Z `1`, slice count `1`, disabled Z slider, and title `"No image selected."`.
- First usable `setImage(...)` still updates title/detail/Z controls and makes `renderedProcessorForTest()` return the expected processor.
- `setImage(null)` after an image fully clears the preview state and repaints the canvas so stale pixels are not left behind.
- `PreviewPairPanel` construction does not reenter image refresh through shared-Z, object-overlay, source-mode, or display-control listeners.
- Config QC opens without preview-area resize flicker; channel switches, Z slider changes, preview stale/running/ready states, and overlay controls still behave as before.
- Large preview still opens and receives images correctly, because it shares `ImagePreviewPanel` (`src/main/java/flash/pipeline/ui/preview/LargePreviewDialog.java:139-150`).
- Focused tests pass: `ImagePreviewPanelTest`, `PreviewPairPanelTest`, and `LargePreviewDialogTest`.
- Full verification for the implementer: `bash mvnw -Denforcer.skip=true test` and `bash mvnw -Denforcer.skip=true package`.

## Known Risks

- The performance win may be smaller than the original estimate. No-image `refresh()` is mostly label/slider work plus a queued repaint, while heavy processor work starts only after an image is available.
- Removing constructor `refresh()` can accidentally leave the Z slider enabled or labels inconsistent if the replacement empty-state initialization is incomplete.
- Over-suppressing no-image repaint can leave stale pixels after `clearImages()` or `LargePreviewDialog.configurePreviewPanel(false)` clears previews (`src/main/java/flash/pipeline/ui/preview/PreviewPairPanel.java:186-222`, `src/main/java/flash/pipeline/ui/preview/LargePreviewDialog.java:281-299`).
- Layout regressions are possible if preferred sizes change. Do not alter `DEFAULT_CANVAS_SIZE`, `SLIM_CANVAS_SIZE`, or Config QC split sizing in this stage (`src/main/java/flash/pipeline/ui/preview/ImagePreviewPanel.java:31-32`, `src/main/java/flash/pipeline/ui/config/ConfigQcDialog.java:438-495`).
- Listener races are possible, but add the narrowest guard that matches evidence. Programmatic shared-Z updates already use `updatingSharedZSlider` (`src/main/java/flash/pipeline/ui/preview/PreviewPairPanel.java:1120-1133`), and `ImagePreviewPanel` slider updates already use `updatingSlider` (`src/main/java/flash/pipeline/ui/preview/ImagePreviewPanel.java:92-103`, `src/main/java/flash/pipeline/ui/preview/ImagePreviewPanel.java:362-370`).
- Paint-count tests are flaky across look and feels, headless mode, and event-dispatch scheduling. Prefer assertions on preview state and deterministic test hooks over exact paint counts.
