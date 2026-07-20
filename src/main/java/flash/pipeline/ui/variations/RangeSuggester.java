package flash.pipeline.ui.variations;

import flash.pipeline.bin.BinConfig;
import flash.pipeline.ui.config.CellposeParameterStage;
import flash.pipeline.ui.config.StarDistParameterStage;

import ij.IJ;
import ij.ImagePlus;
import ij.ImageStack;
import ij.process.AutoThresholder;
import ij.process.ImageProcessor;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

public final class RangeSuggester {

    private static final AutoThresholder.Method[] CLASSICAL_METHODS = {
            AutoThresholder.Method.Otsu,
            AutoThresholder.Method.Li,
            AutoThresholder.Method.Triangle,
            AutoThresholder.Method.Yen,
            AutoThresholder.Method.Huang
    };
    private static final double[] SIZE_PERCENTILES = { 10.0d, 25.0d, 50.0d, 75.0d, 90.0d };

    private RangeSuggester() {
    }

    public static Map<ParameterId, ParameterValueList> suggest(
            VariationEngineContext context, ParameterSweep currentDraft) {
        LinkedHashMap<ParameterId, ParameterValueList> out =
                new LinkedHashMap<ParameterId, ParameterValueList>();
        ParameterSweep.Method method = context == null
                ? ParameterSweep.Method.CLASSICAL
                : context.method();
        if (method == ParameterSweep.Method.STARDIST) {
            suggestStarDist(context, currentDraft, out);
        } else if (method == ParameterSweep.Method.CELLPOSE) {
            suggestCellpose(context, currentDraft, out);
        } else {
            suggestClassical(context, currentDraft, out);
        }
        return out;
    }

    private static void suggestClassical(VariationEngineContext context,
                                         ParameterSweep currentDraft,
                                         Map<ParameterId, ParameterValueList> out) {
        final ImagePlus source = sourceFor(context);
        final Projection projection;
        if (source == null) {
            projection = null;
        } else {
            projection = projectMax(source);
        }

        put(out, ParameterId.THRESHOLD, new ValueFactory() {
            @Override public ParameterValueList create() {
                if (projection == null) {
                    return currentList(currentDraft, ParameterId.THRESHOLD,
                            Integer.valueOf(currentInt(context, currentDraft,
                                    ParameterId.THRESHOLD, 128)));
                }
                return thresholdSuggestions(projection);
            }
        });

        put(out, ParameterId.MIN_SIZE, new ValueFactory() {
            @Override public ParameterValueList create() {
                if (projection == null) {
                    return currentList(currentDraft, ParameterId.MIN_SIZE,
                            Integer.valueOf(currentInt(context, currentDraft,
                                    ParameterId.MIN_SIZE, 100)));
                }
                return componentSizeSuggestions(projection, currentDraft,
                        ParameterId.MIN_SIZE, currentInt(context, currentDraft,
                                ParameterId.MIN_SIZE, 100));
            }
        });

        put(out, ParameterId.MAX_SIZE, new ValueFactory() {
            @Override public ParameterValueList create() {
                if (projection == null) {
                    return currentList(currentDraft, ParameterId.MAX_SIZE,
                            Integer.valueOf(currentInt(context, currentDraft,
                                    ParameterId.MAX_SIZE, Integer.MAX_VALUE)));
                }
                return componentSizeSuggestions(projection, currentDraft,
                        ParameterId.MAX_SIZE, currentInt(context, currentDraft,
                                ParameterId.MAX_SIZE, Integer.MAX_VALUE));
            }
        });
    }

    private static void suggestStarDist(VariationEngineContext context,
                                        ParameterSweep currentDraft,
                                        Map<ParameterId, ParameterValueList> out) {
        final double prob = currentDouble(context, currentDraft,
                ParameterId.PROB_THRESH, 0.5d);
        put(out, ParameterId.PROB_THRESH, new ValueFactory() {
            @Override public ParameterValueList create() {
                return doubleList(uniqueDoubles(
                        clamp(prob - 0.2d, 0.05d, 0.95d),
                        clamp(prob - 0.1d, 0.05d, 0.95d),
                        clamp(prob, 0.05d, 0.95d),
                        clamp(prob + 0.1d, 0.05d, 0.95d),
                        clamp(prob + 0.2d, 0.05d, 0.95d)));
            }
        });

        final double nms = currentDouble(context, currentDraft,
                ParameterId.NMS_THRESH, 0.3d);
        put(out, ParameterId.NMS_THRESH, new ValueFactory() {
            @Override public ParameterValueList create() {
                return doubleList(uniqueDoubles(0.3d, 0.4d, 0.5d, 0.6d, nms));
            }
        });

        singleCurrent(out, context, currentDraft, ParameterId.LINKING_MAX, Double.valueOf(15.0d));
        singleCurrent(out, context, currentDraft, ParameterId.GAP_CLOSING_MAX, Double.valueOf(15.0d));
        singleCurrent(out, context, currentDraft, ParameterId.FRAME_GAP, Integer.valueOf(2));
        singleCurrent(out, context, currentDraft, ParameterId.AREA_MIN, Double.valueOf(0.0d));
        singleCurrent(out, context, currentDraft, ParameterId.AREA_MAX, Double.valueOf(0.0d));
        singleCurrent(out, context, currentDraft, ParameterId.QUALITY_MIN, Double.valueOf(0.0d));
        singleCurrent(out, context, currentDraft, ParameterId.INTENSITY_MIN, Double.valueOf(0.0d));
    }

    private static void suggestCellpose(VariationEngineContext context,
                                        ParameterSweep currentDraft,
                                        Map<ParameterId, ParameterValueList> out) {
        final ImagePlus source = sourceFor(context);
        final double fallbackDiameter = currentDouble(context, currentDraft,
                ParameterId.DIAMETER, 30.0d);
        put(out, ParameterId.DIAMETER, new ValueFactory() {
            @Override public ParameterValueList create() {
                if (source == null) {
                    return currentList(currentDraft, ParameterId.DIAMETER,
                            Integer.valueOf(Math.max(1, (int) Math.round(fallbackDiameter))));
                }
                Projection projection = projectMax(source);
                ParameterValueList sizes = componentDiameterSuggestions(projection,
                        fallbackDiameter);
                return sizes == null ? currentList(currentDraft, ParameterId.DIAMETER,
                        Integer.valueOf(Math.max(1, (int) Math.round(fallbackDiameter)))) : sizes;
            }
        });
        out.put(ParameterId.FLOW_THRESHOLD,
                ParameterValueList.ofDoubles(0.2d, 0.4d, 0.6d, 0.8d));
        out.put(ParameterId.CELLPROB_THRESHOLD,
                ParameterValueList.ofDoubles(-2.0d, -1.0d, 0.0d, 1.0d, 2.0d));
        out.put(ParameterId.MODEL, ParameterValueList.ofStrings(
                currentString(context, currentDraft, ParameterId.MODEL,
                        BinConfig.DEFAULT_CELLPOSE_MODEL)));
    }

    private static ParameterValueList thresholdSuggestions(Projection projection) {
        Histogram histogram = histogram(projection.values);
        TreeSet<Integer> values = new TreeSet<Integer>();
        AutoThresholder thresholder = new AutoThresholder();
        for (int i = 0; i < CLASSICAL_METHODS.length; i++) {
            int bin = thresholder.getThreshold(CLASSICAL_METHODS[i], histogram.counts);
            values.add(Integer.valueOf(Math.max(0, (int) Math.round(histogram.valueFor(bin)))));
        }
        return intList(centeredWindow(new ArrayList<Integer>(values), 5));
    }

    private static ParameterValueList componentSizeSuggestions(Projection projection,
                                                               ParameterSweep currentDraft,
                                                               ParameterId id,
                                                               int fallback) {
        List<Integer> sizes = componentSizes(projection, otsuThreshold(projection));
        if (sizes.isEmpty()) {
            return currentList(currentDraft, id, Integer.valueOf(fallback));
        }
        return intList(percentileInts(sizes));
    }

    private static ParameterValueList componentDiameterSuggestions(Projection projection,
                                                                   double fallback) {
        List<Integer> sizes = componentSizes(projection, otsuThreshold(projection));
        if (sizes.isEmpty()) {
            int rounded = Math.max(1, (int) Math.round(fallback));
            return ParameterValueList.ofInts(rounded);
        }
        Collections.sort(sizes);
        double medianArea = percentile(sizes, 50.0d);
        double medianDiameter = Math.sqrt((4.0d * medianArea) / Math.PI);
        TreeSet<Integer> values = new TreeSet<Integer>();
        double[] factors = { 0.6d, 0.8d, 1.0d, 1.25d, 1.6d };
        for (int i = 0; i < factors.length; i++) {
            values.add(Integer.valueOf(Math.max(1,
                    (int) Math.round(medianDiameter * factors[i]))));
        }
        return intList(new ArrayList<Integer>(values));
    }

    private static int otsuThreshold(Projection projection) {
        Histogram histogram = histogram(projection.values);
        int bin = new AutoThresholder().getThreshold(AutoThresholder.Method.Otsu,
                histogram.counts);
        return Math.max(0, (int) Math.round(histogram.valueFor(bin)));
    }

    private static List<Integer> componentSizes(Projection projection, int threshold) {
        int width = projection.width;
        int height = projection.height;
        boolean[] visited = new boolean[projection.values.length];
        ArrayDeque<Integer> queue = new ArrayDeque<Integer>();
        List<Integer> sizes = new ArrayList<Integer>();
        for (int i = 0; i < projection.values.length; i++) {
            if (visited[i] || projection.values[i] <= threshold) {
                continue;
            }
            visited[i] = true;
            queue.add(Integer.valueOf(i));
            int count = 0;
            while (!queue.isEmpty()) {
                int pixel = queue.removeFirst().intValue();
                count++;
                int x = pixel % width;
                int y = pixel / width;
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dx = -1; dx <= 1; dx++) {
                        if (dx == 0 && dy == 0) {
                            continue;
                        }
                        int nx = x + dx;
                        int ny = y + dy;
                        if (nx < 0 || nx >= width || ny < 0 || ny >= height) {
                            continue;
                        }
                        int ni = ny * width + nx;
                        if (!visited[ni] && projection.values[ni] > threshold) {
                            visited[ni] = true;
                            queue.add(Integer.valueOf(ni));
                        }
                    }
                }
            }
            sizes.add(Integer.valueOf(count));
        }
        Collections.sort(sizes);
        return sizes;
    }

    private static Projection projectMax(ImagePlus source) {
        int width = source.getWidth();
        int height = source.getHeight();
        double[] values = new double[width * height];
        Arrays.fill(values, -Double.MAX_VALUE);
        ImageStack stack = source.getStack();
        int planes = Math.max(1, stack.getSize());
        for (int z = 1; z <= planes; z++) {
            ImageProcessor processor = stack.getProcessor(z);
            for (int y = 0; y < height; y++) {
                int offset = y * width;
                for (int x = 0; x < width; x++) {
                    double value = processor.getPixelValue(x, y);
                    if (Double.isNaN(value)) {
                        value = 0.0d;
                    }
                    int index = offset + x;
                    if (value > values[index]) {
                        values[index] = value;
                    }
                }
            }
        }
        for (int i = 0; i < values.length; i++) {
            if (values[i] == -Double.MAX_VALUE) {
                values[i] = 0.0d;
            }
        }
        return new Projection(width, height, values);
    }

    private static Histogram histogram(double[] values) {
        double min = Double.POSITIVE_INFINITY;
        double max = Double.NEGATIVE_INFINITY;
        boolean integral = true;
        for (int i = 0; i < values.length; i++) {
            double value = values[i];
            if (value < min) min = value;
            if (value > max) max = value;
            if (Math.abs(value - Math.rint(value)) > 0.000001d) {
                integral = false;
            }
        }
        if (!Double.isFinite(min) || !Double.isFinite(max)) {
            min = 0.0d;
            max = 0.0d;
        }
        boolean direct = integral && min >= 0.0d && max <= 65535.0d;
        int bins;
        if (direct) {
            bins = max <= 255.0d ? 256 : 65536;
        } else {
            bins = 256;
        }
        int[] counts = new int[bins];
        if (max <= min) {
            int bin = direct ? (int) Math.round(min) : 0;
            if (bin < 0) bin = 0;
            if (bin >= counts.length) bin = counts.length - 1;
            counts[bin] = values.length;
            return new Histogram(counts, min, max, direct);
        }
        for (int i = 0; i < values.length; i++) {
            int bin;
            if (direct) {
                bin = (int) Math.round(values[i]);
            } else {
                bin = (int) Math.floor(((values[i] - min) / (max - min)) * (bins - 1));
            }
            if (bin < 0) bin = 0;
            if (bin >= counts.length) bin = counts.length - 1;
            counts[bin]++;
        }
        return new Histogram(counts, min, max, direct);
    }

    private static List<Integer> percentileInts(List<Integer> sortedSizes) {
        List<Integer> out = new ArrayList<Integer>();
        TreeSet<Integer> unique = new TreeSet<Integer>();
        for (int i = 0; i < SIZE_PERCENTILES.length; i++) {
            unique.add(Integer.valueOf(Math.max(1,
                    (int) Math.round(percentile(sortedSizes, SIZE_PERCENTILES[i])))));
        }
        out.addAll(unique);
        return out;
    }

    private static double percentile(List<Integer> sorted, double percentile) {
        if (sorted.isEmpty()) {
            return 0.0d;
        }
        if (sorted.size() == 1) {
            return sorted.get(0).doubleValue();
        }
        double rank = (percentile / 100.0d) * (sorted.size() - 1);
        int low = (int) Math.floor(rank);
        int high = (int) Math.ceil(rank);
        if (low == high) {
            return sorted.get(low).doubleValue();
        }
        double fraction = rank - low;
        return sorted.get(low).doubleValue() * (1.0d - fraction)
                + sorted.get(high).doubleValue() * fraction;
    }

    private static List<Integer> centeredWindow(List<Integer> sorted, int maxCount) {
        if (sorted.size() <= maxCount) {
            return sorted;
        }
        int start = Math.max(0, (sorted.size() - maxCount) / 2);
        return new ArrayList<Integer>(sorted.subList(start, start + maxCount));
    }

    private static List<Double> uniqueDoubles(double... values) {
        TreeSet<Double> unique = new TreeSet<Double>();
        for (int i = 0; i < values.length; i++) {
            unique.add(Double.valueOf(round(values[i])));
        }
        return new ArrayList<Double>(unique);
    }

    private static ParameterValueList intList(List<Integer> values) {
        return new ParameterValueList(values);
    }

    private static ParameterValueList doubleList(List<Double> values) {
        return new ParameterValueList(values);
    }

    private static ParameterValueList currentList(ParameterSweep draft,
                                                  ParameterId id,
                                                  Object fallback) {
        if (draft != null && draft.valueLists().containsKey(id)) {
            return draft.valueLists().get(id);
        }
        return new ParameterValueList(Collections.singletonList(fallback));
    }

    private static void singleCurrent(Map<ParameterId, ParameterValueList> out,
                                      VariationEngineContext context,
                                      ParameterSweep draft,
                                      ParameterId id,
                                      Object fallback) {
        out.put(id, currentList(draft, id, currentObject(context, draft, id, fallback)));
    }

    private static int currentInt(VariationEngineContext context,
                                  ParameterSweep draft,
                                  ParameterId id,
                                  int fallback) {
        Object value = currentObject(context, draft, id, Integer.valueOf(fallback));
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        return fallback;
    }

    private static double currentDouble(VariationEngineContext context,
                                        ParameterSweep draft,
                                        ParameterId id,
                                        double fallback) {
        Object value = currentObject(context, draft, id, Double.valueOf(fallback));
        if (value instanceof Number) {
            return ((Number) value).doubleValue();
        }
        return fallback;
    }

    private static String currentString(VariationEngineContext context,
                                        ParameterSweep draft,
                                        ParameterId id,
                                        String fallback) {
        Object value = currentObject(context, draft, id, fallback);
        return value == null ? fallback : String.valueOf(value);
    }

    private static Object currentObject(VariationEngineContext context,
                                        ParameterSweep draft,
                                        ParameterId id,
                                        Object fallback) {
        if (draft != null && draft.valueLists().containsKey(id)) {
            ParameterValueList values = draft.valueLists().get(id);
            if (values != null && values.size() > 0) {
                return values.get(0);
            }
        }
        if (context == null || context.baseParameters() == null) {
            return fallback;
        }
        Object base = context.baseParameters();
        if (base instanceof ParameterCombo) {
            ParameterCombo combo = (ParameterCombo) base;
            return combo.contains(id) ? combo.get(id) : fallback;
        }
        if (base instanceof StarDistParameterStage.Parameters) {
            StarDistParameterStage.Parameters p =
                    (StarDistParameterStage.Parameters) base;
            if (id == ParameterId.PROB_THRESH) return Double.valueOf(p.probabilityThreshold);
            if (id == ParameterId.NMS_THRESH) return Double.valueOf(p.nmsThreshold);
            if (id == ParameterId.LINKING_MAX) return Double.valueOf(p.linkingMaxDistance);
            if (id == ParameterId.GAP_CLOSING_MAX) return Double.valueOf(p.gapClosingMaxDistance);
            if (id == ParameterId.FRAME_GAP) return Integer.valueOf(p.maxFrameGap);
            if (id == ParameterId.AREA_MIN) return Double.valueOf(p.areaMin);
            if (id == ParameterId.AREA_MAX) {
                return Double.valueOf(Double.isInfinite(p.areaMax) ? 0.0d : p.areaMax);
            }
            if (id == ParameterId.QUALITY_MIN) return Double.valueOf(p.qualityMin);
            if (id == ParameterId.INTENSITY_MIN) return Double.valueOf(p.intensityMin);
        }
        if (base instanceof CellposeParameterStage.Parameters) {
            CellposeParameterStage.Parameters p =
                    (CellposeParameterStage.Parameters) base;
            if (id == ParameterId.DIAMETER) return Double.valueOf(p.diameter);
            if (id == ParameterId.FLOW_THRESHOLD) return Double.valueOf(p.flowThreshold);
            if (id == ParameterId.CELLPROB_THRESHOLD) return Double.valueOf(p.cellprobThreshold);
            if (id == ParameterId.MODEL) return p.modelToken;
        }
        return fallback;
    }

    private static ImagePlus sourceFor(VariationEngineContext context) {
        if (context == null) {
            return null;
        }
        if (context.filteredSource() != null) {
            return context.filteredSource();
        }
        return context.rawSource();
    }

    private static void put(Map<ParameterId, ParameterValueList> out,
                            ParameterId id,
                            ValueFactory factory) {
        try {
            ParameterValueList values = factory.create();
            if (values != null && values.size() > 0) {
                out.put(id, values);
            }
        } catch (RuntimeException e) {
            IJ.log("WARNING: Could not suggest variation range for " + id + ": "
                    + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
        }
    }

    private static double clamp(double value, double min, double max) {
        if (value < min) return min;
        if (value > max) return max;
        return value;
    }

    private static double round(double value) {
        return Math.round(value * 1000.0d) / 1000.0d;
    }

    private interface ValueFactory {
        ParameterValueList create();
    }

    private static final class Projection {
        final int width;
        final int height;
        final double[] values;

        Projection(int width, int height, double[] values) {
            this.width = width;
            this.height = height;
            this.values = values;
        }
    }

    private static final class Histogram {
        final int[] counts;
        final double min;
        final double max;
        final boolean direct;

        Histogram(int[] counts, double min, double max, boolean direct) {
            this.counts = counts;
            this.min = min;
            this.max = max;
            this.direct = direct;
        }

        double valueFor(int bin) {
            if (max <= min) {
                return min;
            }
            if (direct) {
                return bin;
            }
            return min + ((double) bin / (double) Math.max(1, counts.length - 1))
                    * (max - min);
        }
    }
}
