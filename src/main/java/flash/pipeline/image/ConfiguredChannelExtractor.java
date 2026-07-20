package flash.pipeline.image;

import ij.ImagePlus;
import ij.ImageStack;
import ij.measure.Calibration;
import ij.process.ImageProcessor;

/**
 * Extracts configured setup channels from images whose ImageJ dimensions may
 * under-report Bio-Formats channel planes as a single interleaved stack.
 */
public final class ConfiguredChannelExtractor {
    private ConfiguredChannelExtractor() {}

    public static ImagePlus duplicateChannel(ImagePlus source, int channelNum,
                                             int expectedChannels) {
        if (source == null) return null;
        int requested = Math.max(1, channelNum);
        int reportedChannels = Math.max(1, source.getNChannels());
        int configuredChannels = Math.max(0, expectedChannels);

        ConfiguredLayout configuredLayout = configuredLayout(source, configuredChannels, 0);
        if (configuredLayout != null && configuredChannels > reportedChannels
                && configuredChannels >= requested) {
            return duplicateInterleavedConfiguredChannel(source, requested, configuredLayout);
        }

        if (reportedChannels >= requested) {
            ImagePlus duplicate = ImageOps.duplicateThreadSafe(
                    source,
                    requested,
                    requested,
                    1,
                    Math.max(1, source.getNSlices()),
                    1,
                    Math.max(1, source.getNFrames()));
            if (duplicate != null) {
                return duplicate;
            }
        }

        if (configuredLayout != null && configuredChannels >= requested) {
            return duplicateInterleavedConfiguredChannel(source, requested, configuredLayout);
        }

        if (requested == 1 && reportedChannels == 1) {
            return ImageOps.duplicateThreadSafe(source);
        }

        throw new IllegalStateException("Cannot extract C" + requested
                + " from " + safe(source.getTitle())
                + ": image reports " + reportedChannels
                + " channel(s), configured setup has " + configuredChannels + ".");
    }

    public static int inferredConfiguredSliceCount(ImagePlus source,
                                                   int configuredChannels,
                                                   int expectedTotalSlices) {
        ConfiguredLayout layout = configuredLayout(source, configuredChannels, expectedTotalSlices);
        return layout == null ? -1 : layout.zSlices;
    }

    public static ImagePlus duplicateConfiguredZRange(ImagePlus source,
                                                      int configuredChannels,
                                                      int firstZ,
                                                      int lastZ,
                                                      int expectedTotalSlices) {
        ConfiguredLayout layout = configuredLayout(source, configuredChannels, expectedTotalSlices);
        if (layout == null) return null;
        int start = Math.max(1, firstZ);
        int end = Math.min(Math.max(start, lastZ), layout.zSlices);
        ImageStack in = source.getStack();
        ImageStack out = new ImageStack(source.getWidth(), source.getHeight());
        for (int t = 0; t < layout.frames; t++) {
            for (int z = start - 1; z <= end - 1; z++) {
                for (int c = 1; c <= layout.channels; c++) {
                    int sourceIndex = stackIndex(layout, c, z, t);
                    ImageProcessor processor = in.getProcessor(sourceIndex);
                    if (processor == null) {
                        throw new IllegalStateException("Cannot extract configured z-range from "
                                + safe(source.getTitle()) + ": source plane "
                                + sourceIndex + " is empty.");
                    }
                    out.addSlice(in.getSliceLabel(sourceIndex), processor.duplicate());
                }
            }
        }
        ImagePlus duplicate = new ImagePlus(source.getTitle(), out);
        copyCalibration(source, duplicate);
        duplicate.setDimensions(layout.channels, end - start + 1, layout.frames);
        duplicate.setOpenAsHyperStack(layout.channels > 1 || end > start || layout.frames > 1);
        copyDisplayRange(source, duplicate);
        return duplicate;
    }

    private static ImagePlus duplicateInterleavedConfiguredChannel(ImagePlus source,
                                                                   int channelNum,
                                                                   ConfiguredLayout layout) {
        ImageStack in = source.getStack();
        if (in == null) return null;
        ImageStack out = new ImageStack(source.getWidth(), source.getHeight());
        for (int t = 0; t < layout.frames; t++) {
            for (int z = 0; z < layout.zSlices; z++) {
                int sourceIndex = stackIndex(layout, channelNum, z, t);
                ImageProcessor processor = in.getProcessor(sourceIndex);
                if (processor == null) {
                    throw new IllegalStateException("Cannot extract C" + channelNum
                            + " from " + safe(source.getTitle())
                            + ": source plane " + sourceIndex + " is empty.");
                }
                out.addSlice(in.getSliceLabel(sourceIndex), processor.duplicate());
            }
        }
        ImagePlus duplicate = new ImagePlus(source.getTitle(), out);
        copyCalibration(source, duplicate);
        duplicate.setDimensions(1, layout.zSlices, layout.frames);
        duplicate.setOpenAsHyperStack(layout.frames > 1);
        copyDisplayRange(source, duplicate);
        return duplicate;
    }

    private static ConfiguredLayout configuredLayout(ImagePlus source,
                                                     int configuredChannels,
                                                     int expectedTotalSlices) {
        if (source == null || source.getStack() == null) return null;
        int channels = Math.max(0, configuredChannels);
        if (channels <= 1) return null;
        int reportedChannels = Math.max(1, source.getNChannels());
        if (reportedChannels >= channels) return null;
        int totalPlanes = Math.max(1, source.getStackSize());

        int expectedSlices = Math.max(0, expectedTotalSlices);
        if (expectedSlices > 0) {
            int planesPerTimepoint = channels * expectedSlices;
            if (planesPerTimepoint > 0 && totalPlanes % planesPerTimepoint == 0) {
                return new ConfiguredLayout(channels, expectedSlices,
                        Math.max(1, totalPlanes / planesPerTimepoint));
            }
        }

        int reportedFrames = Math.max(1, source.getNFrames());
        if (reportedFrames > 1 && totalPlanes % (channels * reportedFrames) == 0) {
            return new ConfiguredLayout(channels,
                    Math.max(1, totalPlanes / (channels * reportedFrames)),
                    reportedFrames);
        }

        if (totalPlanes % channels == 0) {
            return new ConfiguredLayout(channels, Math.max(1, totalPlanes / channels), 1);
        }
        return null;
    }

    private static int stackIndex(ConfiguredLayout layout, int channelNum, int zeroBasedZ,
                                  int zeroBasedFrame) {
        return (zeroBasedFrame * layout.channels * layout.zSlices)
                + (zeroBasedZ * layout.channels)
                + channelNum;
    }

    private static void copyCalibration(ImagePlus source, ImagePlus target) {
        Calibration calibration = source == null ? null : source.getCalibration();
        if (calibration != null) {
            target.setCalibration(calibration.copy());
        }
    }

    private static void copyDisplayRange(ImagePlus source, ImagePlus target) {
        if (source == null || target == null) return;
        try {
            target.setDisplayRange(source.getDisplayRangeMin(), source.getDisplayRangeMax());
        } catch (RuntimeException ignored) {
            // Display range is presentation-only; extraction should not fail because of it.
        }
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static final class ConfiguredLayout {
        final int channels;
        final int zSlices;
        final int frames;

        ConfiguredLayout(int channels, int zSlices, int frames) {
            this.channels = channels;
            this.zSlices = zSlices;
            this.frames = frames;
        }
    }
}
