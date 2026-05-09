package flash.pipeline.ui.preview;

public final class PreviewDisplaySettings {

    public enum LutMode {
        GREY,
        CHANNEL
    }

    private final double displayMin;
    private final double displayMax;
    private final LutMode lutMode;
    private final String channelLutName;

    private PreviewDisplaySettings(double displayMin, double displayMax,
                                   LutMode lutMode, String channelLutName) {
        this.displayMin = displayMin;
        this.displayMax = displayMax;
        this.lutMode = lutMode == null ? LutMode.CHANNEL : lutMode;
        this.channelLutName = normalizeLutName(channelLutName);
    }

    public static PreviewDisplaySettings of(double displayMin, double displayMax,
                                            LutMode lutMode, String channelLutName) {
        return new PreviewDisplaySettings(displayMin, displayMax, lutMode, channelLutName);
    }

    public static PreviewDisplaySettings defaultFor(String channelLutName) {
        return new PreviewDisplaySettings(Double.NaN, Double.NaN, LutMode.CHANNEL, channelLutName);
    }

    public boolean hasDisplayRange() {
        return Double.isFinite(displayMin) && Double.isFinite(displayMax) && displayMax > displayMin;
    }

    public double getDisplayMin() {
        return displayMin;
    }

    public double getDisplayMax() {
        return displayMax;
    }

    public LutMode getLutMode() {
        return lutMode;
    }

    public String getChannelLutName() {
        return channelLutName;
    }

    public String effectiveLutName() {
        return lutMode == LutMode.GREY ? "Grays" : channelLutName;
    }

    public PreviewDisplaySettings withChannelLutName(String channelLutName) {
        return new PreviewDisplaySettings(displayMin, displayMax, lutMode, channelLutName);
    }

    static String normalizeLutName(String value) {
        if (value == null) return "Grays";
        String normalized = value.trim().toLowerCase();
        if (normalized.isEmpty()) return "Grays";
        if ("gray".equals(normalized) || "grey".equals(normalized)
                || "grays".equals(normalized) || "greys".equals(normalized)) {
            return "Grays";
        }
        if ("red".equals(normalized)) return "Red";
        if ("green".equals(normalized)) return "Green";
        if ("blue".equals(normalized)) return "Blue";
        if ("cyan".equals(normalized)) return "Cyan";
        if ("magenta".equals(normalized)) return "Magenta";
        if ("yellow".equals(normalized)) return "Yellow";
        return value.trim();
    }
}
