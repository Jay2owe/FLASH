# Per-object texture GLCM

## What it measures

GLCM (Grey-Level Co-occurrence Matrix) texture describes how often neighbouring pixel intensities occur together inside each segmented object. FLASH uses this to measure whether an object has smooth, speckled, edge-rich, or mixed internal signal.

The implementation is `ObjectTextureGLCM`. It quantises each object's raw intensity patch, builds 2D per-slice co-occurrence matrices at four directions, and averages valid slice results for each object.

## When to enable it

Use GLCM when the biology is about signal texture inside already-segmented objects, not just object size or count.

Good fits:
- comparing dense-core and diffuse plaque texture
- separating smooth from punctate intracellular staining
- checking whether activated cells have more heterogeneous marker signal
- measuring internal object texture without changing the segmentation

Do not use it as a substitute for segmentation quality control. If the object mask is wrong, the texture values describe the wrong pixels.

## Columns produced

Per-channel object CSVs use the `MorphTexture_*` prefix:

- `MorphTexture_GLCMContrast`: local grey-level differences. Higher values mean sharper changes between neighbouring pixels.
- `MorphTexture_GLCMASM`: angular second moment, also called energy. Higher values mean more repeated, orderly grey-level pairs.
- `MorphTexture_GLCMCorrelation`: linear dependence between neighbouring grey levels. Higher values mean neighbouring pixels vary together.
- `MorphTexture_GLCMEntropy`: disorder in the co-occurrence matrix. Higher values mean more varied texture.
- `MorphTexture_GLCMHomogeneity`: similarity of neighbouring grey levels. Higher values mean smoother local texture.

Master Aggregation writes channel-prefixed means:

- `<channel>_MorphTexture_GLCMContrastMean`
- `<channel>_MorphTexture_GLCMASMMean`
- `<channel>_MorphTexture_GLCMCorrelationMean`
- `<channel>_MorphTexture_GLCMEntropyMean`
- `<channel>_MorphTexture_GLCMHomogeneityMean`

Native-3D mode adds separate columns in the same per-channel CSV:

- `MorphTexture_GLCM3DContrast`
- `MorphTexture_GLCM3DASM`
- `MorphTexture_GLCM3DCorrelation`
- `MorphTexture_GLCM3DEntropy`
- `MorphTexture_GLCM3DHomogeneity`

## 3D vs 2D mode

The default GLCM path uses 2D per-slice results averaged across the object's z-range. Enable native-3D texture when objects span multiple z-slices and their voxel-to-voxel structure matters through depth, especially for axially anisotropic or layered structures.

The toggle is in Spatial Analysis -> Texture question -> advanced -> Native-3D texture (GLCM + texture classes). Native-3D columns coexist with the 2D columns in the same per-channel object CSV, so enabling it does not replace existing `MorphTexture_GLCM*` values.

## How to interpret typical values

Interpret GLCM values within the same marker, microscope setup, and segmentation strategy. The values are most useful as relative comparisons across animals or regions.

Rules of thumb:
- high contrast with high entropy usually means sharp, varied internal signal
- high homogeneity with low contrast usually means smooth signal
- high ASM usually means repeated texture, such as uniform puncta or uniform fill
- low correlation can happen when neighbouring pixels do not share a consistent intensity pattern

Example:
- dense, edge-rich plaque cores may show higher contrast than diffuse plaque halos
- a uniform nuclear marker object should usually have lower entropy than a mixed granular object
- microglia with strong internal puncta may have higher contrast and entropy than smoother cells

## Limitations

- Small objects can have too few valid neighbouring pixel pairs for stable texture estimates.
- GLCM values depend on image quality, background subtraction, and saturation. Avoid comparing runs with different acquisition settings unless those differences are controlled.
- The metric uses object-mask pixels only, but the object bounding box still defines the patch workspace.
- The default path uses 2D per-slice GLCM results averaged across slices.
- Native-3D GLCM skips single-slice objects because a 3D neighbourhood is not defined for them.

## Where it appears in the wizard

Spatial Analysis -> Texture question -> Object texture (GLCM)

It is also enabled by:
- Spatial Analysis -> Texture question -> All object texture features (exploratory)
- CLI option `spatial.texture.glcm=true`
- CLI option `spatial.texture.native3d=true` for native-3D GLCM and texture classes
