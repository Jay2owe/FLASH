# Changelog

## [5.3.1] - 2026-11-19

### Fixed
- 3D object measurements on rotated or flipped images: every object reported an
  integrated density of 0, a mean of NaN and negative centre/bounding-box Y (and X)
  coordinates. Orientation gives the image a calibration origin, which mcib3d applied as
  a pixel offset, so intensities were read outside the image. All mcib3d measurements now
  run on the pixel grid. Re-run 3D Object Analysis (and the steps after it) for projects
  with orientation set.
- Dialogs no longer flicker or snap back to their packed size when a toggle is clicked;
  a toggle that updates the Next/Run button label used to re-pack the visible window.

### Changed
- Excel per-metric sheets: per-animal values now start directly under the condition
  headers (A1 downwards), ready to paste into GraphPad Prism. When summary statistics are
  included (n, mean, SEM, median, IQR), they sit to the right after a blank column instead
  of above the values. The export preview says which layouts are Prism-ready.

## [5.3.0] - 2026-11-18

Includes the measurement corrections from the 5.2.0 local candidate, which was not
published, and the 5.1.1 fix.

### Added
- Representative figures are also exported through Figure Panel Builder 0.4.0, bundled
  inside the FLASH jar (no extra Fiji download): an editable SVG, TIFF and PNG figure,
  full-resolution per-panel images, a manifest, methods text and a clip report. FLASH
  hands over the same prepared pixels (deconvolution routing, Z-slice subset,
  orientation, maximum projection) and the same locked display ranges. Channels set to
  auto-enhance are reported in the Log and FLASH's own figure is still written.

### Fixed
- Representative and presentation figures shrink full-resolution images by area
  averaging instead of a bilinear draw, so puncta and fine processes no longer alias.
- An image with fewer channels than the channel setup is reported in the Log instead of
  silently rendering fewer channels.
- Unknown channel colour names fall back to grey; `#RRGGBB` colours are accepted.
- FLASH builds from a clean checkout: libraries that are not in a public Maven repository
  are kept in `lib/`, so the GitHub update-site workflow can build the release.

## [5.1.1] - 2026-11-18

### Added
- Project status strip and analysis side panel in the main dialog.
- Series include-range selection in the project builder.
- In-plugin feedback reporting.

### Fixed
- Saving a project file over a Dropbox or OneDrive online-only placeholder no longer
  fails with "replacement target is not a regular file"; a readable error is shown when
  the sync app cannot download the existing file.

## [5.2.0] - 2026-10-01

Local candidate (lab copy only); its changes are released in 5.3.0.

- Correct calibrated measurements, distance shells, object geometry and statistical estimators.
- Reject malformed inputs, ambiguous physical calibration and incompatible image axes.
- Bind cached results and training selections to their source pixels and segmentation.
- Verify replay inputs and settings, compiled code identity and source-run lineage.
- Preserve image axes, physical calibration and channel colors in exported images.
- Bound background save work and close only analysis-owned images after processing.
- Preserve segmentation identity for clicks made in embedded object previews.
- Remove empty run-record folders when cancelling before any output is recorded.
- Open the main window without loading every recent project's source images.

[5.3.0]: https://github.com/Jay2owe/FLASH/compare/v5.1.1...v5.3.0
[5.1.1]: https://github.com/Jay2owe/FLASH/compare/v5.0.0...v5.1.1
[5.2.0]: https://github.com/Jay2owe/FLASH/compare/v5.0.0...fix/quantification-audit-20261001
