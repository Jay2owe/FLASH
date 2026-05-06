package flash.pipeline.cellpose;

public enum CellposeModel {
    CYTO3("cyto3",
            "cyto3",
            "Recommended first-pass model for irregular whole-cell bodies and glial soma.",
            true),
    CYTO2("cyto2",
            "cyto2",
            "Legacy whole-cell model that can perform better on older cytoplasmic datasets.",
            true),
    CYTO("cyto",
            "cyto",
            "Older cytoplasm model. Useful mainly for legacy comparisons.",
            true),
    NUCLEI("nuclei",
            "nuclei",
            "Built for rounded nuclear objects rather than irregular whole-cell bodies.",
            false),
    TISSUENET_CP3("tissuenet_cp3",
            "tissuenet_cp3",
            "Broad tissue-trained model. Useful when cyto3 under-segments tissue sections.",
            false),
    LIVECELL_CP3("livecell_cp3",
            "livecell_cp3",
            "Cultured-cell model. Usually less suitable for fixed tissue sections.",
            false);

    private final String token;
    private final String displayName;
    private final String description;
    private final boolean supportsSecondChannel;

    CellposeModel(String token, String displayName, String description, boolean supportsSecondChannel) {
        this.token = token;
        this.displayName = displayName;
        this.description = description;
        this.supportsSecondChannel = supportsSecondChannel;
    }

    public String token() {
        return token;
    }

    public String displayName() {
        return displayName;
    }

    public String description() {
        return description;
    }

    public boolean supportsSecondChannel() {
        return supportsSecondChannel;
    }

    public static CellposeModel fromToken(String token) {
        if (token != null) {
            for (CellposeModel model : values()) {
                if (model.token.equalsIgnoreCase(token.trim())) {
                    return model;
                }
            }
        }
        return CYTO3;
    }

    public static String[] displayNames() {
        CellposeModel[] values = values();
        String[] names = new String[values.length];
        for (int i = 0; i < values.length; i++) {
            names[i] = values[i].displayName;
        }
        return names;
    }
}
