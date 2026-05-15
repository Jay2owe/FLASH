package flash.pipeline.click.suggest;

import flash.pipeline.cellpose.Cellpose3DRunner;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class CellposeFilterSuggester
        implements ParameterSuggester<CellposeFilterSuggester.CellposeSuggestion> {

    public static final class CellposeSuggestion {
        public final Double cellprobThreshold;
        public final Double diameter;
        public final int badRemoved;
        public final int collateralRemoved;

        CellposeSuggestion(Double cellprobThreshold,
                           Double diameter,
                           int badRemoved,
                           int collateralRemoved) {
            this.cellprobThreshold = cellprobThreshold;
            this.diameter = diameter;
            this.badRemoved = Math.max(0, badRemoved);
            this.collateralRemoved = Math.max(0, collateralRemoved);
        }

        public boolean hasSuggestion() {
            return cellprobThreshold != null || diameter != null;
        }

        public static CellposeSuggestion none() {
            return new CellposeSuggestion(null, null, 0, 0);
        }
    }

    @Override
    public CellposeSuggestion suggest(SuggestionContext ctx) {
        if (ctx == null || ctx.negativeClicks.size() < 3) {
            return CellposeSuggestion.none();
        }
        Map<Integer, SuggestionSupport.ObjectStats> stats =
                SuggestionSupport.objectStats(ctx.labelImage, ctx.channelImage);
        if (stats.isEmpty()) {
            return CellposeSuggestion.none();
        }
        Set<Integer> badLabels =
                SuggestionSupport.labelsForClicks(ctx.labelImage, ctx.negativeClicks);
        Set<Integer> positiveLabels =
                SuggestionSupport.labelsForClicks(ctx.labelImage, ctx.positiveClicks);
        if (badLabels.size() < 3) {
            return CellposeSuggestion.none();
        }

        CellposeSuggestion cellprob = cellprobSuggestion(ctx, stats.keySet(),
                badLabels, positiveLabels);
        if (cellprob != null && cellprob.hasSuggestion()
                && cellprob.badRemoved >= badLabels.size()) {
            return cellprob;
        }
        CellposeSuggestion diameter = diameterSuggestion(ctx, stats,
                badLabels, positiveLabels);
        if (cellprob != null && cellprob.hasSuggestion()) {
            return cellprob;
        }
        return diameter == null ? CellposeSuggestion.none() : diameter;
    }

    private CellposeSuggestion cellprobSuggestion(SuggestionContext ctx,
                                                 Set<Integer> allLabels,
                                                 Set<Integer> badLabels,
                                                 Set<Integer> positiveLabels) {
        if (ctx.auxImage == null) {
            return null;
        }
        final Map<Integer, Double> means = cellprobMeans(ctx);
        if (means.isEmpty()) return null;

        double maxBad = Double.NEGATIVE_INFINITY;
        for (Integer label : badLabels) {
            Double value = means.get(label);
            if (value == null || !Double.isFinite(value.doubleValue())) return null;
            maxBad = Math.max(maxBad, value.doubleValue());
        }
        double minPositive = Double.POSITIVE_INFINITY;
        for (Integer label : positiveLabels) {
            Double value = means.get(label);
            if (value != null && Double.isFinite(value.doubleValue())) {
                minPositive = Math.min(minPositive, value.doubleValue());
            }
        }
        if (!(maxBad < minPositive)) {
            return null;
        }
        double current = SuggestionSupport.current(ctx.currentParams,
                "cellprob_threshold",
                SuggestionSupport.current(ctx.currentParams, "cellprobThreshold",
                        Double.NEGATIVE_INFINITY));
        final double threshold = maxBad + 0.01d;
        if (threshold <= current + SuggestionSupport.EPSILON) {
            return null;
        }
        SuggestionSupport.RemovalRule rule = new SuggestionSupport.RemovalRule() {
            @Override public boolean removes(int label) {
                Double value = means.get(Integer.valueOf(label));
                return value != null && Double.isFinite(value.doubleValue())
                        && value.doubleValue() < threshold;
            }
        };
        if (SuggestionSupport.removesAny(positiveLabels, rule)) {
            return null;
        }
        int badRemoved = SuggestionSupport.countRemoved(badLabels, rule);
        int collateralRemoved = SuggestionSupport.countCollateral(
                allLabels, badLabels, positiveLabels, rule);
        return badRemoved <= 0 ? null : new CellposeSuggestion(
                Double.valueOf(threshold), null, badRemoved, collateralRemoved);
    }

    private static Map<Integer, Double> cellprobMeans(SuggestionContext ctx) {
        Map<Integer, Double> out = new LinkedHashMap<Integer, Double>();
        double[] means = Cellpose3DRunner.perObjectMeanCellprob(ctx.labelImage, ctx.auxImage);
        for (int label = 1; label < means.length; label++) {
            if (Double.isFinite(means[label])) {
                out.put(Integer.valueOf(label), Double.valueOf(means[label]));
            }
        }
        return out;
    }

    private CellposeSuggestion diameterSuggestion(SuggestionContext ctx,
                                                 Map<Integer, SuggestionSupport.ObjectStats> stats,
                                                 Set<Integer> badLabels,
                                                 Set<Integer> positiveLabels) {
        Map<Integer, Double> areas = new LinkedHashMap<Integer, Double>();
        for (Map.Entry<Integer, SuggestionSupport.ObjectStats> entry : stats.entrySet()) {
            areas.put(entry.getKey(), Double.valueOf(entry.getValue().voxelCount));
        }
        List<Double> badAreas = SuggestionSupport.valuesForLabels(areas, badLabels);
        if (badAreas.size() < badLabels.size()) return null;
        List<Double> goodAreas = SuggestionSupport.valuesForLabels(areas, positiveLabels);
        if (goodAreas.isEmpty()) {
            goodAreas = new ArrayList<Double>();
            for (Map.Entry<Integer, Double> entry : areas.entrySet()) {
                if (!badLabels.contains(entry.getKey())) {
                    goodAreas.add(entry.getValue());
                }
            }
        }
        if (goodAreas.isEmpty()) return null;
        double medianBad = SuggestionSupport.median(badAreas);
        double medianGood = SuggestionSupport.median(goodAreas);
        if (!Double.isFinite(medianBad) || !Double.isFinite(medianGood)
                || !(medianBad < 0.5d * medianGood)) {
            return null;
        }
        double current = SuggestionSupport.current(ctx.currentParams, "diameter", 30.0d);
        double targetDiameter = equivalentDiameter(medianGood);
        if (!Double.isFinite(targetDiameter) || targetDiameter <= current) {
            targetDiameter = Math.max(1.0d, current * 1.25d);
        }
        double maxBadArea = Double.NEGATIVE_INFINITY;
        for (Double value : badAreas) {
            maxBadArea = Math.max(maxBadArea, value.doubleValue());
        }
        int collateral = 0;
        for (Map.Entry<Integer, Double> entry : areas.entrySet()) {
            Integer label = entry.getKey();
            if (badLabels.contains(label) || positiveLabels.contains(label)) continue;
            if (entry.getValue().doubleValue() <= maxBadArea) {
                collateral++;
            }
        }
        return new CellposeSuggestion(null, Double.valueOf(targetDiameter),
                badLabels.size(), collateral);
    }

    private static double equivalentDiameter(double areaPixels) {
        if (!Double.isFinite(areaPixels) || areaPixels <= 0.0d) return Double.NaN;
        return 2.0d * Math.sqrt(areaPixels / Math.PI);
    }
}
