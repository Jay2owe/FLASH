package flash.pipeline.click.suggest;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class ClassicalParameterSuggester
        implements ParameterSuggester<ClassicalParameterSuggester.ClassicalSuggestion> {

    public static final class ClassicalSuggestion {
        public final Double thresholdLow;
        public final Integer minSize;
        public final Integer maxSize;
        public final int badRemoved;
        public final int collateralRemoved;

        ClassicalSuggestion(Double thresholdLow,
                            Integer minSize,
                            Integer maxSize,
                            int badRemoved,
                            int collateralRemoved) {
            this.thresholdLow = thresholdLow;
            this.minSize = minSize;
            this.maxSize = maxSize;
            this.badRemoved = Math.max(0, badRemoved);
            this.collateralRemoved = Math.max(0, collateralRemoved);
        }

        public boolean hasSuggestion() {
            return thresholdLow != null || minSize != null || maxSize != null;
        }

        public static ClassicalSuggestion none() {
            return new ClassicalSuggestion(null, null, null, 0, 0);
        }
    }

    @Override
    public ClassicalSuggestion suggest(SuggestionContext ctx) {
        if (ctx == null || ctx.negativeClicks.size() < 3) {
            return ClassicalSuggestion.none();
        }
        final Map<Integer, SuggestionSupport.ObjectStats> stats =
                SuggestionSupport.objectStats(ctx.labelImage, ctx.channelImage);
        if (stats.isEmpty()) {
            return ClassicalSuggestion.none();
        }
        final Set<Integer> badLabels =
                SuggestionSupport.labelsForClicks(ctx.labelImage, ctx.negativeClicks);
        final Set<Integer> positiveLabels =
                SuggestionSupport.labelsForClicks(ctx.labelImage, ctx.positiveClicks);
        if (badLabels.size() < 3) {
            return ClassicalSuggestion.none();
        }
        final Set<Integer> protectedLabels =
                protectedLabels(stats.keySet(), badLabels, positiveLabels);
        if (positiveLabels.isEmpty() && protectedLabels.isEmpty()) {
            return ClassicalSuggestion.none();
        }

        List<Candidate> candidates = new ArrayList<Candidate>();
        Candidate threshold = thresholdCandidate(ctx, stats, badLabels, protectedLabels);
        if (threshold != null) candidates.add(threshold);
        Candidate minSize = minSizeCandidate(ctx, stats, badLabels, protectedLabels);
        Candidate maxSize = maxSizeCandidate(ctx, stats, badLabels, protectedLabels);
        Candidate size = betterSizeCandidate(minSize, maxSize);
        if (size != null) candidates.add(size);

        Candidate best = bestCandidate(candidates, badLabels.size());
        if (best == null || best.badRemoved <= 0) {
            return ClassicalSuggestion.none();
        }
        return best.toSuggestion();
    }

    private Candidate thresholdCandidate(SuggestionContext ctx,
                                         final Map<Integer, SuggestionSupport.ObjectStats> stats,
                                         Set<Integer> badLabels,
                                         Set<Integer> positiveLabels) {
        double maxBadMean = Double.NEGATIVE_INFINITY;
        for (Integer label : badLabels) {
            SuggestionSupport.ObjectStats object = stats.get(label);
            if (object == null || !SuggestionSupport.finite(object.meanIntensity())) {
                return null;
            }
            maxBadMean = Math.max(maxBadMean, object.meanIntensity());
        }
        if (!SuggestionSupport.finite(maxBadMean)) return null;
        double candidate = Math.floor(maxBadMean) + 1.0d;
        double current = SuggestionSupport.current(ctx.currentParams, "thresholdLow",
                Double.NEGATIVE_INFINITY);
        if (candidate <= current + SuggestionSupport.EPSILON) return null;
        final double threshold = candidate;
        SuggestionSupport.RemovalRule rule = new SuggestionSupport.RemovalRule() {
            @Override public boolean removes(int label) {
                SuggestionSupport.ObjectStats object = stats.get(Integer.valueOf(label));
                return object != null
                        && SuggestionSupport.finite(object.meanIntensity())
                        && object.meanIntensity() < threshold;
            }
        };
        return candidateIfSafe(Candidate.threshold(threshold), stats.keySet(),
                badLabels, positiveLabels, rule);
    }

    private Candidate minSizeCandidate(SuggestionContext ctx,
                                       final Map<Integer, SuggestionSupport.ObjectStats> stats,
                                       Set<Integer> badLabels,
                                       Set<Integer> positiveLabels) {
        long maxBadSize = Long.MIN_VALUE;
        for (Integer label : badLabels) {
            SuggestionSupport.ObjectStats object = stats.get(label);
            if (object == null || object.voxelCount <= 0L) return null;
            maxBadSize = Math.max(maxBadSize, object.voxelCount);
        }
        if (maxBadSize >= Integer.MAX_VALUE) return null;
        int candidate = (int) maxBadSize + 1;
        double current = SuggestionSupport.current(ctx.currentParams, "minSize", 0.0d);
        if (candidate <= current + SuggestionSupport.EPSILON) return null;
        final int minSize = candidate;
        SuggestionSupport.RemovalRule rule = new SuggestionSupport.RemovalRule() {
            @Override public boolean removes(int label) {
                SuggestionSupport.ObjectStats object = stats.get(Integer.valueOf(label));
                return object != null && object.voxelCount < minSize;
            }
        };
        return candidateIfSafe(Candidate.minSize(minSize), stats.keySet(),
                badLabels, positiveLabels, rule);
    }

    private Candidate maxSizeCandidate(SuggestionContext ctx,
                                       final Map<Integer, SuggestionSupport.ObjectStats> stats,
                                       Set<Integer> badLabels,
                                       Set<Integer> positiveLabels) {
        long minBadSize = Long.MAX_VALUE;
        for (Integer label : badLabels) {
            SuggestionSupport.ObjectStats object = stats.get(label);
            if (object == null || object.voxelCount <= 0L) return null;
            minBadSize = Math.min(minBadSize, object.voxelCount);
        }
        if (minBadSize <= 0L) return null;
        int candidate = (int) Math.min(Integer.MAX_VALUE, minBadSize - 1L);
        double current = SuggestionSupport.current(ctx.currentParams, "maxSize",
                Double.POSITIVE_INFINITY);
        if (candidate >= current - SuggestionSupport.EPSILON) return null;
        final int maxSize = candidate;
        SuggestionSupport.RemovalRule rule = new SuggestionSupport.RemovalRule() {
            @Override public boolean removes(int label) {
                SuggestionSupport.ObjectStats object = stats.get(Integer.valueOf(label));
                return object != null && object.voxelCount > maxSize;
            }
        };
        return candidateIfSafe(Candidate.maxSize(maxSize), stats.keySet(),
                badLabels, positiveLabels, rule);
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

    private static Set<Integer> protectedLabels(Set<Integer> allLabels,
                                                Set<Integer> badLabels,
                                                Set<Integer> positiveLabels) {
        if (positiveLabels != null && !positiveLabels.isEmpty()) {
            return positiveLabels;
        }
        Set<Integer> out = new LinkedHashSet<Integer>();
        if (allLabels == null) return out;
        for (Integer label : allLabels) {
            if (label == null) continue;
            if (badLabels != null && badLabels.contains(label)) continue;
            out.add(label);
        }
        return out;
    }

    private static Candidate betterSizeCandidate(Candidate minSize, Candidate maxSize) {
        if (minSize == null) return maxSize;
        if (maxSize == null) return minSize;
        if (minSize.badRemoved != maxSize.badRemoved) {
            return minSize.badRemoved > maxSize.badRemoved ? minSize : maxSize;
        }
        if (minSize.collateralRemoved != maxSize.collateralRemoved) {
            return minSize.collateralRemoved < maxSize.collateralRemoved ? minSize : maxSize;
        }
        return minSize;
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

    private static final class Candidate {
        final int order;
        final Double thresholdLow;
        final Integer minSize;
        final Integer maxSize;
        int badRemoved;
        int collateralRemoved;

        Candidate(int order, Double thresholdLow, Integer minSize, Integer maxSize) {
            this.order = order;
            this.thresholdLow = thresholdLow;
            this.minSize = minSize;
            this.maxSize = maxSize;
        }

        static Candidate threshold(double value) {
            return new Candidate(0, Double.valueOf(value), null, null);
        }

        static Candidate minSize(int value) {
            return new Candidate(1, null, Integer.valueOf(value), null);
        }

        static Candidate maxSize(int value) {
            return new Candidate(2, null, null, Integer.valueOf(value));
        }

        ClassicalSuggestion toSuggestion() {
            return new ClassicalSuggestion(thresholdLow, minSize, maxSize,
                    badRemoved, collateralRemoved);
        }
    }
}
