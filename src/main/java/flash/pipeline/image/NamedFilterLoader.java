package flash.pipeline.image;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Loads Named Filter .ijm macros bundled inside the plugin JAR.
 *
 * Resources live at /named-filters/ in the JAR (src/main/resources/named-filters/).
 */
public final class NamedFilterLoader {

    private static final String RESOURCE_DIR = "/named-filters/";

    /** All bundled filter preset names (order matches the UI dropdown). */
    public static final String[] FILTER_NAMES = {
            "Default",
            "Punctate Signal / High Background",
            "Ramified Cells (Microglia/Astrocytes)",
            "Clustered Small",
            "Clustered Large",
            "Overlapping Cellular Marker",
            "Puncta Resolve",
            "Diffuse Object"
    };

    private NamedFilterLoader() {}

    /** Maps a display preset name to its resource filename. */
    private static String toResourceFilename(String presetName) {
        switch (presetName) {
            case "Default":                              return "defaultFilter.ijm";
            case "Punctate Signal / High Background":    return "High Signal-Noise Particle Filter.ijm";
            case "Ramified Cells (Microglia/Astrocytes)":return "Microglia Filter.ijm";
            case "Clustered Small":                      return "Clustered Small Particle Filter.ijm";
            case "Clustered Large":                      return "Clustered Large Particle Filter.ijm";
            case "Overlapping Cellular Marker":          return "Overlapping Cellular Marker Filter.ijm";
            case "Puncta Resolve":                       return "Puncta Resolve Filter.ijm";
            case "Diffuse Object":                       return "diffuse_object_filter.ijm";
            // Historical names accepted by saved configurations.
            case "Default Filter":                       return "defaultFilter.ijm";
            case "High Signal-Noise Particle Filter":    return "High Signal-Noise Particle Filter.ijm";
            case "Microglia Filter":                     return "Microglia Filter.ijm";
            case "Clustered Small Particle Filter":      return "Clustered Small Particle Filter.ijm";
            case "Clustered Large Particle Filter":      return "Clustered Large Particle Filter.ijm";
            case "Overlapping Cellular Marker Filter":   return "Overlapping Cellular Marker Filter.ijm";
            default:                                     return presetName + ".ijm";
        }
    }

    /**
     * Loads the macro content for a named filter preset from bundled JAR resources.
     *
     * @param presetName one of {@link #FILTER_NAMES}, or "Custom" (returns null)
     * @return the macro text, or null if the preset is unknown or not found
     */
    public static String loadFilterContent(String presetName) {
        if (presetName == null || "Custom".equals(presetName)) return null;
        String path = RESOURCE_DIR + toResourceFilename(presetName);
        InputStream is = NamedFilterLoader.class.getResourceAsStream(path);
        if (is == null) {
            ClassLoader cl = Thread.currentThread().getContextClassLoader();
            if (cl != null) is = cl.getResourceAsStream(path.substring(1));
        }
        if (is == null) return null;
        try {
            return readStreamFully(is);
        } catch (IOException e) {
            return null;
        } finally {
            try { is.close(); } catch (IOException ignored) {}
        }
    }

    /**
     * Loads the default filter macro content ("Default" preset).
     * Used by 3D Object Analysis as a fallback when no per-channel filter is set.
     *
     * @return the default filter macro text, or null if the resource is missing
     */
    public static String loadDefaultFilter() {
        return loadFilterContent("Default");
    }

    /**
     * Loads the intensity analysis filter (Median r=2 + Subtract Background rolling=50).
     * This is a separate, simpler filter used exclusively by Intensity Analysis —
     * distinct from the 3D Object Analysis default filter.
     *
     * @return the intensity filter macro text, or null if the resource is missing
     */
    public static String loadIntensityFilter() {
        String path = RESOURCE_DIR + "intensityFilter.ijm";
        InputStream is = NamedFilterLoader.class.getResourceAsStream(path);
        if (is == null) {
            // Fallback: try thread context classloader (Fiji classloader quirk)
            ClassLoader cl = Thread.currentThread().getContextClassLoader();
            if (cl != null) is = cl.getResourceAsStream(path.substring(1));
        }
        if (is != null) {
            try {
                return readStreamFully(is);
            } catch (IOException e) {
                // fall through to hardcoded default
            } finally {
                try { is.close(); } catch (IOException ignored) {}
            }
        }
        // Hardcoded fallback — Median r=2 + Subtract Background rolling=50
        return "run(\"Median...\", \"radius=2 stack\");\nrun(\"Subtract Background...\", \"rolling=50 stack\");\n";
    }

    /** Java-8-safe stream read. */
    private static String readStreamFully(InputStream is) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] tmp = new byte[1024];
        int len;
        while ((len = is.read(tmp)) != -1) {
            buf.write(tmp, 0, len);
        }
        return buf.toString("UTF-8");
    }
}
