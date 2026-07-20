package flash.pipeline.runtime;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Declarative runtime dependency definition.
 */
public final class DependencySpec {

    public enum Criticality {
        OPTIONAL_FEATURE,
        STARTUP_CRITICAL,
        INTERNAL_INTEGRITY
    }

    public enum FixerStrategy {
        NONE,
        DIRECT_JAR_DOWNLOAD,
        FIJI_UPDATER,
        PYTHON_SETUP,
        MANUAL_PLUGIN_REINSTALL
    }

    public interface Probe {
        DependencyStatus probe(DependencyRegistry.ProbeContext context);
    }

    public static final class InstallOption {
        private final String actionId;
        private final String buttonLabelTemplate;
        private final long approxDownloadSizeBytes;

        public InstallOption(String actionId, String buttonLabelTemplate, long approxDownloadSizeBytes) {
            this.actionId = actionId == null ? "" : actionId.trim();
            this.buttonLabelTemplate = buttonLabelTemplate == null ? "" : buttonLabelTemplate;
            this.approxDownloadSizeBytes = approxDownloadSizeBytes;
        }

        public String getActionId() {
            return actionId;
        }

        public String getButtonLabelTemplate() {
            return buttonLabelTemplate;
        }

        public long getApproxDownloadSizeBytes() {
            return approxDownloadSizeBytes;
        }

        public String formatButtonLabel() {
            if (buttonLabelTemplate.isEmpty()) {
                return "";
            }
            String sizeSuffix = approxDownloadSizeBytes > 0
                    ? " " + DependencyRegistry.formatApproxSize(approxDownloadSizeBytes)
                    : "";
            if (buttonLabelTemplate.contains("%s")) {
                return String.format(Locale.ROOT, buttonLabelTemplate, sizeSuffix);
            }
            return buttonLabelTemplate + sizeSuffix;
        }
    }

    public static final class JarRequirement {
        private final String label;
        private final String expectedFile;
        private final String matchPrefix;
        private final String folder;
        private final String downloadUrl;
        private final String expectedSha1;
        private final boolean acceptAnyExisting;

        public JarRequirement(String label,
                              String expectedFile,
                              String matchPrefix,
                              String folder,
                              String downloadUrl,
                              boolean acceptAnyExisting) {
            this(label, expectedFile, matchPrefix, folder, downloadUrl, "", acceptAnyExisting);
        }

        public JarRequirement(String label,
                              String expectedFile,
                              String matchPrefix,
                              String folder,
                              String downloadUrl,
                              String expectedSha1,
                              boolean acceptAnyExisting) {
            this.label = label;
            this.expectedFile = expectedFile;
            this.matchPrefix = matchPrefix;
            this.folder = folder;
            this.downloadUrl = downloadUrl;
            this.expectedSha1 = expectedSha1 == null ? "" : expectedSha1.trim().toLowerCase(Locale.ROOT);
            this.acceptAnyExisting = acceptAnyExisting;
        }

        public String getLabel() {
            return label;
        }

        public String getExpectedFile() {
            return expectedFile;
        }

        public String getMatchPrefix() {
            return matchPrefix;
        }

        public String getFolder() {
            return folder;
        }

        public String getDownloadUrl() {
            return downloadUrl;
        }

        public String getExpectedSha1() {
            return expectedSha1;
        }

        public boolean isAcceptAnyExisting() {
            return acceptAnyExisting;
        }
    }

    public static Builder builder(DependencyId id, String displayName) {
        return new Builder(id, displayName);
    }

    private final DependencyId id;
    private final String displayName;
    private final String description;
    private final List<String> affectedFeatures;
    private final Criticality criticality;
    private final String detectionStrategyLabel;
    private final Probe probe;
    private final FixerStrategy fixerStrategy;
    private final long approxDownloadSizeBytes;
    private final boolean restartRequired;
    private final String fixButtonLabelTemplate;
    private final String presentButtonLabel;
    private final boolean fixableInApp;
    private final String nonFixableReason;
    private final boolean visibleInDependenciesDialog;
    private final String dialogSectionLabel;
    private final List<InstallOption> installOptions;
    private final List<JarRequirement> jarRequirements;
    private final List<String> jarIgnorePrefixes;

    private DependencySpec(Builder builder) {
        this.id = builder.id;
        this.displayName = builder.displayName;
        this.description = builder.description;
        this.affectedFeatures = copy(builder.affectedFeatures);
        this.criticality = builder.criticality;
        this.detectionStrategyLabel = builder.detectionStrategyLabel;
        this.probe = builder.probe;
        this.fixerStrategy = builder.fixerStrategy;
        this.approxDownloadSizeBytes = builder.approxDownloadSizeBytes;
        this.restartRequired = builder.restartRequired;
        this.fixButtonLabelTemplate = builder.fixButtonLabelTemplate;
        this.presentButtonLabel = builder.presentButtonLabel;
        this.fixableInApp = builder.fixableInApp;
        this.nonFixableReason = builder.nonFixableReason;
        this.visibleInDependenciesDialog = builder.visibleInDependenciesDialog;
        this.dialogSectionLabel = builder.dialogSectionLabel;
        this.installOptions = copy(builder.installOptions);
        this.jarRequirements = copy(builder.jarRequirements);
        this.jarIgnorePrefixes = copy(builder.jarIgnorePrefixes);
    }

    public DependencyId getId() {
        return id;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getDescription() {
        return description;
    }

    public List<String> getAffectedFeatures() {
        return affectedFeatures;
    }

    public Criticality getCriticality() {
        return criticality;
    }

    public String getDetectionStrategyLabel() {
        return detectionStrategyLabel;
    }

    public FixerStrategy getFixerStrategy() {
        return fixerStrategy;
    }

    public long getApproxDownloadSizeBytes() {
        return approxDownloadSizeBytes;
    }

    public boolean isRestartRequired() {
        return restartRequired;
    }

    public boolean isFixableInApp() {
        return fixableInApp;
    }

    public String getNonFixableReason() {
        return nonFixableReason;
    }

    public boolean isVisibleInDependenciesDialog() {
        return visibleInDependenciesDialog;
    }

    public String getDialogSectionLabel() {
        return dialogSectionLabel;
    }

    public List<InstallOption> getInstallOptions() {
        return installOptions;
    }

    public List<JarRequirement> getJarRequirements() {
        return jarRequirements;
    }

    public List<String> getJarIgnorePrefixes() {
        return jarIgnorePrefixes;
    }

    public boolean isOptionalFeature() {
        return criticality == Criticality.OPTIONAL_FEATURE;
    }

    public DependencyStatus probe(DependencyRegistry.ProbeContext context) {
        return probe.probe(context);
    }

    public String formatButtonLabel(DependencyStatus status) {
        if (!fixableInApp) {
            return null;
        }
        if (status != null && status.isPresent() && presentButtonLabel != null && !presentButtonLabel.isEmpty()) {
            return presentButtonLabel;
        }
        String sizeSuffix = approxDownloadSizeBytes > 0
                ? " " + DependencyRegistry.formatApproxSize(approxDownloadSizeBytes)
                : "";
        if (fixButtonLabelTemplate == null || fixButtonLabelTemplate.isEmpty()) {
            return null;
        }
        if (fixButtonLabelTemplate.contains("%s")) {
            return String.format(Locale.ROOT, fixButtonLabelTemplate, sizeSuffix);
        }
        return fixButtonLabelTemplate + sizeSuffix;
    }

    private static <T> List<T> copy(List<T> source) {
        if (source == null || source.isEmpty()) {
            return Collections.emptyList();
        }
        return Collections.unmodifiableList(new ArrayList<T>(source));
    }

    public static final class Builder {
        private final DependencyId id;
        private final String displayName;
        private String description = "";
        private List<String> affectedFeatures = Collections.emptyList();
        private Criticality criticality = Criticality.OPTIONAL_FEATURE;
        private String detectionStrategyLabel = "";
        private Probe probe = new Probe() {
            @Override
            public DependencyStatus probe(DependencyRegistry.ProbeContext context) {
                return DependencyStatus.error("No dependency probe configured.");
            }
        };
        private FixerStrategy fixerStrategy = FixerStrategy.NONE;
        private long approxDownloadSizeBytes = 0L;
        private boolean restartRequired = false;
        private String fixButtonLabelTemplate = "";
        private String presentButtonLabel = "";
        private boolean fixableInApp = false;
        private String nonFixableReason = "";
        private boolean visibleInDependenciesDialog = true;
        private String dialogSectionLabel = "";
        private List<InstallOption> installOptions = Collections.emptyList();
        private List<JarRequirement> jarRequirements = Collections.emptyList();
        private List<String> jarIgnorePrefixes = Collections.emptyList();

        private Builder(DependencyId id, String displayName) {
            this.id = id;
            this.displayName = displayName;
        }

        public Builder description(String description) {
            this.description = description == null ? "" : description;
            return this;
        }

        public Builder affectedFeatures(String... features) {
            List<String> items = new ArrayList<String>();
            if (features != null) {
                for (String feature : features) {
                    if (feature != null && !feature.trim().isEmpty()) {
                        items.add(feature.trim());
                    }
                }
            }
            this.affectedFeatures = items;
            return this;
        }

        public Builder criticality(Criticality criticality) {
            this.criticality = criticality == null ? Criticality.OPTIONAL_FEATURE : criticality;
            return this;
        }

        public Builder detectionStrategyLabel(String detectionStrategyLabel) {
            this.detectionStrategyLabel = detectionStrategyLabel == null ? "" : detectionStrategyLabel;
            return this;
        }

        public Builder probe(Probe probe) {
            if (probe != null) {
                this.probe = probe;
            }
            return this;
        }

        public Builder fixerStrategy(FixerStrategy fixerStrategy) {
            this.fixerStrategy = fixerStrategy == null ? FixerStrategy.NONE : fixerStrategy;
            return this;
        }

        public Builder approxDownloadSizeBytes(long approxDownloadSizeBytes) {
            this.approxDownloadSizeBytes = approxDownloadSizeBytes;
            return this;
        }

        public Builder restartRequired(boolean restartRequired) {
            this.restartRequired = restartRequired;
            return this;
        }

        public Builder fixButtonLabelTemplate(String fixButtonLabelTemplate) {
            this.fixButtonLabelTemplate = fixButtonLabelTemplate == null ? "" : fixButtonLabelTemplate;
            return this;
        }

        public Builder presentButtonLabel(String presentButtonLabel) {
            this.presentButtonLabel = presentButtonLabel == null ? "" : presentButtonLabel;
            return this;
        }

        public Builder fixableInApp(boolean fixableInApp) {
            this.fixableInApp = fixableInApp;
            return this;
        }

        public Builder nonFixableReason(String nonFixableReason) {
            this.nonFixableReason = nonFixableReason == null ? "" : nonFixableReason;
            return this;
        }

        public Builder visibleInDependenciesDialog(boolean visibleInDependenciesDialog) {
            this.visibleInDependenciesDialog = visibleInDependenciesDialog;
            return this;
        }

        public Builder dialogSectionLabel(String dialogSectionLabel) {
            this.dialogSectionLabel = dialogSectionLabel == null ? "" : dialogSectionLabel;
            return this;
        }

        public Builder installOptions(List<InstallOption> installOptions) {
            this.installOptions = installOptions == null ? Collections.<InstallOption>emptyList() : installOptions;
            return this;
        }

        public Builder jarRequirements(List<JarRequirement> jarRequirements) {
            this.jarRequirements = jarRequirements == null ? Collections.<JarRequirement>emptyList() : jarRequirements;
            return this;
        }

        public Builder jarIgnorePrefixes(List<String> jarIgnorePrefixes) {
            this.jarIgnorePrefixes = jarIgnorePrefixes == null ? Collections.<String>emptyList() : jarIgnorePrefixes;
            return this;
        }

        public DependencySpec build() {
            return new DependencySpec(this);
        }
    }
}
