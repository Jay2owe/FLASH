# Main Dialog ROI Completion Status

## Why this stage exists

After users draw an ROI set, Draw ROIs should be ticked or marked complete in the main dialog so the workflow reflects the work already done.

## Prerequisites

- Read `src/main/java/flash/pipeline/intelligence/AnalysisStatusScanner.java:35`.
- Read `src/main/java/flash/pipeline/intelligence/AnalysisStatusScanner.java:269`.
- Read `src/main/java/flash/pipeline/FLASH_Pipeline.java:689`.
- Read `src/main/java/flash/pipeline/io/FlashProjectLayout.java:40`.

## Read first

- `AnalysisStatusScanner` already has ROI output detection and a ROI tooltip.
- `FlashProjectLayout` controls current and legacy read directories.
- The main dialog builds analysis rows and status indicators in `FLASH_Pipeline`.

## Scope

- Confirm where Draw ROIs should be read from after the current save path.
- Fix `AnalysisStatusScanner.hasRoiOutputs(...)` or the save path so saved ROI sets are detected.
- Ensure the main dialog marks Draw ROIs as already completed after a scan.
- Add a test with a realistic ROI zip/CSV in the expected output folder.

## Out of scope

- Changing the main workflow order.
- Renaming output folders; that belongs to the outputs split-plan.

## Files touched

- `src/main/java/flash/pipeline/intelligence/AnalysisStatusScanner.java`
- `src/main/java/flash/pipeline/FLASH_Pipeline.java` only if status is detected but not reflected
- `src/main/java/flash/pipeline/io/FlashProjectLayout.java` only if read dirs are wrong
- `src/test/java/flash/pipeline/intelligence/AnalysisStatusScannerTest.java`

## Implementation sketch

1. Create a test project with a saved ROI zip and any companion CSV the scanner expects.
2. Run `AnalysisStatusScanner.scan(...)` and assert Draw ROIs is complete.
3. If the scanner misses current folders, update `FlashProjectLayout.analysisReadDirs(ROIS)`.
4. If the scanner detects completion but the row is not ticked, update the main dialog row binding in `FLASH_Pipeline`.
5. Keep legacy output folders as read fallbacks.

## Exit gate

- Saved ROI sets cause Draw ROIs to appear complete/ticked in the main dialog.
- Missing ROI files still show incomplete.
- Existing status scanner tests pass with updated expectations.

## Known risks

- ROI outputs may be saved under several historical folders. Keep read fallback broad enough to detect old projects without writing new outputs to old folders.
