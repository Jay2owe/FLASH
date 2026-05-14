# Step 12 — Shift-click two cells to open ComparisonPreviewDialog

## Goal

Shift-click on two cells in the variations grid -> both load into a side-by-side `ComparisonPreviewDialog` with synced z-slices. User can study them in detail, close the comparison dialog, then click the winning tile in the grid.

## Pre-conditions

- Steps 01–11 complete.
- `ComparisonPreviewDialog` exists at `src/main/java/flash/pipeline/ui/preview/ComparisonPreviewDialog.java` (currently untracked in git status).
- Current API reality:
  - public constructor: `public ComparisonPreviewDialog(Window owner)`
  - setup methods such as `setImages(...)`, `setSourceChoices(...)`, `setPreviewStatus(...)`, and `setCurrentZ(...)` are package-private in `flash.pipeline.ui.preview`
  - there is no accept-left / accept-right callback; the dialog is a modeless viewer with a Close button

## Deliverables

### `VariationCellPanel` shift-click handler

Already wired in step 02 to a stub callback. Implement now:

```java
private VariationCellPanel pendingCompare = null;

void onCellClicked(VariationCellPanel cell, MouseEvent e) {
    if (e.isShiftDown()) {
        if (pendingCompare == null) {
            pendingCompare = cell;
            cell.setSelected(true);  // visual: thick blue border
            statusBar.setText("Shift-click a second tile to compare.");
        } else if (pendingCompare == cell) {
            pendingCompare.setSelected(false);
            pendingCompare = null;
            statusBar.setText("Comparison cancelled.");
        } else {
            openComparison(pendingCompare, cell);
            pendingCompare.setSelected(false);
            pendingCompare = null;
        }
    } else {
        if (pendingCompare != null) {
            pendingCompare.setSelected(false);
            pendingCompare = null;
        }
        onAccept.accept(cell.combo());
    }
}
```

### Public comparison facade

Do not call package-private methods from `flash.pipeline.ui.variations`. Add one small public facade to `ComparisonPreviewDialog` instead of making every internal setter public:

```java
public void showVariationComparison(ImagePlus leftLabel,
                                    String leftStatus,
                                    ImagePlus rightLabel,
                                    String rightStatus,
                                    ImagePlus rawSource,
                                    PreviewDisplaySettings rawSettings,
                                    ImagePlus filteredSource,
                                    PreviewDisplaySettings filteredSettings,
                                    int zSlice) {
    setSourceChoices(rawSource, rawSettings, filteredSource, filteredSettings);
    setImages(leftLabel, rightLabel, zSlice);
    setPreviewStatus(leftStatus, rightStatus);
    setObjectSizeGuide(null);
    raiseForUser();
}
```

Keep accept/writeback in the variations grid for v1. The comparison dialog has no current accept callback, so after comparing the user closes it and clicks the winning tile in the grid.

### `openComparison(left, right)`

Construct/reuse `ComparisonPreviewDialog` with `new ComparisonPreviewDialog(SwingUtilities.getWindowAncestor(this))`. Pass through the facade:
- Left: `left.cachedLabel()`, label "Variation A" + combo summary.
- Right: `right.cachedLabel()`, label "Variation B" + combo summary.
- Source: the cropped `filteredSource` (both share the same raw — overlay differs only by mask).
- Z-slice: the current global z.

Do not add accept buttons to `ComparisonPreviewDialog` in v1. If users ask for that workflow later, add explicit "Use left" / "Use right" buttons as a separate UI change.

### Status bar feedback

After a shift-click selects the first cell, the status bar shows "Shift-click a second tile to compare." Pressing Esc clears the selection. Clicking anywhere else (non-shift, on a cell) accepts that cell and clears the pending selection.

## Acceptance

- `VariationCellPanelShiftClickTest`: simulate shift+mouse events on two cells, verify the openComparison callback receives the right pair.
- Manual: run a 9-cell sweep, shift-click cell 1, status bar updates, shift-click cell 7, comparison dialog opens with both labels rendered side-by-side and z-scrolling synced between the two comparison panes.
- Manual: shift-click cell 1, then click cell 4 without shift — variations dialog accepts cell 4 normally, pending shift-selection cleared.

## Tests location

`src/test/java/flash/pipeline/ui/variations/VariationCellPanelShiftClickTest.java`

## Notes / gotchas

- Keep the public facade narrow. The existing internal listener interfaces are package-private; do not expose them unless the variations workflow truly needs two-way z sync.
- Double-shift-clicking the same cell deselects it. Don't open a comparison-with-itself.
- For 2D sweeps with many cells, the comparison choice is most useful between distant cells in the grid (corner vs corner). No need to special-case adjacency.
- If either chosen cell hasn't rendered yet (cache miss + still pending), block the shift-click with a status message: "Wait for both tiles to finish rendering."
