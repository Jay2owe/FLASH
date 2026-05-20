package flash.pipeline.decontamination;

import flash.pipeline.bin.BinConfig;
import flash.pipeline.io.DeferredImageSupplier;
import flash.pipeline.io.ImageSourceDispatcher;
import ij.IJ;
import ij.ImagePlus;
import ij.ImageStack;
import ij.measure.Calibration;
import ij.process.ImageProcessor;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Renders condition-aware Spectral Decontamination previews from the selected subset.
 */
public final class SpectralPreviewRenderer {

    private static final int THUMBNAIL_WIDTH = 170;
    private static final int THUMBNAIL_HEIGHT = 170;

    private static final Color PLACEHOLDER_BACKGROUND = new Color(244, 244, 244);
    private static final Color PLACEHOLDER_BORDER = new Color(210, 210, 210);
    private static final Color PLACEHOLDER_TEXT = new Color(90, 90, 90);

    private SpectralPreviewRenderer() {
    }

    public static List<RenderedPreview> render(String directory,
                                               BinConfig binConfig,
                                               SpectralDecontaminationConfig config,
                                               List<SpectralPreviewSelector.PreviewSelection> selections)
            throws Exception {
        if (binConfig == null) {
            throw new IllegalArgumentException("binConfig must not be null");
        }
        if (config == null) {
            throw new IllegalArgumentException("config must not be null");
        }
        if (selections == null || selections.isEmpty()) {
            return new ArrayList<RenderedPreview>();
        }

        List<RenderedPreview> rendered = new ArrayList<RenderedPreview>();
        DeferredImageSupplier supplier = ImageSourceDispatcher.createSupplier(directory);
        try {
            for (int i = 0; i < selections.size(); i++) {
                SpectralPreviewSelector.PreviewSelection selection = selections.get(i);
                if (selection == null || selection.candidate == null) {
                    continue;
                }
                IJ.showStatus("Rendering Spectral preview " + (i + 1) + "/" + selections.size());
                IJ.showProgress(i, selections.size());

                ImagePlus source = null;
                try {
                    source = supplier.openSeriesMaterialized(selection.candidate.seriesIndex);
                    if (source == null) {
                        throw new IllegalStateException("Series " + (selection.candidate.seriesIndex + 1)
                                + " could not be opened.");
                    }
                    ImagePlus preparedSource = applyConfiguredZSliceSubset(
                            binConfig,
                            selection.candidate.seriesIndex,
                            source,
                            "Spectral preview");
                    source = preparedSource;
                    rendered.add(renderLoadedImage(source, binConfig, config, selection));
                } finally {
                    closeImages(source);
                }
            }
        } finally {
            supplier.shutdownPrefetch();
            IJ.showProgress(1.0);
            IJ.showStatus("Spectral preview ready");
        }
        return rendered;
    }

    static RenderedPreview renderLoadedImage(ImagePlus source,
                                             BinConfig binConfig,
                                             SpectralDecontaminationConfig config,
                                             SpectralPreviewSelector.PreviewSelection selection) {
        if (source == null) {
            throw new IllegalArgumentException("source image must not be null");
        }
        if (binConfig == null) {
            throw new IllegalArgumentException("binConfig must not be null");
        }
        if (config == null) {
            throw new IllegalArgumentException("config must not be null");
        }
        if (selection == null || selection.candidate == null) {
            throw new IllegalArgumentException("selection must not be null");
        }

        CorrectionFeatureRegistry registry = CorrectionFeatureRegistry.getDefault();
        CorrectionPipeline.ExecutionState state = CorrectionPipeline.ExecutionState.create(source, config);
        config.getCorrectionPipeline().execute(registry, state);

        ImagePlus corrected = state.getCorrectedImage();
        ImagePlus mask = state.getMaskImage();
        SpectralPreviewSelector.ImageScores imageScores = SpectralPreviewSelector.scoreImage(source, config);

        int width = Math.max(1, source.getWidth());
        int height = Math.max(1, source.getHeight());

        RenderedImage rawTarget = buildChannelPreview(
                source,
                config.getTargetChannelIndex(),
                channelLabel(binConfig, config.getTargetChannelIndex()),
                THUMBNAIL_WIDTH,
                THUMBNAIL_HEIGHT);
        List<RenderedImage> bleedThrough = buildChannelGroup(
                source,
                config.getBleedThroughChannelIndexes(),
                binConfig,
                THUMBNAIL_WIDTH,
                THUMBNAIL_HEIGHT,
                "No bleed-through channels selected");
        List<RenderedImage> autofluorescence = buildChannelGroup(
                source,
                config.getAutofluorescenceChannelIndexes(),
                binConfig,
                THUMBNAIL_WIDTH,
                THUMBNAIL_HEIGHT,
                "No autofluorescence channels selected");

        RenderedImage correctedTarget = corrected == null
                ? placeholder("Corrected target", "Current stack does not create a corrected target.")
                : buildSingleChannelPreview(corrected, "Corrected target", THUMBNAIL_WIDTH, THUMBNAIL_HEIGHT);
        RenderedImage overlay = buildOverlayPreview(
                corrected == null ? source : corrected,
                corrected == null ? config.getTargetChannelIndex() : 0,
                mask,
                THUMBNAIL_WIDTH,
                THUMBNAIL_HEIGHT);

        PreviewMetrics metrics = PreviewMetrics.from(
                source,
                binConfig,
                state.getFeatureSummaries(),
                imageScores);

        closeImages(corrected, mask, state.getVetoMaskImage());
        closeImages(state.getParameterMaps().values().toArray(new ImagePlus[state.getParameterMaps().size()]));
        return new RenderedPreview(
                selection,
                rawTarget,
                bleedThrough,
                autofluorescence,
                correctedTarget,
                overlay,
                metrics,
                width,
                height);
    }

    private static List<RenderedImage> buildChannelGroup(ImagePlus source,
                                                         List<Integer> channelIndexes,
                                                         BinConfig binConfig,
                                                         int width,
                                                         int height,
                                                         String emptyMessage) {
        List<RenderedImage> rendered = new ArrayList<RenderedImage>();
        if (channelIndexes != null) {
            for (Integer channelIndex : channelIndexes) {
                if (channelIndex == null) {
                    continue;
                }
                rendered.add(buildChannelPreview(
                        source,
                        channelIndex.intValue(),
                        channelLabel(binConfig, channelIndex.intValue()),
                        width,
                        height));
            }
        }
        if (rendered.isEmpty()) {
            rendered.add(placeholder("None", emptyMessage));
        }
        return rendered;
    }

    private static RenderedImage buildChannelPreview(ImagePlus source,
                                                     int channelIndex,
                                                     String label,
                                                     int width,
                                                     int height) {
        if (channelIndex < 0 || channelIndex >= Math.max(1, source.getNChannels())) {
            return placeholder(label, "Channel is outside the current image.");
        }
        int[] projected = projectChannel(source, channelIndex);
        BufferedImage image = scale(renderGrayscale(projected, source.getWidth(), source.getHeight()), width, height);
        return new RenderedImage(label, image, false);
    }

    private static RenderedImage buildSingleChannelPreview(ImagePlus image,
                                                           String label,
                                                           int width,
                                                           int height) {
        int[] projected = projectSingleChannel(image);
        BufferedImage rendered = scale(renderGrayscale(projected, image.getWidth(), image.getHeight()), width, height);
        return new RenderedImage(label, rendered, false);
    }

    private static RenderedImage buildOverlayPreview(ImagePlus baseImage,
                                                     int channelIndex,
                                                     ImagePlus mask,
                                                     int width,
                                                     int height) {
        if (mask == null) {
            return placeholder("Final overlay", "Current stack does not create a final mask.");
        }
        int[] baseProjection;
        if (Math.max(1, baseImage.getNChannels()) == 1) {
            baseProjection = projectSingleChannel(baseImage);
        } else {
            baseProjection = projectChannel(baseImage, channelIndex);
        }
        boolean[] maskProjection = projectMask(mask);
        BufferedImage overlay = renderOverlay(baseProjection, maskProjection,
                baseImage.getWidth(), baseImage.getHeight());
        return new RenderedImage("Final overlay", scale(overlay, width, height), false);
    }

    private static ImagePlus applyConfiguredZSliceSubset(BinConfig cfg,
                                                         int seriesIndex,
                                                         ImagePlus source,
                                                         String contextLabel) {
        if (source == null || cfg == null || !cfg.usesZSliceSubset()) {
            return source;
        }
        if (Math.max(1, source.getNSlices()) <= 1) {
            return source;
        }

        if (cfg.getZSliceRange(seriesIndex) == null) {
            IJ.log("WARNING: " + contextLabel + ": no saved z-slice range for series "
                    + (seriesIndex + 1) + ". Using full stack.");
            return source;
        }
        if (!cfg.getZSliceRange(seriesIndex).isValidFor(Math.max(1, source.getNSlices()))
                || cfg.getZSliceRange(seriesIndex).coversFullStack(Math.max(1, source.getNSlices()))) {
            return source;
        }

        int channels = Math.max(1, source.getNChannels());
        int frames = Math.max(1, source.getNFrames());
        int startSlice = cfg.getZSliceRange(seriesIndex).startSlice;
        int endSlice = cfg.getZSliceRange(seriesIndex).endSlice;
        ImageStack subsetStack = new ImageStack(source.getWidth(), source.getHeight());

        for (int frame = 1; frame <= frames; frame++) {
            for (int slice = startSlice; slice <= endSlice; slice++) {
                for (int channel = 1; channel <= channels; channel++) {
                    int stackIndex = source.getStackIndex(channel, slice, frame);
                    ImageProcessor duplicateProcessor = source.getStack().getProcessor(stackIndex).duplicate();
                    subsetStack.addSlice(duplicateProcessor);
                }
            }
        }

        ImagePlus subset = new ImagePlus(source.getTitle(), subsetStack);
        subset.setDimensions(channels, endSlice - startSlice + 1, frames);
        if (channels > 1 || endSlice > startSlice || frames > 1) {
            subset.setOpenAsHyperStack(true);
        }
        if (source.getCalibration() != null) {
            subset.setCalibration(source.getCalibration().copy());
        }

        source.changes = false;
        source.close();
        source.flush();
        return subset;
    }

    private static int[] projectChannel(ImagePlus image, int channelIndex) {
        int width = image.getWidth();
        int height = image.getHeight();
        int[] projection = new int[width * height];
        int planes = CorrectionImageOps.planeCount(image);
        for (int plane = 0; plane < planes; plane++) {
            short[] pixels = CorrectionImageOps.channelPlanePixels(image, channelIndex, plane);
            for (int i = 0; i < pixels.length; i++) {
                int value = pixels[i] & 0xffff;
                if (value > projection[i]) {
                    projection[i] = value;
                }
            }
        }
        return projection;
    }

    private static int[] projectSingleChannel(ImagePlus image) {
        int width = image.getWidth();
        int height = image.getHeight();
        int[] projection = new int[width * height];
        int planes = CorrectionImageOps.planeCount(image);
        for (int plane = 0; plane < planes; plane++) {
            short[] pixels = CorrectionImageOps.singleChannelPlanePixels(image, plane);
            for (int i = 0; i < pixels.length; i++) {
                int value = pixels[i] & 0xffff;
                if (value > projection[i]) {
                    projection[i] = value;
                }
            }
        }
        return projection;
    }

    private static boolean[] projectMask(ImagePlus image) {
        int width = image.getWidth();
        int height = image.getHeight();
        boolean[] projection = new boolean[width * height];
        int planes = CorrectionImageOps.planeCount(image);
        for (int plane = 0; plane < planes; plane++) {
            byte[] pixels = CorrectionImageOps.singleChannelMaskPlanePixels(image, plane);
            for (int i = 0; i < pixels.length; i++) {
                if ((pixels[i] & 0xff) != 0) {
                    projection[i] = true;
                }
            }
        }
        return projection;
    }

    private static BufferedImage renderGrayscale(int[] pixels, int width, int height) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        if (pixels == null || pixels.length == 0) {
            return image;
        }

        int displayMax = percentile(pixels, 99.5);
        if (displayMax <= 0) {
            displayMax = max(pixels);
        }
        if (displayMax <= 0) {
            displayMax = 1;
        }

        for (int i = 0; i < pixels.length; i++) {
            int gray = (int) Math.round(255.0 * ((double) pixels[i] / (double) displayMax));
            if (gray < 0) gray = 0;
            if (gray > 255) gray = 255;
            int rgb = new Color(gray, gray, gray).getRGB();
            image.setRGB(i % width, i / width, rgb);
        }
        return image;
    }

    private static BufferedImage renderOverlay(int[] basePixels,
                                               boolean[] maskPixels,
                                               int width,
                                               int height) {
        BufferedImage base = renderGrayscale(basePixels, width, height);
        BufferedImage overlay = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int rgb = base.getRGB(x, y);
                if (maskPixels[y * width + x]) {
                    int gray = rgb & 0xff;
                    int red = Math.max(0, gray / 3);
                    int green = Math.min(255, gray / 2 + 140);
                    int blue = Math.max(0, gray / 3);
                    rgb = new Color(red, green, blue).getRGB();
                }
                overlay.setRGB(x, y, rgb);
            }
        }
        return overlay;
    }

    private static BufferedImage scale(BufferedImage source, int width, int height) {
        BufferedImage scaled = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g2 = scaled.createGraphics();
        try {
            g2.setColor(Color.WHITE);
            g2.fillRect(0, 0, width, height);
            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            double scale = Math.min((double) width / (double) source.getWidth(),
                    (double) height / (double) source.getHeight());
            int drawWidth = Math.max(1, (int) Math.round(source.getWidth() * scale));
            int drawHeight = Math.max(1, (int) Math.round(source.getHeight() * scale));
            int x = (width - drawWidth) / 2;
            int y = (height - drawHeight) / 2;
            g2.drawImage(source, x, y, drawWidth, drawHeight, null);
            g2.setColor(new Color(210, 210, 210));
            g2.drawRect(0, 0, width - 1, height - 1);
        } finally {
            g2.dispose();
        }
        return scaled;
    }

    private static RenderedImage placeholder(String label, String text) {
        BufferedImage image = new BufferedImage(THUMBNAIL_WIDTH, THUMBNAIL_HEIGHT, BufferedImage.TYPE_INT_RGB);
        Graphics2D g2 = image.createGraphics();
        try {
            g2.setColor(PLACEHOLDER_BACKGROUND);
            g2.fillRect(0, 0, THUMBNAIL_WIDTH, THUMBNAIL_HEIGHT);
            g2.setColor(PLACEHOLDER_BORDER);
            g2.drawRect(0, 0, THUMBNAIL_WIDTH - 1, THUMBNAIL_HEIGHT - 1);
            g2.setColor(PLACEHOLDER_TEXT);
            g2.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));

            List<String> lines = wrap(text, 22);
            int lineHeight = g2.getFontMetrics().getHeight();
            int totalHeight = lines.size() * lineHeight;
            int y = Math.max(24, (THUMBNAIL_HEIGHT - totalHeight) / 2);
            for (String line : lines) {
                int lineWidth = g2.getFontMetrics().stringWidth(line);
                int x = Math.max(8, (THUMBNAIL_WIDTH - lineWidth) / 2);
                g2.drawString(line, x, y);
                y += lineHeight;
            }
        } finally {
            g2.dispose();
        }
        return new RenderedImage(label, image, true);
    }

    private static List<String> wrap(String text, int maxChars) {
        if (text == null || text.trim().isEmpty()) {
            return Arrays.asList("");
        }
        List<String> lines = new ArrayList<String>();
        String[] words = text.trim().split("\\s+");
        StringBuilder current = new StringBuilder();
        for (String word : words) {
            if (current.length() == 0) {
                current.append(word);
            } else if (current.length() + 1 + word.length() <= maxChars) {
                current.append(' ').append(word);
            } else {
                lines.add(current.toString());
                current.setLength(0);
                current.append(word);
            }
        }
        if (current.length() > 0) {
            lines.add(current.toString());
        }
        return lines;
    }

    private static int percentile(int[] values, double percentile) {
        if (values == null || values.length == 0) {
            return 0;
        }
        int[] copy = Arrays.copyOf(values, values.length);
        Arrays.sort(copy);
        int index = (int) Math.ceil((percentile / 100.0) * copy.length) - 1;
        if (index < 0) index = 0;
        if (index >= copy.length) index = copy.length - 1;
        return copy[index];
    }

    private static int max(int[] values) {
        int max = 0;
        if (values == null) {
            return max;
        }
        for (int value : values) {
            if (value > max) {
                max = value;
            }
        }
        return max;
    }

    private static String channelLabel(BinConfig binConfig, int channelIndex) {
        if (channelIndex < 0) {
            return "Channel";
        }
        String name = binConfig == null || channelIndex >= binConfig.channelNames.size()
                ? ""
                : binConfig.channelNames.get(channelIndex);
        if (name == null || name.trim().isEmpty()) {
            name = "Channel " + (channelIndex + 1);
        }
        return (channelIndex + 1) + " - " + name;
    }

    private static void closeImages(ImagePlus... images) {
        if (images == null) {
            return;
        }
        Set<ImagePlus> seen = Collections.newSetFromMap(new IdentityHashMap<ImagePlus, Boolean>());
        for (ImagePlus image : images) {
            if (image == null || !seen.add(image)) {
                continue;
            }
            image.changes = false;
            image.close();
            image.flush();
        }
    }

    public static final class RenderedPreview {
        public final SpectralPreviewSelector.PreviewSelection selection;
        public final RenderedImage rawTarget;
        public final List<RenderedImage> bleedThroughChannels;
        public final List<RenderedImage> autofluorescenceChannels;
        public final RenderedImage correctedTarget;
        public final RenderedImage finalOverlay;
        public final PreviewMetrics metrics;
        public final int sourceWidth;
        public final int sourceHeight;

        private RenderedPreview(SpectralPreviewSelector.PreviewSelection selection,
                                RenderedImage rawTarget,
                                List<RenderedImage> bleedThroughChannels,
                                List<RenderedImage> autofluorescenceChannels,
                                RenderedImage correctedTarget,
                                RenderedImage finalOverlay,
                                PreviewMetrics metrics,
                                int sourceWidth,
                                int sourceHeight) {
            this.selection = selection;
            this.rawTarget = rawTarget;
            this.bleedThroughChannels = bleedThroughChannels == null
                    ? new ArrayList<RenderedImage>()
                    : new ArrayList<RenderedImage>(bleedThroughChannels);
            this.autofluorescenceChannels = autofluorescenceChannels == null
                    ? new ArrayList<RenderedImage>()
                    : new ArrayList<RenderedImage>(autofluorescenceChannels);
            this.correctedTarget = correctedTarget;
            this.finalOverlay = finalOverlay;
            this.metrics = metrics;
            this.sourceWidth = sourceWidth;
            this.sourceHeight = sourceHeight;
        }
    }

    public static final class RenderedImage {
        public final String label;
        public final BufferedImage image;
        public final boolean placeholder;

        RenderedImage(String label, BufferedImage image, boolean placeholder) {
            this.label = label == null ? "" : label.trim();
            this.image = image;
            this.placeholder = placeholder;
        }
    }

    public static final class PreviewMetrics {
        public final Long targetPositiveVoxels;
        public final String targetPositiveLabel;
        public final Integer objectsKept;
        public final Integer objectsRemoved;
        public final double saturatedFraction;
        public final List<String> warningLines;
        public final List<String> coefficientLines;
        public final List<String> detailLines;

        private PreviewMetrics(Long targetPositiveVoxels,
                               String targetPositiveLabel,
                               Integer objectsKept,
                               Integer objectsRemoved,
                               double saturatedFraction,
                               List<String> warningLines,
                               List<String> coefficientLines,
                               List<String> detailLines) {
            this.targetPositiveVoxels = targetPositiveVoxels;
            this.targetPositiveLabel = targetPositiveLabel == null ? "" : targetPositiveLabel;
            this.objectsKept = objectsKept;
            this.objectsRemoved = objectsRemoved;
            this.saturatedFraction = saturatedFraction;
            this.warningLines = warningLines == null
                    ? new ArrayList<String>()
                    : new ArrayList<String>(warningLines);
            this.coefficientLines = coefficientLines == null
                    ? new ArrayList<String>()
                    : new ArrayList<String>(coefficientLines);
            this.detailLines = detailLines == null
                    ? new ArrayList<String>()
                    : new ArrayList<String>(detailLines);
        }

        public static PreviewMetrics from(ImagePlus source,
                                          BinConfig binConfig,
                                          List<CorrectionPipeline.FeatureSummary> featureSummaries,
                                          SpectralPreviewSelector.ImageScores imageScores) {
            Map<String, String> byMetric = flatten(featureSummaries);

            Long targetPositive = firstLong(byMetric,
                    "kept_voxels",
                    "kept_pixels",
                    "positive_pixels",
                    "candidate_pixels");
            Integer objectsKept = firstInteger(byMetric, "kept_components", "objects_kept");
            Integer objectsRemoved = firstInteger(byMetric, "removed_components", "objects_removed", "vetoed_pixels");

            List<String> warnings = extractWarningLines(byMetric);
            List<String> coefficients = extractCoefficientLines(byMetric, binConfig);
            List<String> details = extractDetailLines(byMetric, binConfig);
            String positiveLabel = formatPositiveLabel(source, targetPositive);
            double saturated = imageScores == null ? 0.0 : imageScores.saturatedFraction;

            return new PreviewMetrics(
                    targetPositive,
                    positiveLabel,
                    objectsKept,
                    objectsRemoved,
                    saturated,
                    warnings,
                    coefficients,
                    details);
        }

        public boolean hasObjectCounts() {
            return objectsKept != null || objectsRemoved != null;
        }

        private static Map<String, String> flatten(List<CorrectionPipeline.FeatureSummary> summaries) {
            LinkedHashMap<String, String> values = new LinkedHashMap<String, String>();
            if (summaries == null) {
                return values;
            }
            for (CorrectionPipeline.FeatureSummary summary : summaries) {
                if (summary == null) {
                    continue;
                }
                for (Map.Entry<String, String> entry : summary.getValues().entrySet()) {
                    if (entry.getKey() == null || entry.getKey().trim().isEmpty()) {
                        continue;
                    }
                    if (entry.getValue() == null || entry.getValue().trim().isEmpty()) {
                        continue;
                    }
                    values.put(entry.getKey().trim().toLowerCase(Locale.US), entry.getValue().trim());
                }
            }
            return values;
        }

        private static Long firstLong(Map<String, String> values, String... keys) {
            if (values == null || keys == null) {
                return null;
            }
            for (String key : keys) {
                if (key == null) {
                    continue;
                }
                String value = values.get(key.toLowerCase(Locale.US));
                if (value == null || value.trim().isEmpty()) {
                    continue;
                }
                try {
                    return Long.valueOf(Math.round(Double.parseDouble(value.trim())));
                } catch (NumberFormatException ignored) {
                    return null;
                }
            }
            return null;
        }

        private static Integer firstInteger(Map<String, String> values, String... keys) {
            Long value = firstLong(values, keys);
            return value == null ? null : Integer.valueOf(value.intValue());
        }

        private static List<String> extractCoefficientLines(Map<String, String> values, BinConfig binConfig) {
            List<String> lines = new ArrayList<String>();
            if (values == null) {
                return lines;
            }
            for (Map.Entry<String, String> entry : values.entrySet()) {
                String key = entry.getKey();
                if (key == null) {
                    continue;
                }
                if (!(key.startsWith("weight_") || key.contains("coefficient"))) {
                    continue;
                }
                String label = key.startsWith("weight_channel_")
                        ? channelMetricLabel(binConfig, key.substring("weight_channel_".length()))
                        : prettifyMetricName(key);
                lines.add(label + ": " + formatNumber(entry.getValue()));
            }
            return lines;
        }

        private static List<String> extractWarningLines(Map<String, String> values) {
            List<String> lines = new ArrayList<String>();
            if (values == null) {
                return lines;
            }
            for (Map.Entry<String, String> entry : values.entrySet()) {
                String key = entry.getKey();
                String value = entry.getValue();
                if (key == null || value == null || value.trim().isEmpty()) {
                    continue;
                }
                if (key.startsWith("warning_") && !"warning_count".equals(key)) {
                    lines.add(value.trim());
                }
            }
            return lines;
        }

        private static List<String> extractDetailLines(Map<String, String> values, BinConfig binConfig) {
            List<String> lines = new ArrayList<String>();
            if (values == null) {
                return lines;
            }
            for (Map.Entry<String, String> entry : values.entrySet()) {
                String key = entry.getKey();
                if (key == null || key.trim().isEmpty()) {
                    continue;
                }
                if (key.startsWith("warning_")) {
                    continue;
                }
                if (key.startsWith("weight_") || key.contains("coefficient")) {
                    continue;
                }
                if ("positive_pixels".equals(key)
                        || "kept_pixels".equals(key)
                        || "kept_voxels".equals(key)
                        || "kept_components".equals(key)
                        || "removed_components".equals(key)
                        || "vetoed_pixels".equals(key)
                        || "candidate_pixels".equals(key)) {
                    continue;
                }
                if (key.startsWith("threshold_channel_")
                        || key.startsWith("contaminant_threshold_channel_")) {
                    lines.add(channelMetricLabel(binConfig, digitsAtEnd(key)) + ": " + formatNumber(entry.getValue()));
                    continue;
                }
                if ("target_threshold".equals(key) || "threshold".equals(key)) {
                    lines.add("Threshold: " + formatNumber(entry.getValue()));
                } else if ("selected_false_positive_rate".equals(key)) {
                    lines.add("Control false-positive rate: " + formatNumber(entry.getValue()));
                } else if ("selected_experimental_retention".equals(key)) {
                    lines.add("Experimental retention: " + formatNumber(entry.getValue()));
                } else if ("allowed_false_positive_rate".equals(key)) {
                    lines.add("Allowed false-positive rate: " + formatNumber(entry.getValue()));
                } else if ("grid_point_count".equals(key)) {
                    lines.add("ROC grid points: " + entry.getValue());
                } else if ("search_scope".equals(key)) {
                    lines.add("ROC search scope: " + entry.getValue());
                }
            }
            return lines;
        }

        private static String formatPositiveLabel(ImagePlus source, Long positiveVoxels) {
            if (positiveVoxels == null) {
                return "Target-positive volume: not available";
            }
            if (source == null || source.getCalibration() == null) {
                return "Target-positive volume: " + positiveVoxels + " voxels";
            }
            Calibration calibration = source.getCalibration();
            if (calibration.pixelWidth <= 0.0 || calibration.pixelHeight <= 0.0 || calibration.pixelDepth <= 0.0) {
                return "Target-positive volume: " + positiveVoxels + " voxels";
            }
            String unit = calibration.getUnit();
            if (unit == null || unit.trim().isEmpty()
                    || "pixel".equalsIgnoreCase(unit)
                    || "pixels".equalsIgnoreCase(unit)) {
                return "Target-positive volume: " + positiveVoxels + " voxels";
            }

            double voxelVolume = calibration.pixelWidth * calibration.pixelHeight * calibration.pixelDepth;
            double positiveVolume = positiveVoxels.longValue() * voxelVolume;
            DecimalFormat format = new DecimalFormat("0.###");
            return "Target-positive volume: " + format.format(positiveVolume) + " "
                    + unit.trim() + "^3 (" + positiveVoxels + " voxels)";
        }

        private static String channelMetricLabel(BinConfig binConfig, String channelToken) {
            try {
                int channelIndex = Integer.parseInt(channelToken.trim()) - 1;
                return channelLabel(binConfig, channelIndex);
            } catch (NumberFormatException e) {
                return "Channel " + channelToken;
            }
        }

        private static String digitsAtEnd(String key) {
            int end = key.length() - 1;
            while (end >= 0 && Character.isDigit(key.charAt(end))) {
                end--;
            }
            return key.substring(end + 1);
        }

        private static String prettifyMetricName(String key) {
            String[] parts = key.split("_");
            StringBuilder sb = new StringBuilder();
            for (String part : parts) {
                if (part == null || part.isEmpty()) {
                    continue;
                }
                if (sb.length() > 0) {
                    sb.append(' ');
                }
                sb.append(Character.toUpperCase(part.charAt(0)));
                if (part.length() > 1) {
                    sb.append(part.substring(1));
                }
            }
            return sb.toString();
        }

        private static String formatNumber(String value) {
            if (value == null || value.trim().isEmpty()) {
                return "";
            }
            try {
                double parsed = Double.parseDouble(value.trim());
                DecimalFormat format = new DecimalFormat("0.###");
                return format.format(parsed);
            } catch (NumberFormatException e) {
                return value.trim();
            }
        }
    }
}
