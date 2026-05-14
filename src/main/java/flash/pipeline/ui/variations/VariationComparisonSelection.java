package flash.pipeline.ui.variations;

import java.util.function.Consumer;

final class VariationComparisonSelection {

    interface Opener {
        void openComparison(VariationCellPanel left, VariationCellPanel right);
    }

    private static final String WAIT_FOR_RENDERING = "Wait for both tiles to finish rendering.";
    private static final String PICK_SECOND = "Shift-click a second tile to compare.";
    private static final String CANCELLED = "Comparison cancelled.";

    private final Consumer<String> statusSink;
    private final Opener opener;
    private VariationCellPanel pendingCell;

    VariationComparisonSelection(Consumer<String> statusSink, Opener opener) {
        this.statusSink = statusSink;
        this.opener = opener;
    }

    void handleShiftClick(VariationCellPanel cell) {
        if (cell == null) {
            return;
        }
        if (!cell.hasCachedLabel()) {
            setStatus(WAIT_FOR_RENDERING);
            return;
        }
        if (pendingCell == null) {
            pendingCell = cell;
            cell.setSelectedForCompare(true);
            setStatus(PICK_SECOND);
            return;
        }
        if (pendingCell == cell) {
            clearWithStatus(CANCELLED);
            return;
        }
        if (!pendingCell.hasCachedLabel()) {
            clearSelection();
            setStatus(WAIT_FOR_RENDERING);
            return;
        }
        VariationCellPanel left = pendingCell;
        clearSelection();
        if (opener != null) {
            opener.openComparison(left, cell);
        }
    }

    void clearForAccept() {
        clearSelection();
    }

    void cancelSelection() {
        clearWithStatus(CANCELLED);
    }

    boolean hasPendingSelection() {
        return pendingCell != null;
    }

    private void clearWithStatus(String status) {
        clearSelection();
        setStatus(status);
    }

    private void clearSelection() {
        if (pendingCell != null) {
            pendingCell.setSelectedForCompare(false);
            pendingCell = null;
        }
    }

    private void setStatus(String status) {
        if (statusSink != null) {
            statusSink.accept(status);
        }
    }
}
