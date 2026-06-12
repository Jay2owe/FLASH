package flash.pipeline.zslice;

import flash.pipeline.bin.BinConfig;
import flash.pipeline.image.ConfiguredChannelExtractor;
import flash.pipeline.image.ImageOps;
import ij.IJ;
import ij.ImagePlus;

/**
 * Applies saved Z-slice selections to opened image series.
 */
public final class ZSliceOps {
    private ZSliceOps() {}

    public static ImagePlus applyConfiguredRange(ImagePlus source, BinConfig cfg, int seriesIndex, String contextLabel) {
        if (source == null || cfg == null || !cfg.usesZSliceSubset()) return source;
        ZSliceSelection selection = cfg.getZSliceSelection(seriesIndex);
        if (selection == null) {
            IJ.log("WARNING: " + label(contextLabel) + "has no saved z-slice selection for series "
                    + (seriesIndex + 1) + ". Using full stack.");
            return source;
        }
        return applyRange(source, selection.range, selection.totalSlices, contextLabel,
                cfg == null ? 0 : cfg.numChannels());
    }

    public static ImagePlus applySelection(ImagePlus source, ZSliceSelection selection, String contextLabel) {
        if (source == null || selection == null) return source;
        return applyRange(source, selection.range, selection.totalSlices, contextLabel, 0);
    }

    public static ImagePlus applyRange(ImagePlus source, ZSliceRange range, int expectedTotalSlices, String contextLabel) {
        return applyRange(source, range, expectedTotalSlices, contextLabel, 0);
    }

    private static ImagePlus applyRange(ImagePlus source, ZSliceRange range, int expectedTotalSlices,
                                        String contextLabel, int configuredChannels) {
        if (source == null || range == null) return source;

        int actualSlices = Math.max(1, source.getNSlices());
        int configuredSlices = ConfiguredChannelExtractor.inferredConfiguredSliceCount(
                source, configuredChannels, expectedTotalSlices);
        boolean useConfiguredLayout = configuredSlices > 0;
        if (useConfiguredLayout) {
            actualSlices = configuredSlices;
        }
        if (expectedTotalSlices > 0 && actualSlices != expectedTotalSlices) {
            IJ.log("WARNING: " + label(contextLabel) + "expected " + expectedTotalSlices
                    + " slices but found " + actualSlices + " for " + source.getTitle()
                    + ". Validating against the actual stack.");
        }
        if (!range.isValidFor(actualSlices)) {
            IJ.log("WARNING: " + label(contextLabel) + "saved z-slice range " + range.toToken()
                    + " is invalid for " + source.getTitle() + " (" + actualSlices + " slices). Using full stack.");
            return source;
        }
        if (range.coversFullStack(actualSlices)) {
            return source;
        }

        if (useConfiguredLayout) {
            ImagePlus subset = ConfiguredChannelExtractor.duplicateConfiguredZRange(
                    source,
                    configuredChannels,
                    range.startSlice,
                    range.endSlice,
                    expectedTotalSlices);
            if (subset != null) {
                subset.setTitle(source.getTitle());
                return subset;
            }
        }

        ImagePlus subset = ImageOps.duplicateThreadSafe(source,
                1, Math.max(1, source.getNChannels()),
                range.startSlice, range.endSlice,
                1, Math.max(1, source.getNFrames()));
        if (subset != null) {
            subset.setTitle(source.getTitle());
        }
        return subset == null ? source : subset;
    }

    private static String label(String contextLabel) {
        return contextLabel == null || contextLabel.trim().isEmpty()
                ? ""
                : contextLabel.trim() + ": ";
    }
}
