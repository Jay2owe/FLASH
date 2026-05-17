# Runtime Correctness Audit

Read-only audit of the segmentation-and-training stages and follow-up fixes. No build, tests, or runtime smoke were run.

## Findings

### [RESOLVED] Cellpose cell-probability dump is requested but not produced on the QC preview path

**Stage:** Stage 07 - Cellpose cellprob dump; Stage 08 - click-to-suggest filters; Stage 09/14 - RF training over Cellpose bases

**User-visible symptom:** On a Cellpose channel, enabling click-to-suggest and rerunning preview is supposed to keep cell probability data so the suggester can propose a better `cellprob` threshold. The UI can still suggest diameter changes, but the documented cell-probability-threshold suggestion path is effectively starved because the preview label image never gets the cellprob sidecar attached. RF training over a Cellpose base also loses that cellprob feature and falls back to missing/NaN feature values.

**Root cause:** The UI does set `dumpCellprob` when the suggest panel is enabled (`src/main/java/flash/pipeline/ui/config/CellposeParameterStage.java:1633`, `src/main/java/flash/pipeline/ui/config/CellposeParameterStage.java:1647`) and passes it into the preview runner (`src/main/java/flash/pipeline/analyses/CreateBinFileAnalysis.java:6712`, `src/main/java/flash/pipeline/analyses/CreateBinFileAnalysis.java:6720`). The runner only adds Cellpose CLI `--save_flows` (`src/main/java/flash/pipeline/cellpose/Cellpose3DRunner.java:414`, `src/main/java/flash/pipeline/cellpose/Cellpose3DRunner.java:415`), then tries to read `cellpose_input_cellprob.tif` (`src/main/java/flash/pipeline/cellpose/Cellpose3DRunner.java:116`, `src/main/java/flash/pipeline/cellpose/Cellpose3DRunner.java:118`, `src/main/java/flash/pipeline/cellpose/Cellpose3DRunner.java:601`). The file name it expects is written by the persistent helper script (`src/main/resources/flash/pipeline/cellpose/cellpose_loop.py:116`, `src/main/resources/flash/pipeline/cellpose/cellpose_loop.py:121`), but the QC preview path does not use that helper. Stage 07 explicitly promised enabling the dump for click-suggest and RF training (`docs/segmentation-and-training/07_cellpose_cellprob_dump.md:68`, `docs/segmentation-and-training/07_cellpose_cellprob_dump.md:74`).

**Fix sketch:** Make the one-shot `Cellpose3DRunner` path produce the same sidecar contract it later reads. Either route Cellpose QC/RF calls through `CellposePersistentWorker` when `dumpCellprob` is true, or update the one-shot runner to read the actual Cellpose `--save_flows` output and extract/write a real `_cellprob.tif` before calling `readCellprobImage`.

**Resolution:** RESOLVED in commit `355381b` by routing `dumpCellprob=true` runs through the existing persistent Cellpose worker, which writes `cellpose_input_cellprob.tif`, then attaching that sidecar to the label image for click suggestions and RF feature extraction. The one-shot CLI path remains unchanged for runs that do not request cellprob.

**Confidence:** MEDIUM (the Java path is fully traced; the exact external Cellpose CLI flow-file naming was not executed in this audit).

### [RESOLVED] Reopening the initial segmentation setup can overwrite `trained_rf:` channels as Classical

**Stage:** Stage 14 - Train Custom Engine wizard; Priority A2 trained RF picker round-trip

**User-visible symptom:** A channel trained through the wizard can reload correctly in the later QC method picker, but if the user reopens the initial channel setup dialog and confirms it, the trained engine is shown as Classical and the saved `trained_rf:<key>:base=<base>` token can be replaced with `classical`.

**Root cause:** The initial setup segmentation choices only include Classical, Enhanced Classical, StarDist, and Cellpose (`src/main/java/flash/pipeline/analyses/CreateBinFileAnalysis.java:214`, `src/main/java/flash/pipeline/analyses/CreateBinFileAnalysis.java:220`). Its default-selection mapper recognizes `enhanced_classical`, `stardist`, and `cellpose`, then falls back to Classical for everything else (`src/main/java/flash/pipeline/analyses/CreateBinFileAnalysis.java:8309`, `src/main/java/flash/pipeline/analyses/CreateBinFileAnalysis.java:8315`). After the dialog closes, it applies that displayed selection back into `cfg.segmentationMethods` (`src/main/java/flash/pipeline/analyses/CreateBinFileAnalysis.java:649`, `src/main/java/flash/pipeline/analyses/CreateBinFileAnalysis.java:652`), and the fallback branch writes `classical` (`src/main/java/flash/pipeline/analyses/CreateBinFileAnalysis.java:8468`). Stage 14 promised that wizard success writes a concrete method token and updates the picker (`docs/segmentation-and-training/14_train_custom_engine_wizard.md:39`, `docs/segmentation-and-training/14_train_custom_engine_wizard.md:44`), but this earlier setup surface does not preserve that token.

**Fix sketch:** Teach the initial setup dialog to recognize `trained_rf:` tokens. The safest UI is a disabled or explicit "Trained RF: <model>" choice that preserves the existing token unless the user deliberately switches away. At minimum, `segmentationChoiceForMethod` and `applySegmentationSelection` should round-trip unknown trained engine tokens instead of collapsing them to Classical.

**Resolution:** RESOLVED by adding a per-channel `Trained RF: <model>` setup choice for existing `trained_rf:` tokens, using the model catalog name when available, and preserving the exact saved token when that choice is confirmed. Selecting Classical still deliberately writes `classical`.

**Confidence:** HIGH (the dialog defaulting and write-back path is traced end to end).

### [MAJOR][RESOLVED] Missing-model repair UI is only built at stage construction, not after manager edits

**Stage:** Stage 12 - QC dialog model selectors

**User-visible symptom:** The documented manual workflow says: select a custom model, delete it in Manage Models, close the manager, and the parameter stage should show a missing-model banner with a replacement picker and manager shortcut. In the current runtime path, the dropdown is refreshed and an error is set, but the missing-model banner/replacement row is not added until the stage is rebuilt.

**Root cause:** StarDist computes `missingModelKey` during `buildControls` and only adds the banner there (`src/main/java/flash/pipeline/ui/config/StarDistParameterStage.java:220`, `src/main/java/flash/pipeline/ui/config/StarDistParameterStage.java:231`). After Manage Models closes, `refreshModelOptionsFromCatalog` only refreshes the combo and sets an error when the selected key vanished (`src/main/java/flash/pipeline/ui/config/StarDistParameterStage.java:993`, `src/main/java/flash/pipeline/ui/config/StarDistParameterStage.java:1020`); it never inserts `buildMissingModelNoticeRow()` into the live panel. Cellpose has the same pattern: build-time-only banner (`src/main/java/flash/pipeline/ui/config/CellposeParameterStage.java:295`, `src/main/java/flash/pipeline/ui/config/CellposeParameterStage.java:306`) and refresh-time error only (`src/main/java/flash/pipeline/ui/config/CellposeParameterStage.java:1996`, `src/main/java/flash/pipeline/ui/config/CellposeParameterStage.java:2023`). The promised workflow is explicit in Stage 12 (`docs/segmentation-and-training/12_qc_dialog_model_selectors.md:62`, `docs/segmentation-and-training/12_qc_dialog_model_selectors.md:73`) and its manual check (`docs/segmentation-and-training/12_qc_dialog_model_selectors.md:111`).

**Fix sketch:** Keep a reference to the parameter-stage container and a stable placeholder for the missing-model row. After catalog refresh, update/remove/insert that row, refresh the replacement combo, then `revalidate()` and `repaint()`. Apply the same controller behavior to StarDist and Cellpose.

**Resolution:** RESOLVED by adding stable missing-model notice containers to the StarDist and Cellpose parameter stages. Catalog refresh now rebuilds or clears the live notice row after the model dropdown is repopulated, so deleting the selected model in Manage Models immediately exposes the replacement picker without rebuilding the stage. The reload path still shows the banner for already-missing saved model keys.

**Confidence:** HIGH (the manager close to refresh path is traced in both stages).

### [MAJOR][RESOLVED] A just-clicked marker can be lost if Fiji closes before the async daemon writer runs

**Stage:** Stage 03 - Click Capture Foundation

**User-visible symptom:** A user can click an object, see the marker appear immediately, then close the bin or quit Fiji quickly and find the last click missing on reopen.

**Root cause:** Stage 03 promised persisted sidecar JSON (`docs/segmentation-and-training/03_click_capture_foundation.md:5`, `docs/segmentation-and-training/03_click_capture_foundation.md:144`). The click handler mutates the in-memory store and schedules a write (`src/main/java/flash/pipeline/ui/preview/PreviewPairPanel.java:1391`, `src/main/java/flash/pipeline/ui/preview/PreviewPairPanel.java:1410`). That write is queued on a single daemon executor (`src/main/java/flash/pipeline/ui/preview/PreviewPairPanel.java:48`, `src/main/java/flash/pipeline/ui/preview/PreviewPairPanel.java:52`) and performs the disk write later (`src/main/java/flash/pipeline/ui/preview/PreviewPairPanel.java:1425`, `src/main/java/flash/pipeline/ui/preview/PreviewPairPanel.java:1432`). I found no synchronous flush on stage leave/dialog close in this path, so JVM shutdown can discard the queued daemon task.

**Fix sketch:** Add an explicit flush boundary when leaving a QC stage, closing the config dialog, or applying/locking a click-dependent stage. The minimal fix is a synchronous `ClicksConfigIO.write(binFolder, store)` on close/leave, with the async writer remaining only for responsive in-session updates.

**Resolution:** Fixed in commit `b3df2f0460c488da695d6d34319746bc6ae0bdab`. `PreviewPairPanel.flushClicksSync()` drains the async daemon writer with `shutdown()`/`awaitTermination(...)` and synchronously writes dirty click state. `clearClickCapture()`, QC stage leave, and dialog close invoke the flush before click capture state is detached. Covered by `PreviewPairPanelClickFlushTest`.

**Confidence:** MEDIUM (the async writer is traced; the exact close timing was not exercised).

### [MINOR][RESOLVED] Cellpose missing-model banner lacks the promised Manage Models shortcut

**Stage:** Stage 12 - QC dialog model selectors; Stage 05 - Cellpose model selection

**User-visible symptom:** A Cellpose channel whose saved model key is missing shows a replacement dropdown, but the missing-model banner itself does not include the promised "Open Manage models..." action. Users can still reach Manage Models from the normal model row, so this is an affordance mismatch rather than a hard blocker.

**Root cause:** The Stage 12 banner spec includes both replacement and manager actions (`docs/segmentation-and-training/12_qc_dialog_model_selectors.md:66`, `docs/segmentation-and-training/12_qc_dialog_model_selectors.md:72`). StarDist's missing row adds the manager button (`src/main/java/flash/pipeline/ui/config/StarDistParameterStage.java:539`, `src/main/java/flash/pipeline/ui/config/StarDistParameterStage.java:553`). Cellpose's missing row only adds the warning and replacement combo (`src/main/java/flash/pipeline/ui/config/CellposeParameterStage.java:577`, `src/main/java/flash/pipeline/ui/config/CellposeParameterStage.java:603`).

**Fix sketch:** Add the same `Open Manage models...` button to `CellposeParameterStage.buildMissingModelNoticeRow()` and wire it to `openModelManager()`, matching StarDist.

**Resolution:** RESOLVED by adding the `Open Manage models...` button to the Cellpose missing-model banner, using the same placement and action as the StarDist banner. A regression test verifies that the missing-model row exposes the button and that clicking it invokes the model manager launcher for the Cellpose engine.

**Confidence:** HIGH (the two banner implementations are directly compared).

### [RESOLVED] Train Custom Engine wizard is launched without the QC dialog as owner

**Stage:** Stage 14 - Train Custom Engine wizard

**User-visible symptom:** The wizard opens, but it is not owned by the QC dialog. On some Swing window managers this can mean poorer centering/focus behavior and a child dialog that does not stay visually tied to the active QC workflow.

**Root cause:** The method-stage launcher calls `TrainCustomEngineWizard.show(null, workflow)` (`src/main/java/flash/pipeline/analyses/CreateBinFileAnalysis.java:6218`). The wizard passes that owner into each `PipelineDialog` step (`src/main/java/flash/pipeline/ui/wizard/TrainCustomEngineWizard.java:50`, `src/main/java/flash/pipeline/ui/wizard/TrainCustomEngineWizard.java:59`, `src/main/java/flash/pipeline/ui/wizard/TrainCustomEngineWizard.java:91`). `PipelineDialog` creates an unowned modal dialog when owner is null, and an owned application-modal dialog otherwise (`src/main/java/flash/pipeline/ui/PipelineDialog.java:118`, `src/main/java/flash/pipeline/ui/PipelineDialog.java:121`).

**Fix sketch:** Thread the QC window owner into `SegmentationMethodStage.TrainCustomEngineLauncher` or expose it on `ConfigQcContext`, then call `TrainCustomEngineWizard.show(owner, workflow)`. This keeps the wizard centered and focus-owned by the active QC dialog.

**Resolution:** RESOLVED by storing the active QC dialog window on `ConfigQcContext` and passing that owner through the Create Bin Train Custom Engine launcher into `TrainCustomEngineWizard.show(owner, workflow)`.

**Confidence:** MEDIUM (the owner data flow is traced; platform-specific focus behavior was not exercised).

## Summary

0 critical / 0 unresolved major / 0 unresolved minor. All six findings in this audit are marked RESOLVED.
