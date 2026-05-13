package flash.pipeline.image.variation;

import flash.pipeline.image.FilterMacroParser.OpType;
import flash.pipeline.image.dag.DagNode;
import flash.pipeline.image.variation.ParamSpec.Scale;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Static registry mapping operation types to sweepable numeric parameters.
 */
public final class OpTypeParamRegistry {

    private static final Map<OpType, List<ParamSpec>> SPECS;

    static {
        Map<OpType, List<ParamSpec>> m = new EnumMap<OpType, List<ParamSpec>>(OpType.class);

        m.put(OpType.GAUSSIAN_BLUR, Collections.unmodifiableList(Arrays.asList(
                new ParamSpec("Sigma", "sigma", 2.0, 0.5, 10.0, Scale.LOG, "px", false))));

        m.put(OpType.MEDIAN, Collections.unmodifiableList(Arrays.asList(
                new ParamSpec("Radius", "radius", 2.0, 1.0, 10.0, Scale.LINEAR, "px", false))));
        m.put(OpType.MEAN, Collections.unmodifiableList(Arrays.asList(
                new ParamSpec("Radius", "radius", 2.0, 1.0, 10.0, Scale.LINEAR, "px", false))));
        m.put(OpType.MINIMUM, Collections.unmodifiableList(Arrays.asList(
                new ParamSpec("Radius", "radius", 2.0, 1.0, 10.0, Scale.LINEAR, "px", false))));
        m.put(OpType.MAXIMUM, Collections.unmodifiableList(Arrays.asList(
                new ParamSpec("Radius", "radius", 2.0, 1.0, 10.0, Scale.LINEAR, "px", false))));
        m.put(OpType.VARIANCE, Collections.unmodifiableList(Arrays.asList(
                new ParamSpec("Radius", "radius", 2.0, 1.0, 10.0, Scale.LINEAR, "px", false))));

        m.put(OpType.SUBTRACT_BACKGROUND, Collections.unmodifiableList(Arrays.asList(
                new ParamSpec("Rolling-ball radius", "rolling", 20.0, 5.0, 500.0,
                        Scale.LOG, "px", true))));

        m.put(OpType.UNSHARP_MASK, Collections.unmodifiableList(Arrays.asList(
                new ParamSpec("Radius", "radius", 10.0, 1.0, 50.0, Scale.LOG, "px", false),
                new ParamSpec("Mask weight", "mask", 0.60, 0.1, 0.9,
                        Scale.LINEAR, "", false))));

        m.put(OpType.GAUSSIAN_BLUR_3D, Collections.unmodifiableList(Arrays.asList(
                new ParamSpec("Sigma X", "x", 2.0, 0.5, 10.0, Scale.LOG, "px", false),
                new ParamSpec("Sigma Y", "y", 2.0, 0.5, 10.0, Scale.LOG, "px", false),
                new ParamSpec("Sigma Z", "z", 1.0, 0.5, 5.0, Scale.LOG, "px", false))));

        m.put(OpType.MEDIAN_3D, Collections.unmodifiableList(Arrays.asList(
                new ParamSpec("Radius X", "x", 2.0, 1.0, 10.0, Scale.LINEAR, "px", false),
                new ParamSpec("Radius Y", "y", 2.0, 1.0, 10.0, Scale.LINEAR, "px", false),
                new ParamSpec("Radius Z", "z", 1.0, 1.0, 5.0, Scale.LINEAR, "px", false))));

        m.put(OpType.MINIMUM_3D, Collections.unmodifiableList(Arrays.asList(
                new ParamSpec("Radius X", "x", 2.0, 1.0, 10.0, Scale.LINEAR, "px", false),
                new ParamSpec("Radius Y", "y", 2.0, 1.0, 10.0, Scale.LINEAR, "px", false),
                new ParamSpec("Radius Z", "z", 1.0, 1.0, 5.0, Scale.LINEAR, "px", false))));

        SPECS = Collections.unmodifiableMap(m);
    }

    private OpTypeParamRegistry() {}

    public static List<ParamSpec> paramsOf(OpType type) {
        if (type == null) return Collections.emptyList();
        List<ParamSpec> specs = SPECS.get(type);
        return specs == null ? Collections.<ParamSpec>emptyList() : specs;
    }

    public static boolean isSweepable(DagNode node) {
        return node != null && !node.disabled && !paramsOf(node.type).isEmpty();
    }

    public static String argsForDefaults(OpType type) {
        StringBuilder sb = new StringBuilder();
        for (ParamSpec p : paramsOf(type)) {
            if (sb.length() > 0) sb.append(' ');
            appendKeyValue(sb, p, p.defaultValue);
        }
        return sb.toString();
    }

    public static Map<String, Double> parseArgs(OpType type, String args) {
        List<ParamSpec> specs = paramsOf(type);
        if (specs.isEmpty()) return Collections.emptyMap();
        String safeArgs = args == null ? "" : args;

        Map<String, Double> out = new LinkedHashMap<String, Double>();
        for (ParamSpec p : specs) {
            Double parsed = extractNumeric(safeArgs, p.argKey);
            out.put(p.argKey, parsed == null ? Double.valueOf(p.defaultValue) : parsed);
        }
        return out;
    }

    public static String renderArgs(OpType type, Map<String, Double> values) {
        Map<String, Double> safeValues =
                values == null ? Collections.<String, Double>emptyMap() : values;
        StringBuilder sb = new StringBuilder();
        for (ParamSpec p : paramsOf(type)) {
            Double v = safeValues.get(p.argKey);
            double effective = v == null ? p.defaultValue : v.doubleValue();
            if (sb.length() > 0) sb.append(' ');
            appendKeyValue(sb, p, effective);
        }
        return sb.toString();
    }

    static List<OpType> coveredTypes() {
        return Collections.unmodifiableList(new ArrayList<OpType>(SPECS.keySet()));
    }

    private static void appendKeyValue(StringBuilder sb, ParamSpec p, double value) {
        sb.append(p.argKey).append('=');
        if (p.isInteger) {
            sb.append((int) value);
        } else {
            sb.append(value);
        }
    }

    private static Double extractNumeric(String args, String key) {
        Pattern p = Pattern.compile(
                "(?:^|[^A-Za-z0-9_])" + Pattern.quote(key)
                        + "\\s*=\\s*(-?[0-9]*\\.?[0-9]+(?:[eE][-+]?[0-9]+)?)");
        Matcher m = p.matcher(args);
        if (m.find()) {
            try {
                return Double.valueOf(m.group(1));
            } catch (NumberFormatException nfe) {
                return null;
            }
        }
        return null;
    }
}
