# Stage 02: RecorderDialog Async Sample Open

## Why

`RecorderDialog.open()` opens (and `show()`s) the sample `ImagePlus` before `dialog.pack()` / `setVisible(true)`: the blocking sample path is `src/main/java/flash/pipeline/ui/RecorderDialog.java:201-213`, and the dialog is not visible until `src/main/java/flash/pipeline/ui/RecorderDialog.java:222-229`. On a real Bio-Formats z-stack this means the user clicks "Record Filter" and the screen does nothing for several seconds. We want the dialog to paint immediately with a "Loading sample…" state, then the sample window appears and the recorder enables interaction with it.

## Prerequisites

- Read `docs/fast-ui-opening/00_overview.md`.
- `CLAUDE.md` is not present in this checkout; use `CLAUDE_CONTEXT_CONDENSED.md:228-232` for the current ImageJ quirks notes. Recorder + WindowManager interaction is sensitive.

## Read First

- `src/main/java/flash/pipeline/ui/RecorderDialog.java:57-60` — `SampleSupplier` interface.
- `src/main/java/flash/pipeline/ui/RecorderDialog.java:78-121` — public `show(...)` overloads, prior recorder-state capture at `src/main/java/flash/pipeline/ui/RecorderDialog.java:104-105`, `Session.open()` / `await()` / `shutdown()` call order at `src/main/java/flash/pipeline/ui/RecorderDialog.java:115-120`.
- `src/main/java/flash/pipeline/ui/RecorderDialog.java:135-196` — `Session` fields, constructor, initial `baseline = safeText()` at `src/main/java/flash/pipeline/ui/RecorderDialog.java:178`, and UI build.
- `src/main/java/flash/pipeline/ui/RecorderDialog.java:198-240` — current `open()` body. The blocking pre-dialog sample load is `src/main/java/flash/pipeline/ui/RecorderDialog.java:201-213`, recorder enabling is `src/main/java/flash/pipeline/ui/RecorderDialog.java:215-216`, and the existing position call is `src/main/java/flash/pipeline/ui/RecorderDialog.java:227`.
- `src/main/java/flash/pipeline/ui/RecorderDialog.java:356-368`, `src/main/java/flash/pipeline/ui/RecorderDialog.java:390-399`, `src/main/java/flash/pipeline/ui/RecorderDialog.java:407-432`, and `src/main/java/flash/pipeline/ui/RecorderDialog.java:611-628` — banner, current-image banner visibility, manual sample-open path, and geometry-dependent recorder positioning.
- `src/main/java/flash/pipeline/ui/CustomFilterEntryDialog.java:86-131` — overloads that can pass a null `sampleSupplier`, and the only production path into `RecorderDialog.show(...)` at `src/main/java/flash/pipeline/ui/CustomFilterEntryDialog.java:131`.
- `src/main/java/flash/pipeline/analyses/CreateBinFileAnalysis.java:6314-6348` and `src/main/java/flash/pipeline/analyses/CreateBinFileAnalysis.java:7056-7079` — production call sites passing non-null `sampleSupplier`.
- `src/main/java/flash/pipeline/analyses/CreateBinFileAnalysis.java:7329-7351` — supplier factories. The QC supplier currently calls `duplicateQcChannel(..., true)` at `src/main/java/flash/pipeline/analyses/CreateBinFileAnalysis.java:7347-7348`.
- `src/main/java/flash/pipeline/analyses/CreateBinFileAnalysis.java:3487-3502` — `duplicateQcChannel(...)`; when `show=true`, it runs `dup.show()` at `src/main/java/flash/pipeline/analyses/CreateBinFileAnalysis.java:3497` and `IJ.run(dup, toLutName(...), "")` at `src/main/java/flash/pipeline/analyses/CreateBinFileAnalysis.java:3498`.
- `src/main/java/flash/pipeline/analyses/CreateBinFileAnalysis.java:6590-6598`, `src/main/java/flash/pipeline/analyses/CreateBinFileAnalysis.java:6607-6614`, and `src/main/java/flash/pipeline/analyses/CreateBinFileAnalysis.java:8033-8044` — LUT application, close/flush helper, and LUT command names that must not leak into recorded macros.
- `src/main/java/flash/pipeline/io/DeferredImageSupplier.java:250-275`, `src/main/java/flash/pipeline/io/DeferredImageSupplier.java:527-543`, and `src/main/java/flash/pipeline/io/LifIO.java:213-225` — Bio-Formats virtual-stack opens are not a reliable interrupt/cancel boundary.
- `src/main/java/flash/pipeline/image/WindowManagerLock.java:5-18` — WindowManager/show/macro operations are global mutable state.
- `src/test/java/flash/pipeline/ui/RecorderDialogTest.java:7-38` — existing recorder unit coverage; add async tests beside this.

## Scope

- Reorder `RecorderDialog.open()` so:
  1. The dialog packs, sizes, and becomes visible immediately, with a banner / placeholder label stating "Loading sample image…".
  2. A `SwingWorker<ImagePlus, Void>` runs only sample preparation / duplication on its background thread.
  3. In `done()`: on the EDT, call `sample.show()` if needed, run any supplier-specific display hook, then call `allowFijiRecordingInteraction(sample)`, `WindowManager.setCurrentWindow`, `positionRecorderBesideSample(sample)`, and `updateBannerVisibility()`.
  4. If the worker throws or returns null, log via `IJ.log` (same message as today) and fall back to the no-sample banner state.
- Do **not** set `Recorder.record = true` / `Recorder.recordInMacros = true` before the worker starts. The current QC supplier path runs FLASH-owned setup commands before recorder enabling: `createQcCustomFilterSampleSupplier(...).openSample()` calls `duplicateQcChannel(..., true)` at `src/main/java/flash/pipeline/analyses/CreateBinFileAnalysis.java:7347-7348`, which calls `dup.show()` at `src/main/java/flash/pipeline/analyses/CreateBinFileAnalysis.java:3497` and `IJ.run(dup, toLutName(...), "")` at `src/main/java/flash/pipeline/analyses/CreateBinFileAnalysis.java:3498`. Turning recording on before that would record LUT setup commands such as the names returned by `src/main/java/flash/pipeline/analyses/CreateBinFileAnalysis.java:8033-8044`, which is a regression from the current order at `src/main/java/flash/pipeline/ui/RecorderDialog.java:201-216`.
- Start recording only after sample preparation and EDT display setup are complete, then reset `baseline = safeText()` immediately before setting `Recorder.record = true` and `Recorder.recordInMacros = true`. This preserves the current "record only user actions" behavior from `src/main/java/flash/pipeline/ui/RecorderDialog.java:215-216`.
- Keep `moveOwnerBehindRecordingWorkspace()` and `allowFijiRecordingInteraction(null)` at the top of `open()`. They're cheap and need to run first.
- Disable the Save / Preview / Start over controls until the sample lands. Leave Cancel, Help, and the loading banner usable. The current button locals are created at `src/main/java/flash/pipeline/ui/RecorderDialog.java:305-310`; add fields for any buttons that `setControlsEnabled(...)` must manage.
- Keep the no-sample path fast: if `sampleSupplier == null`, set `sampleLoading = false`, enable controls, start recording immediately, and keep the existing "open manually" banner behavior from `src/main/java/flash/pipeline/ui/RecorderDialog.java:407-432`.
- Keep the banner "Open sample" path consistent with the async startup path. `onOpenSample()` currently calls `sampleSupplier.openSample()` and then shows/focuses the sample at `src/main/java/flash/pipeline/ui/RecorderDialog.java:414-431`; after the supplier contract is tightened, it must reuse the same EDT display hook and reset `baseline = safeText()` after internal setup so setup commands are not included in this dialog's captured diff.

## Out Of Scope

- Changing the underlying image loading / duplication algorithm. It can still be slow; we're just hiding the slowness behind a visible dialog.
- Broad refactoring of the supplier factories in `CreateBinFileAnalysis`.
- True interruption of Bio-Formats / virtual-stack reads. `worker.cancel(true)` is only a request; Bio-Formats calls like `BF.openImagePlus(...)` at `src/main/java/flash/pipeline/io/DeferredImageSupplier.java:274`, `src/main/java/flash/pipeline/io/DeferredImageSupplier.java:542`, and `src/main/java/flash/pipeline/io/LifIO.java:224` may ignore thread interruption.
- Recorder banner visual redesign.

## Files Touched

- `src/main/java/flash/pipeline/ui/RecorderDialog.java`
- `src/main/java/flash/pipeline/analyses/CreateBinFileAnalysis.java` only if needed to make the QC `SampleSupplier` stop showing / running LUT commands inside `openSample()`; the unsafe current path is `src/main/java/flash/pipeline/analyses/CreateBinFileAnalysis.java:7347-7348` -> `src/main/java/flash/pipeline/analyses/CreateBinFileAnalysis.java:3497-3498`.
- New test: `src/test/java/flash/pipeline/ui/RecorderDialogAsyncSampleTest.java`

## Implementation Sketch

1. Add fields to `Session`: `private boolean sampleLoading`, `private SwingWorker<ImagePlus, Void> sampleWorker`, `private volatile ImagePlus openedSample`, `private volatile boolean closeRequested`, plus button fields needed by `setControlsEnabled(...)`. `sampleLoading` should be true only when `sampleSupplier != null`; a null supplier must not disable the dialog indefinitely.
2. In `open()`:
    ```java
    moveOwnerBehindRecordingWorkspace();
    allowFijiRecordingInteraction(null);
    sampleLoading = sampleSupplier != null;
    setControlsEnabled(!sampleLoading);   // new helper; leave Cancel/Help enabled
    updateBannerVisibility();
    dialog.addWindowListener(...);
    dialog.pack(); /* sizing block */ ;
    positionRecorderBesideSample(null);   // initial centered placement only
    timer.start();
    dialog.setVisible(true);
    dialog.toFront();
    dialog.requestFocus();
    if (sampleSupplier != null) launchSampleWorker();
    else { startRecordingNow(); refocusEnabledControl(); }
    ```
3. Add `startRecordingNow()`:
   - Set `baseline = safeText()` first; the constructor baseline at `src/main/java/flash/pipeline/ui/RecorderDialog.java:178` predates sample setup and must not allow setup commands into the diff.
   - Then set `Recorder.record = true` and `Recorder.recordInMacros = true`, matching the existing state flip at `src/main/java/flash/pipeline/ui/RecorderDialog.java:215-216`.
4. `launchSampleWorker()` constructs a `SwingWorker<ImagePlus, Void>`:
   - `doInBackground()` calls `sampleSupplier.openSample()` and immediately stores the return value in `openedSample`. It must not call `sample.show()`, `WindowManager.setCurrentWindow`, `updateBannerVisibility()`, or any Swing method. `updateBannerVisibility()` touches Swing components at `src/main/java/flash/pipeline/ui/RecorderDialog.java:390-399`.
   - If `isCancelled()` or `closeRequested` is true after `openSample()` returns, close/flush the returned image and return null. Use the close pattern from `src/main/java/flash/pipeline/analyses/CreateBinFileAnalysis.java:6607-6614`.
   - `done()` runs on the EDT. Retrieve with `get()` only when not cancelled; otherwise close `openedSample` if present, set `sampleLoading = false`, update the banner, and return without showing anything.
   - On success, if `sample.getWindow() == null`, call `sample.show()` on the EDT. Then run any supplier-specific display hook, `allowFijiRecordingInteraction(sample)`, and `WindowManager.setCurrentWindow(sample.getWindow())`.
   - Because `positionRecorderBesideSample(...)` reads `sample.getWindow().isShowing()`, position, width, and height at `src/main/java/flash/pipeline/ui/RecorderDialog.java:611-627`, call it after `sample.show()` and prefer a `SwingUtilities.invokeLater(...)` reposition if the sample window has just been created.
   - In `finally`, set `sampleLoading = false`, call `startRecordingNow()` unless the dialog is closed/cancelled, enable controls, update the banner, and refocus an enabled control.
5. Make the `SampleSupplier` contract explicit at `src/main/java/flash/pipeline/ui/RecorderDialog.java:57-60`: worker-time `openSample()` must prepare/return an `ImagePlus` but must not show windows or run recorded `IJ.run(...)` setup. If preserving the QC LUT requires a hook, add a Java 8 default method such as `afterSampleShown(ImagePlus sample)` and call it from `done()` before `startRecordingNow()`.
6. Factor the EDT sample-display sequence into one helper and use it from both worker `done()` and `onOpenSample()` (`src/main/java/flash/pipeline/ui/RecorderDialog.java:407-432`). The helper should run the display hook, set the current window, update/reposition, and then reset `baseline = safeText()` so any internal setup macro text is outside the captured diff.
7. Update `CreateBinFileAnalysis` only as needed for that contract: the current-image supplier at `src/main/java/flash/pipeline/analyses/CreateBinFileAnalysis.java:7329-7335` can remain a fast lookup; the QC supplier at `src/main/java/flash/pipeline/analyses/CreateBinFileAnalysis.java:7337-7351` must not call `duplicateQcChannel(..., true)` inside worker code because that reaches `src/main/java/flash/pipeline/analyses/CreateBinFileAnalysis.java:3497-3498`. Prefer returning a hidden duplicate (`show=false`) and applying LUT / positioning from the EDT hook before recording starts.
8. Keep the initial focus-request `invokeLater` block at the end of `open()` but guard the saveButton focus request so it only fires after `sampleLoading` is false. Re-fire focus-request inside worker `done()` after enabling controls.
9. New async test uses a fake `SampleSupplier` that blocks on a `CountDownLatch`, asserts `dialog.isVisible()` is true while supplier is still blocked, releases the latch, and asserts controls become enabled. Run with `Assume.assumeFalse(GraphicsEnvironment.isHeadless())`.
10. Add a regression test or manual assertion that the recorded diff does not include internal LUT commands (`Red`, `Green`, `Blue`, `Cyan`, `Magenta`, `Yellow`, `Grays` from `src/main/java/flash/pipeline/analyses/CreateBinFileAnalysis.java:8033-8044`) when opening a QC sample.

## Exit Gate

- With a blocking test `SampleSupplier`, `RecorderDialog.open()` reaches `dialog.setVisible(true)` at `src/main/java/flash/pipeline/ui/RecorderDialog.java:229` within 200 ms while the supplier latch is still blocked.
- While `sampleLoading == true`, Save / Preview / Start over are disabled, Cancel remains enabled, and the banner says "Loading sample image...". The null-supplier path from `src/main/java/flash/pipeline/ui/CustomFilterEntryDialog.java:86-113` enables controls and starts recording immediately.
- A QC sample open does not add FLASH setup commands to the captured diff: no recorded `run("Red")`, `run("Green")`, `run("Blue")`, `run("Cyan")`, `run("Magenta")`, `run("Yellow")`, or `run("Grays")` from `src/main/java/flash/pipeline/analyses/CreateBinFileAnalysis.java:8033-8044` before the user's first command.
- Closing the dialog while the worker is blocked sets `closeRequested`, calls `worker.cancel(true)`, returns without throwing, and never shows a late sample after the latch is released. If the sample object exists, it is closed/flushed using the pattern at `src/main/java/flash/pipeline/analyses/CreateBinFileAnalysis.java:6607-6614`.
- A sample that appears after async load is positioned beside the recorder after `sample.show()` has created a showing window; this verifies the geometry path at `src/main/java/flash/pipeline/ui/RecorderDialog.java:611-628`.
- New async recorder test passes; existing `RecorderDialogTest` coverage at `src/test/java/flash/pipeline/ui/RecorderDialogTest.java:7-38` still passes.
- Final verification command for this repo: `JAVA_HOME=/c/Program Files/Java/jdk-25.0.2 bash mvnw -Denforcer.skip=true package`.

## Known Risks

- ImageJ's `Recorder` is global state. Do not broaden the recording window beyond the current state flip at `src/main/java/flash/pipeline/ui/RecorderDialog.java:215-216`; setting it before worker sample prep would record FLASH's own `IJ.run(dup, toLutName(...), "")` at `src/main/java/flash/pipeline/analyses/CreateBinFileAnalysis.java:3498`.
- If `priorRecord` is already true at `src/main/java/flash/pipeline/ui/RecorderDialog.java:104-105`, external recording was already active before this dialog. Avoid trying to "fix" that in this stage; just do not introduce new early `Recorder.record = true` writes.
- WindowManager state is shared process-wide. Keep `sample.show()`, supplier display hooks, `WindowManager.setCurrentWindow(...)`, `positionRecorderBesideSample(...)`, and banner updates on the EDT; `WindowManagerLock` documents the shared-state risk at `src/main/java/flash/pipeline/image/WindowManagerLock.java:5-18`.
- `positionRecorderBesideSample(...)` depends on real sample-window geometry at `src/main/java/flash/pipeline/ui/RecorderDialog.java:611-627`. Calling it before `sample.show()` will center the recorder instead of placing it beside the image; use a post-show EDT pass.
- `updateBannerVisibility()` reads `WindowManager.getCurrentImage()` and mutates Swing components at `src/main/java/flash/pipeline/ui/RecorderDialog.java:390-399`. Never call it from `doInBackground()`.
- Cancel correctness is best-effort. `worker.cancel(true)` may not interrupt Bio-Formats virtual-stack work at `src/main/java/flash/pipeline/io/DeferredImageSupplier.java:274`, `src/main/java/flash/pipeline/io/DeferredImageSupplier.java:542`, or `src/main/java/flash/pipeline/io/LifIO.java:224`; the hard requirement is that late results are dropped/closed and never shown after dialog close.
- Test in headless mode is impossible; use `Assume.assumeFalse(GraphicsEnvironment.isHeadless())` and run an interactive smoke test for recorder/window-manager behavior.
