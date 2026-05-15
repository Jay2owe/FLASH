package flash.pipeline.click.suggest;

import flash.pipeline.click.ClickStore;
import flash.pipeline.objects.LabelIndex;
import ij.ImagePlus;
import ij.ImageStack;
import ij.process.ImageProcessor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class SuggestionSupport {
    static final double EPSILON = 1.0e-6;

    interface RemovalRule {
        boolean removes(int label);
    }

    static final class ObjectStats {
        final int label;
        long voxelCount;
        double intensitySum;

        ObjectStats(int label) {
            this.label = label;
        }

        double meanIntensity() {
            return voxelCount <= 0L ? Double.NaN : intensitySum / (double) voxelCount;
        }
    }

    private SuggestionSupport() {
    }

    static Set<Integer> labelsForClicks(ImagePlus labelImage, List<ClickStore.Click> clicks) {
        Set<Integer> labels = new HashSet<Integer>();
        if (clicks == null) return labels;
        for (int i = 0; i < clicks.size(); i++) {
            ClickStore.Click click = clicks.get(i);
            int label = labelForClick(labelImage, click);
            if (label > 0) {
                labels.add(Integer.valueOf(label));
            }
        }
        return labels;
    }

    static int labelForClick(ImagePlus labelImage, ClickStore.Click click) {
        if (click == null) return 0;
        return LabelIndex.getLabelAt(labelImage,
                (int) Math.round(click.x),
                (int) Math.round(click.y),
                Math.max(1, click.z));
    }

    static Map<Integer, ObjectStats> objectStats(ImagePlus labelImage,
                                                 ImagePlus valueImage) {
        Map<Integer, ObjectStats> out = new LinkedHashMap<Integer, ObjectStats>();
        if (labelImage == null || labelImage.getStack() == null) return out;
        ImageStack labels = labelImage.getStack();
        ImageStack values = valueImage == null ? null : valueImage.getStack();
        int slices = labels.getSize();
        if (values != null) slices = Math.min(slices, values.getSize());
        for (int s = 1; s <= slices; s++) {
            ImageProcessor labelProcessor = labels.getProcessor(s);
            ImageProcessor valueProcessor = values == null ? null : values.getProcessor(s);
            if (labelProcessor == null) continue;
            int count = labelProcessor.getPixelCount();
            for (int i = 0; i < count; i++) {
                int label = labelFromPixel(labelProcessor.getf(i));
                if (label <= 0) continue;
                Integer key = Integer.valueOf(label);
                ObjectStats stats = out.get(key);
                if (stats == null) {
                    stats = new ObjectStats(label);
                    out.put(key, stats);
                }
                stats.voxelCount++;
                if (valueProcessor != null && i < valueProcessor.getPixelCount()) {
                    float value = valueProcessor.getf(i);
                    if (Float.isFinite(value)) {
                        stats.intensitySum += value;
                    }
                }
            }
        }
        return out;
    }

    static int countRemoved(Set<Integer> labels, RemovalRule rule) {
        if (labels == null || rule == null) return 0;
        int count = 0;
        for (Integer label : labels) {
            if (label != null && rule.removes(label.intValue())) {
                count++;
            }
        }
        return count;
    }

    static boolean removesAny(Set<Integer> labels, RemovalRule rule) {
        return countRemoved(labels, rule) > 0;
    }

    static int countCollateral(Set<Integer> allLabels,
                               Set<Integer> negativeLabels,
                               Set<Integer> positiveLabels,
                               RemovalRule rule) {
        if (allLabels == null || rule == null) return 0;
        int count = 0;
        for (Integer label : allLabels) {
            if (label == null) continue;
            if (negativeLabels != null && negativeLabels.contains(label)) continue;
            if (positiveLabels != null && positiveLabels.contains(label)) continue;
            if (rule.removes(label.intValue())) {
                count++;
            }
        }
        return count;
    }

    static double current(Map<String, Double> params, String key, double fallback) {
        if (params == null || key == null) return fallback;
        Double value = params.get(key);
        if (value == null) return fallback;
        double parsed = value.doubleValue();
        return Double.isFinite(parsed) ? parsed : fallback;
    }

    static boolean finite(double value) {
        return Double.isFinite(value);
    }

    static int labelFromPixel(float value) {
        if (!Float.isFinite(value) || value <= 0f || value > Integer.MAX_VALUE) return 0;
        return Math.round(value);
    }

    static List<Double> valuesForLabels(Map<Integer, ? extends Number> values,
                                        Set<Integer> labels) {
        List<Double> out = new ArrayList<Double>();
        if (values == null || labels == null) return out;
        for (Integer label : labels) {
            Number value = values.get(label);
            if (value == null) continue;
            double parsed = value.doubleValue();
            if (Double.isFinite(parsed)) {
                out.add(Double.valueOf(parsed));
            }
        }
        return out;
    }

    static double median(List<Double> values) {
        if (values == null || values.isEmpty()) return Double.NaN;
        List<Double> copy = new ArrayList<Double>(values);
        Collections.sort(copy);
        int middle = copy.size() / 2;
        if (copy.size() % 2 == 1) {
            return copy.get(middle).doubleValue();
        }
        return (copy.get(middle - 1).doubleValue() + copy.get(middle).doubleValue()) / 2.0;
    }
}
