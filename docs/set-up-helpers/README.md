# Set Up Configuration Helpers

This folder tracks the confirmed wording for the blue question-mark helper buttons that will be added to Set Up Configuration stages.

Workflow:

1. Draft one stage at a time.
2. Mark it confirmed only after Jamie approves the wording.
3. Implement the helper buttons from the confirmed text.

Writing preferences:

- Keep each helper concise.
- Lead with the main choice the user is making.
- For option-heavy controls, explain the main control once, then list each option with when to choose it.
- Put follow-up dialog help in separate helper text shown only on that follow-up window.
- Use the exact front-end labels the user sees.

Status key:

- Draft: proposed wording, not approved yet.
- Confirmed: approved wording ready to implement.
- Merged: wording belongs inside another helper rather than a standalone helper.
- Implemented: wired into the UI and tested.

Current stage order found in the code:

| Order | Stage | Code location | Status |
| --- | --- | --- | --- |
| 01 | Channel Identity / Channel Setup | `CreateBinFileAnalysis.collectBinConfigFromUser` | Implemented |
| 02 | Analysis Scope | `CreateBinFileAnalysis.showAnalysisScopeDialog` | Implemented |
| 03 | Z-Slice Subset | `ZSliceSelectionStage` | Implemented |
| 04 | Settings Mode | `CreateBinFileAnalysis.showGranularCustomFork` | Implemented |
| 05 | Select Images for Quality Check | `CreateBinFileAnalysis.openImagesForQC` | Implemented |
| 06 | Segmentation Method | `SegmentationMethodStage` | Implemented |
| 07 | Classical Object Segmentation | `ClassicalSegmentationStage` for classical channels | Implemented |
| 08 | StarDist Object Segmentation | `StarDistParameterStage` | Implemented |
| 09 | Cellpose Object Segmentation | `CellposeParameterStage` | Implemented |
| 10 | Set Filter and Parameters | `FilterParameterStage` | Implemented |
| 11 | Display Range | `DisplayRangeStage` | Implemented |
| 12 | Channel Threshold | `ChannelThresholdStage` for ROI/intensity thresholds | Implemented |
| 13 | Particle Size | `ParticleSizeStage` for classical object channels | Merged into 07 |

Notes:

- Classical, StarDist, and Cellpose will share a short object-segmentation introduction, but each needs its own method-specific parameter descriptions.
- Classical helper text owns the merged threshold and particle-size screen.
- Display ranges need to explicitly say they control saved display scaling and are reused for presentation-ready images from the main UI.
- Implementation lives in `SetupHelpCatalog`, `SetupHelpDialog`, `PipelineDialog.addSetupHelpHeader`, `PipelineDialog.addSetupHelpSubHeader`, and `ConfigQcStage.helpTopic()`.
