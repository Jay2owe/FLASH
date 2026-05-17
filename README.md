# FLASH

**FLASH** is an ImageJ/Fiji plugin for fluorescence microscopy analysis.

FLASH stands for **Fluorescence Automated Spatial Histology**. It provides a modular pipeline for channel setup, ROI management, image preparation, fluorescence intensity measurement, 3D object analysis, spatial analysis, result aggregation, statistics, and Excel export.

The plugin is distributed through the public ImageJ update site:

```text
https://sites.imagej.net/FLASH/
```

## Installation

Install through the Fiji updater:

1. Open Fiji.
2. Choose **Help > Update...**.
3. Click **Manage update sites**.
4. Add or enable the **FLASH** update site:

   ```text
   https://sites.imagej.net/FLASH/
   ```

5. Apply changes and restart Fiji.
6. Run FLASH from:

   ```text
   Plugins > FLASH - The Pipeline for Fluorescence Automated Spatial Histology
   ```

Manual installation is also possible by copying the built `FLASH-<version>.jar` into Fiji's `plugins/` directory, but the update site is preferred because it keeps the plugin updateable through Fiji.

## Main Workflow

FLASH writes analysis outputs into a `FLASH/` folder inside the selected project directory:

```text
Project/
|-- input images
`-- FLASH/
    |-- 00 - Configuration/
    |-- 01 - Regions of Interest/
    |-- 02 - 3D Deconvolution/
    |-- 03 - Split and Merge/
    |-- 04 - Fluorescence Intensity/
    |-- 05 - 3D Object Analysis/
    |-- 06 - Spatial Analysis/
    |-- 07 - Line Distance/
    |-- 08 - Spectral Decontamination/
    |-- 09 - Result Aggregation/
    |-- 10 - Statistical Analysis/
    |-- 11 - Excel Summary Export/
    |-- .settings/
    |   `-- Presets/
    |-- Reports/
    |-- Cache/
    `-- Status/
```

The main dialog groups modules into setup, image preparation, display, image analysis, and results/validation sections. Each analysis row has a help button that explains what the module does, when to use it, what setup it needs, and what outputs it writes.

## Analysis Modules

- **Set Up Configuration**: Define channels, colors, thresholds, segmentation settings, filters, display ranges, and Z-slice selections.
- **Draw and Save ROIs**: Open each image with ROI drawing tools and always-available rotate/flip controls. Saved orientation transforms are reused by ROI reruns and downstream analyses.
- **3D Deconvolution**: Run optional deconvolution before downstream image analysis.
- **Spectral Decontamination**: Run experimental channel bleed-through and autofluorescence correction workflows.
- **Split and Merge Image Channels**: Export display-ready split-channel and merged images.
- **Fluorescence Intensity Analysis**: Measure fluorescence inside ROIs and masks.
- **3D Object Analysis**: Segment, count, and measure 3D objects, with colocalisation and process-length workflows.
- **Spatial Analysis**: Recompute nearest-neighbour, spatial statistics, heatmap, phenotyping, and morphometry outputs from object tables.

  **Per-object texture and complexity.** Spatial Analysis can score each segmented object on its internal texture (2D per-slice GLCM Haralick features), morphological complexity (box-counting fractal dimension and lacunarity on an XY mask projection), or assign it to an auto-discovered texture class (2D per-slice Gabor and wavelet k-means). Columns appear under the `MorphTexture_*` prefix in per-channel output. Native-3D texture metrics are deferred. See `docs/how_tos/per-object-texture-*.md`.
- **Combine results per condition / animal**: Aggregate per-image analysis CSVs into project-level master tables.
- **Statistical Analysis**: Run configured group comparisons from aggregated result tables.
- **Excel Summary Export**: Export formatted `.xlsx` workbooks from aggregated and statistical outputs.

  Advanced texture-class feature vectors (`MorphTexture_F1..F8` and native-3D `MorphTexture_F3D1..F3D8`) are hidden from Excel exports by default to keep workbooks compact. Use `excel.texture.features=true` to include them with raw vector labels.

### Set Up Configuration QC

Interactive configuration quality checks use an embedded FLASH preview screen instead of separate native ImageJ Brightness/Contrast, Threshold, or 3D Objects Counter windows. The preview keeps the original image stacked above the adjusted or output image, and **Large view** opens a bigger raw-vs-adjusted comparison where that helps.

Display min/max and threshold controls update the preview live. Filter parameters, StarDist, Cellpose, and 3D object previews rerun only from their explicit preview buttons.

### Segmentation Models And Click Training

FLASH supports built-in and project-specific segmentation models. StarDist and Cellpose parameter stages can select model catalog entries, and the Custom Model Manager can register Fiji-compatible StarDist `.zip` exports, Cellpose model files, or Cellpose registered model names.

Click-driven setup can mark good and bad preview objects, suggest safer filter parameters, and launch the Train Custom Engine wizard. Classical and Enhanced Classical training uses an in-process Smile Random Forest object classifier; StarDist and Cellpose workflows package click-derived datasets for external training and then register the trained model in the project catalog.

#### Runtime And Catalog Notes

FLASH pins Cellpose `3.1.1.2`; Cellpose 4, Cellpose-SAM, and `cpsam` models are not supported. StarDist custom models must be Fiji-compatible TensorFlow SavedModel `.zip` exports. FLASH runs StarDist per slice as 2D detections with Z-linking; full 3D StarDist is not built in.

The project model catalog lives under `<projectRoot>/FLASH/Configuration/Segmentation Models/`, with entries in `catalog.json` and copied model files under `files/<modelKey>/...`. For training and import steps, see [docs/training_segmentation_models.md](docs/training_segmentation_models.md).

Publication-oriented method notes live in [docs/methods/](docs/methods/), including [custom models and click training](docs/methods/Methodology%20-%20Custom%20Models%20and%20Click%20Training.md).

## Supported Inputs

FLASH is designed for multi-channel fluorescence microscopy projects. It supports common Bio-Formats-readable microscopy containers and TIFF-based workflows through Fiji/Bio-Formats.

Typical input formats include:

- Leica `.lif`
- Zeiss `.czi`
- Nikon `.nd2`
- OME-TIFF and TIFF stacks

## Runtime Dependencies

FLASH opens even if optional runtime dependencies are missing. Features that need optional dependencies show a clear message and repair guidance when used.

The **Dependencies** button in the main dialog opens the runtime check and repair panel. Depending on the selected workflow, optional dependencies can include Bio-Formats, 3D Objects Counter, mcib3d, StarDist/TrackMate, TensorFlow native libraries, Apache POI, Cellpose, and JTS.

## Headless and Macro Use

FLASH can be driven from ImageJ macros and headless workflows after project configuration exists. CLI options are parsed by `flash.pipeline.cli.CLIArgumentParser`, and the main plugin entry point is `flash.pipeline.FLASH_Pipeline`.

Example ImageJ macro invocation:

```ijm
run("FLASH - The Pipeline for Fluorescence Automated Spatial Histology",
    "dir=[/path/to/project] run_3d run_intensity");
```

## Building

The project uses Maven and targets Java 8 bytecode for Fiji compatibility.

```powershell
.\mvnw.cmd clean package "-Denforcer.skip=true"
```

The deployable artifact is:

```text
target/FLASH-<version>.jar
```

Do not deploy `*-sources.jar`, `*-tests.jar`, shaded jars, or `original-*` jars as Fiji plugins.

## Testing And Runtime Validation

Run the default Maven suite before committing or building a release:

```powershell
.\mvnw.cmd test "-Denforcer.skip=true"
```

In the 2026-05-13 codebase review, this command passed with 1539 tests, 0 failures, 0 errors, and 6 skipped. A green default suite does not prove every Fiji runtime path, because these runtime-sensitive test classes can be skipped on local machines without the required Fiji plugins, native/GPU dependencies, non-headless runtime, or LIF fixture:

- `flash.pipeline.deconv.engine.Clij2FftEngineTest`
- `flash.pipeline.deconv.engine.DeconvolutionLab2EngineTest`
- `flash.pipeline.deconv.engine.IterativeDeconvolve3DEngineTest`
- `flash.pipeline.deconv.psf.EpflPsfGeneratorAdapterIntegrationTest`
- `flash.pipeline.image.FilterExecutorTest`
- `flash.pipeline.integration.LifBaselineRegressionTest`

Before deployment to lab Fiji installs or public update-site release, validate those areas in a real Fiji install:

- Install the built `target/FLASH-<version>.jar` into Fiji or install FLASH from the update site, then confirm the plugin opens.
- Open **Pipeline Dependencies** and confirm the Fiji runtime dependencies needed for the planned workflow are present or show clear repair guidance.
- Run 3D deconvolution with each available engine: CLIJ2 FFT, DeconvolutionLab2, and Iterative Deconvolve 3D. Verify outputs keep the input dimensions and the result is visibly deconvolved.
- Generate an EPFL PSF from the deconvolution workflow and verify a non-empty, centered PSF is produced.
- Run a filter or threshold workflow that uses Fiji's Auto Local Threshold command and verify it produces output without a missing-command error.
- Run a representative `.lif` project through image loading and split/merge, then verify series count, calibration metadata, and representative outputs match the expected baseline.
- Record the Fiji version, Java version, operating system, GPU/native dependency state, and pass/fail notes for each runtime-sensitive area.

There is currently no dedicated `pom.xml` profile or exact Maven command that runs all of these real-Fiji validations. Use the manual checklist above until a real-Fiji integration harness is added.

## Repository Layout

```text
pom.xml
mvnw / mvnw.cmd
src/main/java/flash/pipeline/
src/main/resources/plugins.config
src/test/java/flash/pipeline/
```

## License

This project is released under CC0 as declared in `pom.xml`.
