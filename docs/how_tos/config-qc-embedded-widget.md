# Config QC Embedded Widget

Use the embedded `ConfigQcDialog` stages for configuration quality checks (QC). Full setup and selective overrides should reach min/max, threshold, filter-parameter, StarDist, Cellpose, particle-size, and z-slice review through the owned widget rather than native ImageJ tool windows.

## Routing

- `CreateBinFileAnalysis.interactiveQC(...)` is the shared QC route for full setup and selective override.
- Stage runners call `showEmbeddedConfigQcDialog(...)`, which creates the modal `ConfigQcDialog` in production and can be overridden by tests.
- Keep headless, macro, and command-line paths outside this UI route; `canShowFilteredDialogs()` still gates interactive setup.

## Preview Behaviour

- Main preview stays stacked: original image above adjusted/output image.
- `Large view` is available from the shared dialog footer for bigger raw-vs-adjusted comparison.
- Cheap display operations can update live: LUT, Z display, min/max, and threshold overlay.
- Expensive operations require explicit preview buttons: filter reruns, StarDist, Cellpose, and object-count previews.

## Testing Notes

- Add routing tests against `showEmbeddedConfigQcDialog(...)` instead of opening Swing windows.
- Add writeback tests against stage factories such as `createDisplayRangeStage(...)` and `createChannelThresholdStage(...)`.
- Do not reintroduce visible calls to `IJ.run(..., "Brightness/Contrast...", "")`, `IJ.run(..., "Threshold...", "")`, or `IJ.run("3D Objects Counter", ...)` from config QC routing.
