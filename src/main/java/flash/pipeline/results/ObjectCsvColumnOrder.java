package flash.pipeline.results;

import flash.pipeline.io.CsvTableIO.ChannelData;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Applies a stable, analysis-aware column order to per-object CSV outputs.
 *
 * <p>The order is defined by semantic groups rather than insertion order so
 * later dialog or implementation changes do not scramble exported tables.
 */
public final class ObjectCsvColumnOrder {

    private static final int UNKNOWN_PARTNER_RANK = 10_000;

    private static final Map<String, Integer> METADATA_ORDER = fixedOrder(
            "Region",
            "Hemisphere",
            "ROI",
            "Animal Name",
            "SCN"
    );
    private static final Map<String, Integer> CENTROID_ORDER = fixedOrder(
            "XM",
            "YM",
            "ZM",
            "XM_um",
            "YM_um",
            "ZM_um"
    );
    private static final Map<String, Integer> VORONOI_ORDER = fixedOrder(
            "Voronoi_TerritoryArea_um2",
            "Voronoi_NumNeighbors"
    );
    private static final Map<String, Integer> MORPH_2D_ORDER = fixedOrder(
            "Morph_Area_um2",
            "Morph_Perimeter_um",
            "Morph_Circularity",
            "Morph_Solidity",
            "Morph_AspectRatio",
            "Morph_Feret_um",
            "Morph_Extent",
            "Morph_ConvexHullArea_um2"
    );
    private static final Map<String, Integer> MORPH_3D_ORDER = fixedOrder(
            "Morph_Sphericity",
            "Morph_Compactness",
            "Morph_Elongation",
            "Morph_Flatness",
            "Morph_Spareness",
            "Morph_MajorRadius_um",
            "Morph_Feret3D_um",
            "Morph_Moment1",
            "Morph_Moment2",
            "Morph_Moment3",
            "Morph_Moment4",
            "Morph_Moment5",
            "Morph_DistCenter_Min_um",
            "Morph_DistCenter_Max_um",
            "Morph_DistCenter_Mean_um",
            "Morph_DistCenter_SD_um"
    );
    private static final Map<String, Integer> MORPH_COMPOSITE_ORDER = fixedOrder(
            "Morph_RI",
            "Morph_SRI",
            "Morph_PB",
            "Morph_MP",
            "Morph_VSD"
    );
    private static final Map<String, Integer> MORPH_POPULATION_ORDER = fixedOrder(
            "Morph_CMS",
            "Morph_SMSD",
            "Morph_IMDI"
    );
    private static final Map<String, Integer> MORPH_SPATIAL_ORDER = fixedOrder(
            "Morph_TDR",
            "Morph_FEV_Mag"
    );
    private static final Map<String, Integer> MORPH_TEXTURE_ORDER = fixedOrder(
            "MorphTexture_GLCMContrast",
            "MorphTexture_GLCMASM",
            "MorphTexture_GLCMCorrelation",
            "MorphTexture_GLCMEntropy",
            "MorphTexture_GLCMHomogeneity",
            "MorphTexture_GLCM3DContrast",
            "MorphTexture_GLCM3DASM",
            "MorphTexture_GLCM3DCorrelation",
            "MorphTexture_GLCM3DEntropy",
            "MorphTexture_GLCM3DHomogeneity",
            "MorphTexture_FractalDim",
            "MorphTexture_FractalDim_R2",
            "MorphTexture_LacunarityMean",
            "MorphTexture_LacunaritySpread",
            "MorphTexture_ClassLabel",
            "MorphTexture_ClassDistance",
            "MorphTexture_F1",
            "MorphTexture_F2",
            "MorphTexture_F3",
            "MorphTexture_F4",
            "MorphTexture_F5",
            "MorphTexture_F6",
            "MorphTexture_F7",
            "MorphTexture_F8",
            "MorphTexture_Class3DLabel",
            "MorphTexture_Class3DDistance",
            "MorphTexture_F3D1",
            "MorphTexture_F3D2",
            "MorphTexture_F3D3",
            "MorphTexture_F3D4",
            "MorphTexture_F3D5",
            "MorphTexture_F3D6",
            "MorphTexture_F3D7",
            "MorphTexture_F3D8"
    );

    private ObjectCsvColumnOrder() {}

    public static List<String> orderedColumns(String channelName, List<String> columns,
                                              List<String> channelNames) {
        if (columns == null || columns.isEmpty()) {
            return new ArrayList<String>();
        }

        List<String> unique = new ArrayList<String>();
        Set<String> seen = new LinkedHashSet<String>();
        for (String col : columns) {
            if (col == null) continue;
            String trimmed = col.trim();
            if (trimmed.isEmpty() || !seen.add(trimmed)) continue;
            unique.add(trimmed);
        }

        final Map<String, Integer> originalIndex = new HashMap<String, Integer>();
        for (int i = 0; i < unique.size(); i++) {
            originalIndex.put(unique.get(i), i);
        }

        final Map<String, Integer> partnerRanks = new HashMap<String, Integer>();
        if (channelNames != null) {
            int rank = 0;
            for (String partner : channelNames) {
                if (partner == null || partner.equals(channelName)) continue;
                if (!partnerRanks.containsKey(partner)) {
                    partnerRanks.put(partner, rank++);
                }
            }
        }

        List<String> ordered = new ArrayList<String>(unique);
        Collections.sort(ordered, new Comparator<String>() {
            @Override
            public int compare(String left, String right) {
                ColumnKey a = classify(channelName, left, partnerRanks, originalIndex.get(left));
                ColumnKey b = classify(channelName, right, partnerRanks, originalIndex.get(right));
                return a.compareTo(b);
            }
        });
        return ordered;
    }

    public static void reorder(ChannelData cd, List<String> channelNames) {
        if (cd == null || cd.header == null || cd.header.isEmpty()) return;

        List<String> ordered = orderedColumns(cd.name, cd.header, channelNames);
        if (ordered.equals(cd.header)) return;

        Map<String, Integer> oldIdx = new HashMap<String, Integer>(cd.colIdx);
        for (int rowIdx = 0; rowIdx < cd.rows.size(); rowIdx++) {
            List<String> oldRow = cd.rows.get(rowIdx);
            List<String> reordered = new ArrayList<String>(ordered.size());
            for (String col : ordered) {
                Integer ci = oldIdx.get(col);
                reordered.add(ci != null && ci < oldRow.size() ? oldRow.get(ci) : "");
            }
            cd.rows.set(rowIdx, reordered);
        }

        cd.header.clear();
        cd.header.addAll(ordered);
        cd.colIdx.clear();
        for (int i = 0; i < ordered.size(); i++) {
            cd.colIdx.put(ordered.get(i), i);
        }
    }

    private static ColumnKey classify(String channelName, String col,
                                      Map<String, Integer> partnerRanks, int originalIndex) {
        Integer exact = METADATA_ORDER.get(col);
        if (exact != null) return new ColumnKey(0, exact, 0, "", originalIndex);

        if ("Label".equals(col)) return new ColumnKey(1, 0, 0, "", originalIndex);

        int simpleOrder = simpleMetricOrder(col);
        if (simpleOrder >= 0) return new ColumnKey(2, simpleOrder, 0, "", originalIndex);

        exact = CENTROID_ORDER.get(col);
        if (exact != null) return new ColumnKey(3, exact, 0, "", originalIndex);

        String lineSet = lineDistanceName(channelName, col);
        if (lineSet != null) return new ColumnKey(4, 0, 0, lineSet, originalIndex);

        String partner = overlapPartner(col);
        if (partner != null) {
            return new ColumnKey(5, partnerRank(partnerRanks, partner), 0, partner, originalIndex);
        }
        MetricPartner colocMetric = intensityColocMetricPartner(channelName, col);
        if (colocMetric != null) {
            return new ColumnKey(5, partnerRank(partnerRanks, colocMetric.partner),
                    colocMetric.order, colocMetric.partner, originalIndex);
        }
        partner = volColocPartner(channelName, col);
        if (partner != null) {
            return new ColumnKey(5, partnerRank(partnerRanks, partner), 20, partner, originalIndex);
        }

        partner = distToClosestPartner(channelName, col);
        if (partner != null) {
            return new ColumnKey(6, partnerRank(partnerRanks, partner), 0, partner, originalIndex);
        }
        partner = closestToPartner(channelName, col);
        if (partner != null) {
            return new ColumnKey(6, partnerRank(partnerRanks, partner), 1, partner, originalIndex);
        }
        partner = volContainsPartner(channelName, col);
        if (partner != null) {
            return new ColumnKey(6, partnerRank(partnerRanks, partner), 2, partner, originalIndex);
        }

        partner = cpcColocPartner(channelName, col);
        if (partner != null) {
            return new ColumnKey(7, partnerRank(partnerRanks, partner), 0, partner, originalIndex);
        }
        partner = cpcContainsPartner(channelName, col);
        if (partner != null) {
            return new ColumnKey(7, partnerRank(partnerRanks, partner), 1, partner, originalIndex);
        }
        if ((channelName + "_CPCTargetsHit").equals(col)) {
            return new ColumnKey(7, Integer.MAX_VALUE - 1, 0, "", originalIndex);
        }
        if ((channelName + "_CPCPattern").equals(col)) {
            return new ColumnKey(7, Integer.MAX_VALUE, 0, "", originalIndex);
        }

        exact = VORONOI_ORDER.get(col);
        if (exact != null) return new ColumnKey(8, exact, 0, "", originalIndex);
        if ("Cluster".equals(col)) return new ColumnKey(9, 0, 0, "", originalIndex);

        exact = MORPH_2D_ORDER.get(col);
        if (exact != null) return new ColumnKey(10, exact, 0, "", originalIndex);
        exact = MORPH_3D_ORDER.get(col);
        if (exact != null) return new ColumnKey(11, exact, 0, "", originalIndex);
        exact = MORPH_COMPOSITE_ORDER.get(col);
        if (exact != null) return new ColumnKey(12, exact, 0, "", originalIndex);
        exact = MORPH_POPULATION_ORDER.get(col);
        if (exact != null) return new ColumnKey(13, exact, 0, "", originalIndex);
        exact = MORPH_SPATIAL_ORDER.get(col);
        if (exact != null) return new ColumnKey(14, exact, 0, "", originalIndex);
        if (col.startsWith("Morph_")) return new ColumnKey(15, 0, 0, col, originalIndex);
        exact = MORPH_TEXTURE_ORDER.get(col);
        if (exact != null) return new ColumnKey(16, exact, 0, "", originalIndex);
        if (col.startsWith("MorphTexture_")) return new ColumnKey(17, 0, 0, col, originalIndex);

        return new ColumnKey(100, 0, 0, "", originalIndex);
    }

    private static int simpleMetricOrder(String col) {
        if (col == null) return -1;
        if (col.startsWith("Volume (") && col.endsWith("^3)")) return 0;
        if (col.startsWith("Surface (") && col.endsWith("^2)")) return 1;
        if ("IntDen".equals(col)) return 2;
        if ("Mean".equals(col)) return 3;
        if ("Length".equals(col)) return 4;
        return -1;
    }

    private static String lineDistanceName(String channelName, String col) {
        String prefix = channelName + "_DistTo_";
        String closestPrefix = channelName + "_DistToClosest_";
        if (!col.startsWith(prefix) || col.startsWith(closestPrefix)) return null;
        return col.substring(prefix.length());
    }

    private static String overlapPartner(String col) {
        String prefix = "Colocalisation with ";
        return col.startsWith(prefix) ? col.substring(prefix.length()) : null;
    }

    private static MetricPartner intensityColocMetricPartner(String channelName, String col) {
        MetricPartner metric = metricPartnerAfter(col, channelName + "_ObjPearson_", 1);
        if (metric != null) return metric;
        metric = metricPartnerAfter(col, channelName + "_ObjMandersM1_", 2);
        if (metric != null) return metric;
        metric = metricPartnerAfter(col, channelName + "_ObjMandersM2_", 3);
        if (metric != null) return metric;
        metric = metricPartnerAfter(col, channelName + "_ObjCostesTa_", 4);
        if (metric != null) return metric;
        metric = metricPartnerAfter(col, channelName + "_ObjCostesTb_", 5);
        if (metric != null) return metric;
        metric = metricPartnerAfter(col, channelName + "_ObjPearsonT_", 6);
        if (metric != null) return metric;
        metric = metricPartnerAfter(col, channelName + "_ObjCostesP_", 7);
        if (metric != null) return metric;
        metric = metricPartnerAfter(col, channelName + "_ROICostesTa_", 8);
        if (metric != null) return metric;
        metric = metricPartnerAfter(col, channelName + "_ROICostesTb_", 9);
        if (metric != null) return metric;
        metric = metricPartnerAfter(col, channelName + "_ROICostesP_", 10);
        if (metric != null) return metric;

        metric = metricPartnerAfter(col, channelName + "_Pearson_t_", 16);
        if (metric != null) return metric;
        metric = metricPartnerAfter(col, channelName + "_Pearson_", 11);
        if (metric != null) return metric;
        metric = metricPartnerAfter(col, channelName + "_Manders_M1_", 12);
        if (metric != null) return metric;
        metric = metricPartnerAfter(col, channelName + "_Manders_M2_", 13);
        if (metric != null) return metric;
        metric = metricPartnerAfter(col, channelName + "_Costes_Ta_", 14);
        if (metric != null) return metric;
        metric = metricPartnerAfter(col, channelName + "_Costes_Tb_", 15);
        if (metric != null) return metric;
        return metricPartnerAfter(col, channelName + "_Costes_p_", 17);
    }

    private static MetricPartner metricPartnerAfter(String col, String prefix, int order) {
        String partner = suffixAfter(col, prefix);
        return partner == null ? null : new MetricPartner(partner, order);
    }

    private static String distToClosestPartner(String channelName, String col) {
        return suffixAfter(col, channelName + "_DistToClosest_");
    }

    private static String closestToPartner(String channelName, String col) {
        return suffixAfter(col, channelName + "_ClosestTo_");
    }

    private static String cpcColocPartner(String channelName, String col) {
        return suffixAfter(col, channelName + "_CPCColoc_");
    }

    private static String cpcContainsPartner(String channelName, String col) {
        return suffixAfter(col, channelName + "_CPCContains_");
    }

    private static String volColocPartner(String channelName, String col) {
        return partnerAfterThreshold(col, channelName + "_VolColoc");
    }

    private static String volContainsPartner(String channelName, String col) {
        return partnerAfterThreshold(col, channelName + "_VolContains");
    }

    private static String suffixAfter(String value, String prefix) {
        return value.startsWith(prefix) ? value.substring(prefix.length()) : null;
    }

    private static String partnerAfterThreshold(String col, String prefix) {
        if (!col.startsWith(prefix)) return null;
        String tail = col.substring(prefix.length());
        int split = tail.indexOf('_');
        if (split < 0 || split == tail.length() - 1) return null;
        return tail.substring(split + 1);
    }

    private static int partnerRank(Map<String, Integer> partnerRanks, String partner) {
        Integer rank = partnerRanks.get(partner);
        return rank != null ? rank.intValue() : UNKNOWN_PARTNER_RANK;
    }

    private static Map<String, Integer> fixedOrder(String... values) {
        Map<String, Integer> order = new HashMap<String, Integer>();
        for (int i = 0; i < values.length; i++) {
            order.put(values[i], i);
        }
        return order;
    }

    private static final class MetricPartner {
        private final String partner;
        private final int order;

        private MetricPartner(String partner, int order) {
            this.partner = partner;
            this.order = order;
        }
    }

    private static final class ColumnKey implements Comparable<ColumnKey> {
        private final int section;
        private final int group;
        private final int item;
        private final String text;
        private final int originalIndex;

        private ColumnKey(int section, int group, int item, String text, int originalIndex) {
            this.section = section;
            this.group = group;
            this.item = item;
            this.text = text == null ? "" : text;
            this.originalIndex = originalIndex;
        }

        @Override
        public int compareTo(ColumnKey other) {
            if (section != other.section) return section < other.section ? -1 : 1;
            if (group != other.group) return group < other.group ? -1 : 1;
            if (item != other.item) return item < other.item ? -1 : 1;
            int byText = text.compareTo(other.text);
            if (byText != 0) return byText;
            return originalIndex < other.originalIndex ? -1 : (originalIndex == other.originalIndex ? 0 : 1);
        }
    }
}
