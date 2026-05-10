package flash.pipeline.analyses.spatial;

import flash.pipeline.naming.ChannelFilenameCodec;

import java.util.Objects;

public final class SectionKey {
    private final String animalName;
    private final String labelSuffix;

    private SectionKey(String animalName, String labelSuffix) {
        this.animalName = normalize(animalName);
        this.labelSuffix = normalize(labelSuffix);
    }

    public static SectionKey of(String animalName, String labelSuffix) {
        return new SectionKey(animalName, labelSuffix);
    }

    public String animalName() {
        return animalName;
    }

    public String labelSuffix() {
        return labelSuffix;
    }

    public String labelFileName(String channelName) {
        String safeChannelName = ChannelFilenameCodec.toSafe(channelName);
        return safeChannelName + "_objects" + (labelSuffix.isEmpty() ? "" : "_" + labelSuffix) + ".tif";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof SectionKey)) return false;
        SectionKey that = (SectionKey) o;
        return animalName.equals(that.animalName)
                && labelSuffix.equals(that.labelSuffix);
    }

    @Override
    public int hashCode() {
        return Objects.hash(animalName, labelSuffix);
    }

    @Override
    public String toString() {
        return animalName + "|" + labelSuffix;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}
