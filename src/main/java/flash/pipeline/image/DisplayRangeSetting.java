package flash.pipeline.image;

import ij.ImagePlus;
import ij.plugin.ContrastEnhancer;

import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;

/**
 * Display-only contrast setting persisted in channel_config.json minmax fields.
 */
public final class DisplayRangeSetting {

    public enum Mode {
        NONE,
        MANUAL,
        AUTO_ENHANCE
    }

    public static final double DEFAULT_AUTO_SATURATION_PERCENT = 0.35;
    public static final double MAX_AUTO_SATURATION_PERCENT = 100.0;

    private static final DecimalFormat TOKEN_FORMAT =
            new DecimalFormat("0.###", DecimalFormatSymbols.getInstance(Locale.US));

    private final Mode mode;
    private final double min;
    private final double max;
    private final double saturationPercent;

    private DisplayRangeSetting(Mode mode, double min, double max, double saturationPercent) {
        this.mode = mode == null ? Mode.NONE : mode;
        this.min = min;
        this.max = max;
        this.saturationPercent = saturationPercent;
    }

    public static DisplayRangeSetting none() {
        return new DisplayRangeSetting(Mode.NONE, Double.NaN, Double.NaN,
                DEFAULT_AUTO_SATURATION_PERCENT);
    }

    public static DisplayRangeSetting manual(double min, double max) {
        if (!Double.isFinite(min) || !Double.isFinite(max) || max <= min) {
            return none();
        }
        return new DisplayRangeSetting(Mode.MANUAL, min, max,
                DEFAULT_AUTO_SATURATION_PERCENT);
    }

    public static DisplayRangeSetting autoEnhance(double saturationPercent) {
        return new DisplayRangeSetting(Mode.AUTO_ENHANCE, Double.NaN, Double.NaN,
                normalizeSaturation(saturationPercent, DEFAULT_AUTO_SATURATION_PERCENT));
    }

    public static DisplayRangeSetting parse(String token) {
        String text = token == null ? "" : token.trim();
        if (text.isEmpty() || "none".equalsIgnoreCase(text)) {
            return none();
        }
        String lower = text.toLowerCase(Locale.ROOT);
        if (lower.startsWith("auto:")) {
            Double saturation = parseFiniteDouble(text.substring(text.indexOf(':') + 1));
            return saturation == null ? none() : autoEnhance(saturation.doubleValue());
        }
        if (lower.startsWith("auto-enhance:")) {
            Double saturation = parseFiniteDouble(text.substring(text.indexOf(':') + 1));
            return saturation == null ? none() : autoEnhance(saturation.doubleValue());
        }
        if ("auto".equals(lower) || "automatic".equals(lower) || "auto-enhance".equals(lower)) {
            return autoEnhance(DEFAULT_AUTO_SATURATION_PERCENT);
        }
        double[] range = parseManualRange(text);
        return range == null ? none() : manual(range[0], range[1]);
    }

    public static boolean isValidToken(String token) {
        String text = token == null ? "" : token.trim();
        if (text.isEmpty()) return false;
        if ("none".equalsIgnoreCase(text)) return true;
        return parse(text).isConfigured();
    }

    public static double[] parseManualRange(String token) {
        String text = token == null ? "" : token.trim();
        if (text.isEmpty() || "none".equalsIgnoreCase(text)) return null;
        int dash = text.indexOf('-');
        if (dash <= 0 || dash >= text.length() - 1) return null;
        try {
            double min = Double.parseDouble(text.substring(0, dash).trim());
            double max = Double.parseDouble(text.substring(dash + 1).trim());
            if (!Double.isFinite(min) || !Double.isFinite(max) || max <= min) {
                return null;
            }
            return new double[]{min, max};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public static double normalizeSaturation(double value, double fallback) {
        double safeFallback = Double.isFinite(fallback) ? fallback : DEFAULT_AUTO_SATURATION_PERCENT;
        if (!Double.isFinite(value)) return safeFallback;
        if (value < 0.0) return 0.0;
        if (value > MAX_AUTO_SATURATION_PERCENT) return MAX_AUTO_SATURATION_PERCENT;
        return value;
    }

    public static String formatAutoToken(double saturationPercent) {
        return "auto:" + formatNumber(normalizeSaturation(
                saturationPercent, DEFAULT_AUTO_SATURATION_PERCENT));
    }

    public static String formatManualToken(double min, double max) {
        return String.valueOf((int) Math.round(min))
                + "-" + String.valueOf((int) Math.round(max));
    }

    public Mode mode() {
        return mode;
    }

    public boolean isConfigured() {
        return isManual() || isAutoEnhance();
    }

    public boolean isManual() {
        return mode == Mode.MANUAL && Double.isFinite(min) && Double.isFinite(max) && max > min;
    }

    public boolean isAutoEnhance() {
        return mode == Mode.AUTO_ENHANCE;
    }

    public double min() {
        return min;
    }

    public double max() {
        return max;
    }

    public double saturationPercent() {
        return saturationPercent;
    }

    public String toToken() {
        if (isManual()) {
            return formatManualToken(min, max);
        }
        if (isAutoEnhance()) {
            return formatAutoToken(saturationPercent);
        }
        return "None";
    }

    public String summary() {
        if (isManual()) {
            return toToken();
        }
        if (isAutoEnhance()) {
            return "auto-enhance " + formatNumber(saturationPercent) + "%";
        }
        return "None";
    }

    public void applyTo(ImagePlus image) {
        if (image == null) return;
        if (isManual()) {
            image.setDisplayRange(min, max);
        } else if (isAutoEnhance()) {
            new ContrastEnhancer().stretchHistogram(image, saturationPercent);
        }
    }

    private static Double parseFiniteDouble(String text) {
        try {
            double value = Double.parseDouble(text == null ? "" : text.trim());
            return Double.isFinite(value) ? Double.valueOf(value) : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String formatNumber(double value) {
        if (!Double.isFinite(value)) return "";
        double rounded = Math.rint(value);
        if (Math.abs(value - rounded) < 0.0000001 && Math.abs(rounded) < 1000000000.0) {
            return String.valueOf((long) rounded);
        }
        synchronized (TOKEN_FORMAT) {
            return TOKEN_FORMAT.format(value);
        }
    }
}
