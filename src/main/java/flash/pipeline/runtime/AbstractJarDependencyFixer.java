package flash.pipeline.runtime;

import java.io.File;
import java.util.Collections;
import java.util.List;

abstract class AbstractJarDependencyFixer implements DependencyFixer {

    private final int executionOrder;
    private final String verifiedMessage;
    private final String repairedMessage;

    AbstractJarDependencyFixer(int executionOrder, String verifiedMessage, String repairedMessage) {
        this.executionOrder = executionOrder;
        this.verifiedMessage = verifiedMessage == null ? "" : verifiedMessage;
        this.repairedMessage = repairedMessage == null ? "" : repairedMessage;
    }

    @Override
    public int getExecutionOrder() {
        return executionOrder;
    }

    @Override
    public DependencyFixResult apply(DependencySpec spec, String actionId, ProgressCallback callback) {
        if (spec == null) {
            return new DependencyFixResult(null, false, false, false, "No dependency spec was provided.");
        }
        String normalizedAction = actionId == null ? "" : actionId.trim();
        if (normalizedAction.isEmpty()) {
            normalizedAction = DependencyService.DialogAction.AUTO_FIX;
        }
        if (!DependencyService.DialogAction.AUTO_FIX.equals(normalizedAction)) {
            return new DependencyFixResult(spec.getId(), false, false, false,
                    "Unknown dependency action: " + normalizedAction);
        }

        DependencyStatus before = probe(spec);
        File fijiDir = getFijiDir();
        if (fijiDir == null) {
            return new DependencyFixResult(spec.getId(), true, false, false,
                    "Could not determine the Fiji.app directory.");
        }

        List<String> issues = check(fijiDir, spec);
        if (issues == null || issues.isEmpty()) {
            DependencyStatus after = probe(spec);
            boolean success = after != null && after.isPresent();
            if (!success && spec.isRestartRequired()) {
                success = true;
            }
            boolean restartRequired = spec.isRestartRequired() && after != null && !after.isPresent();
            return new DependencyFixResult(spec.getId(), true, success, restartRequired,
                    buildInstallMessage(verifiedMessage, restartHint(spec, after), after));
        }

        List<String> writeProblems = checkWritableTargets(fijiDir, spec);
        if (!writeProblems.isEmpty()) {
            return new DependencyFixResult(spec.getId(), true, false, false,
                    buildInstallMessage("Cannot repair " + spec.getDisplayName() + " because Fiji.app is not writable.",
                            joinLines(writeProblems), before));
        }

        notify(callback, spec, "Repairing " + spec.getDisplayName() + "...");
        List<String> actions = repair(fijiDir, spec);
        if (actions == null) {
            actions = Collections.emptyList();
        }

        List<String> remainingIssues = check(fijiDir, spec);
        DependencyStatus after = probe(spec);
        boolean manifestSatisfied = remainingIssues == null || remainingIssues.isEmpty();
        boolean success = manifestSatisfied && !containsFailure(actions);
        boolean restartRequired = spec.isRestartRequired()
                && before != null
                && !before.isPresent()
                && (success || madeRuntimeChange(actions));
        String detail = appendDetails(
                joinLines(actions),
                restoreGuidance(spec, actions),
                remainingIssues(spec, remainingIssues),
                restartHint(spec, after));
        return new DependencyFixResult(
                spec.getId(),
                true,
                success,
                restartRequired,
                buildInstallMessage(repairedMessage, detail, after));
    }

    protected File getFijiDir() {
        return DependencyRegistry.resolveFijiDir();
    }

    protected List<String> check(File fijiDir, DependencySpec spec) {
        return DependencyRegistry.checkJarRequirements(
                fijiDir,
                spec.getJarRequirements(),
                spec.getJarIgnorePrefixes());
    }

    protected List<String> repair(File fijiDir, DependencySpec spec) {
        return DependencyRegistry.repairJarRequirements(
                fijiDir,
                spec.getJarRequirements(),
                spec.getJarIgnorePrefixes());
    }

    private static List<String> checkWritableTargets(File fijiDir, DependencySpec spec) {
        java.util.ArrayList<String> problems = new java.util.ArrayList<String>();
        if (spec.getJarRequirements().isEmpty()) {
            return problems;
        }
        java.util.HashSet<String> folders = new java.util.HashSet<String>();
        for (DependencySpec.JarRequirement requirement : spec.getJarRequirements()) {
            folders.add(requirement.getFolder());
        }
        for (String folder : folders) {
            File dir = new File(fijiDir, folder);
            if (!dir.exists() && !dir.mkdirs()) {
                problems.add("Could not create " + dir.getAbsolutePath());
                continue;
            }
            if (!dir.isDirectory()) {
                problems.add("Expected a folder but found a file: " + dir.getAbsolutePath());
                continue;
            }
            File probe = new File(dir, ".ihf-runtime-write-test-" + System.currentTimeMillis());
            try {
                if (!probe.createNewFile()) {
                    problems.add("Could not create a temporary file in " + dir.getAbsolutePath());
                }
            } catch (Exception e) {
                problems.add("Cannot write to " + dir.getAbsolutePath() + ": "
                        + e.getClass().getSimpleName() + ": " + safeMessage(e));
            } finally {
                if (probe.exists() && !probe.delete()) {
                    probe.deleteOnExit();
                }
            }
        }
        return problems;
    }

    private static DependencyStatus probe(DependencySpec spec) {
        java.util.EnumMap<DependencyId, DependencyStatus> statuses =
                DependencyRegistry.snapshotStatuses(Collections.singletonList(spec));
        return statuses.get(spec.getId());
    }

    private static void notify(ProgressCallback callback, DependencySpec spec, String message) {
        if (callback != null) {
            callback.onProgress(spec, message);
        }
    }

    private static boolean containsFailure(List<String> actions) {
        if (actions == null) {
            return false;
        }
        for (String action : actions) {
            if (action != null && action.trim().startsWith("FAILED")) {
                return true;
            }
        }
        return false;
    }

    private static boolean madeRuntimeChange(List<String> actions) {
        if (actions == null) {
            return false;
        }
        for (String action : actions) {
            String trimmed = action == null ? "" : action.trim();
            if (trimmed.startsWith("Downloaded:")
                    || trimmed.startsWith("Disabled:")
                    || trimmed.startsWith("Scheduled disable after Fiji closes:")) {
                return true;
            }
        }
        return false;
    }

    private static String remainingIssues(DependencySpec spec, List<String> remainingIssues) {
        if (remainingIssues == null || remainingIssues.isEmpty()) {
            return "";
        }
        return "Remaining jar issues for " + spec.getDisplayName() + ":\n" + joinLines(remainingIssues);
    }

    private static String restartHint(DependencySpec spec, DependencyStatus after) {
        if (spec == null || !spec.isRestartRequired() || after == null || after.isPresent()) {
            return "";
        }
        return "Required jars are in place, but Fiji must restart before class-based verification can pass.";
    }

    private static String restoreGuidance(DependencySpec spec, List<String> actions) {
        if (!DependencyRegistry.hasDisabledJarActions(actions)) {
            return "";
        }
        return DependencyRegistry.disabledJarRestoreGuidance(
                spec == null ? "" : spec.getDisplayName());
    }

    private static String appendDetails(String... details) {
        StringBuilder sb = new StringBuilder();
        if (details != null) {
            for (String detail : details) {
                appendDetail(sb, detail);
            }
        }
        return sb.toString();
    }

    private static void appendDetail(StringBuilder sb, String detail) {
        if (detail == null || detail.trim().isEmpty()) {
            return;
        }
        if (sb.length() > 0) {
            sb.append("\n\n");
        }
        sb.append(detail.trim());
    }

    private static String buildInstallMessage(String summary, String detail, DependencyStatus after) {
        StringBuilder sb = new StringBuilder();
        if (summary != null && !summary.trim().isEmpty()) {
            sb.append(summary.trim());
        }
        if (detail != null && !detail.trim().isEmpty()) {
            if (sb.length() > 0) {
                sb.append("\n\n");
            }
            sb.append(detail.trim());
        }
        String statusMessage = formatStatusMessage(after);
        if (!statusMessage.isEmpty()) {
            if (sb.length() > 0) {
                sb.append("\n\n");
            }
            sb.append(statusMessage);
        }
        return sb.toString();
    }

    private static String formatStatusMessage(DependencyStatus status) {
        if (status == null) {
            return "Status is unavailable.";
        }
        String detail = status.getDetailMessage();
        String label;
        if (status.isPresent()) {
            label = "Present";
        } else if (status.isError()) {
            label = "Error";
        } else if (status.isMissing()) {
            label = "Missing";
        } else {
            label = "Status unavailable";
        }
        if (detail == null || detail.trim().isEmpty()) {
            return label + ".";
        }
        return label + ": " + detail.trim();
    }

    private static String joinLines(List<String> lines) {
        if (lines == null || lines.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (String line : lines) {
            if (line == null || line.trim().isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(line.trim());
        }
        return sb.toString();
    }

    private static String safeMessage(Throwable throwable) {
        if (throwable == null || throwable.getMessage() == null || throwable.getMessage().trim().isEmpty()) {
            return "no detail message";
        }
        return throwable.getMessage().trim();
    }
}
