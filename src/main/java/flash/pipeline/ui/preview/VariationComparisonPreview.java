package flash.pipeline.ui.preview;

import ij.ImagePlus;

public final class VariationComparisonPreview {

    private VariationComparisonPreview() {
    }

    public static void showVariationComparison(ComparisonPreviewDialog dialog,
                                               ImagePlus leftLabel,
                                               String leftStatus,
                                               ImagePlus rightLabel,
                                               String rightStatus,
                                               ImagePlus rawSource,
                                               PreviewDisplaySettings rawSettings,
                                               ImagePlus filteredSource,
                                               PreviewDisplaySettings filteredSettings,
                                               int zSlice) {
        if (dialog == null) {
            return;
        }
        dialog.setSourceChoices(rawSource, rawSettings, filteredSource, filteredSettings);
        dialog.setImages(leftLabel, rightLabel, zSlice);
        dialog.setPreviewStatus(leftStatus, rightStatus);
        dialog.setObjectSizeGuide(null);
        dialog.raiseForUser();
    }
}
