package flash.pipeline.image;

import ij.ImagePlus;
import ij.ImageStack;
import ij.process.ImageProcessor;

import java.util.Locale;

/** Threshold utilities to match macro behavior without invoking ImageJ commands/macros. */
public final class ThresholdOps {

    public static final String AUTO_PREFIX = "auto:";
    public static final String DEFAULT_METHOD = "Default";
    public static final String DEFAULT_BACKGROUND = "dark";

    private static final String[] AUTO_METHODS = {
            "Default", "Huang", "Intermodes", "IsoData", "Li", "MaxEntropy",
            "Mean", "MinError", "Minimum", "Moments", "Otsu", "Percentile",
            "RenyiEntropy", "Shanbhag", "Triangle", "Yen"
    };
    private static final String[] BACKGROUNDS = {"dark", "light"};

    private ThresholdOps() {}

    public static String[] autoMethods() {
        String[] copy = new String[AUTO_METHODS.length];
        System.arraycopy(AUTO_METHODS, 0, copy, 0, AUTO_METHODS.length);
        return copy;
    }

    /**
     * Macro get_stack_threshold(): slice 6, setAutoThreshold("Default dark"), return lower threshold.
     *
     * <p>This implementation avoids {@code IJ.run("Auto Threshold", ...)} and instead uses ImageJ1's
     * {@link ImageProcessor#setAutoThreshold(String)} which is the same mechanism used by the UI.
     *
     * <p>We only return the threshold value; we do not apply thresholding to the image.
     */
    public static double defaultDarkThresholdAtSlice6(ImagePlus imp) {
        return autoThresholdAtSlice(imp, DEFAULT_METHOD, DEFAULT_BACKGROUND, 6);
    }

    public static String formatAutoToken(String method, String background) {
        AutoThresholdSpec spec = new AutoThresholdSpec(method, background);
        return AUTO_PREFIX + spec.method + ":" + spec.background;
    }

    public static boolean isAutoThresholdToken(String token) {
        String trimmed = token == null ? "" : token.trim();
        if ("default".equalsIgnoreCase(trimmed)) return false;
        return autoThresholdSpecForToken(token, false) != null;
    }

    public static boolean isAlgorithmicThresholdToken(String token) {
        return autoThresholdSpecForToken(token, false) != null;
    }

    public static AutoThresholdSpec parseAutoThresholdToken(String token) {
        if (token == null) return null;
        String trimmed = token.trim();
        if (trimmed.length() == 0) return null;
        String body;
        if (startsWithIgnoreCase(trimmed, AUTO_PREFIX)) {
            body = trimmed.substring(AUTO_PREFIX.length());
        } else if (startsWithIgnoreCase(trimmed, "auto=")) {
            body = trimmed.substring("auto=".length());
        } else {
            return null;
        }
        String normalized = body.trim().replace('|', ':').replace(';', ':');
        if (normalized.length() == 0) {
            return new AutoThresholdSpec(DEFAULT_METHOD, DEFAULT_BACKGROUND);
        }
        String[] parts = normalized.split(":", 3);
        String method = parts.length > 0 ? parts[0] : DEFAULT_METHOD;
        String background = parts.length > 1 ? parts[1] : DEFAULT_BACKGROUND;
        return new AutoThresholdSpec(method, background);
    }

    public static AutoThresholdSpec autoThresholdSpecForToken(String token, boolean emptyIsDefault) {
        String trimmed = token == null ? "" : token.trim();
        if (trimmed.length() == 0) {
            return emptyIsDefault ? new AutoThresholdSpec(DEFAULT_METHOD, DEFAULT_BACKGROUND) : null;
        }
        if ("default".equalsIgnoreCase(trimmed)) {
            return new AutoThresholdSpec(DEFAULT_METHOD, DEFAULT_BACKGROUND);
        }
        AutoThresholdSpec explicit = parseAutoThresholdToken(trimmed);
        if (explicit != null) return explicit;
        String[] parts = trimmed.split("\\s+", 3);
        if (parts.length == 1 && isKnownAutoMethod(parts[0])) {
            return new AutoThresholdSpec(parts[0], DEFAULT_BACKGROUND);
        }
        if (parts.length == 2 && isKnownAutoMethod(parts[0]) && isKnownBackground(parts[1])) {
            return new AutoThresholdSpec(parts[0], parts[1]);
        }
        return null;
    }

    public static double thresholdFromTokenAtSlice(ImagePlus image, String token,
                                                   int preferredSlice,
                                                   boolean emptyIsDefault) {
        Double numeric = parseNumericThreshold(token);
        if (numeric != null) return numeric.doubleValue();
        AutoThresholdSpec spec = autoThresholdSpecForToken(token, emptyIsDefault);
        return spec == null
                ? Double.NaN
                : autoThresholdAtSlice(image, spec.method, spec.background, preferredSlice);
    }

    public static double thresholdFromTokenCurrentSlice(ImagePlus image, String token,
                                                        boolean emptyIsDefault) {
        Double numeric = parseNumericThreshold(token);
        if (numeric != null) return numeric.doubleValue();
        AutoThresholdSpec spec = autoThresholdSpecForToken(token, emptyIsDefault);
        return spec == null
                ? Double.NaN
                : autoThresholdCurrentSlice(image, spec.method, spec.background);
    }

    public static double autoThresholdAtSlice(ImagePlus imp, String method,
                                              String background,
                                              int preferredSlice) {
        if (imp == null || imp.getStack() == null || imp.getStack().getSize() < 1) {
            return Double.NaN;
        }
        int z = Math.min(6, Math.max(1, imp.getNSlices()));
        if (preferredSlice > 0) {
            z = Math.min(preferredSlice, Math.max(1, imp.getStack().getSize()));
        }

        ImageProcessor ip = imp.getStack().getProcessor(z);
        if (ip == null) return Double.NaN;

        ThresholdRange range = autoThresholdRange(ip, method, background);
        return range.hasValues() ? range.lower : Double.NaN;
    }

    public static double autoThresholdCurrentSlice(ImagePlus image, String method,
                                                   String background) {
        if (image == null) return Double.NaN;
        ImageProcessor processor = image.getProcessor();
        if (processor == null) return Double.NaN;
        ThresholdRange range = autoThresholdRange(processor, method, background);
        return range.hasValues() ? range.lower : Double.NaN;
    }

    public static ThresholdRange thresholdRangeFromTokenCurrentSlice(ImagePlus image,
                                                                     String token,
                                                                     boolean emptyIsDefault) {
        Double numeric = parseNumericThreshold(token);
        if (numeric != null) {
            return ThresholdRange.from(numeric.doubleValue(), Double.POSITIVE_INFINITY);
        }
        AutoThresholdSpec spec = autoThresholdSpecForToken(token, emptyIsDefault);
        if (spec == null || image == null) return ThresholdRange.empty();
        ImageProcessor processor = image.getProcessor();
        return processor == null
                ? ThresholdRange.empty()
                : autoThresholdRange(processor, spec.method, spec.background);
    }

    public static ThresholdRange autoThresholdRange(ImageProcessor processor,
                                                    String method,
                                                    String background) {
        if (processor == null) return ThresholdRange.empty();
        try {
            ImageProcessor duplicate = processor.duplicate();
            duplicate.setAutoThreshold(autoThresholdArgument(method, background));
            double lower = duplicate.getMinThreshold();
            double upper = duplicate.getMaxThreshold();
            if (!isValidThreshold(lower)) return ThresholdRange.empty();
            if (!isValidThreshold(upper) || upper < lower) {
                upper = Double.POSITIVE_INFINITY;
            }
            return ThresholdRange.from(lower, upper);
        } catch (RuntimeException e) {
            return ThresholdRange.empty();
        }
    }

    public static boolean applyStackThresholdInPlace(ImagePlus image, String token,
                                                     boolean emptyIsDefault) {
        if (image == null || image.getStack() == null || image.getStack().getSize() < 1) {
            return false;
        }
        ImageStack stack = image.getStack();
        ThresholdRange[] ranges = new ThresholdRange[stack.getSize()];
        Double numeric = parseNumericThreshold(token);
        AutoThresholdSpec spec = autoThresholdSpecForToken(token, emptyIsDefault);
        if (numeric == null && spec == null) {
            return false;
        }
        for (int s = 1; s <= stack.getSize(); s++) {
            if (numeric != null) {
                ranges[s - 1] = ThresholdRange.from(numeric.doubleValue(), Double.POSITIVE_INFINITY);
            } else {
                ranges[s - 1] = autoThresholdRange(stack.getProcessor(s), spec.method, spec.background);
                if (!ranges[s - 1].hasValues()) {
                    return false;
                }
            }
        }
        for (int s = 1; s <= stack.getSize(); s++) {
            ImageProcessor ip = stack.getProcessor(s);
            ThresholdRange range = ranges[s - 1];
            for (int p = 0; p < ip.getPixelCount(); p++) {
                double value = ip.getf(p);
                ip.set(p, range.contains(value) ? 255 : 0);
            }
        }
        image.updateAndDraw();
        return true;
    }

    public static Double parseNumericThreshold(String token) {
        if (token == null) return null;
        String trimmed = token.trim();
        if (trimmed.length() == 0) return null;
        try {
            double parsed = Double.parseDouble(trimmed);
            return Double.isFinite(parsed) ? Double.valueOf(parsed) : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public static String describeToken(String token) {
        String trimmed = token == null ? "" : token.trim();
        if (trimmed.length() == 0) return "";
        AutoThresholdSpec spec = autoThresholdSpecForToken(trimmed, false);
        if (spec == null) {
            return trimmed;
        }
        return spec.method + " " + spec.background + " auto";
    }

    public static String canonicalMethod(String method) {
        String safe = method == null ? "" : method.trim();
        if (safe.length() == 0) return DEFAULT_METHOD;
        String compact = safe.replace(" ", "").replace("_", "").replace("-", "");
        for (int i = 0; i < AUTO_METHODS.length; i++) {
            String candidate = AUTO_METHODS[i];
            if (candidate.equalsIgnoreCase(safe)
                    || candidate.equalsIgnoreCase(compact)) {
                return candidate;
            }
        }
        return safe;
    }

    public static String canonicalBackground(String background) {
        String safe = background == null ? "" : background.trim();
        if (safe.length() == 0) return DEFAULT_BACKGROUND;
        for (int i = 0; i < BACKGROUNDS.length; i++) {
            if (BACKGROUNDS[i].equalsIgnoreCase(safe)) {
                return BACKGROUNDS[i];
            }
        }
        return safe.toLowerCase(Locale.US);
    }

    private static boolean isKnownAutoMethod(String method) {
        String safe = method == null ? "" : method.trim();
        if (safe.length() == 0) return false;
        String compact = safe.replace(" ", "").replace("_", "").replace("-", "");
        for (int i = 0; i < AUTO_METHODS.length; i++) {
            String candidate = AUTO_METHODS[i];
            if (candidate.equalsIgnoreCase(safe)
                    || candidate.equalsIgnoreCase(compact)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isKnownBackground(String background) {
        String safe = background == null ? "" : background.trim();
        for (int i = 0; i < BACKGROUNDS.length; i++) {
            if (BACKGROUNDS[i].equalsIgnoreCase(safe)) {
                return true;
            }
        }
        return false;
    }

    private static String autoThresholdArgument(String method, String background) {
        AutoThresholdSpec spec = new AutoThresholdSpec(method, background);
        return spec.method + " " + spec.background;
    }

    private static boolean startsWithIgnoreCase(String text, String prefix) {
        return text.regionMatches(true, 0, prefix, 0, prefix.length());
    }

    private static boolean isValidThreshold(double value) {
        return Double.isFinite(value) && value != ImageProcessor.NO_THRESHOLD;
    }

    public static final class AutoThresholdSpec {
        public final String method;
        public final String background;

        public AutoThresholdSpec(String method, String background) {
            this.method = canonicalMethod(method);
            this.background = canonicalBackground(background);
        }
    }

    public static final class ThresholdRange {
        public final double lower;
        public final double upper;
        private final boolean hasValues;

        private ThresholdRange(double lower, double upper, boolean hasValues) {
            this.lower = lower;
            this.upper = upper;
            this.hasValues = hasValues;
        }

        public static ThresholdRange from(double lower, double upper) {
            if (!Double.isFinite(lower)) return empty();
            double safeUpper = upper;
            if (Double.isNaN(safeUpper) || safeUpper < lower) {
                safeUpper = Double.POSITIVE_INFINITY;
            }
            return new ThresholdRange(lower, safeUpper, true);
        }

        public static ThresholdRange empty() {
            return new ThresholdRange(Double.NaN, Double.NaN, false);
        }

        public boolean hasValues() {
            return hasValues;
        }

        public boolean contains(double value) {
            return Double.isFinite(value) && hasValues && value >= lower && value <= upper;
        }
    }
}
