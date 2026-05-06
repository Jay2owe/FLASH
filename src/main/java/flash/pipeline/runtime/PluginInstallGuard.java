package flash.pipeline.runtime;

import ij.IJ;

/**
 * User-facing guard for stale or partial plugin installs.
 */
public final class PluginInstallGuard {

    private static final String INTERNAL_PREFIX_DOTTED = "flash.pipeline.";
    private static final String INTERNAL_PREFIX_SLASHED = "flash/pipeline/";

    private PluginInstallGuard() {}

    /**
     * Show an actionable error when Fiji is missing one of this plugin's own classes.
     *
     * @return true when the error matched an internal FLASH class and was handled.
     */
    public static boolean reportMissingInternalClass(String analysisName, NoClassDefFoundError error) {
        String missingClass = missingClassName(error);
        if (missingClass == null || !missingClass.startsWith(INTERNAL_PREFIX_DOTTED)) {
            return false;
        }

        String message =
                "This Fiji install is using a stale or partial FLASH plugin JAR.\n"
                        + "Missing runtime class: " + missingClass + "\n\n"
                        + "Close Fiji, delete every FLASH-*.jar (and any legacy IHF-Analysis-Pipeline-*.jar)\n"
                        + "from Fiji's plugins folder, copy one fresh plugin JAR into plugins/, and restart Fiji.\n"
                        + "Do not leave -shaded, -sources, -tests, or original-* copies beside the live jar.";

        IJ.log(analysisName + ": " + message.replace('\n', ' '));
        IJ.error(analysisName, message);
        return true;
    }

    static String missingClassName(NoClassDefFoundError error) {
        if (error == null) return null;

        String message = error.getMessage();
        if (message == null) return null;

        message = message.trim();
        if (message.isEmpty()) return null;

        String initPrefix = "Could not initialize class ";
        if (message.startsWith(initPrefix)) {
            message = message.substring(initPrefix.length()).trim();
        }

        if (message.startsWith(INTERNAL_PREFIX_SLASHED)) {
            return message.replace('/', '.');
        }
        if (message.startsWith(INTERNAL_PREFIX_DOTTED)) {
            return message;
        }
        return message.replace('/', '.');
    }
}
