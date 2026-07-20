package flash.pipeline.representative;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Per-series statistic table consumed by the representative image picker.
 * Series ids are zero-based source series indexes represented as strings.
 */
public final class RepresentativeStatTable {

    private final LinkedHashMap<String, MutableRow> rowsBySeriesId =
            new LinkedHashMap<String, MutableRow>();
    private final LinkedHashSet<String> channelNames = new LinkedHashSet<String>();

    public static String seriesIdForIndex(int seriesIndex) {
        return String.valueOf(seriesIndex);
    }

    public boolean isEmpty() {
        return rowsBySeriesId.isEmpty() || channelNames.isEmpty();
    }

    public int rowCount() {
        return rowsBySeriesId.size();
    }

    public Set<String> channelNames() {
        return Collections.unmodifiableSet(channelNames);
    }

    public List<Row> rows() {
        List<Row> out = new ArrayList<Row>();
        for (MutableRow row : rowsBySeriesId.values()) {
            out.add(row.snapshot());
        }
        return Collections.unmodifiableList(out);
    }

    public Row row(String seriesId) {
        MutableRow row = rowsBySeriesId.get(seriesId);
        return row == null ? null : row.snapshot();
    }

    public Double value(String seriesId, String channelName) {
        MutableRow row = rowsBySeriesId.get(seriesId);
        if (row == null) return null;
        return row.valuesByChannel.get(channelName);
    }

    public void putValue(String seriesId,
                         int seriesIndex,
                         int seriesNumber,
                         String seriesName,
                         String animalName,
                         String conditionName,
                         String hemisphere,
                         String region,
                         String channelName,
                         double value) {
        String normalizedSeriesId = clean(seriesId);
        if (normalizedSeriesId.isEmpty()) {
            normalizedSeriesId = seriesIdForIndex(seriesIndex);
        }
        String normalizedChannel = clean(channelName);
        if (normalizedChannel.isEmpty()) {
            normalizedChannel = "Statistic";
        }

        MutableRow row = rowsBySeriesId.get(normalizedSeriesId);
        if (row == null) {
            row = new MutableRow(normalizedSeriesId, seriesIndex, seriesNumber,
                    clean(seriesName), clean(animalName), clean(conditionName),
                    clean(hemisphere), clean(region));
            rowsBySeriesId.put(normalizedSeriesId, row);
        } else {
            row.mergeMetadata(seriesIndex, seriesNumber, seriesName, animalName,
                    conditionName, hemisphere, region);
        }

        row.valuesByChannel.put(normalizedChannel, Double.valueOf(value));
        channelNames.add(normalizedChannel);
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }

    private static final class MutableRow {
        final String seriesId;
        int seriesIndex;
        int seriesNumber;
        String seriesName;
        String animalName;
        String conditionName;
        String hemisphere;
        String region;
        final LinkedHashMap<String, Double> valuesByChannel =
                new LinkedHashMap<String, Double>();

        MutableRow(String seriesId,
                   int seriesIndex,
                   int seriesNumber,
                   String seriesName,
                   String animalName,
                   String conditionName,
                   String hemisphere,
                   String region) {
            this.seriesId = seriesId;
            this.seriesIndex = seriesIndex;
            this.seriesNumber = seriesNumber;
            this.seriesName = seriesName;
            this.animalName = animalName;
            this.conditionName = conditionName;
            this.hemisphere = hemisphere;
            this.region = region;
        }

        void mergeMetadata(int seriesIndex,
                           int seriesNumber,
                           String seriesName,
                           String animalName,
                           String conditionName,
                           String hemisphere,
                           String region) {
            if (this.seriesIndex < 0 && seriesIndex >= 0) this.seriesIndex = seriesIndex;
            if (this.seriesNumber <= 0 && seriesNumber > 0) this.seriesNumber = seriesNumber;
            if (this.seriesName.isEmpty()) this.seriesName = clean(seriesName);
            if (this.animalName.isEmpty()) this.animalName = clean(animalName);
            if (this.conditionName.isEmpty()) this.conditionName = clean(conditionName);
            if (this.hemisphere.isEmpty()) this.hemisphere = clean(hemisphere);
            if (this.region.isEmpty()) this.region = clean(region);
        }

        Row snapshot() {
            return new Row(seriesId, seriesIndex, seriesNumber, seriesName,
                    animalName, conditionName, hemisphere, region, valuesByChannel);
        }
    }

    /**
     * Immutable statistic values and metadata for one source series.
     */
    public static final class Row {
        public final String seriesId;
        public final int seriesIndex;
        public final int seriesNumber;
        public final String seriesName;
        public final String animalName;
        public final String conditionName;
        public final String hemisphere;
        public final String region;
        public final Map<String, Double> valuesByChannel;

        private Row(String seriesId,
                    int seriesIndex,
                    int seriesNumber,
                    String seriesName,
                    String animalName,
                    String conditionName,
                    String hemisphere,
                    String region,
                    Map<String, Double> valuesByChannel) {
            this.seriesId = seriesId;
            this.seriesIndex = seriesIndex;
            this.seriesNumber = seriesNumber;
            this.seriesName = seriesName;
            this.animalName = animalName;
            this.conditionName = conditionName;
            this.hemisphere = hemisphere;
            this.region = region;
            this.valuesByChannel = Collections.unmodifiableMap(
                    new LinkedHashMap<String, Double>(valuesByChannel));
        }

        public Double value(String channelName) {
            return valuesByChannel.get(channelName);
        }
    }
}
