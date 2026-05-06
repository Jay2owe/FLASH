package flash.pipeline.intelligence;

import flash.pipeline.naming.ImageNameParser;
import flash.pipeline.naming.NameParts;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * I-04 Odd-Image-Out.
 *
 * Reads the persisted per-image summary snapshot and flags images whose
 * saved numeric metrics are strong within-animal outliers. Advisory only:
 * it never excludes anything or triggers an action.
 */
public final class OddImageOutDiagnostics {

    private static final double MAD_SCALE = 1.4826;
    private static final double IQR_SCALE = 1.349;
    private static final Set<String> TOP_LEVEL_METRIC_KEYS = new LinkedHashSet<String>(
            Arrays.asList("mean", "median", "p95", "saturationPct", "focusScore", "snrDb"));

    private OddImageOutDiagnostics() {}

    public static void check(String directory, DiagnosticsReport.Section section) {
        SummaryHistoryStore.Snapshot snapshot;
        try {
            snapshot = SummaryHistoryStore.load(directory);
        } catch (IOException e) {
            section.warn("Could not read " + SummaryHistoryStore.FILE_NAME + ": " + e.getMessage());
            return;
        }

        if (snapshot == null) {
            section.info("No saved " + SummaryHistoryStore.FILE_NAME
                    + " found in this output folder yet. Run the pipeline once, then re-run Check my data.");
            return;
        }

        render(snapshot, section);
    }

    static void render(SummaryHistoryStore.Snapshot snapshot, DiagnosticsReport.Section section) {
        if (snapshot == null || snapshot.imageMetadata.isEmpty()) {
            section.info("Saved snapshot does not contain any per-image entries.");
            return;
        }

        LinkedHashMap<String, ImageSnapshot> groups = new LinkedHashMap<String, ImageSnapshot>();
        for (Map.Entry<String, Map<String, Object>> entry : snapshot.imageMetadata.entrySet()) {
            ImageSnapshot image = toImageSnapshot(entry.getKey(), entry.getValue());
            if (image == null || image.metrics.isEmpty()) continue;
            groups.put(entry.getKey(), image);
        }

        if (groups.isEmpty()) {
            section.info("Saved snapshot does not contain any per-image numeric metrics yet.");
            return;
        }

        LinkedHashMap<String, List<ImageSnapshot>> byGroup = new LinkedHashMap<String, List<ImageSnapshot>>();
        for (ImageSnapshot image : groups.values()) {
            String key = groupKey(image.animalId, image.region);
            List<ImageSnapshot> bucket = byGroup.get(key);
            if (bucket == null) {
                bucket = new ArrayList<ImageSnapshot>();
                byGroup.put(key, bucket);
            }
            bucket.add(image);
        }

        List<FlaggedMetric> flagged = new ArrayList<FlaggedMetric>();
        int groupsWithEnoughImages = 0;

        for (List<ImageSnapshot> bucket : byGroup.values()) {
            if (bucket.size() < 3) continue;
            groupsWithEnoughImages++;

            LinkedHashSet<String> metricNames = new LinkedHashSet<String>();
            for (ImageSnapshot image : bucket) {
                metricNames.addAll(image.metrics.keySet());
            }

            List<String> sortedMetricNames = new ArrayList<String>(metricNames);
            Collections.sort(sortedMetricNames, String.CASE_INSENSITIVE_ORDER);

            for (String metricName : sortedMetricNames) {
                List<ImageValue> values = new ArrayList<ImageValue>();
                for (ImageSnapshot image : bucket) {
                    Double value = image.metrics.get(metricName);
                    if (value != null && !Double.isNaN(value.doubleValue()) && !Double.isInfinite(value.doubleValue())) {
                        values.add(new ImageValue(image, value.doubleValue()));
                    }
                }
                if (values.size() < 3) continue;

                RobustScale scale = robustScale(values);
                if (scale == null || scale.scale <= 0.0) continue;

                for (ImageValue value : values) {
                    double score = Math.abs(value.value - scale.median) / scale.scale;
                    if (score > 3.0) {
                        flagged.add(new FlaggedMetric(value.image, metricName,
                                value.value, scale.median, score, scale.label));
                    }
                }
            }
        }

        if (groupsWithEnoughImages == 0) {
            section.info("No animal groups with at least 3 saved images had comparable per-image metrics.");
            return;
        }

        if (flagged.isEmpty()) {
            section.ok("No saved per-image metrics were more than 3 robust deviations from the within-animal median.");
            return;
        }

        Collections.sort(flagged, new Comparator<FlaggedMetric>() {
            @Override
            public int compare(FlaggedMetric a, FlaggedMetric b) {
                int scoreOrder = Double.compare(b.score, a.score);
                if (scoreOrder != 0) return scoreOrder;
                int animalOrder = a.image.animalId.compareToIgnoreCase(b.image.animalId);
                if (animalOrder != 0) return animalOrder;
                int sectionOrder = Integer.compare(a.image.sectionIndex, b.image.sectionIndex);
                if (sectionOrder != 0) return sectionOrder;
                return a.metricName.compareToIgnoreCase(b.metricName);
            }
        });

        for (FlaggedMetric finding : flagged) {
            section.warn(finding.render());
        }
    }

    private static ImageSnapshot toImageSnapshot(String snapshotKey, Map<String, Object> metadata) {
        if (metadata == null) return null;

        String displayName = firstNonBlank(
                stringValue(metadata.get("displayName")),
                stringValue(metadata.get("imageName")),
                stringValue(metadata.get("file")),
                snapshotKey);
        String parseSeed = firstNonBlank(
                stringValue(metadata.get("imageName")),
                stringValue(metadata.get("file")),
                displayName,
                snapshotKey);

        NameParts parsed = ImageNameParser.parse(parseSeed);
        String animalId = firstNonBlank(stringValue(metadata.get("animalId")), parsed.animal);
        if (animalId.isEmpty()) return null;

        String region = firstNonBlank(stringValue(metadata.get("region")), parsed.csvRegion());
        int sectionIndex = intValue(metadata.get("sectionIndex"));
        if (sectionIndex <= 0) {
            int seriesIndex = intValue(metadata.get("seriesIndex"));
            if (seriesIndex >= 0) {
                sectionIndex = seriesIndex + 1;
            }
        }
        if (sectionIndex <= 0) {
            sectionIndex = parseSectionIndex(displayName);
        }

        LinkedHashMap<String, Double> metrics = new LinkedHashMap<String, Double>();
        collectNestedMetrics(metrics, metadata.get("metrics"));
        for (String key : TOP_LEVEL_METRIC_KEYS) {
            Double value = asDouble(metadata.get(key));
            if (value != null) {
                metrics.put(key, value);
            }
        }

        if (metrics.isEmpty()) return null;
        return new ImageSnapshot(animalId, region, sectionIndex, displayName, metrics);
    }

    private static void collectNestedMetrics(Map<String, Double> out, Object value) {
        if (!(value instanceof Map)) return;
        @SuppressWarnings("unchecked")
        Map<String, Object> metrics = (Map<String, Object>) value;
        for (Map.Entry<String, Object> entry : metrics.entrySet()) {
            Double numeric = asDouble(entry.getValue());
            if (numeric != null) {
                out.put(entry.getKey(), numeric);
            }
        }
    }

    private static RobustScale robustScale(List<ImageValue> values) {
        List<Double> numeric = new ArrayList<Double>(values.size());
        for (ImageValue value : values) {
            numeric.add(value.value);
        }

        double median = median(numeric);
        List<Double> deviations = new ArrayList<Double>(numeric.size());
        for (double value : numeric) {
            deviations.add(Math.abs(value - median));
        }

        double mad = median(deviations);
        double scaledMad = mad * MAD_SCALE;
        if (scaledMad > 0.0) {
            return new RobustScale(median, scaledMad, "MAD");
        }

        double iqr = interquartileRange(numeric);
        double scaledIqr = iqr / IQR_SCALE;
        if (scaledIqr > 0.0) {
            return new RobustScale(median, scaledIqr, "IQR");
        }
        return null;
    }

    private static double interquartileRange(List<Double> values) {
        if (values == null || values.size() < 2) return 0.0;
        List<Double> sorted = new ArrayList<Double>(values);
        Collections.sort(sorted);
        return percentile(sorted, 0.75) - percentile(sorted, 0.25);
    }

    private static double percentile(List<Double> sorted, double p) {
        if (sorted == null || sorted.isEmpty()) return 0.0;
        if (sorted.size() == 1) return sorted.get(0);

        double position = p * (sorted.size() - 1);
        int lowerIndex = (int) Math.floor(position);
        int upperIndex = (int) Math.ceil(position);
        if (lowerIndex == upperIndex) return sorted.get(lowerIndex);

        double fraction = position - lowerIndex;
        double lower = sorted.get(lowerIndex);
        double upper = sorted.get(upperIndex);
        return lower + ((upper - lower) * fraction);
    }

    private static double median(List<Double> values) {
        if (values == null || values.isEmpty()) return 0.0;
        List<Double> sorted = new ArrayList<Double>(values);
        Collections.sort(sorted);
        int mid = sorted.size() / 2;
        if ((sorted.size() % 2) == 0) {
            return (sorted.get(mid - 1) + sorted.get(mid)) / 2.0;
        }
        return sorted.get(mid);
    }

    private static String groupKey(String animalId, String region) {
        return animalId.toLowerCase(Locale.ROOT) + "|" + region.toLowerCase(Locale.ROOT);
    }

    private static Double asDouble(Object value) {
        if (value instanceof Number) {
            return ((Number) value).doubleValue();
        }
        if (value == null) return null;
        try {
            return Double.valueOf(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static int intValue(Object value) {
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        if (value == null) return -1;
        try {
            return Integer.parseInt(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static int parseSectionIndex(String text) {
        if (text == null) return 0;
        String lower = text.toLowerCase(Locale.ROOT);
        int marker = lower.lastIndexOf("section ");
        if (marker >= 0) {
            String digits = lower.substring(marker + 8).replaceAll("[^0-9].*$", "");
            if (!digits.isEmpty()) {
                try {
                    return Integer.parseInt(digits);
                } catch (NumberFormatException ignored) {
                    return 0;
                }
            }
        }
        return 0;
    }

    private static String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static String firstNonBlank(String... values) {
        if (values == null) return "";
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) {
                return value.trim();
            }
        }
        return "";
    }

    private static String formatNumber(double value) {
        if (Math.rint(value) == value) {
            return String.format(Locale.ROOT, "%.0f", value);
        }
        if (Math.rint(value * 10.0) == value * 10.0) {
            return String.format(Locale.ROOT, "%.1f", value);
        }
        if (Math.rint(value * 100.0) == value * 100.0) {
            return String.format(Locale.ROOT, "%.2f", value);
        }
        return String.format(Locale.ROOT, "%.3f", value);
    }

    private static final class ImageSnapshot {
        final String animalId;
        final String region;
        final int sectionIndex;
        final String displayName;
        final Map<String, Double> metrics;

        ImageSnapshot(String animalId, String region, int sectionIndex,
                      String displayName, Map<String, Double> metrics) {
            this.animalId = animalId == null ? "" : animalId.trim();
            this.region = region == null ? "" : region.trim();
            this.sectionIndex = Math.max(0, sectionIndex);
            this.displayName = displayName == null ? "" : displayName.trim();
            this.metrics = metrics;
        }

        String groupLabel() {
            if (region.isEmpty()) return animalId;
            return animalId + " / " + region;
        }

        String subjectLabel() {
            StringBuilder sb = new StringBuilder(animalId);
            if (sectionIndex > 0) {
                sb.append(" section ").append(sectionIndex);
            }
            if (!displayName.isEmpty() && !displayName.equalsIgnoreCase(sb.toString())) {
                sb.append(" (").append(displayName).append(")");
            }
            return sb.toString();
        }
    }

    private static final class ImageValue {
        final ImageSnapshot image;
        final double value;

        ImageValue(ImageSnapshot image, double value) {
            this.image = image;
            this.value = value;
        }
    }

    private static final class RobustScale {
        final double median;
        final double scale;
        final String label;

        RobustScale(double median, double scale, String label) {
            this.median = median;
            this.scale = scale;
            this.label = label;
        }
    }

    private static final class FlaggedMetric {
        final ImageSnapshot image;
        final String metricName;
        final double value;
        final double median;
        final double score;
        final String scoreLabel;

        FlaggedMetric(ImageSnapshot image, String metricName,
                      double value, double median, double score, String scoreLabel) {
            this.image = image;
            this.metricName = metricName;
            this.value = value;
            this.median = median;
            this.score = score;
            this.scoreLabel = scoreLabel;
        }

        String render() {
            return image.subjectLabel() + ": "
                    + formatNumber(value) + " " + metricName
                    + " vs " + image.groupLabel() + " median " + formatNumber(median)
                    + " - outlier (" + formatNumber(score) + " " + scoreLabel + ").";
        }
    }
}
