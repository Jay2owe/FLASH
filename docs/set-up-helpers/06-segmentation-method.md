# 06 - Segmentation Method

Status: Implemented

## Proposed Helper Text

### Segmentation Method

Choose one of three ways to turn this channel into detected 3D objects. The method you choose controls which preview and parameter screen opens next.

Object segmentation means separating the signal into individual objects so FLASH can count them, measure them, and use them in downstream object and spatial analyses.

### Options

Classical: 3D Objects Counter: threshold-based segmentation. Best for bright, clean signal where objects stand out clearly from background, such as puncta, plaques, or well-separated labelled structures. Next you tune `Classical Object Segmentation`.

StarDist 3D: AI segmentation for round or star-convex objects, especially crowded nuclei or soma. Best when classical thresholding merges touching round objects. Next you tune `TrackMate-StarDist Parameters`.

Cellpose: AI segmentation for cells and cell-like objects with more flexible shapes. Best for whole cells, irregular cell bodies, complex morphology, or cases where thresholding does not separate objects cleanly. Next you tune `Cellpose 3D Parameters`.

### Watch Out

Choose the method for the biology and image quality, not just the most advanced option. Simple bright objects may be faster and more reproducible with `Classical`, while crowded or irregular objects may need `StarDist 3D` or `Cellpose`.
