package flash.pipeline.segmentation;

import ij.IJ;

import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

public final class SegmentationTokenParser {
    interface WarningSink {
        void warn(String message);
    }

    private static final WarningSink DEFAULT_WARNING_SINK = new WarningSink() {
        @Override public void warn(String message) {
            IJ.log(message);
        }
    };

    private static WarningSink warningSink = DEFAULT_WARNING_SINK;

    private static final Set<String> STARDIST_KNOWN_KEYS = new HashSet<String>(Arrays.asList(
            "prob", "nms", "linking", "gapClosing", "frameGap",
            "area", "quality", "intensity", "model"));
    private static final Set<String> CELLPOSE_KNOWN_KEYS = new HashSet<String>(Arrays.asList(
            "diameter", "flow", "cellprob", "gpu", "chan2", "model"));
    private static final Set<String> ENHANCED_CLASSICAL_KNOWN_KEYS = new HashSet<String>(Arrays.asList(
            "thresh", "minSize", "maxSize", "morph"));

    private SegmentationTokenParser() {
    }

    public static SegmentationMethod parse(String token) {
        String raw = token == null ? "" : token.trim();
        if (raw.isEmpty()) {
            throw new IllegalArgumentException("Segmentation token is blank.");
        }

        String[] parts = raw.split(":", -1);
        String engine = parts[0].trim().toLowerCase();
        if ("classical".equals(engine)) {
            ensureOnlyEmptyTail(parts, raw);
            return SegmentationMethod.classical(raw);
        }
        if ("enhanced_classical".equals(engine)) {
            return parseEnhancedClassical(raw, parts);
        }
        if ("stardist".equals(engine)) {
            return parseStarDist(raw, parts);
        }
        if ("cellpose".equals(engine)) {
            return parseCellpose(raw, parts);
        }
        if ("trained_rf".equals(engine)) {
            return parseTrainedRf(raw, parts);
        }
        throw new IllegalArgumentException("Unknown segmentation engine: " + parts[0]);
    }

    public static SegmentationMethod parseLenient(String token) {
        try {
            return parse(token);
        } catch (IllegalArgumentException e) {
            String raw = token == null ? "" : token.trim();
            warn("Warning: could not parse segmentation token '" + raw
                    + "'. Falling back to classical segmentation. " + e.getMessage());
            return SegmentationMethod.lenientFallback(raw);
        }
    }

    public static String format(SegmentationMethod method) {
        if (method == null) return "classical";
        if (method.isClassical()) return "classical";
        if (method.isEnhancedClassical()) return formatEnhancedClassical(method);
        if (method.isStarDist()) return formatStarDist(method);
        if (method.isCellpose()) return formatCellpose(method);
        if (method.isTrainedRf()) return formatTrainedRf(method);
        throw new IllegalArgumentException("Unsupported segmentation engine: " + method.engine);
    }

    static void setWarningSinkForTest(WarningSink sink) {
        warningSink = sink == null ? DEFAULT_WARNING_SINK : sink;
    }

    private static SegmentationMethod parseEnhancedClassical(String raw, String[] parts) {
        LinkedHashMap<String, String> params = parseKeyValues(parts, 1);
        validateDouble(params, "thresh", true, raw);
        validateInt(params, "minSize", true, raw);
        validateInt(params, "maxSize", true, raw);
        String morph = params.get("morph");
        if (morph != null && !morph.trim().isEmpty()) {
            MorphPredicate.parseList(SegmentationTokenCodec.percentDecodeToken(morph));
        }
        return new SegmentationMethod(SegmentationMethod.Engine.ENHANCED_CLASSICAL, params, raw);
    }

    private static SegmentationMethod parseStarDist(String raw, String[] parts) {
        if (parts.length < 3 || isEmpty(parts[1]) || isEmpty(parts[2])) {
            throw new IllegalArgumentException("StarDist token must include probability and NMS thresholds: " + raw);
        }
        validateFiniteDouble(parts[1], "StarDist probability", raw);
        validateFiniteDouble(parts[2], "StarDist NMS", raw);

        LinkedHashMap<String, String> params = new LinkedHashMap<String, String>();
        params.put("prob", parts[1].trim());
        params.put("nms", parts[2].trim());
        putKeyValues(params, parts, 3);
        validateDouble(params, "linking", false, raw);
        validateDouble(params, "gapClosing", false, raw);
        validateInt(params, "frameGap", false, raw);
        validateDouble(params, "quality", false, raw);
        validateDouble(params, "intensity", false, raw);
        validateArea(params.get("area"), raw);
        return new SegmentationMethod(SegmentationMethod.Engine.STARDIST, params, raw);
    }

    private static SegmentationMethod parseCellpose(String raw, String[] parts) {
        if (parts.length < 2 || isEmpty(parts[1])) {
            throw new IllegalArgumentException("Cellpose token must include diameter: " + raw);
        }
        validateFiniteDouble(parts[1], "Cellpose diameter", raw);
        LinkedHashMap<String, String> params = new LinkedHashMap<String, String>();
        params.put("diameter", parts[1].trim());

        boolean canonical = parts.length >= 4 && isFiniteDouble(parts[2]);
        if (canonical) {
            params.put("flow", parts[2].trim());
            params.put("cellprob", parts[3].trim());
            validateFiniteDouble(parts[3], "Cellpose cell probability", raw);
            putKeyValues(params, parts, 4);
        } else {
            if (parts.length >= 3 && !isEmpty(parts[2])) {
                params.put("model", SegmentationMethod.canonicalCellposeModelKey(parts[2]));
            }
            if (parts.length >= 4 && !isEmpty(parts[3])) {
                validateFiniteDouble(parts[3], "Cellpose flow", raw);
                params.put("flow", parts[3].trim());
            }
            if (parts.length >= 5 && !isEmpty(parts[4])) {
                validateFiniteDouble(parts[4], "Cellpose cell probability", raw);
                params.put("cellprob", parts[4].trim());
            }
            putKeyValues(params, parts, 5);
        }

        validateDouble(params, "flow", false, raw);
        validateDouble(params, "cellprob", false, raw);
        validateBoolean(params, "gpu", false, raw);
        validateInt(params, "chan2", false, raw);
        params.put("model", SegmentationMethod.canonicalCellposeModelKey(params.get("model")));
        return new SegmentationMethod(SegmentationMethod.Engine.CELLPOSE, params, raw);
    }

    private static SegmentationMethod parseTrainedRf(String raw, String[] parts) {
        if (parts.length < 3 || isEmpty(parts[1])) {
            throw new IllegalArgumentException("trained_rf token must include a model key and base token: " + raw);
        }
        LinkedHashMap<String, String> params = new LinkedHashMap<String, String>();
        params.put("modelKey", parts[1].trim());
        putKeyValues(params, parts, 2);
        String encodedBase = params.get("base");
        if (encodedBase == null || encodedBase.trim().isEmpty()) {
            throw new IllegalArgumentException("trained_rf token must include base=: " + raw);
        }
        params.put("base", SegmentationTokenCodec.percentDecodeToken(encodedBase));
        return new SegmentationMethod(SegmentationMethod.Engine.TRAINED_RF, params, raw);
    }

    private static String formatEnhancedClassical(SegmentationMethod method) {
        StringBuilder sb = new StringBuilder("enhanced_classical");
        appendIfPresent(sb, "thresh", method.params.get("thresh"));
        appendIfPresent(sb, "minSize", method.params.get("minSize"));
        appendIfPresent(sb, "maxSize", method.params.get("maxSize"));
        String morph = method.params.get("morph");
        if (morph != null) {
            String decoded = SegmentationTokenCodec.percentDecodeToken(morph);
            String canonical = MorphPredicate.formatList(MorphPredicate.parseList(decoded));
            appendIfPresent(sb, "morph", SegmentationTokenCodec.percentEncodeToken(canonical));
        }
        appendUnknownParams(sb, method.params, ENHANCED_CLASSICAL_KNOWN_KEYS);
        return sb.toString();
    }

    private static String formatStarDist(SegmentationMethod method) {
        StringBuilder sb = new StringBuilder("stardist");
        sb.append(':').append(valueOrDefault(method.params.get("prob"), "0.5"));
        sb.append(':').append(valueOrDefault(method.params.get("nms"), "0.4"));
        appendIfPresent(sb, "linking", method.params.get("linking"));
        appendIfPresent(sb, "gapClosing", method.params.get("gapClosing"));
        appendIfPresent(sb, "frameGap", method.params.get("frameGap"));
        appendIfPresent(sb, "area", method.params.get("area"));
        appendIfPresent(sb, "quality", method.params.get("quality"));
        appendIfPresent(sb, "intensity", method.params.get("intensity"));
        appendUnknownParams(sb, method.params, STARDIST_KNOWN_KEYS);
        appendIfPresent(sb, "model", method.params.get("model"));
        return sb.toString();
    }

    private static String formatCellpose(SegmentationMethod method) {
        StringBuilder sb = new StringBuilder("cellpose");
        sb.append(':').append(valueOrDefault(method.params.get("diameter"), "30.0"));
        sb.append(':').append(valueOrDefault(method.params.get("flow"), "0.4"));
        sb.append(':').append(valueOrDefault(method.params.get("cellprob"), "0.0"));
        appendIfPresent(sb, "gpu", valueOrDefault(method.params.get("gpu"), "true"));
        appendIfPresent(sb, "chan2", method.params.get("chan2"));
        appendUnknownParams(sb, method.params, CELLPOSE_KNOWN_KEYS);
        appendIfPresent(sb, "model", SegmentationMethod.canonicalCellposeModelKey(method.params.get("model")));
        return sb.toString();
    }

    private static String formatTrainedRf(SegmentationMethod method) {
        StringBuilder sb = new StringBuilder("trained_rf");
        sb.append(':').append(valueOrDefault(method.params.get("modelKey"), ""));
        appendIfPresent(sb, "base",
                SegmentationTokenCodec.percentEncodeToken(valueOrDefault(method.params.get("base"), "classical")));
        return sb.toString();
    }

    private static LinkedHashMap<String, String> parseKeyValues(String[] parts, int startIndex) {
        LinkedHashMap<String, String> params = new LinkedHashMap<String, String>();
        putKeyValues(params, parts, startIndex);
        return params;
    }

    private static void putKeyValues(LinkedHashMap<String, String> params, String[] parts, int startIndex) {
        for (int i = startIndex; i < parts.length; i++) {
            String part = parts[i] == null ? "" : parts[i].trim();
            if (part.isEmpty()) continue;
            int eq = part.indexOf('=');
            if (eq <= 0) {
                throw new IllegalArgumentException("Expected key=value segment, got: " + part);
            }
            params.put(part.substring(0, eq).trim(), part.substring(eq + 1).trim());
        }
    }

    private static void ensureOnlyEmptyTail(String[] parts, String raw) {
        for (int i = 1; i < parts.length; i++) {
            if (!isEmpty(parts[i])) {
                throw new IllegalArgumentException("Classical token does not accept parameters: " + raw);
            }
        }
    }

    private static void validateArea(String area, String raw) {
        if (area == null || area.trim().isEmpty()) return;
        String[] range = area.split("-", 2);
        if (range.length < 2) {
            throw new IllegalArgumentException("StarDist area must be min-max: " + raw);
        }
        validateFiniteDouble(range[0], "StarDist area minimum", raw);
        String max = range[1] == null ? "" : range[1].trim();
        if (!"Infinity".equalsIgnoreCase(max) && !"Inf".equalsIgnoreCase(max)) {
            validateFiniteDouble(max, "StarDist area maximum", raw);
        }
    }

    private static void validateDouble(Map<String, String> params,
                                       String key,
                                       boolean required,
                                       String raw) {
        String value = params.get(key);
        if (value == null || value.trim().isEmpty()) {
            if (required) {
                throw new IllegalArgumentException("Missing " + key + " in segmentation token: " + raw);
            }
            return;
        }
        validateFiniteDouble(value, key, raw);
    }

    private static void validateInt(Map<String, String> params,
                                    String key,
                                    boolean required,
                                    String raw) {
        String value = params.get(key);
        if (value == null || value.trim().isEmpty()) {
            if (required) {
                throw new IllegalArgumentException("Missing " + key + " in segmentation token: " + raw);
            }
            return;
        }
        try {
            Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid integer for " + key + " in segmentation token: " + raw, e);
        }
    }

    private static void validateBoolean(Map<String, String> params,
                                        String key,
                                        boolean required,
                                        String raw) {
        String value = params.get(key);
        if (value == null || value.trim().isEmpty()) {
            if (required) {
                throw new IllegalArgumentException("Missing " + key + " in segmentation token: " + raw);
            }
            return;
        }
        if (!"true".equalsIgnoreCase(value.trim()) && !"false".equalsIgnoreCase(value.trim())) {
            throw new IllegalArgumentException("Invalid boolean for " + key + " in segmentation token: " + raw);
        }
    }

    private static void validateFiniteDouble(String value, String label, String raw) {
        if (!isFiniteDouble(value)) {
            throw new IllegalArgumentException("Invalid " + label + " in segmentation token: " + raw);
        }
    }

    private static boolean isFiniteDouble(String value) {
        if (value == null) return false;
        try {
            double parsed = Double.parseDouble(value.trim());
            return Double.isFinite(parsed);
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static void appendIfPresent(StringBuilder sb, String key, String value) {
        if (value == null || value.trim().isEmpty()) return;
        sb.append(':').append(key).append('=').append(value.trim());
    }

    private static void appendUnknownParams(StringBuilder sb,
                                            Map<String, String> params,
                                            Set<String> knownKeys) {
        for (Map.Entry<String, String> entry : params.entrySet()) {
            if (!knownKeys.contains(entry.getKey())) {
                appendIfPresent(sb, entry.getKey(), entry.getValue());
            }
        }
    }

    private static String valueOrDefault(String value, String fallback) {
        return value == null || value.trim().isEmpty() ? fallback : value.trim();
    }

    private static boolean isEmpty(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static void warn(String message) {
        WarningSink sink = warningSink == null ? DEFAULT_WARNING_SINK : warningSink;
        sink.warn(message);
    }
}
