package flash.pipeline.deconv.psf;

public enum ScopeModality {
    WIDEFIELD("Widefield"),
    CONFOCAL("Confocal"),
    SPINNING_DISK("Spinning Disk");

    private final String displayName;

    ScopeModality(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }
}
