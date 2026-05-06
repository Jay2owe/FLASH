`markers.json` is the source-of-truth for the bundled marker library.

History:
- Initially generated from `docs/ANALYSIS_WIZARDS_COMPLETED/MARKER_LIBRARY.md`
  via `scripts/transcribe_markers.py`.
- 2026-05 expansion: displayNames simplified to plain marker names (e.g.
  "Microglia (IBA1)" -> "IBA1", "Nuclei (DAPI)" -> "DAPI") and ~50 markers
  added from the lab antibody list and Abcam/Thermo Fisher catalogs via
  `scripts/expand_marker_library.py`. The markdown spec is now lagging.

For new edits: edit `markers.json` directly, or extend
`scripts/expand_marker_library.py` and re-run it (it is idempotent).
The schema and crosswalk are enforced by
`MarkerLibraryValidationTest` — keep that test green.
