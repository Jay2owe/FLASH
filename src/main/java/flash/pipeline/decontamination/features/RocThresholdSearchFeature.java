package flash.pipeline.decontamination.features;

import flash.pipeline.decontamination.CorrectionFeature;
import flash.pipeline.decontamination.CorrectionImageOps;
import flash.pipeline.decontamination.CorrectionPipeline;
import flash.pipeline.decontamination.RocSearchResult;
import flash.pipeline.decontamination.SpectralDecontaminationConfig;
import ij.ImagePlus;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * Applies a condition-calibrated threshold selected by ROC search.
 */
public class RocThresholdSearchFeature implements CorrectionPipeline.ExecutableFeature {

    public static final String ID = "roc_threshold_search";

    private static final String METRIC = "metric";
    private static final String ALLOWED_FALSE_POSITIVE_RATE = "allowed_false_positive_rate";
    private static final String THRESHOLD_MIN = "threshold_min";
    private static final String THRESHOLD_MAX = "threshold_max";
    private static final String THRESHOLD_STEP = "threshold_step";
    private static final String SELECTED_THRESHOLD = "selected_threshold";
    private static final String SELECTED_FALSE_POSITIVE_RATE = "selected_false_positive_rate";
    private static final String SELECTED_EXPERIMENTAL_RETENTION = "selected_experimental_retention";
    private static final String SELECTED_EXPERIMENTAL_MEAN_METRIC = "selected_experimental_mean_metric";
    private static final String SELECTED_CONTROL_POSITIVE_COUNT = "selected_control_positive_count";
    private static final String SELECTED_EXPERIMENTAL_POSITIVE_COUNT = "selected_experimental_positive_count";
    private static final String CONTROL_IMAGE_COUNT = "control_image_count";
    private static final String EXPERIMENTAL_IMAGE_COUNT = "experimental_image_count";
    private static final String GRID_POINT_COUNT = "grid_point_count";
    private static final String SEARCH_SCOPE = "search_scope";
    private static final String SEARCH_MESSAGE = "search_message";
    private static final String TARGET_MIN_THRESHOLD = "target_min_threshold";
    private static final double EPSILON = 1e-9;
    private static final int MAX_GRID_POINTS = 10000;

    private final Set<RequiredChannel> requiredChannels =
            Collections.singleton(RequiredChannel.TARGET);

    @Override
    public String getId() {
        return ID;
    }

    @Override
    public String getDisplayName() {
        return "ROC threshold search";
    }

    @Override
    public String getDescription() {
        return "Searches a threshold grid that rejects controls while retaining experimental signal.";
    }

    @Override
    public InputType getRequiredInputType() {
        return InputType.CORRECTED_IMAGE;
    }

    @Override
    public OutputType getOutputType() {
        return OutputType.MASK;
    }

    @Override
    public Set<RequiredChannel> getRequiredChannels() {
        return requiredChannels;
    }

    @Override
    public boolean requiresConditions() {
        return true;
    }

    @Override
    public boolean requiresControls() {
        return true;
    }

    @Override
    public boolean requiresExistingObjectMaps() {
        return false;
    }

    @Override
    public boolean canPreviewCheaply() {
        return false;
    }

    @Override
    public boolean isExpertOnly() {
        return false;
    }

    @Override
    public boolean isThresholdFeature() {
        return true;
    }

    @Override
    public boolean requiresVetoMask() {
        return false;
    }

    @Override
    public void apply(CorrectionPipeline.ExecutionState state) {
        if (state == null) {
            throw new IllegalArgumentException("Execution state is required.");
        }
        SpectralDecontaminationConfig config = state.getConfig();
        if (config == null) {
            throw new IllegalArgumentException("Spectral Decontamination config is required.");
        }

        ImagePlus corrected = state.getCorrectedImage();
        CorrectionImageOps.requireSingleChannel16Bit(corrected, "Corrected");

        Settings settings = Settings.from(state.getFeatureSettings(ID));
        if (!settings.hasSelectedThreshold()) {
            throw new IllegalStateException(
                    "ROC threshold search has not been calibrated. Run preview or batch ROC search first.");
        }

        Metric metric = settings.getMetric();
        ImagePlus source = state.getSourceImage();
        List<Integer> contaminantChannels = CorrectionImageOps.contaminantChannels(config);
        if (metric == Metric.TARGET_TO_CONTAMINANT_RATIO) {
            CorrectionImageOps.require16Bit(source, "Source");
            if (contaminantChannels.isEmpty()) {
                throw new IllegalArgumentException(
                        "Target-to-contaminant ratio ROC search requires at least one contaminant channel.");
            }
        }

        double threshold = settings.getSelectedThreshold();
        int planeCount = CorrectionImageOps.planeCount(corrected);
        byte[][] maskPlanes = new byte[planeCount][];
        long positivePixels = 0L;
        long totalPixels = 0L;

        for (int plane = 0; plane < planeCount; plane++) {
            short[] correctedPixels = CorrectionImageOps.singleChannelPlanePixels(corrected, plane);
            short[][] contaminantPixels = metric == Metric.TARGET_TO_CONTAMINANT_RATIO
                    ? loadContaminants(source, contaminantChannels, plane)
                    : null;
            byte[] mask = new byte[correctedPixels.length];
            for (int pixel = 0; pixel < correctedPixels.length; pixel++) {
                totalPixels++;
                boolean positive;
                if (metric == Metric.TARGET_TO_CONTAMINANT_RATIO) {
                    double correctedValue = correctedPixels[pixel] & 0xffff;
                    double ratio = correctedValue / Math.max(1.0, maxContaminant(contaminantPixels, pixel));
                    positive = correctedValue >= settings.getTargetMinThreshold() && ratio >= threshold;
                } else {
                    positive = (correctedPixels[pixel] & 0xffff) >= threshold;
                }
                if (positive) {
                    mask[pixel] = (byte) 255;
                    positivePixels++;
                }
            }
            maskPlanes[plane] = mask;
        }

        state.setMaskImage(CorrectionImageOps.createMaskImageLike(
                corrected,
                "roc_threshold_search_mask",
                maskPlanes));

        state.addSummary(new CorrectionPipeline.FeatureSummary(ID, getDisplayName())
                .put("metric", metric.getKey())
                .put("metric_label", metric.getLabel())
                .putDouble(ALLOWED_FALSE_POSITIVE_RATE, settings.getAllowedFalsePositiveRate())
                .putDouble(THRESHOLD_MIN, settings.getThresholdMin())
                .putDouble(THRESHOLD_MAX, settings.getThresholdMax())
                .putDouble(THRESHOLD_STEP, settings.getThresholdStep())
                .putDouble("threshold", threshold)
                .putDouble(SELECTED_THRESHOLD, threshold)
                .putDouble(SELECTED_FALSE_POSITIVE_RATE, settings.getSelectedFalsePositiveRate())
                .putDouble(SELECTED_EXPERIMENTAL_RETENTION, settings.getSelectedExperimentalRetention())
                .putDouble(SELECTED_EXPERIMENTAL_MEAN_METRIC, settings.getSelectedExperimentalMeanMetric())
                .putInt(SELECTED_CONTROL_POSITIVE_COUNT, settings.getSelectedControlPositiveCount())
                .putInt(SELECTED_EXPERIMENTAL_POSITIVE_COUNT, settings.getSelectedExperimentalPositiveCount())
                .putInt(CONTROL_IMAGE_COUNT, settings.getControlImageCount())
                .putInt(EXPERIMENTAL_IMAGE_COUNT, settings.getExperimentalImageCount())
                .putInt(GRID_POINT_COUNT, settings.getGridPointCount())
                .put(SEARCH_SCOPE, settings.getSearchScope())
                .put(SEARCH_MESSAGE, settings.getSearchMessage())
                .putInt("positive_pixels", positivePixels)
                .putInt("total_pixels", totalPixels)
                .putDouble("positive_fraction", totalPixels <= 0L
                        ? 0.0
                        : (double) positivePixels / (double) totalPixels));
    }

    public static List<Double> buildThresholdGrid(Settings settings) {
        Settings resolved = settings == null ? new Settings() : settings.copy();
        resolved.normalizeGrid();
        List<Double> thresholds = new ArrayList<Double>();
        double value = resolved.getThresholdMin();
        int count = 0;
        while (value <= resolved.getThresholdMax() + EPSILON && count < MAX_GRID_POINTS) {
            thresholds.add(Double.valueOf(value));
            value += resolved.getThresholdStep();
            count++;
        }
        if (thresholds.isEmpty()) {
            thresholds.add(Double.valueOf(resolved.getThresholdMin()));
        }
        return thresholds;
    }

    public static MeasuredSample measureSample(SearchSample sample,
                                               SpectralDecontaminationConfig config,
                                               Settings settings,
                                               List<Double> thresholds) {
        if (sample == null) {
            throw new IllegalArgumentException("ROC search sample is required.");
        }
        if (config == null) {
            throw new IllegalArgumentException("Spectral Decontamination config is required.");
        }
        if (thresholds == null || thresholds.isEmpty()) {
            throw new IllegalArgumentException("ROC threshold grid is empty.");
        }
        Settings resolved = settings == null ? new Settings() : settings;
        Metric metric = resolved.getMetric();
        ImagePlus corrected = sample.getCorrectedImage();
        CorrectionImageOps.requireSingleChannel16Bit(corrected, "Corrected");

        double[] values = new double[thresholds.size()];
        boolean[] positives = new boolean[thresholds.size()];

        if (metric == Metric.POSITIVE_VOLUME) {
            for (int i = 0; i < thresholds.size(); i++) {
                values[i] = positiveVoxelCount(corrected, thresholds.get(i).doubleValue());
                positives[i] = values[i] > 0.0;
            }
        } else if (metric == Metric.OBJECT_COUNT) {
            for (int i = 0; i < thresholds.size(); i++) {
                values[i] = objectCount(corrected, thresholds.get(i).doubleValue());
                positives[i] = values[i] > 0.0;
            }
        } else if (metric == Metric.QUIET_POOL_P999 || metric == Metric.QUIET_POOL_P9999) {
            double percentile = metric == Metric.QUIET_POOL_P999 ? 99.9 : 99.99;
            double score = quietPoolPercentile(sample.getSourceImage(), corrected, config, percentile);
            for (int i = 0; i < thresholds.size(); i++) {
                positives[i] = score >= thresholds.get(i).doubleValue();
                values[i] = positives[i] ? score : 0.0;
            }
        } else if (metric == Metric.TARGET_TO_CONTAMINANT_RATIO) {
            double score = maxTargetToContaminantRatio(sample.getSourceImage(), corrected, config);
            for (int i = 0; i < thresholds.size(); i++) {
                positives[i] = score >= thresholds.get(i).doubleValue();
                values[i] = positives[i] ? score : 0.0;
            }
        }

        return new MeasuredSample(
                sample.getImageLabel(),
                sample.getConditionName(),
                sample.isControl(),
                sample.isExperimental(),
                values,
                positives);
    }

    public static RocSearchResult searchMeasured(List<MeasuredSample> samples,
                                                 Settings settings,
                                                 List<Double> thresholds,
                                                 String searchScope) {
        if (samples == null || samples.isEmpty()) {
            throw new IllegalArgumentException("ROC search requires at least one measured image.");
        }
        if (thresholds == null || thresholds.isEmpty()) {
            throw new IllegalArgumentException("ROC threshold grid is empty.");
        }
        Settings resolved = settings == null ? new Settings() : settings.copy();
        resolved.normalizeGrid();

        int controlCount = 0;
        int experimentalCount = 0;
        for (MeasuredSample sample : samples) {
            if (sample == null) continue;
            if (sample.isControl()) controlCount++;
            if (sample.isExperimental()) experimentalCount++;
        }
        if (controlCount <= 0) {
            throw new IllegalArgumentException("ROC search requires at least one control image.");
        }
        if (experimentalCount <= 0) {
            throw new IllegalArgumentException("ROC search requires at least one experimental image.");
        }

        List<RocSearchResult.GridPoint> gridPoints =
                new ArrayList<RocSearchResult.GridPoint>();
        int selectedIndex = -1;
        RocSearchResult.GridPoint selected = null;
        boolean selectedMetLimit = false;

        for (int index = 0; index < thresholds.size(); index++) {
            int controlPositive = 0;
            int experimentalPositive = 0;
            double experimentalMetricSum = 0.0;

            for (MeasuredSample sample : samples) {
                if (sample == null) continue;
                boolean positive = sample.isPositive(index);
                double value = sample.getMetricValue(index);
                if (sample.isControl() && positive) {
                    controlPositive++;
                }
                if (sample.isExperimental()) {
                    if (positive) {
                        experimentalPositive++;
                    }
                    experimentalMetricSum += value;
                }
            }

            double fpr = (double) controlPositive / (double) controlCount;
            double retention = (double) experimentalPositive / (double) experimentalCount;
            double experimentalMean = experimentalMetricSum / (double) experimentalCount;
            RocSearchResult.GridPoint point = new RocSearchResult.GridPoint(
                    thresholds.get(index).doubleValue(),
                    fpr,
                    retention,
                    experimentalMean,
                    controlPositive,
                    experimentalPositive,
                    controlCount,
                    experimentalCount);
            gridPoints.add(point);

            boolean meetsLimit = fpr <= resolved.getAllowedFalsePositiveRate() + EPSILON;
            if (selected == null || isBetter(point, selected, meetsLimit, selectedMetLimit)) {
                selected = point;
                selectedIndex = index;
                selectedMetLimit = meetsLimit;
            }
        }

        if (selected == null) {
            throw new IllegalStateException("ROC search did not evaluate any threshold grid points.");
        }

        String message = selectedMetLimit
                ? "Selected threshold met the allowed control false-positive rate."
                : "No threshold met the allowed control false-positive rate; selected the lowest false-positive operating point.";
        Metric metric = resolved.getMetric();
        return new RocSearchResult(
                metric.getKey(),
                metric.getLabel(),
                searchScope,
                message,
                resolved.getAllowedFalsePositiveRate(),
                resolved.getThresholdMin(),
                resolved.getThresholdMax(),
                resolved.getThresholdStep(),
                selected.getThreshold(),
                selected.getControlFalsePositiveRate(),
                selected.getExperimentalRetention(),
                selected.getExperimentalMeanMetric(),
                selected.getControlPositiveCount(),
                selected.getExperimentalPositiveCount(),
                controlCount,
                experimentalCount,
                selectedIndex,
                gridPoints);
    }

    public static Settings settingsWithResult(Settings base, RocSearchResult result) {
        Settings settings = base == null ? new Settings() : base.copy();
        if (result == null || !result.hasSelectedThreshold()) {
            return settings;
        }
        settings.setSelectedThreshold(result.getSelectedThreshold());
        settings.setSelectedFalsePositiveRate(result.getSelectedFalsePositiveRate());
        settings.setSelectedExperimentalRetention(result.getSelectedExperimentalRetention());
        settings.setSelectedExperimentalMeanMetric(result.getSelectedExperimentalMeanMetric());
        settings.setSelectedControlPositiveCount(result.getSelectedControlPositiveCount());
        settings.setSelectedExperimentalPositiveCount(result.getSelectedExperimentalPositiveCount());
        settings.setControlImageCount(result.getControlImageCount());
        settings.setExperimentalImageCount(result.getExperimentalImageCount());
        settings.setGridPointCount(result.getGridPointCount());
        settings.setSearchScope(result.getSearchScope());
        settings.setSearchMessage(result.getMessage());
        return settings;
    }

    private static boolean isBetter(RocSearchResult.GridPoint candidate,
                                    RocSearchResult.GridPoint current,
                                    boolean candidateMetLimit,
                                    boolean currentMetLimit) {
        if (candidateMetLimit != currentMetLimit) {
            return candidateMetLimit;
        }
        int cmp = Double.compare(candidate.getExperimentalRetention(), current.getExperimentalRetention());
        if (cmp != 0) return cmp > 0;
        cmp = Double.compare(candidate.getExperimentalMeanMetric(), current.getExperimentalMeanMetric());
        if (cmp != 0) return cmp > 0;
        cmp = Double.compare(candidate.getControlFalsePositiveRate(), current.getControlFalsePositiveRate());
        if (cmp != 0) return cmp < 0;
        return candidate.getThreshold() < current.getThreshold();
    }

    private static long positiveVoxelCount(ImagePlus corrected, double threshold) {
        long positive = 0L;
        int planeCount = CorrectionImageOps.planeCount(corrected);
        for (int plane = 0; plane < planeCount; plane++) {
            short[] pixels = CorrectionImageOps.singleChannelPlanePixels(corrected, plane);
            for (short pixel : pixels) {
                if ((pixel & 0xffff) >= threshold) {
                    positive++;
                }
            }
        }
        return positive;
    }

    private static int objectCount(ImagePlus corrected, double threshold) {
        int width = corrected.getWidth();
        int height = corrected.getHeight();
        int slices = Math.max(1, corrected.getNSlices());
        int frames = Math.max(1, corrected.getNFrames());
        int planeSize = width * height;
        int count = 0;

        for (int frame = 0; frame < frames; frame++) {
            boolean[] visited = new boolean[planeSize * slices];
            IntList queue = new IntList();
            for (int slice = 0; slice < slices; slice++) {
                short[] pixels = CorrectionImageOps.singleChannelPlanePixels(
                        corrected,
                        frame * slices + slice);
                for (int pixel = 0; pixel < planeSize; pixel++) {
                    int index = slice * planeSize + pixel;
                    if (visited[index] || (pixels[pixel] & 0xffff) < threshold) {
                        continue;
                    }
                    count++;
                    visited[index] = true;
                    queue.clear();
                    queue.add(index);
                    int queueIndex = 0;
                    while (queueIndex < queue.size()) {
                        int current = queue.get(queueIndex++);
                        int currentSlice = current / planeSize;
                        int sliceOffset = current - (currentSlice * planeSize);
                        int y = sliceOffset / width;
                        int x = sliceOffset - (y * width);
                        for (int dz = -1; dz <= 1; dz++) {
                            int nz = currentSlice + dz;
                            if (nz < 0 || nz >= slices) continue;
                            short[] neighborPixels = CorrectionImageOps.singleChannelPlanePixels(
                                    corrected,
                                    frame * slices + nz);
                            for (int dy = -1; dy <= 1; dy++) {
                                int ny = y + dy;
                                if (ny < 0 || ny >= height) continue;
                                for (int dx = -1; dx <= 1; dx++) {
                                    int nx = x + dx;
                                    if (nx < 0 || nx >= width || (dx == 0 && dy == 0 && dz == 0)) {
                                        continue;
                                    }
                                    int neighborPixel = ny * width + nx;
                                    int neighborIndex = nz * planeSize + neighborPixel;
                                    if (visited[neighborIndex]
                                            || (neighborPixels[neighborPixel] & 0xffff) < threshold) {
                                        continue;
                                    }
                                    visited[neighborIndex] = true;
                                    queue.add(neighborIndex);
                                }
                            }
                        }
                    }
                }
            }
        }
        return count;
    }

    private static double quietPoolPercentile(ImagePlus source,
                                              ImagePlus corrected,
                                              SpectralDecontaminationConfig config,
                                              double percentile) {
        List<Integer> contaminantChannels = CorrectionImageOps.contaminantChannels(config);
        double[] contaminantMedians = contaminantMedians(source, contaminantChannels);
        int[] histogram = new int[65536];
        long total = 0L;
        int planeCount = CorrectionImageOps.planeCount(corrected);
        for (int plane = 0; plane < planeCount; plane++) {
            short[] correctedPixels = CorrectionImageOps.singleChannelPlanePixels(corrected, plane);
            short[][] contaminants = contaminantChannels.isEmpty()
                    ? new short[0][]
                    : loadContaminants(source, contaminantChannels, plane);
            for (int pixel = 0; pixel < correctedPixels.length; pixel++) {
                if (!isQuiet(contaminants, contaminantMedians, pixel)) {
                    continue;
                }
                histogram[correctedPixels[pixel] & 0xffff]++;
                total++;
            }
        }
        return percentileFromHistogram(histogram, total, percentile);
    }

    private static double maxTargetToContaminantRatio(ImagePlus source,
                                                      ImagePlus corrected,
                                                      SpectralDecontaminationConfig config) {
        List<Integer> contaminantChannels = CorrectionImageOps.contaminantChannels(config);
        if (contaminantChannels.isEmpty()) {
            throw new IllegalArgumentException(
                    "Target-to-contaminant ratio ROC search requires at least one contaminant channel.");
        }
        CorrectionImageOps.require16Bit(source, "Source");
        double maxRatio = 0.0;
        int planeCount = CorrectionImageOps.planeCount(corrected);
        for (int plane = 0; plane < planeCount; plane++) {
            short[] correctedPixels = CorrectionImageOps.singleChannelPlanePixels(corrected, plane);
            short[][] contaminants = loadContaminants(source, contaminantChannels, plane);
            for (int pixel = 0; pixel < correctedPixels.length; pixel++) {
                double correctedValue = correctedPixels[pixel] & 0xffff;
                double ratio = correctedValue / Math.max(1.0, maxContaminant(contaminants, pixel));
                if (ratio > maxRatio) {
                    maxRatio = ratio;
                }
            }
        }
        return maxRatio;
    }

    private static double[] contaminantMedians(ImagePlus source, List<Integer> contaminantChannels) {
        double[] medians = new double[contaminantChannels == null ? 0 : contaminantChannels.size()];
        if (contaminantChannels == null || contaminantChannels.isEmpty()) {
            return medians;
        }
        CorrectionImageOps.require16Bit(source, "Source");
        for (int i = 0; i < contaminantChannels.size(); i++) {
            medians[i] = CorrectionImageOps.median(CorrectionImageOps.histogramForChannel(
                    source,
                    contaminantChannels.get(i).intValue()));
        }
        return medians;
    }

    private static boolean isQuiet(short[][] contaminants, double[] contaminantMedians, int pixel) {
        if (contaminants == null || contaminants.length == 0) {
            return true;
        }
        for (int i = 0; i < contaminants.length; i++) {
            if ((contaminants[i][pixel] & 0xffff) > contaminantMedians[i]) {
                return false;
            }
        }
        return true;
    }

    private static short[][] loadContaminants(ImagePlus source,
                                              List<Integer> contaminantChannels,
                                              int plane) {
        short[][] pixels = new short[contaminantChannels == null ? 0 : contaminantChannels.size()][];
        for (int i = 0; i < pixels.length; i++) {
            pixels[i] = CorrectionImageOps.channelPlanePixels(
                    source,
                    contaminantChannels.get(i).intValue(),
                    plane);
        }
        return pixels;
    }

    private static double maxContaminant(short[][] contaminantPixels, int pixel) {
        double max = 0.0;
        if (contaminantPixels == null) {
            return max;
        }
        for (short[] channel : contaminantPixels) {
            if (channel == null) continue;
            double value = channel[pixel] & 0xffff;
            if (value > max) {
                max = value;
            }
        }
        return max;
    }

    private static double percentileFromHistogram(int[] histogram, long total, double percentile) {
        if (histogram == null || total <= 0L) {
            return 0.0;
        }
        double clamped = Math.max(0.0, Math.min(100.0, percentile));
        long rank = (long) Math.ceil((clamped / 100.0) * total) - 1L;
        if (rank < 0L) rank = 0L;
        long cumulative = 0L;
        for (int i = 0; i < histogram.length; i++) {
            cumulative += histogram[i];
            if (cumulative > rank) {
                return i;
            }
        }
        return histogram.length - 1;
    }

    public enum Metric {
        POSITIVE_VOLUME("positive_volume", "Positive volume"),
        OBJECT_COUNT("object_count", "Object count"),
        QUIET_POOL_P999("quiet_pool_p999", "P99.9 target signal in quiet pool"),
        QUIET_POOL_P9999("quiet_pool_p9999", "P99.99 target signal in quiet pool"),
        TARGET_TO_CONTAMINANT_RATIO("target_to_contaminant_ratio", "Target-to-contaminant ratio");

        private final String key;
        private final String label;

        Metric(String key, String label) {
            this.key = key;
            this.label = label;
        }

        public String getKey() {
            return key;
        }

        public String getLabel() {
            return label;
        }

        public static Metric fromKeyOrLabel(String value) {
            if (value == null || value.trim().isEmpty()) {
                return POSITIVE_VOLUME;
            }
            String normalized = value.trim();
            for (Metric metric : values()) {
                if (metric.key.equalsIgnoreCase(normalized)
                        || metric.label.equalsIgnoreCase(normalized)
                        || metric.name().equalsIgnoreCase(normalized)) {
                    return metric;
                }
            }
            return POSITIVE_VOLUME;
        }

        public static String[] labels() {
            Metric[] metrics = values();
            String[] labels = new String[metrics.length];
            for (int i = 0; i < metrics.length; i++) {
                labels[i] = metrics[i].getLabel();
            }
            return labels;
        }
    }

    public static final class Settings {
        private Metric metric = Metric.POSITIVE_VOLUME;
        private double allowedFalsePositiveRate = 0.05;
        private double thresholdMin = 0.0;
        private double thresholdMax = 65535.0;
        private double thresholdStep = 512.0;
        private double targetMinThreshold = 1.0;
        private double selectedThreshold = Double.NaN;
        private double selectedFalsePositiveRate = Double.NaN;
        private double selectedExperimentalRetention = Double.NaN;
        private double selectedExperimentalMeanMetric = Double.NaN;
        private int selectedControlPositiveCount = 0;
        private int selectedExperimentalPositiveCount = 0;
        private int controlImageCount = 0;
        private int experimentalImageCount = 0;
        private int gridPointCount = 0;
        private String searchScope = "";
        private String searchMessage = "";

        public Metric getMetric() {
            return metric;
        }

        public Settings setMetric(Metric metric) {
            this.metric = metric == null ? Metric.POSITIVE_VOLUME : metric;
            return this;
        }

        public double getAllowedFalsePositiveRate() {
            return allowedFalsePositiveRate;
        }

        public Settings setAllowedFalsePositiveRate(double allowedFalsePositiveRate) {
            if (Double.isNaN(allowedFalsePositiveRate) || Double.isInfinite(allowedFalsePositiveRate)) {
                this.allowedFalsePositiveRate = 0.05;
            } else {
                this.allowedFalsePositiveRate = Math.max(0.0, Math.min(1.0, allowedFalsePositiveRate));
            }
            return this;
        }

        public double getThresholdMin() {
            return thresholdMin;
        }

        public Settings setThresholdMin(double thresholdMin) {
            this.thresholdMin = sanitizeNumber(thresholdMin, 0.0);
            return this;
        }

        public double getThresholdMax() {
            return thresholdMax;
        }

        public Settings setThresholdMax(double thresholdMax) {
            this.thresholdMax = sanitizeNumber(thresholdMax, 65535.0);
            return this;
        }

        public double getThresholdStep() {
            return thresholdStep;
        }

        public Settings setThresholdStep(double thresholdStep) {
            this.thresholdStep = sanitizeNumber(thresholdStep, 512.0);
            return this;
        }

        public double getTargetMinThreshold() {
            return targetMinThreshold;
        }

        public Settings setTargetMinThreshold(double targetMinThreshold) {
            this.targetMinThreshold = Math.max(0.0, sanitizeNumber(targetMinThreshold, 1.0));
            return this;
        }

        public double getSelectedThreshold() {
            return selectedThreshold;
        }

        public Settings setSelectedThreshold(double selectedThreshold) {
            this.selectedThreshold = selectedThreshold;
            return this;
        }

        public double getSelectedFalsePositiveRate() {
            return selectedFalsePositiveRate;
        }

        public Settings setSelectedFalsePositiveRate(double selectedFalsePositiveRate) {
            this.selectedFalsePositiveRate = selectedFalsePositiveRate;
            return this;
        }

        public double getSelectedExperimentalRetention() {
            return selectedExperimentalRetention;
        }

        public Settings setSelectedExperimentalRetention(double selectedExperimentalRetention) {
            this.selectedExperimentalRetention = selectedExperimentalRetention;
            return this;
        }

        public double getSelectedExperimentalMeanMetric() {
            return selectedExperimentalMeanMetric;
        }

        public Settings setSelectedExperimentalMeanMetric(double selectedExperimentalMeanMetric) {
            this.selectedExperimentalMeanMetric = selectedExperimentalMeanMetric;
            return this;
        }

        public int getSelectedControlPositiveCount() {
            return selectedControlPositiveCount;
        }

        public Settings setSelectedControlPositiveCount(int selectedControlPositiveCount) {
            this.selectedControlPositiveCount = Math.max(0, selectedControlPositiveCount);
            return this;
        }

        public int getSelectedExperimentalPositiveCount() {
            return selectedExperimentalPositiveCount;
        }

        public Settings setSelectedExperimentalPositiveCount(int selectedExperimentalPositiveCount) {
            this.selectedExperimentalPositiveCount = Math.max(0, selectedExperimentalPositiveCount);
            return this;
        }

        public int getControlImageCount() {
            return controlImageCount;
        }

        public Settings setControlImageCount(int controlImageCount) {
            this.controlImageCount = Math.max(0, controlImageCount);
            return this;
        }

        public int getExperimentalImageCount() {
            return experimentalImageCount;
        }

        public Settings setExperimentalImageCount(int experimentalImageCount) {
            this.experimentalImageCount = Math.max(0, experimentalImageCount);
            return this;
        }

        public int getGridPointCount() {
            return gridPointCount;
        }

        public Settings setGridPointCount(int gridPointCount) {
            this.gridPointCount = Math.max(0, gridPointCount);
            return this;
        }

        public String getSearchScope() {
            return searchScope;
        }

        public Settings setSearchScope(String searchScope) {
            this.searchScope = searchScope == null ? "" : searchScope.trim();
            return this;
        }

        public String getSearchMessage() {
            return searchMessage;
        }

        public Settings setSearchMessage(String searchMessage) {
            this.searchMessage = searchMessage == null ? "" : searchMessage.trim();
            return this;
        }

        public boolean hasSelectedThreshold() {
            return !Double.isNaN(selectedThreshold) && !Double.isInfinite(selectedThreshold);
        }

        public Settings clearSelectedThreshold() {
            selectedThreshold = Double.NaN;
            selectedFalsePositiveRate = Double.NaN;
            selectedExperimentalRetention = Double.NaN;
            selectedExperimentalMeanMetric = Double.NaN;
            selectedControlPositiveCount = 0;
            selectedExperimentalPositiveCount = 0;
            controlImageCount = 0;
            experimentalImageCount = 0;
            gridPointCount = 0;
            searchScope = "";
            searchMessage = "";
            return this;
        }

        public Settings applyMetricDefaultsIfNeeded() {
            if (metric == Metric.TARGET_TO_CONTAMINANT_RATIO
                    && Math.abs(thresholdMin) < EPSILON
                    && Math.abs(thresholdMax - 65535.0) < EPSILON
                    && Math.abs(thresholdStep - 512.0) < EPSILON) {
                thresholdMin = 1.0;
                thresholdMax = 10.0;
                thresholdStep = 0.25;
            }
            return this;
        }

        public Settings normalizeGrid() {
            thresholdMin = sanitizeNumber(thresholdMin, 0.0);
            thresholdMax = sanitizeNumber(thresholdMax, 65535.0);
            thresholdStep = sanitizeNumber(thresholdStep, 512.0);
            if (thresholdStep <= 0.0) {
                thresholdStep = metric == Metric.TARGET_TO_CONTAMINANT_RATIO ? 0.25 : 512.0;
            }
            if (thresholdMax < thresholdMin) {
                double temp = thresholdMin;
                thresholdMin = thresholdMax;
                thresholdMax = temp;
            }
            return this;
        }

        public Settings copy() {
            Settings copy = new Settings();
            copy.metric = metric;
            copy.allowedFalsePositiveRate = allowedFalsePositiveRate;
            copy.thresholdMin = thresholdMin;
            copy.thresholdMax = thresholdMax;
            copy.thresholdStep = thresholdStep;
            copy.targetMinThreshold = targetMinThreshold;
            copy.selectedThreshold = selectedThreshold;
            copy.selectedFalsePositiveRate = selectedFalsePositiveRate;
            copy.selectedExperimentalRetention = selectedExperimentalRetention;
            copy.selectedExperimentalMeanMetric = selectedExperimentalMeanMetric;
            copy.selectedControlPositiveCount = selectedControlPositiveCount;
            copy.selectedExperimentalPositiveCount = selectedExperimentalPositiveCount;
            copy.controlImageCount = controlImageCount;
            copy.experimentalImageCount = experimentalImageCount;
            copy.gridPointCount = gridPointCount;
            copy.searchScope = searchScope;
            copy.searchMessage = searchMessage;
            return copy;
        }

        public CorrectionPipeline.Settings toPipelineSettings() {
            CorrectionPipeline.Settings raw = new CorrectionPipeline.Settings()
                    .put(METRIC, metric.getKey())
                    .putDouble(ALLOWED_FALSE_POSITIVE_RATE, allowedFalsePositiveRate)
                    .putDouble(THRESHOLD_MIN, thresholdMin)
                    .putDouble(THRESHOLD_MAX, thresholdMax)
                    .putDouble(THRESHOLD_STEP, thresholdStep)
                    .putDouble(TARGET_MIN_THRESHOLD, targetMinThreshold);
            if (hasSelectedThreshold()) {
                raw.putDouble(SELECTED_THRESHOLD, selectedThreshold)
                        .putDouble(SELECTED_FALSE_POSITIVE_RATE, selectedFalsePositiveRate)
                        .putDouble(SELECTED_EXPERIMENTAL_RETENTION, selectedExperimentalRetention)
                        .putDouble(SELECTED_EXPERIMENTAL_MEAN_METRIC, selectedExperimentalMeanMetric)
                        .putInt(SELECTED_CONTROL_POSITIVE_COUNT, selectedControlPositiveCount)
                        .putInt(SELECTED_EXPERIMENTAL_POSITIVE_COUNT, selectedExperimentalPositiveCount)
                        .putInt(CONTROL_IMAGE_COUNT, controlImageCount)
                        .putInt(EXPERIMENTAL_IMAGE_COUNT, experimentalImageCount)
                        .putInt(GRID_POINT_COUNT, gridPointCount)
                        .put(SEARCH_SCOPE, searchScope)
                        .put(SEARCH_MESSAGE, searchMessage);
            }
            return raw;
        }

        public static Settings from(CorrectionPipeline.Settings raw) {
            Settings settings = new Settings();
            if (raw == null) {
                return settings;
            }
            settings.setMetric(Metric.fromKeyOrLabel(raw.get(METRIC, settings.metric.getKey())));
            settings.setAllowedFalsePositiveRate(raw.getDouble(
                    ALLOWED_FALSE_POSITIVE_RATE,
                    settings.allowedFalsePositiveRate));
            settings.setThresholdMin(raw.getDouble(THRESHOLD_MIN, settings.thresholdMin));
            settings.setThresholdMax(raw.getDouble(THRESHOLD_MAX, settings.thresholdMax));
            settings.setThresholdStep(raw.getDouble(THRESHOLD_STEP, settings.thresholdStep));
            settings.setTargetMinThreshold(raw.getDouble(TARGET_MIN_THRESHOLD, settings.targetMinThreshold));
            settings.setSelectedThreshold(raw.getDouble(SELECTED_THRESHOLD, Double.NaN));
            settings.setSelectedFalsePositiveRate(raw.getDouble(SELECTED_FALSE_POSITIVE_RATE, Double.NaN));
            settings.setSelectedExperimentalRetention(raw.getDouble(SELECTED_EXPERIMENTAL_RETENTION, Double.NaN));
            settings.setSelectedExperimentalMeanMetric(raw.getDouble(SELECTED_EXPERIMENTAL_MEAN_METRIC, Double.NaN));
            settings.setSelectedControlPositiveCount(raw.getInt(SELECTED_CONTROL_POSITIVE_COUNT, 0));
            settings.setSelectedExperimentalPositiveCount(raw.getInt(SELECTED_EXPERIMENTAL_POSITIVE_COUNT, 0));
            settings.setControlImageCount(raw.getInt(CONTROL_IMAGE_COUNT, 0));
            settings.setExperimentalImageCount(raw.getInt(EXPERIMENTAL_IMAGE_COUNT, 0));
            settings.setGridPointCount(raw.getInt(GRID_POINT_COUNT, 0));
            settings.setSearchScope(raw.get(SEARCH_SCOPE, ""));
            settings.setSearchMessage(raw.get(SEARCH_MESSAGE, ""));
            settings.normalizeGrid();
            return settings;
        }

        private static double sanitizeNumber(double value, double defaultValue) {
            if (Double.isNaN(value) || Double.isInfinite(value)) {
                return defaultValue;
            }
            return value;
        }
    }

    public static final class SearchSample {
        private final String imageLabel;
        private final String conditionName;
        private final boolean control;
        private final boolean experimental;
        private final ImagePlus sourceImage;
        private final ImagePlus correctedImage;

        public SearchSample(String imageLabel,
                            String conditionName,
                            boolean control,
                            boolean experimental,
                            ImagePlus sourceImage,
                            ImagePlus correctedImage) {
            this.imageLabel = imageLabel == null ? "" : imageLabel.trim();
            this.conditionName = conditionName == null ? "" : conditionName.trim();
            this.control = control;
            this.experimental = experimental;
            this.sourceImage = sourceImage;
            this.correctedImage = correctedImage;
        }

        public String getImageLabel() {
            return imageLabel;
        }

        public String getConditionName() {
            return conditionName;
        }

        public boolean isControl() {
            return control;
        }

        public boolean isExperimental() {
            return experimental;
        }

        public ImagePlus getSourceImage() {
            return sourceImage;
        }

        public ImagePlus getCorrectedImage() {
            return correctedImage;
        }
    }

    public static final class MeasuredSample {
        private final String imageLabel;
        private final String conditionName;
        private final boolean control;
        private final boolean experimental;
        private final double[] metricValues;
        private final boolean[] positives;

        private MeasuredSample(String imageLabel,
                               String conditionName,
                               boolean control,
                               boolean experimental,
                               double[] metricValues,
                               boolean[] positives) {
            this.imageLabel = imageLabel == null ? "" : imageLabel.trim();
            this.conditionName = conditionName == null ? "" : conditionName.trim();
            this.control = control;
            this.experimental = experimental;
            this.metricValues = metricValues == null ? new double[0] : metricValues.clone();
            this.positives = positives == null ? new boolean[0] : positives.clone();
        }

        public String getImageLabel() {
            return imageLabel;
        }

        public String getConditionName() {
            return conditionName;
        }

        public boolean isControl() {
            return control;
        }

        public boolean isExperimental() {
            return experimental;
        }

        public double getMetricValue(int index) {
            return index < 0 || index >= metricValues.length ? 0.0 : metricValues[index];
        }

        public boolean isPositive(int index) {
            return index >= 0 && index < positives.length && positives[index];
        }
    }

    private static final class IntList {
        private int[] values = new int[256];
        private int size = 0;

        void clear() {
            size = 0;
        }

        void add(int value) {
            if (size >= values.length) {
                int[] expanded = new int[values.length * 2];
                System.arraycopy(values, 0, expanded, 0, values.length);
                values = expanded;
            }
            values[size++] = value;
        }

        int get(int index) {
            return values[index];
        }

        int size() {
            return size;
        }
    }
}
