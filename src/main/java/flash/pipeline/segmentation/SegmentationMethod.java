package flash.pipeline.segmentation;

import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

public final class SegmentationMethod {
    public enum Engine { CLASSICAL, ENHANCED_CLASSICAL, STARDIST, CELLPOSE, TRAINED_RF }

    public static final String DEFAULT_STARDIST_MODEL_KEY = "stardist_versatile_fluo";
    public static final String DEFAULT_CELLPOSE_MODEL_KEY = "cellpose_cyto3";
    private static final Map<String, String> LEGACY_CELLPOSE_MODEL_KEYS = legacyCellposeModelKeys();
    private static final double DEFAULT_STARDIST_PROB_THRESH = 0.5;
    private static final double DEFAULT_STARDIST_NMS_THRESH = 0.4;
    private static final double DEFAULT_STARDIST_LINKING_MAX_DISTANCE = 5.0;
    private static final double DEFAULT_STARDIST_GAP_CLOSING_MAX_DISTANCE = 5.0;
    private static final int DEFAULT_STARDIST_MAX_FRAME_GAP = 1;
    private static final double DEFAULT_CELLPOSE_DIAMETER = 30.0;
    private static final String DEFAULT_CELLPOSE_MODEL = DEFAULT_CELLPOSE_MODEL_KEY;
    private static final double DEFAULT_CELLPOSE_FLOW_THRESHOLD = 0.4;
    private static final double DEFAULT_CELLPOSE_CELLPROB_THRESHOLD = 0.0;
    private static final boolean DEFAULT_CELLPOSE_USE_GPU = true;

    public final Engine engine;
    public final Map<String, String> params;
    public final String rawToken;
    private final boolean preserveRawTokenOnWrite;

    public SegmentationMethod(Engine engine, Map<String, String> params, String rawToken) {
        this(engine, params, rawToken, false);
    }

    SegmentationMethod(Engine engine,
                       Map<String, String> params,
                       String rawToken,
                       boolean preserveRawTokenOnWrite) {
        if (engine == null) {
            throw new IllegalArgumentException("Segmentation engine must not be null.");
        }
        this.engine = engine;
        this.params = immutableCopy(params);
        this.rawToken = rawToken == null ? "" : rawToken;
        this.preserveRawTokenOnWrite = preserveRawTokenOnWrite;
    }

    public static SegmentationMethod classical(String rawToken) {
        return new SegmentationMethod(Engine.CLASSICAL, Collections.<String, String>emptyMap(), rawToken);
    }

    static SegmentationMethod lenientFallback(String rawToken) {
        return new SegmentationMethod(Engine.CLASSICAL,
                Collections.<String, String>emptyMap(), rawToken, true);
    }

    public boolean isClassical() {
        return engine == Engine.CLASSICAL;
    }

    public boolean isEnhancedClassical() {
        return engine == Engine.ENHANCED_CLASSICAL;
    }

    public boolean isStarDist() {
        return engine == Engine.STARDIST;
    }

    public boolean isCellpose() {
        return engine == Engine.CELLPOSE;
    }

    public boolean isTrainedRf() {
        return engine == Engine.TRAINED_RF;
    }

    public Optional<String> modelKey() {
        String value = params.get("model");
        if ((value == null || value.trim().isEmpty()) && engine == Engine.STARDIST) {
            value = DEFAULT_STARDIST_MODEL_KEY;
        }
        if ((value == null || value.trim().isEmpty()) && engine == Engine.TRAINED_RF) {
            value = params.get("modelKey");
        }
        return optional(value);
    }

    public Optional<String> baseToken() {
        return optional(params.get("base"));
    }

    public boolean shouldPreserveRawTokenOnWrite() {
        return preserveRawTokenOnWrite;
    }

    public static double threshold(SegmentationMethod m) {
        return parseDouble(param(m, "thresh"), 0);
    }

    public static int minSize(SegmentationMethod m) {
        return parseInt(param(m, "minSize"), 0);
    }

    public static int maxSize(SegmentationMethod m) {
        return parseInt(param(m, "maxSize"), Integer.MAX_VALUE);
    }

    public static List<MorphPredicate> morphPredicates(SegmentationMethod m) {
        String encoded = param(m, "morph");
        if (encoded == null || encoded.trim().isEmpty()) return Collections.emptyList();
        String decoded = SegmentationTokenCodec.percentDecodeToken(encoded);
        return Collections.unmodifiableList(MorphPredicate.parseList(decoded));
    }

    public static double starDistProb(SegmentationMethod m) {
        if (m == null || !m.isStarDist()) return DEFAULT_STARDIST_PROB_THRESH;
        return parseDouble(param(m, "prob"), DEFAULT_STARDIST_PROB_THRESH);
    }

    public static double starDistNms(SegmentationMethod m) {
        if (m == null || !m.isStarDist()) return DEFAULT_STARDIST_NMS_THRESH;
        return parseDouble(param(m, "nms"), DEFAULT_STARDIST_NMS_THRESH);
    }

    public static String starDistModelKey(SegmentationMethod m) {
        if (m == null || !m.isStarDist()) return DEFAULT_STARDIST_MODEL_KEY;
        String value = param(m, "model");
        return value == null || value.trim().isEmpty()
                ? DEFAULT_STARDIST_MODEL_KEY
                : value.trim();
    }

    public static StarDistLinkingParams starDistLinking(SegmentationMethod m) {
        if (m == null || !m.isStarDist()) {
            return new StarDistLinkingParams(DEFAULT_STARDIST_LINKING_MAX_DISTANCE,
                    DEFAULT_STARDIST_GAP_CLOSING_MAX_DISTANCE,
                    DEFAULT_STARDIST_MAX_FRAME_GAP);
        }
        return new StarDistLinkingParams(
                parseDouble(param(m, "linking"), DEFAULT_STARDIST_LINKING_MAX_DISTANCE),
                parseDouble(param(m, "gapClosing"), DEFAULT_STARDIST_GAP_CLOSING_MAX_DISTANCE),
                parseInt(param(m, "frameGap"), DEFAULT_STARDIST_MAX_FRAME_GAP));
    }

    public static StarDistPostFilters starDistPostFilters(SegmentationMethod m) {
        if (m == null || !m.isStarDist()) {
            return new StarDistPostFilters(0, Double.POSITIVE_INFINITY, 0, 0);
        }
        double areaMin = 0;
        double areaMax = Double.POSITIVE_INFINITY;
        String area = param(m, "area");
        if (area != null && !area.trim().isEmpty()) {
            String[] range = area.split("-", 2);
            if (range.length >= 1) areaMin = parseDouble(range[0], 0);
            if (range.length >= 2) {
                areaMax = parseInfinityAwareDouble(range[1], Double.POSITIVE_INFINITY);
            }
        }
        return new StarDistPostFilters(
                areaMin,
                areaMax,
                parseDouble(param(m, "quality"), 0),
                parseDouble(param(m, "intensity"), 0));
    }

    public static double cellposeDiameter(SegmentationMethod m) {
        if (m == null || !m.isCellpose()) return DEFAULT_CELLPOSE_DIAMETER;
        return parseDouble(param(m, "diameter"), DEFAULT_CELLPOSE_DIAMETER);
    }

    public static String cellposeModelKey(SegmentationMethod m) {
        if (m == null || !m.isCellpose()) return DEFAULT_CELLPOSE_MODEL;
        String value = param(m, "model");
        return canonicalCellposeModelKey(value);
    }

    public static String canonicalCellposeModelKey(String value) {
        if (value == null || value.trim().isEmpty()) return DEFAULT_CELLPOSE_MODEL;
        String trimmed = value.trim();
        String mapped = LEGACY_CELLPOSE_MODEL_KEYS.get(trimmed.toLowerCase(Locale.ROOT));
        return mapped == null ? trimmed : mapped;
    }

    public static double cellposeFlow(SegmentationMethod m) {
        if (m == null || !m.isCellpose()) return DEFAULT_CELLPOSE_FLOW_THRESHOLD;
        return parseDouble(param(m, "flow"), DEFAULT_CELLPOSE_FLOW_THRESHOLD);
    }

    public static double cellposeCellprob(SegmentationMethod m) {
        if (m == null || !m.isCellpose()) return DEFAULT_CELLPOSE_CELLPROB_THRESHOLD;
        return parseDouble(param(m, "cellprob"), DEFAULT_CELLPOSE_CELLPROB_THRESHOLD);
    }

    public static boolean cellposeUseGpu(SegmentationMethod m) {
        if (m == null || !m.isCellpose()) return DEFAULT_CELLPOSE_USE_GPU;
        String value = param(m, "gpu");
        return value == null || !"false".equalsIgnoreCase(value.trim());
    }

    public static int cellposeChan2(SegmentationMethod m) {
        if (m == null || !m.isCellpose()) return -1;
        return parseInt(param(m, "chan2"), -1);
    }

    public static String trainedRfModelKey(SegmentationMethod m) {
        if (m == null || !m.isTrainedRf()) return "";
        String value = param(m, "modelKey");
        return value == null ? "" : value.trim();
    }

    public static SegmentationMethod trainedRfBase(SegmentationMethod m) {
        if (m == null || !m.isTrainedRf()) return SegmentationMethod.classical("classical");
        String base = param(m, "base");
        if (base == null || base.trim().isEmpty()) return SegmentationMethod.classical("classical");
        return SegmentationTokenParser.parseLenient(base);
    }

    private static Map<String, String> immutableCopy(Map<String, String> params) {
        LinkedHashMap<String, String> copy = new LinkedHashMap<String, String>();
        if (params != null) {
            for (Map.Entry<String, String> entry : params.entrySet()) {
                if (entry.getKey() != null) {
                    copy.put(entry.getKey(), entry.getValue() == null ? "" : entry.getValue());
                }
            }
        }
        return Collections.unmodifiableMap(copy);
    }

    private static Optional<String> optional(String value) {
        if (value == null || value.trim().isEmpty()) return Optional.empty();
        return Optional.of(value.trim());
    }

    private static String param(SegmentationMethod m, String key) {
        return m == null || key == null ? null : m.params.get(key);
    }

    private static double parseDouble(String value, double fallback) {
        if (value == null) return fallback;
        try {
            double parsed = Double.parseDouble(value.trim());
            return Double.isFinite(parsed) ? parsed : fallback;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static double parseInfinityAwareDouble(String value, double fallback) {
        if (value == null) return fallback;
        String trimmed = value.trim();
        if ("Infinity".equalsIgnoreCase(trimmed) || "Inf".equalsIgnoreCase(trimmed)) {
            return Double.POSITIVE_INFINITY;
        }
        return parseDouble(trimmed, fallback);
    }

    private static int parseInt(String value, int fallback) {
        if (value == null) return fallback;
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static Map<String, String> legacyCellposeModelKeys() {
        Map<String, String> out = new HashMap<String, String>();
        putCellposeAlias(out, "cyto3", "cellpose_cyto3");
        putCellposeAlias(out, "cyto2", "cellpose_cyto2");
        putCellposeAlias(out, "cyto", "cellpose_cyto");
        putCellposeAlias(out, "nuclei", "cellpose_nuclei");
        putCellposeAlias(out, "tissuenet_cp3", "cellpose_tissuenet_cp3");
        putCellposeAlias(out, "livecell_cp3", "cellpose_livecell_cp3");
        putCellposeAlias(out, "yeast_PhC_cp3", "cellpose_yeast_PhC_cp3");
        putCellposeAlias(out, "yeast_BF_cp3", "cellpose_yeast_BF_cp3");
        putCellposeAlias(out, "bact_phase_cp3", "cellpose_bact_phase_cp3");
        putCellposeAlias(out, "bact_fluor_cp3", "cellpose_bact_fluor_cp3");
        putCellposeAlias(out, "deepbacs_cp3", "cellpose_deepbacs_cp3");
        putCellposeAlias(out, "cyto2_cp3", "cellpose_cyto2_cp3");
        return Collections.unmodifiableMap(out);
    }

    private static void putCellposeAlias(Map<String, String> out, String legacy, String key) {
        out.put(legacy.toLowerCase(Locale.ROOT), key);
        out.put(key.toLowerCase(Locale.ROOT), key);
    }
}
