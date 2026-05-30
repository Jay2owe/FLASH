# FLASH Config Cleanup Review

## What Was Verified

- Required context reviewed: `.claude/CLAUDE.md`, `AGENTS.md`, `docs/flash-config-rewrite/CLEANUP_REPORT.md`, and recent `git log --oneline -25`.
- The three command/coordinator failures were re-run together and passed.
- `CellposeParameterStageTest` was re-run, including `sizeEditsAfterPreviewRelabelRemovedObjectsWithoutRerunning`, and passed.
- Original failure set re-run:
  - `CoordinatorRoutesAllEntryPointsTest`
  - `CreateBinFileAnalysisCommandTest`
  - `DrawAndSaveROIsAnalysisCommandTest`
  - `CellposeParameterStageTest`
  - Result: 25 tests, 0 failures, 0 errors.
- Extra suite regression found during full verification was fixed and re-run:
  - `StatisticalAnalysisCommandTest`
  - Result: 2 tests, 0 failures, 0 errors.
- Final full suite command:
  - `bash mvnw -o clean test -Denforcer.skip=true`
  - Result: 2518 tests, 0 failures, 0 errors, 28 skipped.

## Reviewer Fix Commits

- `1e3a26c` - `flash-cleanup review: remove local deprecated preview markers`
- `15761b2` - `flash-cleanup review: restore cellpose JSON round-trip coverage`
- `a7a96fe` - `flash-cleanup review: reject removed orientation index`
- `4742c50` - `flash-cleanup review: remove stale cleanup imports`
- `9afa01f` - `flash-cleanup review: keep stats run records ok without source ids`

## Issues Found And Fixed

- LegacyService initialization errors in command/coordinator tests:
  - Fixed by the interleaved run-record command routing work in `0eed32b`.
  - The tests now verify command metadata by reflection and route execution through `AnalysisRunCoordinator` without initializing ImageJ Legacy services.

- Cellpose size-edit path rendered the old `Objects: [N ready]` state:
  - Survivors-only rendering was introduced in `28b7c0f`.
  - The size-edit debounce/test path was completed in `03e7310`, so size field edits flush the size filter path and render the kept/removed object state.

- Full-suite run-record status regression in `StatisticalAnalysisCommandTest`:
  - Fixed in `9afa01f`.
  - Missing upstream `run_id` metadata is now logged to ImageJ only in `StatisticalAnalysis.parseMasterCsv`; it no longer marks an otherwise successful statistics run as `warn`.

## Final Test Results

- Targeted original failures: green.
- Extra full-suite regression: green after `9afa01f`.
- Full suite: green.

## Sign-off

CLEANUP COMPLETE
