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
|-- Configuration/
|   `-- Segmentation Models/
`-- FLASH/
    |-- Config/
    |   `-- .settings/
    |       |-- channel_config.json
    |       `-- Channel_Data.txt
    |-- Results/
    |   |-- START_HERE.html
    |   |-- Summary.xlsx
    |   |-- Tables/
    |   |-- Presentation Images/
    |   |-- Analysis Images/
    |   |-- QC/
    |   `-- Run Records/
    |-- .settings/
    |   `-- Presets/
    |-- Cache/
    |   `-- TIF/
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

  **Per-object texture and complexity.** Spatial Analysis can score each segmented object on its internal texture (2D per-slice and native-3D GLCM Haralick features), morphological complexity (box-counting fractal dimension and lacunarity on an XY mask projection), or assign it to an auto-discovered texture class using 2D or native-3D Gabor and wavelet k-means feature vectors. Columns appear under the `MorphTexture_*` prefix in per-channel output; native-3D outputs use `MorphTexture_GLCM3D*`, `MorphTexture_Class3D*`, and `MorphTexture_F3D*` names.
- **Combine results per condition / animal**: Aggregate per-image analysis CSVs into project-level master tables.
- **Statistical Analysis**: Run configured group comparisons from aggregated result tables.
- **Excel Summary Export**: Export formatted `.xlsx` workbooks from aggregated and statistical outputs.

  Advanced texture-class feature vectors (`MorphTexture_F1..F8` and native-3D `MorphTexture_F3D1..F3D8`) are hidden from Excel exports by default to keep workbooks compact. Use `excel.texture.features=true` to include them with raw vector labels.

### Set Up Configuration QC

Interactive configuration quality checks use an embedded FLASH preview screen instead of separate native ImageJ Brightness/Contrast, Threshold, or 3D Objects Counter windows. The preview keeps the original image stacked above the adjusted or output image, and **Large view** opens a bigger raw-vs-adjusted comparison where that helps.

Display min/max and threshold controls update the preview live. Filter parameters, StarDist, Cellpose, and 3D object previews rerun only from their explicit preview buttons.

Configuration is stored in `FLASH/Config/.settings/channel_config.json`; `Channel_Data.txt` in the same folder is a derived compatibility projection for downstream analyses. Cancelling setup offers Save & Exit, Keep Working, or Discard & Exit so partial progress can be resumed.

### Segmentation Models And Click Training

FLASH supports built-in and project-specific segmentation models. StarDist and Cellpose parameter stages can select model catalog entries, and the Custom Model Manager can register Fiji-compatible StarDist `.zip` exports, Cellpose model files, or Cellpose registered model names.

The normal setup UI no longer collects preview clicks or offers click-based parameter suggestions. Custom StarDist and Cellpose models are imported through the Custom Model Manager; the Train Custom Engine path remains hidden while its click-collection flow is redesigned.

#### Runtime And Catalog Notes

FLASH pins Cellpose `3.1.1.2`; Cellpose 4, Cellpose-SAM, and `cpsam` models are not supported. StarDist custom models must be Fiji-compatible TensorFlow SavedModel `.zip` exports. FLASH runs StarDist per slice as 2D detections with Z-linking; full 3D StarDist is not built in.

The project model catalog lives under `<projectRoot>/Configuration/Segmentation Models/`, with entries in `catalog.json` and copied model files under `files/<modelKey>/...`. For training and import steps, see [docs/training_segmentation_models.md](docs/training_segmentation_models.md).

## Supported Inputs

FLASH is designed for multi-channel fluorescence microscopy projects. It supports common Bio-Formats-readable microscopy containers and TIFF-based workflows through Fiji/Bio-Formats.

Typical input formats include:

- Leica `.lif`
- Zeiss `.czi`
- Nikon `.nd2`
- OME-TIFF and TIFF stacks

## Runtime Dependencies

FLASH opens even if optional runtime dependencies are missing. Features that need optional dependencies show a clear message and repair guidance when used.

The **Dependencies** button in the main dialog opens the runtime check and repair panel. Depending on the selected workflow, optional dependencies can include Bio-Formats, 3D Objects Counter, mcib3d, StarDist/TrackMate, TensorFlow native libraries, Apache POI, Cellpose, 3D deconvolution engines, PSF Generator, and JTS.

## Headless and Macro Use

FLASH can be driven from ImageJ macros and headless workflows after project configuration exists. CLI options are parsed by `flash.pipeline.cli.CLIArgumentParser`, and the main plugin entry point is `flash.pipeline.FLASH_Pipeline`.

Example ImageJ macro invocation:

```ijm
run("FLASH - The Pipeline for Fluorescence Automated Spatial Histology",
    "dir=[/path/to/project] run_3d run_intensity");
```

## Building

The project uses Maven and targets Java 8 bytecode for Fiji compatibility.

```bash
export JAVA_HOME="/c/Program Files/Java/jdk-25.0.2"
bash mvnw clean package -Denforcer.skip=true -DskipTests=true
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

The full Maven test suite is expected to be green before release, but a green default suite does not prove every Fiji runtime path, because these runtime-sensitive test classes can be skipped on local machines without the required Fiji plugins, native/GPU dependencies, non-headless runtime, or LIF fixture:

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

## Citing FLASH

If you use FLASH in published work, please cite it as:

> Malcolm, J. (2026). *FLASH: Fluorescence Automated Spatial Histology* (Version X.Y.Z) [Computer software]. Manuscript in preparation. https://github.com/Jay2owe/FLASH

A Zenodo concept DOI will be minted from the first tagged GitHub release after the BSD-3 cutover; this README will be updated with the DOI badge once available. Citation metadata is also available in [`CITATION.cff`](CITATION.cff) (use the "Cite this repository" button on GitHub).

If you use specific features, please also cite their upstream tools:

- [Fiji](https://fiji.sc/) (Schindelin et al., *Nature Methods*, 2012, doi:10.1038/nmeth.2019)
- [Bio-Formats](https://www.openmicroscopy.org/bio-formats/) (Linkert et al., *J Cell Biol*, 2010, doi:10.1083/jcb.201004104)
- [3D ImageJ Suite / mcib3d-core](https://mcib3d.frama.io/3d-suite-imagej/) (Ollion et al., *Bioinformatics*, 2013, doi:10.1093/bioinformatics/btt276) for 3D measurement
- [StarDist](https://github.com/stardist/stardist) (Schmidt et al., MICCAI 2018; Weigert et al., WACV 2020) for star-convex segmentation
- [Cellpose](https://www.cellpose.org/) (Stringer et al., *Nature Methods*, 2021, doi:10.1038/s41592-020-01018-x) for generalist cell segmentation
- [TrackMate](https://imagej.net/plugins/trackmate/) (Tinevez et al., *Methods*, 2017, doi:10.1016/j.ymeth.2016.09.016) for tracking

## Acknowledgements

Developed by Jamie Malcolm in the [Brancaccio Lab](https://www.ukdri.ac.uk/labs/brancaccio-lab) at the [UK Dementia Research Institute](https://ukdri.ac.uk/centres/imperial), Imperial College London.

This work was supported by the UK Dementia Research Institute, which receives its core funding from the UK Medical Research Council, the Alzheimer's Society, and Alzheimer's Research UK.

Built on the [Fiji](https://fiji.sc/) / [ImageJ](https://imagej.net/) ecosystem; we thank the SciJava community for the platform.

## License

BSD 3-Clause License. See [`LICENSE`](LICENSE) for the full text.
