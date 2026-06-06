package flash.pipeline.representative;

/**
 * Statistic source used to guide representative image selection.
 */
public enum RepresentativeStatistic {
    QUICK("Quick", "Brightest 1% mean per channel"),
    EXISTING_RESULT("Existing result", "Use a numeric column from an existing result CSV"),
    NONE("None", "Manual selection without a guiding statistic");

    private final String label;
    private final String description;

    RepresentativeStatistic(String label, String description) {
        this.label = label;
        this.description = description;
    }

    public String label() {
        return label;
    }

    public String description() {
        return description;
    }

    public static RepresentativeStatistic fromLabel(String label) {
        String text = label == null ? "" : label.trim();
        for (RepresentativeStatistic statistic : values()) {
            if (statistic.label.equalsIgnoreCase(text) || statistic.name().equalsIgnoreCase(text)) {
                return statistic;
            }
        }
        return QUICK;
    }

    public static String[] labels() {
        RepresentativeStatistic[] values = values();
        String[] labels = new String[values.length];
        for (int i = 0; i < values.length; i++) {
            labels[i] = values[i].label;
        }
        return labels;
    }
}
