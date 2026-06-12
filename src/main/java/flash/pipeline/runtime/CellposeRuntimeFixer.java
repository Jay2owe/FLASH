package flash.pipeline.runtime;

import flash.pipeline.cellpose.CellposeRuntime;

import java.util.Collections;

final class CellposeRuntimeFixer implements DependencyFixer {

    @Override
    public int getExecutionOrder() {
        return 90;
    }

    @Override
    public DependencyFixResult apply(DependencySpec spec, String actionId, ProgressCallback callback) {
        String normalizedAction = actionId == null ? "" : actionId.trim();
        if (normalizedAction.isEmpty() || DependencyService.DialogAction.AUTO_FIX.equals(normalizedAction)) {
            normalizedAction = "cellpose_cpu";
        }

        DependencyStatus before = probe(spec);
        if (callback != null) {
            callback.onProgress(spec, "Installing " + spec.getDisplayName() + "...");
        }

        CellposeRuntime.InstallResult install;
        if ("cellpose_cpu".equals(normalizedAction)) {
            install = CellposeRuntime.installManagedCpu();
        } else if ("cellpose_gpu".equals(normalizedAction)) {
            install = CellposeRuntime.installManagedGpu();
        } else {
            return new DependencyFixResult(DependencyId.CELLPOSE_RUNTIME, false, false, false,
                    "Unknown Cellpose action: " + normalizedAction);
        }

        DependencyStatus after = probe(spec);
        boolean success = install.success && after != null && after.isPresent();
        return new DependencyFixResult(
                DependencyId.CELLPOSE_RUNTIME,
                true,
                success,
                spec.isRestartRequired() && before != null && !before.isPresent() && success,
                buildInstallMessage(install.message, install.details, after));
    }

    private static DependencyStatus probe(DependencySpec spec) {
        java.util.EnumMap<DependencyId, DependencyStatus> statuses =
                DependencyRegistry.snapshotStatuses(Collections.singletonList(spec));
        return statuses.get(spec.getId());
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
        String label = status.isPresent()
                ? "Present"
                : (status.isChecking() ? "Checking" : (status.isError() ? "Error" : "Missing"));
        String detail = status.getDetailMessage();
        if (detail == null || detail.trim().isEmpty()) {
            return label + ".";
        }
        return label + ": " + detail.trim();
    }
}
