# Per-object texture classes

## What it measures

Texture classes group segmented objects by their internal texture feature vectors. FLASH extracts Gabor orientation responses and wavelet-scale energies for each object, then assigns each object to the nearest k-means centroid.

The implementation is `ObjectTextureFeatures`. It produces a fixed 8-value feature vector in v1: four Gabor features and four wavelet features.

## When to enable it

Use texture classes when you expect sub-populations but do not already know a single scalar metric that separates them.

Good fits:
- discovering plaque texture sub-types
- separating smooth, granular, and striated object interiors
- finding microglia or astrocyte sub-populations with different internal marker texture
- adding exploratory classes before deciding which GLCM or morphology metrics to model directly

Keep class labels within the same project context. A class label is not a biological name by itself; it becomes meaningful after inspecting representative objects and comparing marker/region patterns.

## Columns produced

Per-channel object CSVs use the `MorphTexture_*` prefix:

- `MorphTexture_ClassLabel`: integer class assigned to the object, from `0` to `k - 1`.
- `MorphTexture_ClassDistance`: distance from the object feature vector to the assigned centroid.
- `MorphTexture_F1` to `MorphTexture_F4`: Gabor orientation-response features.
- `MorphTexture_F5` to `MorphTexture_F8`: wavelet energy features.

Master Aggregation writes:

- `<channel>_MorphTexture_ClassLabelMode`: the most common class in the aggregation group. If classes tie, the smallest class value wins.
- `<channel>_MorphTexture_ClassDistanceMean`: mean distance to assigned centroid.
- `<channel>_MorphTexture_F1Mean` to `<channel>_MorphTexture_F8Mean`: mean feature-vector values in the master CSV.

Default Excel metric sheets map the class label mode and class distance. The raw `F1` to `F8` feature-vector aggregates stay in the master CSV but are intentionally omitted from default Excel exports to avoid sheet bloat.

## How to interpret typical values

Start by treating classes as data-driven labels:

- class `0` is not automatically "low texture" or "control"
- class numbers can change if centroids are refit
- the useful question is which class is enriched in which animal, region, condition, or marker

Use `MorphTexture_ClassDistance` as a confidence clue. Lower distances mean the object is close to a learned centroid. Higher distances mean the object is unusual for its assigned class.

A practical workflow:
1. Enable texture classes with the default `k = 4`.
2. Run Spatial Analysis and Master Aggregation.
3. Compare `<channel>_MorphTexture_ClassLabelMode` across groups.
4. Inspect raw object rows for classes with high distance or unexpected enrichment.
5. Use GLCM and fractal columns to describe what makes the classes different.

Example:
- one plaque class may have high GLCM contrast and high wavelet energy, consistent with dense granular signal
- another class may have lower contrast and lower distance to a smooth centroid, consistent with diffuse signal

## Limitations

- Class labels are project-specific unless the same centroid file is reused.
- The default `k` is 4; changing `k` changes the meaning of labels.
- Small or low-content objects can produce weak feature vectors and larger class distances.
- The feature vector is fixed at 8 values in v1.
- v1 uses 2D per-slice Gabor and wavelet results averaged across slices.
- Native-3D texture is deferred and not in v1.

## Where it appears in the wizard

Spatial Analysis -> Texture question -> Object texture classes (auto-discover sub-populations)

It is also enabled by:
- Spatial Analysis -> Texture question -> All object texture features (exploratory)
- CLI options `spatial.texture.class=true` and `spatial.texture.k=<2-10>`
