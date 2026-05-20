package flash.pipeline.runtime;

import ij.IJ;

import java.io.File;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;

/**
 * User-facing guard for stale or partial plugin installs.
 */
public final class PluginInstallGuard {

    private static final String INTERNAL_PREFIX_DOTTED = "flash.pipeline.";
    private static final String INTERNAL_PREFIX_SLASHED = "flash/pipeline/";
    private static boolean pinnedRuntimeCheckedThisSession = false;

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

    /**
     * Startup audit for optional runtimes where one wrong jar version can break
     * a later class load even if the main plugin itself loaded cleanly.
     */
    public static synchronized void auditPinnedRuntimeJarsOnStartup(DependencyId... ids) {
        if (pinnedRuntimeCheckedThisSession || ids == null || ids.length == 0) {
            return;
        }

        File fijiDir = DependencyRegistry.resolveFijiDir();
        if (fijiDir == null) {
            return;
        }
        pinnedRuntimeCheckedThisSession = true;

        try {
            List<DependencySpec> specs = new ArrayList<DependencySpec>();
            for (DependencyId id : ids) {
                DependencySpec spec = DependencyRegistry.get(id);
                if (spec != null && hasExactJarPins(spec)) {
                    specs.add(spec);
                }
            }
            EnumMap<DependencyId, DependencyStatus> statuses = DependencyRegistry.snapshotStatuses(specs);
            StringBuilder sb = new StringBuilder();
            for (DependencySpec spec : specs) {
                DependencyStatus status = statuses.get(spec.getId());
                if (status == null || status.isPresent()) {
                    continue;
                }
                if (sb.length() > 0) {
                    sb.append('\n');
                }
                sb.append(spec.getDisplayName()).append(": ");
                String detail = status.getDetailMessage();
                sb.append(detail == null || detail.trim().isEmpty()
                        ? status.getState().name()
                        : detail.trim());
            }
            if (sb.length() > 0) {
                IJ.log("FLASH startup pinned runtime jar check found issue(s). "
                        + "Open Dependencies and run Auto-Fix before using the affected feature.\n"
                        + sb.toString());
            }
        } catch (Throwable t) {
            IJ.log("FLASH startup pinned runtime jar check failed: "
                    + t.getClass().getSimpleName() + ": " + safeMessage(t));
        }
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

    private static boolean hasExactJarPins(DependencySpec spec) {
        if (spec == null || spec.getJarRequirements().isEmpty()) {
            return false;
        }
        for (DependencySpec.JarRequirement requirement : spec.getJarRequirements()) {
            if (!requirement.isAcceptAnyExisting()) {
                return true;
            }
        }
        return false;
    }

    private static String safeMessage(Throwable throwable) {
        if (throwable == null || throwable.getMessage() == null || throwable.getMessage().trim().isEmpty()) {
            return "no detail message";
        }
        return throwable.getMessage().trim();
    }
}
