package flash.pipeline.runtime;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Runtime dependency facade used by FLASH_Pipeline.
 */
public final class DependencyService {

    interface StatusSnapshotProvider {
        EnumMap<DependencyId, DependencyStatus> snapshot(List<DependencySpec> specs);
    }

    private static final ProgressCallback NO_PROGRESS = new ProgressCallback() {
        @Override
        public void onProgress(DependencySpec spec, String message) {
            // UI progress can be added by callers later; fixes still report final details.
        }
    };

    private static final StatusSnapshotProvider REGISTRY_STATUS_PROVIDER = new StatusSnapshotProvider() {
        @Override
        public EnumMap<DependencyId, DependencyStatus> snapshot(List<DependencySpec> specs) {
            return DependencyRegistry.snapshotStatuses(specs);
        }
    };

    private static final Map<DependencyId, DependencyFixer> DEFAULT_FIXERS = buildFixers();

    private final StatusSnapshotProvider statusSnapshotProvider;
    private final Map<DependencyId, DependencyFixer> fixers;

    public static final class DialogAction {
        public static final String VERIFY = "verify";
        public static final String AUTO_FIX = "auto_fix";

        private final String actionId;
        private final String label;

        DialogAction(String actionId, String label) {
            this.actionId = actionId == null ? "" : actionId.trim();
            this.label = label == null ? "" : label;
        }

        public String getActionId() {
            return actionId;
        }

        public String getLabel() {
            return label;
        }
    }

    public static final class DialogRow {
        private final DependencySpec spec;
        private final DependencyStatus status;
        private final String sectionLabel;
        private final String statusLabel;
        private final String statusDetail;
        private final String blockedLabel;
        private final String explanation;
        private final String restartLabel;
        private final String actionNote;
        private final List<DialogAction> actions;

        DialogRow(DependencySpec spec,
                  DependencyStatus status,
                  String sectionLabel,
                  String statusLabel,
                  String statusDetail,
                  String blockedLabel,
                  String explanation,
                  String restartLabel,
                  String actionNote,
                  List<DialogAction> actions) {
            this.spec = spec;
            this.status = status;
            this.sectionLabel = sectionLabel == null ? "" : sectionLabel;
            this.statusLabel = statusLabel == null ? "" : statusLabel;
            this.statusDetail = statusDetail == null ? "" : statusDetail;
            this.blockedLabel = blockedLabel == null ? "" : blockedLabel;
            this.explanation = explanation == null ? "" : explanation;
            this.restartLabel = restartLabel == null ? "" : restartLabel;
            this.actionNote = actionNote == null ? "" : actionNote;
            this.actions = actions == null || actions.isEmpty()
                    ? Collections.<DialogAction>emptyList()
                    : Collections.unmodifiableList(new ArrayList<DialogAction>(actions));
        }

        public DependencySpec getSpec() {
            return spec;
        }

        public DependencyStatus getStatus() {
            return status;
        }

        public String getSectionLabel() {
            return sectionLabel;
        }

        public String getStatusLabel() {
            return statusLabel;
        }

        public String getStatusDetail() {
            return statusDetail;
        }

        public String getBlockedLabel() {
            return blockedLabel;
        }

        public String getExplanation() {
            return explanation;
        }

        public String getRestartLabel() {
            return restartLabel;
        }

        public String getActionNote() {
            return actionNote;
        }

        public List<DialogAction> getActions() {
            return actions;
        }
    }

    private EnumMap<DependencyId, DependencyStatus> statusCache;

    public DependencyService() {
        this(REGISTRY_STATUS_PROVIDER, DEFAULT_FIXERS);
    }

    DependencyService(StatusSnapshotProvider statusSnapshotProvider) {
        this(statusSnapshotProvider, DEFAULT_FIXERS);
    }

    /*
     * Package-private seam for tests: keeps status/fixer behavior injectable
     * without running real Fiji probes or repair code.
     */
    DependencyService(StatusSnapshotProvider statusSnapshotProvider,
                      Map<DependencyId, DependencyFixer> fixers) {
        this.statusSnapshotProvider = statusSnapshotProvider == null
                ? REGISTRY_STATUS_PROVIDER
                : statusSnapshotProvider;
        this.fixers = fixers == null ? DEFAULT_FIXERS : Collections.unmodifiableMap(new LinkedHashMap<DependencyId, DependencyFixer>(fixers));
    }

    public synchronized void invalidateStatusCache() {
        statusCache = null;
    }

    public synchronized EnumMap<DependencyId, DependencyStatus> refreshStatuses() {
        statusCache = statusSnapshotProvider.snapshot(DependencyRegistry.all());
        return new EnumMap<DependencyId, DependencyStatus>(statusCache);
    }

    public synchronized DependencyStatus getStatus(DependencyId id) {
        ensureCache();
        return statusCache.get(id);
    }

    public List<DependencySpec> getAllDependencies() {
        return DependencyRegistry.all();
    }

    public List<DependencySpec> getVisibleDependencies() {
        List<DependencySpec> visible = new ArrayList<DependencySpec>();
        for (DependencySpec spec : DependencyRegistry.all()) {
            if (spec.isVisibleInDependenciesDialog()) {
                visible.add(spec);
            }
        }
        return visible;
    }

    public List<DependencySpec> getFixableDependencies() {
        List<DependencySpec> fixable = new ArrayList<DependencySpec>();
        for (DependencySpec spec : DependencyRegistry.all()) {
            if (hasRegisteredFixer(spec)) {
                fixable.add(spec);
            }
        }
        return fixable;
    }

    public synchronized List<DependencySpec> getMissingFixableDependencies() {
        ensureCache();
        List<DependencySpec> missing = new ArrayList<DependencySpec>();
        for (DependencySpec spec : getFixableDependencies()) {
            DependencyStatus status = statusCache.get(spec.getId());
            if (status != null && status.needsAttention()) {
                missing.add(spec);
            }
        }
        return missing;
    }

    public synchronized List<DialogRow> getDialogRows() {
        ensureCache();
        List<DialogRow> rows = new ArrayList<DialogRow>();
        for (DependencySpec spec : getVisibleDependencies()) {
            DependencyStatus status = statusCache.get(spec.getId());
            rows.add(buildDialogRow(spec, status));
        }
        return rows;
    }

    public synchronized List<DialogRow> getDialogRowsNeedingAttention() {
        ensureCache();
        List<DialogRow> rows = new ArrayList<DialogRow>();
        for (DependencySpec spec : getVisibleDependencies()) {
            DependencyStatus status = statusCache.get(spec.getId());
            if (status != null && status.needsAttention()) {
                rows.add(buildDialogRow(spec, status));
            }
        }
        return rows;
    }

    public synchronized boolean hasVisibleDependenciesNeedingAttention() {
        return !getDialogRowsNeedingAttention().isEmpty();
    }

    public synchronized DependencyFixPlan planFixAll() {
        ensureCache();
        List<DependencySpec> toFix = new ArrayList<DependencySpec>();
        List<DependencySpec> alreadySatisfied = new ArrayList<DependencySpec>();
        List<DependencySpec> blocked = new ArrayList<DependencySpec>();
        long totalBytes = 0L;
        boolean restartRequired = false;

        for (DependencySpec spec : DependencyRegistry.all()) {
            DependencyStatus status = statusCache.get(spec.getId());
            if (status == null) {
                continue;
            }
            if (hasRegisteredFixer(spec)) {
                if (status.isPresent()) {
                    alreadySatisfied.add(spec);
                } else if (status.needsAttention()) {
                    toFix.add(spec);
                    totalBytes += defaultEstimatedBytes(spec);
                    restartRequired = restartRequired || spec.isRestartRequired();
                }
            } else if (spec.isVisibleInDependenciesDialog() && status.needsAttention()) {
                blocked.add(spec);
            }
        }

        sortByFixExecutionOrder(toFix);
        sortByDisplayName(alreadySatisfied);
        sortByDisplayName(blocked);
        return new DependencyFixPlan(toFix, alreadySatisfied, blocked, totalBytes, restartRequired);
    }

    public synchronized DependencyFixResult runDialogAction(DependencyId id, String actionId) {
        DependencySpec spec = DependencyRegistry.get(id);
        if (spec == null) {
            return new DependencyFixResult(id, false, false, false, "Unknown dependency id.");
        }

        String normalizedAction = actionId == null ? "" : actionId.trim();
        if (normalizedAction.isEmpty()) {
            normalizedAction = DialogAction.AUTO_FIX;
        }

        if (DialogAction.VERIFY.equals(normalizedAction)) {
            return verifyDependency(id);
        }

        DependencyFixer fixer = fixers.get(id);
        if (!spec.isFixableInApp() || fixer == null) {
            return new DependencyFixResult(id, false, false, false,
                    spec.getNonFixableReason().isEmpty() ? "No in-app fixer is available." : spec.getNonFixableReason());
        }

        if (!isKnownFixAction(spec, normalizedAction)) {
            return new DependencyFixResult(id, false, false, false, "Unknown dependency action: " + normalizedAction);
        }

        DependencyFixResult result = fixer.apply(spec, normalizedAction, NO_PROGRESS);
        invalidateStatusCache();
        return result;
    }

    private static Map<DependencyId, DependencyFixer> buildFixers() {
        LinkedHashMap<DependencyId, DependencyFixer> fixers = new LinkedHashMap<DependencyId, DependencyFixer>();
        fixers.put(DependencyId.BIO_FORMATS_RUNTIME, new FijiPluginRuntimeFixer(5));
        fixers.put(DependencyId.OBJECTS_COUNTER_3D, new FijiPluginRuntimeFixer(6));
        fixers.put(DependencyId.STARDIST_RUNTIME, new StarDistRuntimeFixer());
        fixers.put(DependencyId.TENSORFLOW_NATIVE_RUNTIME, new TensorFlowNativeRuntimeFixer());
        fixers.put(DependencyId.APACHE_POI_RUNTIME, new ExcelRuntimeFixer());
        fixers.put(DependencyId.JTS_CORE, new JtsCoreFixer());
        fixers.put(DependencyId.EPFL_PSF_GENERATOR_RUNTIME, new FijiPluginRuntimeFixer(19));
        fixers.put(DependencyId.DECONV_CLIJ2_RUNTIME, new FijiPluginRuntimeFixer(20));
        fixers.put(DependencyId.DECONVOLUTIONLAB2_RUNTIME, new FijiPluginRuntimeFixer(21));
        fixers.put(DependencyId.ITERATIVE_DECONVOLVE_3D_RUNTIME, new FijiPluginRuntimeFixer(22));
        fixers.put(DependencyId.COLOC2_RUNTIME, new FijiPluginRuntimeFixer(14));
        fixers.put(DependencyId.IMGLIB2_ALGORITHM_RUNTIME, new FijiPluginRuntimeFixer(15));
        fixers.put(DependencyId.IMGLIB2_FFT_RUNTIME, new FijiPluginRuntimeFixer(16));
        fixers.put(DependencyId.JTRANSFORMS_RUNTIME, new FijiPluginRuntimeFixer(17));
        fixers.put(DependencyId.ORIENTATIONJ_RUNTIME, new FijiPluginRuntimeFixer(18));
        fixers.put(DependencyId.CELLPOSE_RUNTIME, new CellposeRuntimeFixer());
        return Collections.unmodifiableMap(fixers);
    }

    private boolean hasRegisteredFixer(DependencySpec spec) {
        return spec != null && spec.isFixableInApp() && fixers.containsKey(spec.getId());
    }

    private static boolean isKnownFixAction(DependencySpec spec, String actionId) {
        if (DialogAction.AUTO_FIX.equals(actionId)) {
            return true;
        }
        for (DependencySpec.InstallOption option : spec.getInstallOptions()) {
            if (option.getActionId().equals(actionId)) {
                return true;
            }
        }
        return false;
    }

    private DialogRow buildDialogRow(DependencySpec spec, DependencyStatus status) {
        return new DialogRow(
                spec,
                status,
                spec.getDialogSectionLabel(),
                formatStatusLabel(status),
                formatStatusDetail(status),
                buildBlockedLabel(spec),
                spec.getDescription(),
                spec.isRestartRequired()
                        ? "Restart Fiji after repair: required."
                        : "Restart Fiji after repair: not required.",
                buildActionNote(spec, status),
                buildActions(spec, status));
    }

    private static long defaultEstimatedBytes(DependencySpec spec) {
        return spec == null ? 0L : spec.getApproxDownloadSizeBytes();
    }

    private synchronized void ensureCache() {
        if (statusCache == null) {
            refreshStatuses();
        }
    }

    private synchronized DependencyFixResult verifyDependency(DependencyId id) {
        invalidateStatusCache();
        DependencyStatus after = getStatus(id);
        boolean success = after != null && after.isPresent();
        return new DependencyFixResult(id, true, success, false, formatStatusMessage(after));
    }

    private List<DialogAction> buildActions(DependencySpec spec, DependencyStatus status) {
        List<DialogAction> actions = new ArrayList<DialogAction>();
        boolean present = status != null && status.isPresent();
        if (status != null && status.isChecking()) {
            actions.add(new DialogAction(DialogAction.VERIFY, "Refresh Status"));
            return actions;
        }

        if (present) {
            String verifyLabel = resolveVerifyLabel(spec, status);
            if (!verifyLabel.isEmpty()) {
                actions.add(new DialogAction(DialogAction.VERIFY, verifyLabel));
            }
        }

        if (hasRegisteredFixer(spec) && !spec.getInstallOptions().isEmpty()) {
            for (DependencySpec.InstallOption option : spec.getInstallOptions()) {
                String label = option.formatButtonLabel();
                if (!label.isEmpty()) {
                    actions.add(new DialogAction(option.getActionId(), label));
                }
            }
            return actions;
        }

        if (!present && hasRegisteredFixer(spec)) {
            String fixLabel = spec.formatButtonLabel(status);
            if (fixLabel != null && !fixLabel.trim().isEmpty()) {
                actions.add(new DialogAction(DialogAction.AUTO_FIX, fixLabel));
            }
        }

        return actions;
    }

    private static String resolveVerifyLabel(DependencySpec spec, DependencyStatus status) {
        String label = spec.formatButtonLabel(status);
        if (label != null && !label.trim().isEmpty()) {
            return label.trim();
        }
        return "Verify";
    }

    private static String buildBlockedLabel(DependencySpec spec) {
        List<String> features = spec.getAffectedFeatures();
        if (features == null || features.isEmpty()) {
            return "Blocks: Feature scope not declared.";
        }
        StringBuilder sb = new StringBuilder("Blocks: ");
        for (String feature : features) {
            if (feature == null || feature.trim().isEmpty()) {
                continue;
            }
            if (sb.length() > "Blocks: ".length()) {
                sb.append(", ");
            }
            sb.append(feature.trim());
        }
        if (sb.length() == "Blocks: ".length()) {
            return "Blocks: Feature scope not declared.";
        }
        return sb.toString();
    }

    private static String buildActionNote(DependencySpec spec, DependencyStatus status) {
        if (status != null && status.isPresent()) {
            return "";
        }
        if (!spec.isFixableInApp()) {
            String reason = spec.getNonFixableReason() == null ? "" : spec.getNonFixableReason().trim();
            if (reason.isEmpty()) {
                return "Not fixable in-app - see README.";
            }
            return "Not fixable in-app - see README.\n" + reason;
        }
        return "";
    }

    private static String formatStatusLabel(DependencyStatus status) {
        if (status == null) {
            return "Status unavailable";
        }
        if (status.isPresent()) {
            return "Present";
        }
        if (status.isChecking()) {
            return "Checking";
        }
        if (status.isError()) {
            return "Error";
        }
        if (status.isMissing()) {
            return "Missing";
        }
        return "Status unavailable";
    }

    private static String formatStatusDetail(DependencyStatus status) {
        if (status == null) {
            return "Status is unavailable.";
        }
        String detail = status.getDetailMessage();
        if (detail == null || detail.trim().isEmpty()) {
            return "";
        }
        return detail.trim();
    }

    private static String formatStatusMessage(DependencyStatus status) {
        if (status == null) {
            return "Status is unavailable.";
        }
        String detail = formatStatusDetail(status);
        if (detail.isEmpty()) {
            return formatStatusLabel(status) + ".";
        }
        return formatStatusLabel(status) + ": " + detail;
    }

    private static void sortByDisplayName(List<DependencySpec> specs) {
        Collections.sort(specs, new Comparator<DependencySpec>() {
            @Override
            public int compare(DependencySpec left, DependencySpec right) {
                return left.getDisplayName().compareToIgnoreCase(right.getDisplayName());
            }
        });
    }

    private void sortByFixExecutionOrder(List<DependencySpec> specs) {
        Collections.sort(specs, new Comparator<DependencySpec>() {
            @Override
            public int compare(DependencySpec left, DependencySpec right) {
                DependencyFixer leftFixer = fixers.get(left.getId());
                DependencyFixer rightFixer = fixers.get(right.getId());
                int leftOrder = leftFixer == null ? Integer.MAX_VALUE : leftFixer.getExecutionOrder();
                int rightOrder = rightFixer == null ? Integer.MAX_VALUE : rightFixer.getExecutionOrder();
                if (leftOrder != rightOrder) {
                    return leftOrder < rightOrder ? -1 : 1;
                }
                if (left.isRestartRequired() != right.isRestartRequired()) {
                    return left.isRestartRequired() ? 1 : -1;
                }
                return left.getDisplayName().compareToIgnoreCase(right.getDisplayName());
            }
        });
    }
}
