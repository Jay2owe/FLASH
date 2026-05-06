package flash.pipeline.roi;

/** ROI naming rules for Jamie macro parity (+ requested hemisphere addition). */
public final class RoiNaming {

    private RoiNaming() {}

    /**
     * Build a base ROI name from the available parts, skipping empty
     * components so that non-convention filenames produce clean names
     * (e.g. "my_image" instead of "my_image__").
     */
    public static String baseName(String animal, String hemisphere, String region) {
        StringBuilder sb = new StringBuilder();
        append(sb, animal);
        append(sb, hemisphere);
        append(sb, region);
        return sb.length() > 0 ? sb.toString() : "ROI";
    }

    public static String croppedName(String animal, String hemisphere, String region) {
        return baseName(animal, hemisphere, region) + "_Cropped";
    }

    private static void append(StringBuilder sb, String part) {
        if (part != null && !part.isEmpty()) {
            if (sb.length() > 0) sb.append('_');
            sb.append(part);
        }
    }
}
