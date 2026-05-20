package flash.pipeline.deconv.psf;

public enum PsfModel {
    BORN_WOLF(
            "Born & Wolf",
            "Ideal / symmetrical PSF. Best for thin samples near the coverslip with well-matched optics.",
            "Born & Wolf 3D Optical Model"),
    GIBSON_LANNI(
            "Gibson & Lanni",
            "General-purpose model for thick specimens where refractive-index mismatch matters.",
            "Gibson & Lanni 3D Optical Model"),
    DOUGHERTY_THEORETICAL(
            "Dougherty's Theoretical",
            "Simple theoretical approximation when a lighter-weight instrument model is acceptable.",
            "Dougherty 3D Optical Model");

    private final String displayName;
    private final String description;
    private final String epflMacroModelKey;

    PsfModel(String displayName, String description, String epflMacroModelKey) {
        this.displayName = displayName;
        this.description = description;
        this.epflMacroModelKey = epflMacroModelKey;
    }

    public String displayName() {
        return displayName;
    }

    public String description() {
        return description;
    }

    public String epflMacroModelKey() {
        return epflMacroModelKey;
    }
}
