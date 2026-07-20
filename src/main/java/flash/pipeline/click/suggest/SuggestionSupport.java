package flash.pipeline.click.suggest;

import flash.pipeline.click.ClickStore;
import flash.pipeline.objects.LabelIndex;
import ij.ImagePlus;
import ij.ImageStack;
import ij.measure.Calibration;
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

    static final class GeometryMismatchException extends IllegalArgumentException {
        private static final long serialVersionUID = 1L;

        GeometryMismatchException(String message) {
            super(message);
        }
    }

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
        if (labelImage == null || labelImage.getStack() == null) {
            return new LinkedHashMap<Integer, ObjectStats>();
        }
        requireSupportedGeometry(labelImage, "label image");
        if (valueImage != null) {
            requireRegisteredPair(labelImage, valueImage);
        }
        Map<Integer, ObjectStats> out = new LinkedHashMap<Integer, ObjectStats>();
        ImageStack labels = labelImage.getStack();
        ImageStack values = valueImage == null ? null : valueImage.getStack();
        int slices = labels.getSize();
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
                if (valueProcessor != null) {
                    float value = valueProcessor.getf(i);
                    if (Float.isFinite(value)) {
                        stats.intensitySum += value;
                    }
                }
            }
        }
        return out;
    }

    private static void requireRegisteredPair(ImagePlus labels, ImagePlus values) {
        requireSupportedGeometry(values, "value image");
        if (labels.getWidth() != values.getWidth()
                || labels.getHeight() != values.getHeight()
                || labels.getNChannels() != values.getNChannels()
                || labels.getNSlices() != values.getNSlices()
                || labels.getNFrames() != values.getNFrames()
                || !sameSpatialCalibration(labels, values)) {
            throw new GeometryMismatchException("Suggestion image registration mismatch: labels "
                    + geometry(labels) + ", values " + geometry(values)
                    + ". Expected identical X/Y/Z/C/T and spatial calibration; no partial statistics were produced.");
        }
    }

    private static void requireSupportedGeometry(ImagePlus image, String role) {
        if (image == null || image.getStack() == null || image.getStackSize() <= 0) {
            throw new GeometryMismatchException("Suggestion " + role + " is missing or empty.");
        }
        long planes = (long) image.getNChannels() * (long) image.getNSlices()
                * (long) image.getNFrames();
        if (image.getWidth() <= 0 || image.getHeight() <= 0
                || image.getNChannels() != 1 || image.getNSlices() <= 0
                || image.getNFrames() != 1 || planes != image.getStackSize()) {
            throw new GeometryMismatchException("Unsupported suggestion " + role + " geometry: "
                    + geometry(image) + ". Expected C=1 and T=1; time must not be flattened into Z.");
        }
    }

    private static boolean sameSpatialCalibration(ImagePlus first, ImagePlus second) {
        Calibration left = first == null ? null : first.getCalibration();
        Calibration right = second == null ? null : second.getCalibration();
        if (left == null || right == null) return false;
        return validSpatialCalibration(left) && validSpatialCalibration(right)
                && Double.compare(left.pixelWidth, right.pixelWidth) == 0
                && Double.compare(left.pixelHeight, right.pixelHeight) == 0
                && Double.compare(left.pixelDepth, right.pixelDepth) == 0
                && Double.compare(left.xOrigin, right.xOrigin) == 0
                && Double.compare(left.yOrigin, right.yOrigin) == 0
                && Double.compare(left.zOrigin, right.zOrigin) == 0
                && safeUnit(left).equals(safeUnit(right));
    }

    private static boolean validSpatialCalibration(Calibration calibration) {
        return calibration != null
                && Double.isFinite(calibration.pixelWidth) && calibration.pixelWidth > 0.0d
                && Double.isFinite(calibration.pixelHeight) && calibration.pixelHeight > 0.0d
                && Double.isFinite(calibration.pixelDepth) && calibration.pixelDepth > 0.0d
                && Double.isFinite(calibration.xOrigin)
                && Double.isFinite(calibration.yOrigin)
                && Double.isFinite(calibration.zOrigin);
    }

    private static String safeUnit(Calibration calibration) {
        String unit = calibration == null ? "" : calibration.getUnit();
        return unit == null ? "" : unit;
    }

    private static String geometry(ImagePlus image) {
        if (image == null) return "<null>";
        return "X=" + image.getWidth() + ",Y=" + image.getHeight()
                + ",Z=" + image.getNSlices() + ",C=" + image.getNChannels()
                + ",T=" + image.getNFrames() + ",stack=" + image.getStackSize();
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
