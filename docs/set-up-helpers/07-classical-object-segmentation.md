# 07 - Classical Object Segmentation

Status: Implemented

## Proposed Helper Text

### Classical Object Segmentation

Classical object segmentation uses the filtered image, a signal threshold, and a voxel-size range to turn this channel into counted 3D objects.

Use this path when objects are bright enough to separate from background with a consistent threshold. This helper covers the merged Classical screen, where threshold and particle-size settings are locked in together.

### Channel Threshold

Set the signal threshold for this channel. Signal below this value is excluded; signal at or above it is kept. The saved threshold is applied to all images for this channel and is reused for ROI / Intensity Analysis and classical object detection.

Use the left `Threshold preview` to choose the cutoff. It always shows the threshold-applied filtered image; the normal Classical view does not show raw or filtered input without thresholding.

`Large view` lets you inspect the original or filtered input without thresholding, alongside the threshold and object previews.

### Object Preview

Use the right `Object preview` to inspect the label map created by 3D Objects Counter.

`Run Object Preview`: runs 3D object detection with the current threshold and size range.

Threshold or size edits make the object preview out of date until you press `Run Object Preview` again.

### Particle Size

Choose which thresholded 3D objects are kept by size. Objects smaller than `Min` are removed, and objects larger than `Max` are removed.

Particle sizes (voxels): the object-size range to keep.

Min: smallest object size to keep. Increase this to remove specks and debris.

Max: largest object size to keep. Use `Infinity` when there should be no upper limit.

Reset sizes: restores the saved size range.

### Watch Out

If the threshold is too low, background becomes objects. If it is too high, real dim objects disappear. If `Min` is too high, small real objects are removed; if `Max` is too low, large real objects are removed.
