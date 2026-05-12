# 02 - Preview And Large View

## Goal

Make the merged Classical stage preview match the agreed UI:

- normal left pane: threshold preview
- normal right pane: object preview
- large view: source without threshold, threshold preview, object preview

The user should not need, or see, a normal-view source toggle.

## Normal Preview Contract

Set preview titles when entering the stage:

```text
Left title:  Threshold preview
Right title: Object preview
```

Normal pane wiring:

```java
preview.setOriginal(thresholdPreview);
preview.setAdjusted(labelPreview);
```

This is intentionally different from `ParticleSizeStage`, `StarDistParameterStage`, and `CellposeParameterStage`, where the left pane is usually raw or filtered source.

## Toolstrip Rules

For the merged Classical stage:

- hide source toggle
- hide threshold preview mode selector entirely
- keep `Large view`
- keep brightness/contrast controls if they help inspect the threshold and label map
- object overlay controls are optional only if they operate on the object preview without adding confusion

If object overlay remains enabled, it should overlay objects on the threshold preview or filtered source in a clearly named way. Do not add a second source toggle to the normal screen.

## Large View

Current `LargePreviewDialog` supports three panes. Use those panes as:

```text
Source without threshold | Threshold preview | Object preview
```

The first pane should let the user inspect original or filtered input without thresholding. There are two acceptable implementations:

1. Add a source chooser in large view only:

```text
Source: [Original v]
```

Options:

- `Original`
- `Filtered`

2. Default the first pane to original source for the first implementation, then add the filtered-source chooser as a follow-up before marking the plan complete.

The preferred final behaviour is option 1 because it satisfies both inspection needs without adding raw/filtered controls to the normal screen.

## PreviewPairPanel Changes

`PreviewPairPanel.setLargePreviewImages(first, second, third)` already accepts three images. The missing piece is choosing whether `first` is raw or filtered for this stage.

Add only the smallest reusable API needed. Suggested direction:

```java
preview.setLargePreviewSourceChoices(rawSource, filteredSource);
preview.setLargePreviewImages(currentLargeSource(), thresholdPreview, labelPreview);
```

Alternative: keep this logic inside `ClassicalSegmentationStage` if the source chooser is implemented as a stage-owned large-view hook.

Avoid adding a normal source toggle. The source chooser belongs only in Large view.

## Stale State Display

When threshold or size edits make labels stale:

- left pane remains ready because it is live
- right pane becomes stale
- existing label map may remain visible but must be clearly stale
- preview button gets the stale dot

Example status:

```text
Threshold 42. Object preview is out of date. Press Run Object Preview.
```

## Image Lifetime

The stage will generate many threshold preview images while the user drags sliders. Close the replaced threshold preview immediately after the new one is installed.

Object label maps are heavier and should follow the current `ParticleSizeStage` pattern:

- close old label map after installing the new label map
- close masked image returned by 3D Objects Counter if it is not displayed
- close all remaining sources and previews on leave

## Tests

Add or update preview tests for:

- normal preview left pane receives threshold preview
- normal preview does not expose source mode controls
- large view shows three panes after object labels exist
- first large-view pane can show original or filtered source
- stale object state does not replace the live threshold preview
