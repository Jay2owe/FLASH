package flash.pipeline.click.suggest;

import flash.pipeline.objects.LabelIndex;
import flash.pipeline.stardist.StarDist3DRunner;
import ij.measure.ResultsTable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class StarDistFilterSuggester
        implements ParameterSuggester<StarDistFilterSuggester.StarDistSuggestion> {

    private static final double LOW_QUALITY_HINT_MARGIN = 0.1d;

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
            if (row == null || !SuggestionSupport.finite(metric.value(row))) return null;
            maxBad = Math.max(maxBad, metric.value(row));
        }
        if (!SuggestionSupport.finite(maxBad)) return null;
        double value = maxBad + SuggestionSupport.EPSILON;
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
            if (row == null || !SuggestionSupport.finite(metric.value(row))) return null;
            minBad = Math.min(minBad, metric.value(row));
        }
        if (!SuggestionSupport.finite(minBad)) return null;
        double value = Math.max(0.0d, minBad - SuggestionSupport.EPSILON);
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
        if (Math.abs(value - rounded) < 1.0e-9) {
            return String.valueOf((long) rounded);
        }
        return String.valueOf(value);
    }
}
