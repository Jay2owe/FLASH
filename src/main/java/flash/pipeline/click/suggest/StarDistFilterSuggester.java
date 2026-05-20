package flash.pipeline.click.suggest;

import flash.pipeline.objects.LabelIndex;
import flash.pipeline.stardist.StarDist3DRunner;
import ij.ImageStack;
import ij.measure.ResultsTable;
import ij.process.ImageProcessor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class StarDistFilterSuggester
        implements ParameterSuggester<StarDistFilterSuggester.StarDistSuggestion> {

    private static final double LOW_QUALITY_HINT_MARGIN = 0.1d;
    private static final double MAX_REASONABLE_QUALITY = 1.0e6d;
    private static final double MAX_REASONABLE_UNBOUNDED_METRIC = 1.0e12d;
    private static final double AREA_IMAGE_TOLERANCE_FACTOR = 4.0d;
    private static final double INTENSITY_IMAGE_TOLERANCE_FRACTION = 0.01d;

    public static final class StarDistSuggestion {
        public final Double minQuality;
        public final Double minArea;
        public final Double maxArea;
        public final Double minIntensity;
        public final int badRemoved;
        public final int collateralRemoved;
        public final String hint;

        StarDistSuggestion(Double minQuality,
                           Double minArea,
                           Double maxArea,
                           Double minIntensity,
                           int badRemoved,
                           int collateralRemoved,
                           String hint) {
            this.minQuality = minQuality;
            this.minArea = minArea;
            this.maxArea = maxArea;
            this.minIntensity = minIntensity;
            this.badRemoved = Math.max(0, badRemoved);
            this.collateralRemoved = Math.max(0, collateralRemoved);
            this.hint = hint == null ? "" : hint;
        }

        public boolean hasSuggestion() {
            return minQuality != null || minArea != null
                    || maxArea != null || minIntensity != null;
        }

        public boolean hasHint() {
            return hint.length() > 0;
        }

        public static StarDistSuggestion none() {
            return new StarDistSuggestion(null, null, null, null, 0, 0, "");
        }

        static StarDistSuggestion none(String hint) {
            return new StarDistSuggestion(null, null, null, null, 0, 0, hint);
        }
    }

    @Override
    public StarDistSuggestion suggest(SuggestionContext ctx) {
        if (ctx == null || ctx.negativeClicks.size() < 3) {
            return StarDistSuggestion.none();
        }
        ResultsTable table = LabelIndex.starDistStats(ctx.labelImage);
        if (table == null || table.size() == 0) {
            return StarDistSuggestion.none();
        }
        final Map<Integer, StatRow> rows = rowsByLabel(table);
        if (rows.isEmpty()) {
            return StarDistSuggestion.none();
        }
        Set<Integer> badLabels =
                SuggestionSupport.labelsForClicks(ctx.labelImage, ctx.negativeClicks);
        Set<Integer> positiveLabels =
                SuggestionSupport.labelsForClicks(ctx.labelImage, ctx.positiveClicks);
        if (badLabels.size() < 3) {
            return StarDistSuggestion.none();
        }
        String hint = lowQualityHint(ctx, rows, badLabels);

        List<Candidate> candidates = new ArrayList<Candidate>();
        addIfPresent(candidates, minCandidate(ctx, rows, badLabels, positiveLabels,
                Metric.QUALITY, "minQuality", 0));
        Candidate minArea = minCandidate(ctx, rows, badLabels, positiveLabels,
                Metric.AREA, "minArea", 1);
        Candidate maxArea = maxCandidate(ctx, rows, badLabels, positiveLabels,
                Metric.AREA, "maxArea", 2);
        addIfPresent(candidates, betterAreaCandidate(minArea, maxArea));
        addIfPresent(candidates, minCandidate(ctx, rows, badLabels, positiveLabels,
                Metric.INTENSITY, "minIntensity", 3));

        Candidate best = bestCandidate(candidates, badLabels.size());
        if (best == null || best.badRemoved <= 0) {
            return StarDistSuggestion.none(hint);
        }
        return best.toSuggestion(hint);
    }

    private static void addIfPresent(List<Candidate> candidates, Candidate candidate) {
        if (candidate != null) candidates.add(candidate);
    }

    private Candidate minCandidate(SuggestionContext ctx,
                                   final Map<Integer, StatRow> rows,
                                   Set<Integer> badLabels,
                                   Set<Integer> positiveLabels,
                                   final Metric metric,
                                   String currentKey,
                                   int order) {
        double maxBad = Double.NEGATIVE_INFINITY;
        for (Integer label : badLabels) {
            StatRow row = rows.get(label);
            double metricValue = row == null ? Double.NaN : metric.value(row);
            if (!validMetricValue(ctx, metric, metricValue)) return null;
            maxBad = Math.max(maxBad, metricValue);
        }
        if (!SuggestionSupport.finite(maxBad)) return null;
        double value = minimumThreshold(metric, maxBad);
        if (!validMetricValue(ctx, metric, value)) return null;
        double current = SuggestionSupport.current(ctx.currentParams, currentKey,
                Double.NEGATIVE_INFINITY);
        if (value <= current + SuggestionSupport.EPSILON) return null;
        final double threshold = value;
        SuggestionSupport.RemovalRule rule = new SuggestionSupport.RemovalRule() {
            @Override public boolean removes(int label) {
                StatRow row = rows.get(Integer.valueOf(label));
                double value = row == null ? Double.NaN : metric.value(row);
                return SuggestionSupport.finite(value) && value < threshold;
            }
        };
        return candidateIfSafe(Candidate.forMetric(order, metric, threshold, true),
                rows.keySet(), badLabels, positiveLabels, rule);
    }

    private Candidate maxCandidate(SuggestionContext ctx,
                                   final Map<Integer, StatRow> rows,
                                   Set<Integer> badLabels,
                                   Set<Integer> positiveLabels,
                                   final Metric metric,
                                   String currentKey,
                                   int order) {
        double minBad = Double.POSITIVE_INFINITY;
        for (Integer label : badLabels) {
            StatRow row = rows.get(label);
            double metricValue = row == null ? Double.NaN : metric.value(row);
            if (!validMetricValue(ctx, metric, metricValue)) return null;
            minBad = Math.min(minBad, metricValue);
        }
        if (!SuggestionSupport.finite(minBad)) return null;
        double value = maximumThreshold(metric, minBad);
        if (!validMetricValue(ctx, metric, value)) return null;
        double current = SuggestionSupport.current(ctx.currentParams, currentKey,
                Double.POSITIVE_INFINITY);
        if (value >= current - SuggestionSupport.EPSILON) return null;
        final double threshold = value;
        SuggestionSupport.RemovalRule rule = new SuggestionSupport.RemovalRule() {
            @Override public boolean removes(int label) {
                StatRow row = rows.get(Integer.valueOf(label));
                double value = row == null ? Double.NaN : metric.value(row);
                return SuggestionSupport.finite(value) && value > threshold;
            }
        };
        return candidateIfSafe(Candidate.forMetric(order, metric, threshold, false),
                rows.keySet(), badLabels, positiveLabels, rule);
    }

    private Candidate candidateIfSafe(Candidate candidate,
                                      Set<Integer> allLabels,
                                      Set<Integer> badLabels,
                                      Set<Integer> positiveLabels,
                                      SuggestionSupport.RemovalRule rule) {
        if (SuggestionSupport.removesAny(positiveLabels, rule)) {
            return null;
        }
        candidate.badRemoved = SuggestionSupport.countRemoved(badLabels, rule);
        candidate.collateralRemoved = SuggestionSupport.countCollateral(
                allLabels, badLabels, positiveLabels, rule);
        return candidate.badRemoved <= 0 ? null : candidate;
    }

    private static double minimumThreshold(Metric metric, double maxBad) {
        if (metric == Metric.QUALITY) {
            return maxBad + SuggestionSupport.EPSILON;
        }
        return Math.floor(maxBad) + 1.0d;
    }

    private static double maximumThreshold(Metric metric, double minBad) {
        if (metric == Metric.QUALITY) {
            return Math.max(0.0d, minBad - SuggestionSupport.EPSILON);
        }
        return Math.max(0.0d, Math.ceil(minBad) - 1.0d);
    }

    private static boolean validMetricValue(SuggestionContext ctx,
                                            Metric metric,
                                            double value) {
        if (!SuggestionSupport.finite(value)) return false;
        if (Math.abs(value) > MAX_REASONABLE_UNBOUNDED_METRIC) return false;
        if (metric == Metric.QUALITY) {
            return Math.abs(value) <= MAX_REASONABLE_QUALITY;
        }
        if (metric == Metric.AREA) {
            double maxArea = maxPlausibleArea(ctx);
            return !SuggestionSupport.finite(maxArea)
                    || value <= maxArea * AREA_IMAGE_TOLERANCE_FACTOR;
        }
        if (metric == Metric.INTENSITY) {
            double imageMax = imageMaximum(ctx == null ? null : ctx.channelImage);
            if (!SuggestionSupport.finite(imageMax)) return true;
            double tolerance = Math.max(1.0d,
                    Math.abs(imageMax) * INTENSITY_IMAGE_TOLERANCE_FRACTION);
            return value <= imageMax + tolerance;
        }
        return true;
    }

    private static double maxPlausibleArea(SuggestionContext ctx) {
        if (ctx == null || ctx.labelImage == null) return Double.NaN;
        int width = Math.max(1, ctx.labelImage.getWidth());
        int height = Math.max(1, ctx.labelImage.getHeight());
        ij.measure.Calibration calibration = ctx.labelImage.getCalibration();
        double pixelWidth = calibration == null ? 1.0d : calibration.pixelWidth;
        double pixelHeight = calibration == null ? 1.0d : calibration.pixelHeight;
        if (!SuggestionSupport.finite(pixelWidth) || pixelWidth <= 0.0d) {
            pixelWidth = 1.0d;
        }
        if (!SuggestionSupport.finite(pixelHeight) || pixelHeight <= 0.0d) {
            pixelHeight = 1.0d;
        }
        return (double) width * (double) height * pixelWidth * pixelHeight;
    }

    private static double imageMaximum(ij.ImagePlus image) {
        if (image == null) return Double.NaN;
        ImageStack stack;
        try {
            stack = image.getStack();
        } catch (RuntimeException e) {
            return Double.NaN;
        }
        if (stack == null || stack.getSize() == 0) return Double.NaN;
        double max = Double.NEGATIVE_INFINITY;
        for (int slice = 1; slice <= stack.getSize(); slice++) {
            ImageProcessor processor;
            try {
                processor = stack.getProcessor(slice);
            } catch (RuntimeException e) {
                continue;
            }
            if (processor == null) continue;
            int count = processor.getPixelCount();
            for (int i = 0; i < count; i++) {
                float value = processor.getf(i);
                if (Float.isFinite(value) && value > max) {
                    max = value;
                }
            }
        }
        return max == Double.NEGATIVE_INFINITY ? Double.NaN : max;
    }

    private static String lowQualityHint(SuggestionContext ctx,
                                         Map<Integer, StatRow> rows,
                                         Set<Integer> badLabels) {
        double currentMinQuality = SuggestionSupport.current(ctx.currentParams,
                "minQuality", Double.NaN);
        if (!SuggestionSupport.finite(currentMinQuality)) return "";
        double cutoff = currentMinQuality - LOW_QUALITY_HINT_MARGIN;
        for (Integer label : badLabels) {
            StatRow row = rows.get(label);
            double quality = row == null ? Double.NaN : row.quality;
            if (!SuggestionSupport.finite(quality) || quality >= cutoff) {
                return "";
            }
        }
        return "All clicked-bad objects have Quality below current minQuality ("
                + formatNumber(currentMinQuality)
                + "). Consider raising prob threshold during detection - these may be "
                + "detection-level noise that minQuality alone cannot remove.";
    }

    private static Candidate betterAreaCandidate(Candidate minArea, Candidate maxArea) {
        if (minArea == null) return maxArea;
        if (maxArea == null) return minArea;
        if (minArea.badRemoved != maxArea.badRemoved) {
            return minArea.badRemoved > maxArea.badRemoved ? minArea : maxArea;
        }
        if (minArea.collateralRemoved != maxArea.collateralRemoved) {
            return minArea.collateralRemoved < maxArea.collateralRemoved ? minArea : maxArea;
        }
        return minArea;
    }

    private static Candidate bestCandidate(List<Candidate> candidates, int badLabelCount) {
        Candidate best = null;
        for (Candidate candidate : candidates) {
            if (candidate == null) continue;
            if (best == null || better(candidate, best, badLabelCount)) {
                best = candidate;
            }
        }
        return best;
    }

    private static boolean better(Candidate left, Candidate right, int badLabelCount) {
        boolean leftPerfect = left.badRemoved == badLabelCount && left.collateralRemoved == 0;
        boolean rightPerfect = right.badRemoved == badLabelCount && right.collateralRemoved == 0;
        if (leftPerfect != rightPerfect) return leftPerfect;
        if (left.badRemoved != right.badRemoved) return left.badRemoved > right.badRemoved;
        if (left.collateralRemoved != right.collateralRemoved) {
            return left.collateralRemoved < right.collateralRemoved;
        }
        return left.order < right.order;
    }

    private static Map<Integer, StatRow> rowsByLabel(ResultsTable table) {
        Map<Integer, StatRow> out = new HashMap<Integer, StatRow>();
        for (int row = 0; row < table.size(); row++) {
            int label = labelForRow(table, row);
            if (label <= 0) continue;
            out.put(Integer.valueOf(label), new StatRow(
                    metric(table, StarDist3DRunner.STATS_QUALITY_MEAN, row),
                    metric(table, StarDist3DRunner.STATS_AREA_MEAN, row),
                    metric(table, StarDist3DRunner.STATS_INTENSITY_MEAN, row)));
        }
        return out;
    }

    private static int labelForRow(ResultsTable table, int row) {
        try {
            double label = table.getValue("Label", row);
            if (Double.isFinite(label) && label > 0) {
                return (int) Math.round(label);
            }
        } catch (RuntimeException ignored) {
        }
        return row + 1;
    }

    private static double metric(ResultsTable table, String column, int row) {
        try {
            double value = table.getValue(column, row);
            return Double.isFinite(value) ? value : Double.NaN;
        } catch (RuntimeException e) {
            return Double.NaN;
        }
    }

    private enum Metric {
        QUALITY {
            @Override double value(StatRow row) { return row.quality; }
        },
        AREA {
            @Override double value(StatRow row) { return row.area; }
        },
        INTENSITY {
            @Override double value(StatRow row) { return row.intensity; }
        };

        abstract double value(StatRow row);
    }

    private static final class StatRow {
        final double quality;
        final double area;
        final double intensity;

        StatRow(double quality, double area, double intensity) {
            this.quality = quality;
            this.area = area;
            this.intensity = intensity;
        }
    }

    private static final class Candidate {
        final int order;
        final Metric metric;
        final double value;
        final boolean minimum;
        int badRemoved;
        int collateralRemoved;

        Candidate(int order, Metric metric, double value, boolean minimum) {
            this.order = order;
            this.metric = metric;
            this.value = value;
            this.minimum = minimum;
        }

        static Candidate forMetric(int order, Metric metric, double value, boolean minimum) {
            return new Candidate(order, metric, value, minimum);
        }

        StarDistSuggestion toSuggestion(String hint) {
            Double minQuality = null;
            Double minArea = null;
            Double maxArea = null;
            Double minIntensity = null;
            if (metric == Metric.QUALITY) {
                minQuality = Double.valueOf(value);
            } else if (metric == Metric.AREA && minimum) {
                minArea = Double.valueOf(value);
            } else if (metric == Metric.AREA) {
                maxArea = Double.valueOf(value);
            } else if (metric == Metric.INTENSITY) {
                minIntensity = Double.valueOf(value);
            }
            return new StarDistSuggestion(minQuality, minArea, maxArea, minIntensity,
                    badRemoved, collateralRemoved, hint);
        }
    }

    private static String formatNumber(double value) {
        if (!Double.isFinite(value)) return String.valueOf(value);
        double rounded = Math.rint(value);
        if (Math.abs(value - rounded) < 1.0e-9
                && Math.abs(rounded) <= Long.MAX_VALUE) {
            return String.valueOf((long) rounded);
        }
        double abs = Math.abs(value);
        if (abs >= 1.0e9d || (abs > 0.0d && abs < 1.0e-4d)) {
            return String.format(java.util.Locale.ROOT, "%.6g", Double.valueOf(value));
        }
        String text = String.format(java.util.Locale.ROOT, "%.6f", Double.valueOf(value));
        while (text.endsWith("0")) {
            text = text.substring(0, text.length() - 1);
        }
        return text.endsWith(".") ? text.substring(0, text.length() - 1) : text;
    }
}
