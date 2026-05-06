package flash.pipeline.image;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parser for filter macros written by the pipeline (.ijm).
 *
 * Parses run("Command...", "args") lines into typed Op objects that can
 * be executed natively in Java without the ImageJ macro interpreter.
 *
 * Tier 1 commands (native executors live in {@link FilterExecutor}):
 *   Smoothing      Gaussian Blur, Subtract Background, Median, Mean,
 *                  Unsharp Mask, Minimum, Maximum, Variance
 *   Morphology     Dilate, Erode, Open, Close-, Fill Holes, Skeletonize
 *   Math           Invert, Add, Subtract, Multiply, Divide
 *   Threshold      Auto Local Threshold
 *   Bit-depth      8-bit, 16-bit, 32-bit
 *   Enhance        Enhance Contrast
 *   3D filters     Gaussian Blur 3D, Median 3D, Minimum 3D
 *
 * Anything else falls through to {@link OpType#UNKNOWN} so {@code FilterExecutor}
 * routes it through the locked legacy macro path.
 */
public final class FilterMacroParser {

    private FilterMacroParser() {}

    public enum OpType {
        // Single-slice rank/convolution filters
        GAUSSIAN_BLUR, SUBTRACT_BACKGROUND, MEDIAN, MEAN, UNSHARP_MASK,
        MINIMUM, MAXIMUM, VARIANCE,
        // Binary morphology
        DILATE, ERODE, OPEN, CLOSE_, FILL_HOLES, SKELETONIZE,
        // Pixel math
        INVERT, ADD, SUBTRACT, MULTIPLY, DIVIDE,
        // Threshold
        AUTO_LOCAL_THRESHOLD,
        // Bit-depth conversion (whole stack)
        CONVERT_8BIT, CONVERT_16BIT, CONVERT_32BIT,
        // Histogram normalisation
        ENHANCE_CONTRAST,
        // 3D filters (whole stack)
        GAUSSIAN_BLUR_3D, MEDIAN_3D, MINIMUM_3D,
        // Sentinel
        UNKNOWN
    }

    /**
     * Exact-match command-name → OpType lookup. Keys are the command names as
     * they appear inside {@code run("...")} with any trailing {@code ...} stripped.
     * Substring matching is intentionally avoided so that "Close-" and "Close"
     * (and "Median" / "Median 3D") cannot collide.
     */
    private static final Map<String, OpType> COMMAND_TO_OP = buildCommandMap();

    private static Map<String, OpType> buildCommandMap() {
        Map<String, OpType> m = new HashMap<String, OpType>();
        m.put("Gaussian Blur",        OpType.GAUSSIAN_BLUR);
        m.put("Subtract Background",  OpType.SUBTRACT_BACKGROUND);
        m.put("Median",               OpType.MEDIAN);
        m.put("Mean",                 OpType.MEAN);
        m.put("Unsharp Mask",         OpType.UNSHARP_MASK);
        m.put("Minimum",              OpType.MINIMUM);
        m.put("Maximum",              OpType.MAXIMUM);
        m.put("Variance",             OpType.VARIANCE);
        m.put("Dilate",               OpType.DILATE);
        m.put("Erode",                OpType.ERODE);
        m.put("Open",                 OpType.OPEN);
        m.put("Close-",               OpType.CLOSE_);
        m.put("Fill Holes",           OpType.FILL_HOLES);
        m.put("Skeletonize",          OpType.SKELETONIZE);
        m.put("Invert",               OpType.INVERT);
        m.put("Add",                  OpType.ADD);
        m.put("Subtract",             OpType.SUBTRACT);
        m.put("Multiply",             OpType.MULTIPLY);
        m.put("Divide",               OpType.DIVIDE);
        m.put("Auto Local Threshold", OpType.AUTO_LOCAL_THRESHOLD);
        m.put("8-bit",                OpType.CONVERT_8BIT);
        m.put("16-bit",               OpType.CONVERT_16BIT);
        m.put("32-bit",               OpType.CONVERT_32BIT);
        m.put("Enhance Contrast",     OpType.ENHANCE_CONTRAST);
        m.put("Gaussian Blur 3D",     OpType.GAUSSIAN_BLUR_3D);
        m.put("Median 3D",            OpType.MEDIAN_3D);
        m.put("Minimum 3D",           OpType.MINIMUM_3D);
        return Collections.unmodifiableMap(m);
    }

    /** Regex for {@code run("Command", "args")} or {@code run("Command")}. Greedy on whitespace. */
    private static final Pattern RUN_PATTERN = Pattern.compile(
            "run\\s*\\(\\s*\"([^\"]+)\"(?:\\s*,\\s*\"([^\"]*)\")?\\s*\\)");

    public static final class Op {
        public final OpType type;
        public final String args;

        Op(OpType type, String args) {
            this.type = type;
            this.args = args == null ? "" : args;
        }

        /** Extracts a numeric parameter value from the args string. {@link Double#NaN} when absent. */
        public double getParam(String key) {
            // Anchor on word boundary so "radius" doesn't match "z=2 stack radius=3" wrongly,
            // and "x" doesn't match the "x" inside "max=...".
            Pattern p = Pattern.compile(
                    "(?:^|[^A-Za-z0-9_])" + Pattern.quote(key)
                            + "\\s*=\\s*(-?[0-9]*\\.?[0-9]+(?:[eE][-+]?[0-9]+)?)");
            Matcher m = p.matcher(args);
            if (m.find()) return Double.parseDouble(m.group(1));
            return Double.NaN;
        }

        /** Extracts a non-numeric parameter value (e.g. {@code method=Bernsen}). */
        public String getStringParam(String key) {
            Pattern p = Pattern.compile(
                    "(?:^|[^A-Za-z0-9_])" + Pattern.quote(key) + "\\s*=\\s*([^\\s]+)");
            Matcher m = p.matcher(args);
            if (m.find()) return m.group(1);
            return null;
        }

        /** True when the arg list mentions the bare boolean flag {@code key}. */
        public boolean hasFlag(String key) {
            Pattern p = Pattern.compile("(?:^|\\s)" + Pattern.quote(key) + "(?=$|\\s)");
            return p.matcher(args).find();
        }
    }

    /** Parse a macro file into a list of operations. */
    public static List<Op> parse(File ijmFile) throws Exception {
        List<String> lines = Files.readAllLines(ijmFile.toPath(), StandardCharsets.UTF_8);
        return parseLines(lines);
    }

    /** Parse a macro string into a list of operations. */
    public static List<Op> parseString(String macroContent) {
        if (macroContent == null || macroContent.isEmpty()) return new ArrayList<Op>();
        List<String> lines = new ArrayList<String>();
        for (String line : macroContent.split("\\r?\\n")) {
            lines.add(line);
        }
        return parseLines(lines);
    }

    private static List<Op> parseLines(List<String> lines) {
        List<Op> ops = new ArrayList<Op>();
        for (String line : lines) {
            if (line == null) continue;
            String t = line.trim();
            if (t.isEmpty()) continue;

            // Skip line-level comments. Block-comment bodies are not stripped — by
            // convention bundled presets use only //-style comments.
            if (t.startsWith("//") || t.startsWith("/*") || t.startsWith("*")) continue;

            Matcher m = RUN_PATTERN.matcher(t);
            if (!m.find()) {
                // Anything else (selectWindow, imageCalculator, close, rename,
                // assignments, etc.) is opaque to this parser — keep the line as
                // UNKNOWN so the caller falls back to the legacy macro path.
                ops.add(new Op(OpType.UNKNOWN, t));
                continue;
            }

            String cmd = m.group(1).trim();
            // Strip trailing "..." that ImageJ appends to commands taking arguments.
            if (cmd.endsWith("...")) cmd = cmd.substring(0, cmd.length() - 3).trim();

            String args = m.group(2) == null ? "" : m.group(2);

            OpType type = COMMAND_TO_OP.get(cmd);
            if (type == null) {
                ops.add(new Op(OpType.UNKNOWN, t));
            } else {
                ops.add(new Op(type, args));
            }
        }
        return ops;
    }
}
