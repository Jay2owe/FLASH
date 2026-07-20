package flash.pipeline.image;

/**
 * Macro get_merge_info() mapping:
 * colors = {Red, Green, Blue, Grey, Cyan, Magenta, Yellow}
 * returns "c{index}={image} " where index is 1-based position in that colors array.
 */
public final class MergeSpec {

    private static final String[] COLORS = {"Red", "Green", "Blue", "Grey", "Cyan", "Magenta", "Yellow"};

    private MergeSpec() {}

    public static String mergeInfoToken(String antibodyColor, String imageTitle) {
        if (antibodyColor == null || imageTitle == null) return " ";
        for (int i = 0; i < COLORS.length; i++) {
            if (COLORS[i].equalsIgnoreCase(antibodyColor.trim())) {
                return "c" + (i + 1) + "=" + imageTitle + " ";
            }
        }
        return " ";
    }
}
