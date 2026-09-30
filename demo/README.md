# Small simulated FLASH demo

The published FLASH 5.0.0 binary also passed the supplied numerical validator
through its plugin entry point in a standalone headless Java process. See
[the public-binary receipt](public-binary-verification.json). This complements
the earlier Fiji macro run below; it does not test GUI or menu integration.

The [`data/`](data/) folder contains twelve artificial fluorescence images:
six subjects in group A and six in group B. Each TIFF is a 16-bit ImageJ
hyperstack with two channels (DAPI-like nuclei and an arbitrary Signal marker),
five Z slices, and 64 x 64 pixels per slice. The complete image dataset is
about 1 MB. Pixel size is 1 x 1 micrometre; slice spacing is 2 micrometres.
All image intensities, subject identifiers and group differences are simulated.

The images contain nine separate ellipsoids on a small non-zero background.
The second channel has a deliberately higher signal in group B. The seed is
20260930; [`generate_data.py`](generate_data.py) records the generation method.
This minimal demo exercises image import, channel configuration, whole-image
fluorescence intensity measurement and CSV export. It does not benchmark
segmentation, deconvolution or the other optional workflows.

## Run in Fiji

1. Install FLASH through Fiji's updater, following the main README.
2. Download or clone this repository. Keep `run_demo.ijm` beside its `data/`
   folder; do not run it from a ZIP archive.
3. In Fiji, choose **File > New > Script**, open `demo/run_demo.ijm`, select
   **Language > ImageJ Macro**, then choose **Run**. Alternatively open the
   saved macro in Fiji's macro editor and run it there.
4. Choose an empty folder for the demo outputs when prompted. The macro copies
   the image inputs there and leaves the supplied dataset unchanged.

No region drawing, segmentation models or GPU are required. The macro selects
whole-image, all-slice measurements and explicit filters that leave pixels
unchanged. It uses a single analysis worker and disables optional spatial
analysis and deconvolution.

## Expected outputs

Within the output folder:

```text
input/                                      copied artificial TIFF images
FLASH/Config/.settings/                      generated channel configuration
FLASH/Results/Tables/Intensity/DAPI.csv       60 measurement rows
FLASH/Results/Tables/Intensity/Signal.csv     60 measurement rows
FLASH/Results/Run Records/                   run history and parameter records
```

Each intensity table has one row per subject and Z slice: 12 subjects x 5
slices. The `IntDen` and `IntDen_Unfiltered` columns are integrated intensities
in arbitrary intensity units multiplied by square micrometres. With this
demo's 1 micrometre pixels, both equal the corresponding `PixelSum` in
[`data/pixel_truth.csv`](data/pixel_truth.csv). `z` is the slice number;
`Animal Name` includes the `Simulated-` prefix.

For example, subject A01, Signal channel, slice 1 has integrated intensity
**94937**. The exact expected values for all 120 channel/slice measurements
are supplied in the pixel-truth table. The optional checker requires only
Python 3.9 or later, with no extra packages; run from the repository root:

```bash
python demo/validate_outputs.py /path/to/demo-output
```

Expected checker output ends with:

```text
FLASH demo passed: all 120 slice/channel measurements match.
```

Expected run time on a normal desktop or laptop is **under 2 minutes**, after
Fiji has started and FLASH is installed. This allowance excludes the time
spent selecting a folder. The macro prints its actual elapsed time to the
ImageJ Log. The verification run and its environment are recorded in
[`verification.json`](verification.json).

Rerun into a new empty output folder to preserve an earlier result. To use your
own images, follow the main README's workflow, configure your actual channels,
filters and regions, and choose the analyses appropriate to your experiment.
The supplied numerical checks apply only to this artificial dataset.

## Recreate the inputs (optional)

The supplied TIFFs are ready to use; reviewers do not need Python to generate
them. To reproduce the input files, install `numpy` and `tifffile` in a separate
Python environment and choose a new, empty destination:

```bash
python demo/generate_data.py --output regenerated-demo-data
```

The companion [PyFLASH demo](https://github.com/Jay2owe/PyFLASH/tree/master/demo)
contains one row per simulated subject, with each channel's pixel mean averaged
over all five slices. It can be run without Fiji or FLASH.
