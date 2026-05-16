# Per-object fractal complexity

## What it measures

Fractal complexity describes how much object structure fills space across measurement scales. FLASH uses box-counting fractal dimension and lacunarity to score each segmented object's mask, so the result is about object shape complexity rather than raw fluorescence intensity.

The implementation is `ObjectFractal`. It works from an XY projection of the object's mask, counts occupied boxes at several radii, and computes lacunarity from gliding-box foreground mass.

## When to enable it

Use fractal complexity when object shape is biologically meaningful but a single size or sphericity number is too blunt.

Good fits:
- comparing ramified and amoeboid microglia
- scoring astrocyte process complexity
- checking whether plaques or inclusions have compact versus irregular boundaries
- adding a per-object complexity score alongside existing `Morph_*` shape outputs

It works best when segmentation captures the real outline and processes of the object. If fine processes are removed during filtering, the fractal score will reflect the filtered mask.

## Columns produced

Per-channel object CSVs use the `MorphTexture_*` prefix:

- `MorphTexture_FractalDim`: box-counting fractal dimension from the projected object mask.
- `MorphTexture_FractalDim_R2`: goodness-of-fit for the log-log box-counting regression.
- `MorphTexture_LacunarityMean`: average lacunarity across valid box scales.
- `MorphTexture_LacunaritySpread`: variation in lacunarity across valid box scales.

Master Aggregation writes channel-prefixed means:

- `<channel>_MorphTexture_FractalDimMean`
- `<channel>_MorphTexture_FractalDim_R2Mean`
- `<channel>_MorphTexture_LacunarityMeanMean`
- `<channel>_MorphTexture_LacunaritySpreadMean`

## 3D vs 2D mode

Fractal complexity is still computed from the XY projection of each object mask. The native-3D texture toggle adds separate 3D GLCM and 3D texture-class columns alongside the fractal columns; it does not change the fractal calculation.

Enable native-3D texture when the same objects span multiple z-slices and depth-wise signal texture is biologically relevant, especially for axially anisotropic or layered structures. The toggle is in Spatial Analysis -> Texture question -> advanced -> Native-3D texture (GLCM + texture classes). The 3D texture columns coexist with the 2D texture and fractal columns in the same per-channel object CSV.

## How to interpret typical values

Fractal dimension is easiest to read as a shape-complexity score:

- values near 1 suggest line-like or sparse structures
- values between 1 and 2 suggest branching, irregular, or partially space-filling objects
- values near 2 suggest compact filled shapes in the XY projection

Use `MorphTexture_FractalDim_R2` as a quality check. A high dimension with poor fit can mean the object does not behave consistently across box scales, or that it is too small for a stable estimate.

Lacunarity describes gaps and heterogeneity:
- lower lacunarity means the object mass is more evenly distributed
- higher lacunarity means the mask has more gaps or uneven occupancy
- higher lacunarity spread means scale matters; the object may look compact at one scale and gappy at another

Example:
- ramified microglia should often have lower space filling and higher lacunarity than compact amoeboid cells
- a plaque with an irregular corona may show higher lacunarity than a dense compact core

## Limitations

- Very small masks do not provide enough box sizes for reliable regression.
- Fractal dimension is sensitive to segmentation smoothing, erosion, dilation, and threshold choice.
- The fractal path uses an XY mask projection, not a native 3D box-counting volume.
- Treat `MorphTexture_FractalDim_R2` as a reliability flag before interpreting group differences.

## Where it appears in the wizard

Spatial Analysis -> Texture question -> Object complexity (fractal + lacunarity)

It is also enabled by:
- Spatial Analysis -> Texture question -> All object texture features (exploratory)
- CLI option `spatial.texture.fractal=true`
