package flash.pipeline.project;

/** Tracks the anchor for Shift-click include changes in Project Setup. */
final class SeriesIncludeRangeSelection {

    private int anchorRow = -1;

    /**
     * Records a normal series click or applies a Shift-click range.
     *
     * @return true when the range was applied and JTable should suppress its
     * normal single-cell checkbox handling for this mouse press.
     */
    boolean handlePress(ProjectManifestTableModel model, int rowIndex, boolean shiftDown) {
        if (model == null || rowIndex < 0 || rowIndex >= model.getRowCount()
                || !model.isSeriesRow(rowIndex)) {
            reset();
            return false;
        }
        if (shiftDown && model.canSetSeriesIncludeRange(anchorRow, rowIndex)) {
            boolean currentlyIncluded = Boolean.TRUE.equals(
                    model.getValueAt(rowIndex, ProjectManifestTableModel.COL_INCLUDE));
            model.setSeriesIncludeRange(anchorRow, rowIndex, !currentlyIncluded);
            return true;
        }
        anchorRow = rowIndex;
        return false;
    }

    void reset() {
        anchorRow = -1;
    }
}
