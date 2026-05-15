# 3D Object Analysis — Methodology

Three-dimensional object detection and quantification were performed using the IHF Analysis Pipeline plugin for Fiji/ImageJ. Multi-channel confocal z-stack images acquired in Leica .lif format were imported via Bio-Formats. Images were automatically oriented to a standard left-hemisphere reference based on filename metadata, and user-defined regions of interest (ROIs) were applied to delineate the tissue area for analysis, with non-ROI regions cleared prior to processing.

For each fluorescence channel, image pre-processing was performed using configurable filter macros (e.g., median filtering, background subtraction) stored in per-channel configuration files. Object segmentation was then carried out using the channel's saved method: classical intensity thresholding followed by the 3D Objects Counter (mcib3d-core library; Ollion et al., 2013), Enhanced Classical thresholding with optional object-level morphology filters, StarDist slice-wise instance segmentation with Z-linking (Weigert et al., 2020), Cellpose 3 segmentation, or a project-trained Smile Random Forest post-filter applied to a classical base segmentation. Minimum and maximum particle sizes (in voxels), model keys, and method-specific thresholds were specified per channel. No watershed separation was applied in the classical path; touching objects exceeding threshold jointly were merged unless filter pre-processing, morphology filtering, StarDist, Cellpose, or the trained post-filter had separated or rejected them. StarDist and Cellpose label images were converted directly to 3D object populations, preserving instance labels for downstream measurement.

For each detected object, the following morphometric and intensity measures were recorded: object volume (µm³), surface area (µm²), integrated density (IntDen), mean fluorescence intensity, and 3D centroid coordinates (XM, YM, ZM in calibrated units). Intensity measures (IntDen and Mean) were obtained from the unfiltered original channel image via redirection, not from the filtered/thresholded image.

Pairwise colocalization between all channel combinations was assessed using the mcib3d 3D MultiColoc algorithm. For each object in channel A, the percentage volume overlap with objects in channel B was computed, and vice versa. Values were recorded as "Colocalisation with [channel]" (%) per object. A user-defined colocalization threshold (default 30%) was applied in downstream spatial analyses to classify objects as colocalised or non-colocalised.

When enabled, process (neurite) length was extracted by skeletonizing the relevant fluorescence channel (2D skeletonization applied per z-slice), subtracting the nuclear marker skeleton to isolate neurite-only signal, and quantifying skeleton length via 3D Objects Counter on the subtracted skeleton image. Process length was computed from the integrated density of the skeleton objects (IntDen / 255 to obtain voxel count), converted to calibrated length units using the image pixel size.

Spatial distance analysis computed inter-marker nearest-neighbour distances using 3D Euclidean distance between object centroids across all channel pairs, within each tissue section. Object XM and YM coordinates were converted from pixels to microns using the image pixel size. For each object, the distance to the closest object of every other marker was recorded. Additionally, for each object in channel B, the number of channel A objects for which it was the nearest neighbour ("ClosestTo" count) and the number of colocalised nearest neighbours ("NumColoc" count) were computed. A binary "Contains" flag indicated whether at least one colocalised object of the partner marker mapped to a given object as its nearest neighbour. Where available, distances from objects to user-drawn anatomical reference lines were also computed as the minimum perpendicular distance from the object centroid to the polyline segments.

All per-object results were exported as CSV files (one per channel), with columns for section number (SCN), animal identifier, morphometric measures, colocalization percentages, process lengths, and spatial distance metrics.

## Output Measures Summary

| Measure | Description |
|---------|-------------|
| Volume (µm³) | Calibrated 3D object volume |
| Surface (µm²) | Calibrated object surface area |
| IntDen | Integrated density on unfiltered image |
| Mean | Mean fluorescence intensity on unfiltered image |
| XM, YM, ZM | 3D centroid coordinates |
| Colocalisation with [channel] (%) | Percentage volume overlap with each other channel |
| Length (µm) | Skeleton-based process length (optional) |
| DistToClosest (µm) | Nearest-neighbour distance to each other marker (optional) |
| ClosestTo count | Number of partner objects mapping to this object as nearest neighbour |
| NumColoc count | Number of colocalised partner objects mapping as nearest neighbour |
| Contains flag | Whether at least one colocalised partner maps as nearest neighbour |
| DistTo [line] (µm) | Distance to anatomical reference line (optional) |

## References

- Ollion, J., Cochennec, J., Loll, F., Escudé, C., & Boudier, T. (2013). TANGO: a generic tool for high-throughput 3D image analysis for studying nuclear organization. *Bioinformatics*, 29(14), 1840–1841.
- Weigert, M., Schmidt, U., Haase, R., Sugawara, K., & Myers, G. (2020). Star-convex Polyhedra for 3D Object Detection and Segmentation in Microscopy. *WACV 2020*.
