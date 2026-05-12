# Classical Segmentation Merge - Overview

## Purpose

Merge the two Classical object setup screens into one stage inside Set Up Configuration:

- current Classical flow: `ChannelThresholdStage` then `ParticleSizeStage`
- target Classical flow: one `ClassicalSegmentationStage`

StarDist and Cellpose already use one method-specific stage. Classical should do the same, but it needs two different previews:

- live threshold preview
- explicit generated-object preview

## Accepted UI Decision

The normal two-pane preview must not show raw or filtered input without thresholding.

Normal view:

```text
+-----------------------------+-----------------------------+
| Threshold preview           | Object preview              |
| live threshold applied      | generated label map         |
+-----------------------------+-----------------------------+
```

Large view is where the user can inspect the input without the threshold:

```text
+-----------------------------+-----------------------------+-----------------------------+
| Original or filtered source | Threshold preview           | Object preview              |
| no threshold applied        | live threshold applied      | generated label map         |
+-----------------------------+-----------------------------+-----------------------------+
```

There must be no threshold preview mode selector in the merged Classical stage. The threshold preview is always the threshold-applied view.

## Target Controls

```text
Segmentation Method
Current: Classical                                      [Change Segmentation Method]

Threshold
Lower [----------|------] 42    Upper [----------------|] 255
Method [Default]  Background [Dark]                    [Auto] [Reset]

Objects
Min size [100]   Max size [Infinity]                   [Run Object Preview] [Reset sizes]

Status: Threshold 42. Object preview is out of date.
```

`Run Object Preview` runs 3D Objects Counter with the current threshold and size fields. It must not run while the threshold slider is being dragged.

## Behaviour Rules

- Threshold changes update the left pane immediately.
- Threshold changes mark the right-pane object preview stale.
- Size changes mark the right-pane object preview stale.
- `Run Object Preview` regenerates the right pane.
- `Lock in & Next` saves both threshold and particle-size settings together.
- `Restart` keeps unsaved in-progress threshold and size edits, matching the existing restart behaviour of the separate stages.
- `Skip` leaves the saved threshold and size unchanged for that image.
- Method switching remains available through the compact `Change Segmentation Method` row.

## Current Code Baseline

Relevant files:

- `src/main/java/flash/pipeline/analyses/CreateBinFileAnalysis.java`
- `src/main/java/flash/pipeline/ui/config/ChannelThresholdStage.java`
- `src/main/java/flash/pipeline/ui/config/ParticleSizeStage.java`
- `src/main/java/flash/pipeline/ui/config/SegmentationMethodStage.java`
- `src/main/java/flash/pipeline/ui/preview/PreviewPairPanel.java`
- `src/main/java/flash/pipeline/ui/preview/LargePreviewDialog.java`

Current routing in `CreateBinFileAnalysis.interactiveSegmentationObjectQC(...)` adds:

1. `SegmentationMethodStage`
2. Classical-only `ChannelThresholdStage`
3. Classical-only `ParticleSizeStage`
4. StarDist-only `StarDistParameterStage`
5. Cellpose-only `CellposeParameterStage`
6. optional AI threshold stage

The target routing replaces steps 2 and 3 with one Classical-only `ClassicalSegmentationStage`.

## Implementation Order

| File | Goal |
|---|---|
| `01_classical_segmentation_stage.md` | Add the merged Classical stage and its state model. |
| `02_preview_and_large_view.md` | Wire the fixed normal preview and large-view input inspection. |
| `03_routing_tests_verification.md` | Replace the routing, add tests, and verify the UI. |

## Out Of Scope

- Changing 3D Objects Counter output semantics.
- Changing StarDist or Cellpose stage behaviour.
- Removing standalone `ChannelThresholdStage`; it is still used outside this merged Classical flow.
- Adding a threshold preview selector. The merged Classical threshold preview is fixed.
