# 13 - Particle Size

Status: Merged into 07 - Classical Object Segmentation

This helper is no longer tracked as a standalone helper. Use [07-classical-object-segmentation.md](07-classical-object-segmentation.md), which documents the merged Classical screen.

## Superseded Draft

### Particle Size

Set the voxel-size range for objects detected by `Classical` segmentation. Objects smaller than `Min` are removed, and objects larger than `Max` are removed.

Use this to remove specks, debris, or very large merged objects after thresholding.

### Controls

Particle sizes (voxels): the object-size range to keep.

Min: smallest object size to keep. Increase this to remove tiny objects.

Max: largest object size to keep. Use `Infinity` when there should be no upper limit.

Run Object Preview: runs classical 3D object detection with the current threshold and size range.

Reset sizes: restores the saved size range.

Large view: inspect the original or filtered input without thresholding.

### Watch Out

If `Min` is too high, small real objects disappear. If `Max` is too low, large real objects are removed.
