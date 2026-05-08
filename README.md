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
    |-- Presets/
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
- **Combine results per condition / animal**: Aggregate per-image analysis CSVs into project-level master tables.
- **Statistical Analysis**: Run configured group comparisons from aggregated result tables.
- **Excel Summary Export**: Export formatted `.xlsx` workbooks from aggregated and statistical outputs.

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
