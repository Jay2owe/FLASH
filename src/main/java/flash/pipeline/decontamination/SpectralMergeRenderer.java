package flash.pipeline.decontamination;

import ij.ImagePlus;
import ij.ImageStack;
import ij.process.ColorProcessor;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Builds colored "before"/"after" merge stacks for Spectral Decontamination previews. The target
 * channel is rendered in green; each contaminant (bleed-through then autofluorescence) gets the
 * next colour from a fixed palette (magenta, red, yellow, cyan, blue), additively blended per Z
 * plane into a {@link ColorProcessor} stack that the variations grid renders and scrolls in Z.
 *
 * <p>Display scales are computed once from the raw source and reused for both the before merge
 * (raw target) and every after merge (corrected target), so a genuine intensity drop after
 * correction is visible rather than hidden by per-image renormalisation.</p>
 */
public final class SpectralMergeRenderer {

    /** Additive RGB weights (0..1) for the contaminant palette, in display order. */
    private static final float[][] CONTAMINANT_PALETTE = {
            {1f, 0f, 1f}, // Magenta
            {1f, 0f, 0f}, // Red
            {1f, 1f, 0f}, // Yellow
            {0f, 1f, 1f}, // Cyan
            {0f, 0f, 1f}  // Blue
    };
    private static final float[] TARGET_RGB = {0f, 1f, 0f}; // Green

    private SpectralMergeRenderer() {
    }

    /** Ordered contaminant channel indices: bleed-through first, then autofluorescence, de-duplicated. */
    public static List<Integer> contaminantChannels(SpectralDecontaminationConfig config) {
        LinkedHashSet<Integer> ordered = new LinkedHashSet<Integer>();
        if (config != null) {
            ordered.addAll(config.getBleedThroughChannelIndexes());
            ordered.addAll(config.getAutofluorescenceChannelIndexes());
            ordered.remove(Integer.valueOf(config.getTargetChannelIndex()));
        }
        return new ArrayList<Integer>(ordered);
    }

    /** Per-channel display maxima (p99.5) read once from the raw source, reused across combos. */
    public static DisplayScales computeScales(ImagePlus source, SpectralDecontaminationConfig config) {
        if (source == null || config == null) {
            throw new IllegalArgumentException("source and config must not be null");
        }
        int targetMax = percentileForChannel(source, config.getTargetChannelIndex(), 99.5);
        List<Integer> contaminants = contaminantChannels(config);
        int[] contaminantMax = new int[contaminants.size()];
        for (int i = 0; i < contaminants.size(); i++) {
            contaminantMax[i] = percentileForChannel(source, contaminants.get(i).intValue(), 99.5);
        }
        return new DisplayScales(targetMax, contaminants, contaminantMax);
    }

    /** Before merge: raw target (green) + contaminants, using the shared scales. */
    public static ImagePlus buildBeforeMerge(ImagePlus source,
                                             SpectralDecontaminationConfig config,
                                             DisplayScales scales,
                                             String title) {
        return buildMerge(source, config.getTargetChannelIndex(), false, source, config, scales, title);
    }

    /** After merge: corrected target (green) + contaminants from the raw source, using the shared scales. */
    public static ImagePlus buildAfterMerge(ImagePlus source,
                                            ImagePlus correctedTarget,
                                            SpectralDecontaminationConfig config,
                                            DisplayScales scales,
                                            String title) {
        if (correctedTarget == null) {
            // No corrected image produced (e.g. mask-only or measure-only goal); fall back to raw target.
            return buildMerge(source, config.getTargetChannelIndex(), false, source, config, scales, title);
        }
        return buildMerge(correctedTarget, 0, true, source, config, scales, title);
    }

    private static ImagePlus buildMerge(ImagePlus targetImage,
                                        int targetChannelIndex,
                                        boolean targetIsSingleChannel,
                                        ImagePlus contaminantSource,
                                        SpectralDecontaminationConfig config,
                                        DisplayScales scales,
                                        String title) {
        int width = targetImage.getWidth();
        int height = targetImage.getHeight();
        int planes = CorrectionImageOps.planeCount(targetImage);
        List<Integer> contaminants = scales.contaminantChannels;

        ImageStack stack = new ImageStack(width, height);
        for (int plane = 0; plane < planes; plane++) {
            int[] rgb = new int[width * height];

            short[] targetPixels = targetIsSingleChannel
                    ? CorrectionImageOps.singleChannelPlanePixels(targetImage, plane)
                    : CorrectionImageOps.channelPlanePixels(targetImage, targetChannelIndex, plane);
            addChannel(rgb, targetPixels, scales.targetMax, TARGET_RGB);

            for (int c = 0; c < contaminants.size(); c++) {
                short[] pixels = CorrectionImageOps.channelPlanePixels(
                        contaminantSource, contaminants.get(c).intValue(), plane);
                addChannel(rgb, pixels, scales.contaminantMax[c],
                        CONTAMINANT_PALETTE[c % CONTAMINANT_PALETTE.length]);
            }
            stack.addSlice(new ColorProcessor(width, height, rgb));
        }

        ImagePlus merge = new ImagePlus(title == null ? "merge" : title, stack);
        merge.setDimensions(1, planes, 1);
        merge.setOpenAsHyperStack(planes > 1);
        if (targetImage.getCalibration() != null) {
            merge.setCalibration(targetImage.getCalibration().copy());
        }
        return merge;
    }

    private static void addChannel(int[] rgb, short[] pixels, int displayMax, float[] color) {
        if (pixels == null) {
            return;
        }
        int max = displayMax <= 0 ? 1 : displayMax;
        for (int i = 0; i < rgb.length && i < pixels.length; i++) {
            int value = pixels[i] & 0xffff;
            int intensity = (int) Math.round(255.0 * ((double) value / (double) max));
            if (intensity <= 0) {
                continue;
            }
            if (intensity > 255) {
                intensity = 255;
            }
            int packed = rgb[i];
            int r = clamp(((packed >> 16) & 0xff) + Math.round(color[0] * intensity));
            int g = clamp(((packed >> 8) & 0xff) + Math.round(color[1] * intensity));
            int b = clamp((packed & 0xff) + Math.round(color[2] * intensity));
            rgb[i] = (r << 16) | (g << 8) | b;
        }
    }

    private static int clamp(int value) {
        if (value < 0) return 0;
        if (value > 255) return 255;
        return value;
    }

    private static int percentileForChannel(ImagePlus image, int channelIndex, double percentile) {
        if (channelIndex < 0 || channelIndex >= Math.max(1, image.getNChannels())) {
            return 1;
        }
        int width = image.getWidth();
        int height = image.getHeight();
        int planes = CorrectionImageOps.planeCount(image);
        int[] all = new int[width * height * Math.max(1, planes)];
        int n = 0;
        for (int plane = 0; plane < planes; plane++) {
            short[] pixels = CorrectionImageOps.channelPlanePixels(image, channelIndex, plane);
            for (int i = 0; i < pixels.length; i++) {
                all[n++] = pixels[i] & 0xffff;
            }
        }
        if (n == 0) {
            return 1;
        }
        int[] copy = Arrays.copyOf(all, n);
        Arrays.sort(copy);
        int index = (int) Math.ceil((percentile / 100.0) * copy.length) - 1;
        if (index < 0) index = 0;
        if (index >= copy.length) index = copy.length - 1;
        int value = copy[index];
        return value <= 0 ? Math.max(1, copy[copy.length - 1]) : value;
    }

    /** Immutable per-channel display maxima plus the ordered contaminant channel list. */
    public static final class DisplayScales {
        final int targetMax;
        final List<Integer> contaminantChannels;
        final int[] contaminantMax;

        DisplayScales(int targetMax, List<Integer> contaminantChannels, int[] contaminantMax) {
            this.targetMax = Math.max(1, targetMax);
            this.contaminantChannels = contaminantChannels;
            this.contaminantMax = contaminantMax;
        }
    }
}
