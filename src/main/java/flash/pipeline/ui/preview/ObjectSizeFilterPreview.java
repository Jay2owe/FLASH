package flash.pipeline.ui.preview;

import ij.ImagePlus;
import ij.ImageStack;
import ij.measure.Calibration;
import ij.measure.ResultsTable;
import ij.process.ImageProcessor;
import ij.process.LUT;

import java.awt.Color;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

public final class ObjectSizeFilterPreview {

    public enum Classification {
        KEPT,
        BELOW_MIN,
        ABOVE_MAX
    }

    public static final class Summary {
        public final int minVoxels;
        public final int maxVoxels;
        public final boolean maxFinite;
        public final int totalCount;
        public final int keptCount;
        public final int belowMinCount;
        public final int aboveMaxCount;
        public final String minDiameterText;
        public final String maxDiameterText;
        public final double minDiameterPixels;
        public final double maxDiameterPixels;
        public final int minLinePixels;
        public final int maxLinePixels;
        private final Map<Integer, Classification> classesByLabel;

        private Summary(int minVoxels,
                        int maxVoxels,
                        boolean maxFinite,
                        int totalCount,
                        int keptCount,
                        int belowMinCount,
                        int aboveMaxCount,
                        String minDiameterText,
                        String maxDiameterText,
                        double minDiameterPixels,
                        double maxDiameterPixels,
                        int minLinePixels,
                        int maxLinePixels,
                        Map<Integer, Classification> classesByLabel) {
            this.minVoxels = minVoxels;
            this.maxVoxels = maxVoxels;
            this.maxFinite = maxFinite;
            this.totalCount = totalCount;
            this.keptCount = keptCount;
            this.belowMinCount = belowMinCount;
            this.aboveMaxCount = aboveMaxCount;
            this.minDiameterText = minDiameterText;
            this.maxDiameterText = maxDiameterText;
            this.minDiameterPixels = minDiameterPixels;
            this.maxDiameterPixels = maxDiameterPixels;
            this.minLinePixels = minLinePixels;
            this.maxLinePixels = maxLinePixels;
            this.classesByLabel = classesByLabel == null
                    ? Collections.<Integer, Classification>emptyMap()
                    : Collections.unmodifiableMap(new HashMap<Integer, Classification>(classesByLabel));
        }

        public Classification classificationForLabel(int label) {
            Classification classification = classesByLabel.get(Integer.valueOf(label));
            return classification == null ? Classification.KEPT : classification;
        }

        public Set<Integer> removedLabels() {
            Set<Integer> removed = new HashSet<Integer>();
            for (Map.Entry<Integer, Classification> entry : classesByLabel.entrySet()) {
                Classification classification = entry.getValue();
                if (classification != null && classification != Classification.KEPT) {
                    removed.add(entry.getKey());
                }
            }
            return removed;
        }

        public String statusText() {
            int removed = belowMinCount + aboveMaxCount;
            if (totalCount <= 0) {
                return "Objects: not previewed";
            }
            if (removed <= 0) {
                return "Objects: " + keptCount + " ready";
            }
            return "Objects: " + keptCount + " kept; removed "
                    + belowMinCount + " small, " + aboveMaxCount + " large";
        }
    }

    private static final int BELOW_MIN_RGB = 0xe53935;
    private static final int ABOVE_MAX_RGB = 0xf9a825;

    private ObjectSizeFilterPreview() {
    }

    public static Summary summarize(ResultsTable stats,
                                    ImagePlus reference,
                                    int minVoxels,
                                    int maxVoxels,
                                    boolean maxFinite) {
        int safeMin = Math.max(0, minVoxels);
        int safeMax = Math.max(safeMin, maxVoxels);
        Map<Integer, Classification> classes = new HashMap<Integer, Classification>();
        int total = stats == null ? 0 : Math.max(0, stats.size());
        int kept = 0;
        int small = 0;
        int large = 0;

        String volumeHeading = volumeHeading(stats);
        double voxelVolume = voxelVolume(reference);
        for (int row = 0; row < total; row++) {
            int label = labelForRow(stats, row);
            double voxels = voxelCount(stats, volumeHeading, voxelVolume, row);
            Classification classification = Classification.KEPT;
            if (Double.isFinite(voxels)) {
                if (voxels < safeMin) {
                    classification = Classification.BELOW_MIN;
                } else if (maxFinite && voxels > safeMax) {
                    classification = Classification.ABOVE_MAX;
                }
            }
            classes.put(Integer.valueOf(label), classification);
            if (classification == Classification.BELOW_MIN) {
                small++;
            } else if (classification == Classification.ABOVE_MAX) {
                large++;
            } else {
                kept++;
            }
        }

        return new Summary(
                safeMin,
                safeMax,
                maxFinite,
                total,
                kept,
                small,
                large,
                diameterText(safeMin, reference),
                maxFinite ? diameterText(safeMax, reference) : "",
                diameterPixels(safeMin, reference),
                maxFinite ? diameterPixels(safeMax, reference) : 0.0,
                linePixels(safeMin),
                maxFinite ? linePixels(safeMax) : 0,
                classes);
    }

    public static ResultsTable statisticsFromLabelMap(ImagePlus labelMap, ImagePlus reference) {
        ResultsTable stats = new ResultsTable();
        if (labelMap == null || labelMap.getStack() == null || labelMap.getStackSize() < 1) {
            return stats;
        }
        Map<Integer, Integer> voxelsByLabel = new TreeMap<Integer, Integer>();
        ImageStack stack = labelMap.getStack();
        for (int slice = 1; slice <= stack.getSize(); slice++) {
            ImageProcessor processor = stack.getProcessor(slice);
            if (processor == null) continue;
            for (int i = 0; i < processor.getPixelCount(); i++) {
                double value = processor.getf(i);
                if (!Double.isFinite(value) || value <= 0.0) continue;
                int label = (int) Math.round(value);
                if (label <= 0) continue;
                Integer previous = voxelsByLabel.get(Integer.valueOf(label));
                voxelsByLabel.put(Integer.valueOf(label),
                        Integer.valueOf(previous == null ? 1 : previous.intValue() + 1));
            }
        }
        double voxelVolume = voxelVolume(reference == null ? labelMap : reference);
        String unit = unit(reference == null ? labelMap : reference);
        String volumeHeading = "Volume (" + unit + "^3)";
        int row = 0;
        for (Map.Entry<Integer, Integer> entry : voxelsByLabel.entrySet()) {
            stats.incrementCounter();
            stats.setValue("Label", row, entry.getKey().intValue());
            stats.setValue(volumeHeading, row, entry.getValue().intValue() * voxelVolume);
            row++;
        }
        return stats;
    }

    public static void applyClassifiedLut(ImagePlus labelImage, Summary summary) {
        applyClassifiedLut(labelImage, summary, null);
    }

    public static void applyClassifiedLut(ImagePlus labelImage,
                                          Summary summary,
                                          Map<Integer, Classification> extraClassesByLabel) {
        if (labelImage == null) return;
        Summary safeSummary = summary == null
                ? summarize(null, labelImage, 0, Integer.MAX_VALUE, false)
                : summary;
        Map<Integer, Classification> classes = new HashMap<Integer, Classification>();
        classes.putAll(safeSummary.classesByLabel);
        if (extraClassesByLabel != null) {
            for (Map.Entry<Integer, Classification> entry : extraClassesByLabel.entrySet()) {
                if (entry.getValue() != null && entry.getValue() != Classification.KEPT) {
                    classes.put(entry.getKey(), entry.getValue());
                }
            }
        }
        byte[] red = new byte[256];
        byte[] green = new byte[256];
        byte[] blue = new byte[256];
        red[0] = 0;
        green[0] = 0;
        blue[0] = 0;
        for (int i = 1; i < 256; i++) {
            int rgb = LabelMapStyler.rgbForLabel(i);
            red[i] = (byte) ((rgb >> 16) & 0xff);
            green[i] = (byte) ((rgb >> 8) & 0xff);
            blue[i] = (byte) (rgb & 0xff);
        }
        for (Map.Entry<Integer, Classification> entry : classes.entrySet()) {
            if (entry.getValue() == Classification.KEPT) continue;
            int label = entry.getKey().intValue();
            int index = categoricalIndex(label);
            int rgb = rgbForClassification(label, entry.getValue());
            red[index] = (byte) ((rgb >> 16) & 0xff);
            green[index] = (byte) ((rgb >> 8) & 0xff);
            blue[index] = (byte) (rgb & 0xff);
        }
        labelImage.setDisplayRange(0, Math.max(1, Math.max(safeSummary.totalCount, maxDisplayValue(labelImage))));
        labelImage.setLut(new LUT(red, green, blue));
        labelImage.updateAndDraw();
    }

    public static int rgbForClassification(int label, Classification classification) {
        if (label <= 0) return 0x000000;
        if (classification == Classification.BELOW_MIN) return BELOW_MIN_RGB;
        if (classification == Classification.ABOVE_MAX) return ABOVE_MAX_RGB;
        return LabelMapStyler.rgbForLabel(label);
    }

    private static int labelForRow(ResultsTable stats, int row) {
        if (stats != null) {
            try {
                double label = stats.getValue("Label", row);
                if (Double.isFinite(label) && label > 0) {
                    return Math.max(1, (int) Math.round(label));
                }
            } catch (Exception ignored) {
                // Fall back to the legacy row order.
            }
        }
        return row + 1;
    }

    private static double voxelCount(ResultsTable stats, String volumeHeading,
                                     double voxelVolume, int row) {
        if (stats == null || volumeHeading == null || !Double.isFinite(voxelVolume)
                || voxelVolume <= 0.0) {
            return Double.NaN;
        }
        try {
            double volume = stats.getValue(volumeHeading, row);
            if (Double.isFinite(volume)) {
                return volume / voxelVolume;
            }
        } catch (Exception ignored) {
            // Missing volume data means we leave the object classified as kept.
        }
        return Double.NaN;
    }

    private static String volumeHeading(ResultsTable stats) {
        if (stats == null) return null;
        String[] headings = stats.getHeadings();
        if (headings == null) return null;
        for (int i = 0; i < headings.length; i++) {
            String heading = headings[i];
            if (heading != null && heading.toLowerCase(Locale.ROOT).startsWith("volume")) {
                return heading;
            }
        }
        return null;
    }

    private static double voxelVolume(ImagePlus reference) {
        Calibration cal = reference == null ? null : reference.getCalibration();
        if (cal == null) return 1.0;
        double w = finitePositive(cal.pixelWidth) ? cal.pixelWidth : 1.0;
        double h = finitePositive(cal.pixelHeight) ? cal.pixelHeight : 1.0;
        double d = finitePositive(cal.pixelDepth) ? cal.pixelDepth : 1.0;
        return w * h * d;
    }

    private static String unit(ImagePlus reference) {
        Calibration cal = reference == null ? null : reference.getCalibration();
        String unit = cal == null ? null : cal.getUnit();
        return unit == null || unit.trim().isEmpty() ? "pixel" : unit.trim();
    }

    private static boolean finitePositive(double value) {
        return Double.isFinite(value) && value > 0.0;
    }

    private static String diameterText(int voxelCount, ImagePlus reference) {
        double diameterVoxels = sphereEquivalentDiameter(Math.max(0, voxelCount));
        Calibration cal = reference == null ? null : reference.getCalibration();
        if (cal != null && calibratedUnit(cal)) {
            double voxelVol = voxelVolume(reference);
            double physicalDiameter = sphereEquivalentDiameter(Math.max(0, voxelCount) * voxelVol);
            return "~" + formatOneDecimal(physicalDiameter) + " " + normalizedUnit(cal) + " diameter";
        }
        return "~" + formatOneDecimal(diameterVoxels) + " px diameter";
    }

    private static boolean calibratedUnit(Calibration cal) {
        String unit = cal == null ? null : cal.getUnit();
        if (unit == null) return false;
        String normalized = unit.trim().toLowerCase(Locale.ROOT);
        return !normalized.isEmpty()
                && !"pixel".equals(normalized)
                && !"pixels".equals(normalized)
                && !"px".equals(normalized);
    }

    private static String normalizedUnit(Calibration cal) {
        String unit = cal == null ? "" : cal.getUnit();
        if (unit == null) return "";
        String trimmed = unit.trim();
        if ("micron".equalsIgnoreCase(trimmed)
                || "microns".equalsIgnoreCase(trimmed)
                || "um".equalsIgnoreCase(trimmed)) {
            return "um";
        }
        return trimmed.isEmpty() ? "unit" : trimmed;
    }

    private static double sphereEquivalentDiameter(double volume) {
        if (!Double.isFinite(volume) || volume <= 0.0) return 0.0;
        return 2.0 * Math.cbrt((3.0 * volume) / (4.0 * Math.PI));
    }

    private static String formatOneDecimal(double value) {
        if (!Double.isFinite(value)) return "?";
        if (Math.abs(value - Math.rint(value)) < 0.05) {
            return String.valueOf((int) Math.round(value));
        }
        return String.format(Locale.ROOT, "%.1f", value);
    }

    private static double diameterPixels(int voxelCount, ImagePlus reference) {
        double volume = Math.max(0, voxelCount);
        Calibration cal = reference == null ? null : reference.getCalibration();
        if (cal != null && calibratedUnit(cal)) {
            double physicalDiameter = sphereEquivalentDiameter(volume * voxelVolume(reference));
            double xyPixelSize = xyPixelSize(cal);
            if (Double.isFinite(physicalDiameter) && finitePositive(xyPixelSize)) {
                return physicalDiameter / xyPixelSize;
            }
        }
        return sphereEquivalentDiameter(volume);
    }

    private static double xyPixelSize(Calibration cal) {
        if (cal == null) return 1.0;
        double w = finitePositive(cal.pixelWidth) ? cal.pixelWidth : 1.0;
        double h = finitePositive(cal.pixelHeight) ? cal.pixelHeight : 1.0;
        return (w + h) / 2.0;
    }

    private static int linePixels(int voxelCount) {
        double diameter = sphereEquivalentDiameter(Math.max(0, voxelCount));
        int pixels = (int) Math.round(12.0 + diameter * 5.0);
        return Math.max(14, Math.min(160, pixels));
    }

    private static int categoricalIndex(int label) {
        return ((Math.max(1, label) - 1) % 255) + 1;
    }

    private static int maxDisplayValue(ImagePlus image) {
        if (image == null || image.getStack() == null || image.getStackSize() < 1) {
            return 1;
        }
        ImageStack stack = image.getStack();
        double max = 0.0;
        for (int i = 1; i <= stack.getSize(); i++) {
            ImageProcessor processor = stack.getProcessor(i);
            if (processor != null) {
                max = Math.max(max, processor.getStats().max);
            }
        }
        if (!Double.isFinite(max) || max < 1) return 1;
        return (int) Math.round(max);
    }

    public static Color belowMinColor() {
        return new Color(BELOW_MIN_RGB);
    }

    public static Color aboveMaxColor() {
        return new Color(ABOVE_MAX_RGB);
    }
}
