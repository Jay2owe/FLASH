package flash.pipeline.bin;

import flash.pipeline.analyses.CreateBinFileAnalysis;
import flash.pipeline.cli.CLIArgumentParser;
import flash.pipeline.cli.CLIConfig;
import flash.pipeline.io.FlashProjectLayout;
import flash.pipeline.zslice.ZSliceMode;
import ij.IJ;
import ij.Macro;

import java.awt.GraphicsEnvironment;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Routes missing bin setup to full setup, filtered setup, or direct entry. */
public final class BinSetupDispatcher {
    public enum Outcome { COMPLETED, CANCELLED, BLOCKED }

    public static final String SOURCE_LOADED = "loaded";
    public static final String SOURCE_PROMPTED_FULL = "prompted-full";
    public static final String SOURCE_PROMPTED_PARTIAL = "prompted-partial";
    public static final String SOURCE_BYPASS_DIALOG = "bypass-dialog";
    public static final String SOURCE_CLI_ARGUMENT = "cli-argument";

    private static final ThreadLocal<EnumMap<BinField, String>> lastFieldSources =
            new ThreadLocal<EnumMap<BinField, String>>();
    private static final ThreadLocal<Outcome> lastOutcome = new ThreadLocal<Outcome>();
    private static final ThreadLocal<String> lastOutcomeReason = new ThreadLocal<String>();

    interface HeadlessProbe {
        boolean isHeadlessOrMacro();
    }

    interface Chooser {
        BinSetupChooser.Choice show(String analysisDisplayName, Set<BinField> missing, boolean showRoiTip);
    }

    interface WizardRunner {
        void run(String directory, Set<BinField> fields);
    }

    interface BypassRunner {
        boolean show(String directory, Set<BinField> fields);
    }

    interface ValidationContextProvider {
        ChannelConfigIO.ValidationContext context(String directory, ChannelConfig cfg);
    }

    private static HeadlessProbe headlessProbe = new HeadlessProbe() {
        @Override public boolean isHeadlessOrMacro() {
            return GraphicsEnvironment.isHeadless()
                    || IJ.getInstance() == null
                    || Macro.getOptions() != null;
        }
    };
    private static Chooser chooser = new Chooser() {
        @Override public BinSetupChooser.Choice show(String analysisDisplayName,
                                                     Set<BinField> missing,
                                                     boolean showRoiTip) {
            return BinSetupChooser.show(analysisDisplayName, missing, showRoiTip);
        }
    };
    private static WizardRunner wizardRunner = new WizardRunner() {
        @Override public void run(String directory, Set<BinField> fields) {
            new CreateBinFileAnalysis().executeFiltered(directory, fields);
        }
    };
    private static BypassRunner bypassRunner = new BypassRunner() {
        @Override public boolean show(String directory, Set<BinField> fields) {
            return BinBypassDialog.show(directory, fields);
        }
    };
    private static ValidationContextProvider validationContextProvider =
            new ValidationContextProvider() {
                @Override public ChannelConfigIO.ValidationContext context(
                        String directory, ChannelConfig cfg) {
                    return BinConfigIO.completionValidationContext(directory, cfg, false);
                }
            };

    private BinSetupDispatcher() {}

    public static Outcome ensure(String directory, String analysisDisplayName,
                                 Set<BinField> required, boolean benefitsFromRois) {
        return ensure(directory, analysisDisplayName, required, benefitsFromRois, false, null);
    }

    public static Outcome ensure(String directory, String analysisDisplayName,
                                 Set<BinField> required, boolean benefitsFromRois,
                                 boolean suppressDialogs) {
        return ensure(directory, analysisDisplayName, required, benefitsFromRois, suppressDialogs, null);
    }

    public static Outcome ensure(String directory, String analysisDisplayName,
                                 Set<BinField> required, boolean benefitsFromRois,
                                 boolean suppressDialogs, CLIConfig cli) {
        clearLastFieldSources();
        BinConfig existing = BinConfigIO.readPartialFromDirectory(directory);
        EnumSet<BinField> missing = missingFields(existing, required);
        EnumMap<BinField, String> sources = loadedSources(existing, required);
        addChannelNamesIfContextNeedsThem(existing, missing);
        ChannelConfig rawExisting = readRawConfiguration(directory);
        ChannelConfigIO.ValidationResult semantic = relevantSemanticValidation(
                directory, rawExisting, required);
        addSemanticMissing(missing, sources, semantic);
        if (missing.isEmpty()) {
            recordOutcome(Outcome.COMPLETED, sources);
            return Outcome.COMPLETED;
        }

        // suppressDialogs only hides ordinary module option/completion dialogs.
        // Missing configuration still needs an interactive setup chooser when a UI is available.
        if (headlessProbe.isHeadlessOrMacro()) {
            try {
                applyHeadlessCliValues(directory, analysisDisplayName, existing, missing, cli);
            } catch (RuntimeException failure) {
                recordOutcome(Outcome.BLOCKED, sources, actionableReason(failure,
                        analysisDisplayName, missing));
                throw failure;
            }
            EnumSet<BinField> validationFields = copyOf(required);
            validationFields.addAll(missing);
            ChannelConfigIO.ValidationResult headlessValidation = relevantSemanticValidation(
                    directory, readRawConfiguration(directory), validationFields);
            if (!headlessValidation.isValid()) {
                IllegalArgumentException failure = invalidConfiguration(
                        analysisDisplayName, headlessValidation);
                recordOutcome(Outcome.BLOCKED, sources, failure.getMessage());
                throw failure;
            }
            recordMissingSources(sources, missing, SOURCE_CLI_ARGUMENT);
            recordOutcome(Outcome.COMPLETED, sources);
            return Outcome.COMPLETED;
        }

        boolean showRoiTip = benefitsFromRois && !RoiPresenceCheck.hasSavedRois(directory);
        BinSetupChooser.Choice choice = chooser.show(analysisDisplayName, copyOf(missing), showRoiTip);
        switch (choice) {
            case FULL:
                wizardRunner.run(directory, BinField.all());
                return completeIfConfigurationNowSatisfiesRequiredFields(
                        directory, analysisDisplayName, BinField.all(), sources,
                        BinField.all(), SOURCE_PROMPTED_FULL);
            case PARTIAL:
                wizardRunner.run(directory, copyOf(missing));
                return completeIfConfigurationNowSatisfiesRequiredFields(
                        directory, analysisDisplayName, required, sources,
                        missing, SOURCE_PROMPTED_PARTIAL);
            case BYPASS:
                if (bypassRunner.show(directory, copyOf(missing))) {
                    return completeIfConfigurationNowSatisfiesRequiredFields(
                            directory, analysisDisplayName, required, sources,
                            missing, SOURCE_BYPASS_DIALOG);
                }
                recordOutcome(Outcome.CANCELLED, sources,
                        cleanAnalysisName(analysisDisplayName) + " setup was cancelled.");
                return Outcome.CANCELLED;
            case CANCELLED:
            default:
                recordOutcome(Outcome.CANCELLED, sources,
                        semantic.isValid()
                                ? cleanAnalysisName(analysisDisplayName) + " setup was cancelled."
                                : "Invalid channel configuration: " + semantic.diagnostic());
                return Outcome.CANCELLED;
        }
    }

    private static Outcome completeIfConfigurationNowSatisfiesRequiredFields(
            String directory,
            String analysisDisplayName,
            Set<BinField> required,
            EnumMap<BinField, String> sources,
            Set<BinField> promptedFields,
            String promptedSource) {
        BinConfig updated = BinConfigIO.readPartialFromDirectory(directory);
        EnumSet<BinField> stillMissing = missingFields(updated, required);
        addChannelNamesIfContextNeedsThem(updated, stillMissing);
        EnumSet<BinField> semanticFields = copyOf(required);
        semanticFields.addAll(copyOf(promptedFields));
        ChannelConfigIO.ValidationResult semantic = relevantSemanticValidation(
                directory, readRawConfiguration(directory), semanticFields);
        addSemanticMissing(stillMissing, sources, semantic);
        if (!stillMissing.isEmpty()) {
            String reason = !semantic.isValid()
                    ? "Invalid channel configuration: " + semantic.diagnostic()
                    : cleanAnalysisName(analysisDisplayName)
                            + " setup did not complete. Still missing: " + stillMissing + ".";
            IJ.log("[FLASH] " + reason);
            // Closing the headed wizard is a user cancellation. Headless missing fields are
            // recorded separately as BLOCKED before their exception propagates.
            recordOutcome(Outcome.CANCELLED, sources, reason);
            return Outcome.CANCELLED;
        }
        recordMissingSources(sources, promptedFields, promptedSource);
        recordOutcome(Outcome.COMPLETED, sources);
        return Outcome.COMPLETED;
    }

    public static Map<BinField, String> getLastFieldSources() {
        EnumMap<BinField, String> sources = lastFieldSources.get();
        if (sources == null) {
            return Collections.emptyMap();
        }
        return new EnumMap<BinField, String>(sources);
    }

    public static Outcome getLastOutcome() {
        return lastOutcome.get();
    }

    /** Actionable reason for the most recent blocked or cancelled setup outcome. */
    public static String getLastOutcomeReason() {
        String reason = lastOutcomeReason.get();
        return reason == null ? "" : reason;
    }

    public static void clearLastFieldSources() {
        lastFieldSources.remove();
        lastOutcome.remove();
        lastOutcomeReason.remove();
    }

    private static void applyHeadlessCliValues(String directory,
                                               String analysisDisplayName,
                                               BinConfig cfg,
                                               EnumSet<BinField> missing,
                                               CLIConfig cli) {
        CLIConfig resolved = resolveCliConfig(cli);
        File settingsDir = FlashProjectLayout.forDirectory(directory).configurationWriteDir();
        ChannelConfig rawExisting = ChannelConfigIO.read(settingsDir);
        for (BinField field : missing) {
            if (resolved == null || !resolved.hasBinField(field)) {
                throw missingParameter(analysisDisplayName, field);
            }
            applyCliValue(analysisDisplayName, cfg, field, resolved.getBinFieldValue(field));
        }
        try {
            ChannelConfigIO.write(settingsDir, headlessPartialConfig(cfg, rawExisting, missing));
            if (missing.contains(BinField.FILTER_PRESETS)) {
                BinConfigIO.writeFilterMacrosFromConfig(settingsDir, cfg);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Cannot run " + cleanAnalysisName(analysisDisplayName)
                    + ": failed to write channel_config.json: " + e.getMessage(), e);
        }
    }

    private static ChannelConfig headlessPartialConfig(BinConfig cfg,
                                                       ChannelConfig rawExisting,
                                                       Set<BinField> supplied) {
        ChannelConfig next = ChannelConfigIO.fromBinConfig(cfg);
        next.complete = Boolean.FALSE;
        // fromBinConfig drops deconvolution (BinConfig has no deconv projection); carry the shared
        // optics forward from the freshly-read existing config.
        flash.pipeline.deconv.routing.DeconvConfigBridge.preserveRootDeconv(next, rawExisting);
        boolean preserveZSlice = supplied == null || !supplied.contains(BinField.Z_SLICE);
        if (preserveZSlice && rawExisting != null) {
            next.zSliceMode = rawExisting.zSliceMode;
            next.zSliceSelections.clear();
            if (rawExisting.zSliceSelections != null) {
                next.zSliceSelections.putAll(rawExisting.zSliceSelections);
            }
        }
        if (next.channels == null) return next;
        for (int i = 0; i < next.channels.size(); i++) {
            ChannelConfig.Channel channel = next.channels.get(i);
            ChannelConfig.Channel old = channelAt(rawExisting, i);
            preserveUnrequestedValues(channel, old, supplied);
            channel.status.clear();
            if (old != null && old.status != null) {
                channel.status.putAll(old.status);
            }
            seedMissingStatuses(channel);
            markSuppliedFields(channel, supplied);
        }
        return next;
    }

    private static void preserveUnrequestedValues(ChannelConfig.Channel channel,
                                                  ChannelConfig.Channel old,
                                                  Set<BinField> supplied) {
        if (channel == null || old == null) return;
        channel.markerId = old.markerId;
        channel.markerShape = old.markerShape;
        channel.markerCrowdingSensitive = old.markerCrowdingSensitive;
        if (!hasSupplied(supplied, BinField.CHANNEL_NAMES)) channel.name = old.name;
        if (!hasSupplied(supplied, BinField.CHANNEL_COLORS)) channel.color = old.color;
        if (!hasSupplied(supplied, BinField.OBJECT_THRESHOLDS)) channel.threshold = old.threshold;
        if (!hasSupplied(supplied, BinField.PARTICLE_SIZES)) channel.size = old.size;
        if (!hasSupplied(supplied, BinField.DISPLAY_MIN_MAX)) channel.minmax = old.minmax;
        if (!hasSupplied(supplied, BinField.INTENSITY_THRESHOLDS)) {
            channel.intensityThreshold = old.intensityThreshold;
        }
        if (!hasSupplied(supplied, BinField.SEGMENTATION_METHODS)) {
            channel.segmentationMethod = old.segmentationMethod;
        }
        if (!hasSupplied(supplied, BinField.FILTER_PRESETS)) channel.filterPreset = old.filterPreset;
        // Deconvolution is never a BinField-supplied value, so always carry it forward (like markerId)
        // so a CLI/headless partial save never silently wipes a configured deconvolution.
        flash.pipeline.deconv.routing.DeconvConfigBridge.preserveChannelDeconv(channel, old);
    }

    private static boolean hasSupplied(Set<BinField> supplied, BinField field) {
        return supplied != null && supplied.contains(field);
    }

    private static void seedMissingStatuses(ChannelConfig.Channel channel) {
        if (channel == null) return;
        seedStatus(channel, ChannelConfig.P_NAME);
        seedStatus(channel, ChannelConfig.P_COLOR);
        seedStatus(channel, ChannelConfig.P_MARKER);
        seedStatus(channel, ChannelConfig.P_THRESHOLD);
        seedStatus(channel, ChannelConfig.P_SIZE);
        seedStatus(channel, ChannelConfig.P_MINMAX);
        seedStatus(channel, ChannelConfig.P_INTENSITY);
        seedStatus(channel, ChannelConfig.P_SEGMENTATION);
        seedStatus(channel, ChannelConfig.P_FILTER);
    }

    private static void seedStatus(ChannelConfig.Channel channel, String property) {
        if (!channel.status.containsKey(property)) {
            channel.status.put(property, ChannelConfig.PropertyStatus.PENDING);
        }
    }

    private static void markSuppliedFields(ChannelConfig.Channel channel, Set<BinField> supplied) {
        if (channel == null || supplied == null) return;
        for (BinField field : supplied) {
            markSuppliedField(channel, field);
        }
    }

    private static void markSuppliedField(ChannelConfig.Channel channel, BinField field) {
        switch (field) {
            case CHANNEL_NAMES:
                channel.status.put(ChannelConfig.P_NAME, ChannelConfig.PropertyStatus.CONFIGURED);
                break;
            case CHANNEL_COLORS:
                channel.status.put(ChannelConfig.P_COLOR, ChannelConfig.PropertyStatus.CONFIGURED);
                break;
            case OBJECT_THRESHOLDS:
                channel.status.put(ChannelConfig.P_THRESHOLD, ChannelConfig.PropertyStatus.CONFIGURED);
                break;
            case PARTICLE_SIZES:
                channel.status.put(ChannelConfig.P_SIZE, ChannelConfig.PropertyStatus.CONFIGURED);
                break;
            case DISPLAY_MIN_MAX:
                channel.status.put(ChannelConfig.P_MINMAX, ChannelConfig.PropertyStatus.CONFIGURED);
                break;
            case INTENSITY_THRESHOLDS:
                channel.status.put(ChannelConfig.P_INTENSITY, ChannelConfig.PropertyStatus.CONFIGURED);
                break;
            case SEGMENTATION_METHODS:
                channel.status.put(ChannelConfig.P_SEGMENTATION, ChannelConfig.PropertyStatus.CONFIGURED);
                break;
            case FILTER_PRESETS:
                channel.status.put(ChannelConfig.P_FILTER, ChannelConfig.PropertyStatus.CONFIGURED);
                break;
            case Z_SLICE:
            default:
                break;
        }
    }

    private static ChannelConfig.Channel channelAt(ChannelConfig cfg, int channelIndex) {
        if (cfg == null || cfg.channels == null || channelIndex < 0
                || channelIndex >= cfg.channels.size()) {
            return null;
        }
        return cfg.channels.get(channelIndex);
    }

    private static CLIConfig resolveCliConfig(CLIConfig cli) {
        if (cli != null) return cli;
        String options = Macro.getOptions();
        if (!CLIArgumentParser.hasCliOptions(options)) return null;
        return CLIArgumentParser.parse(options);
    }

    private static void applyCliValue(String analysisDisplayName,
                                      BinConfig cfg,
                                      BinField field,
                                      String rawValue) {
        if (field == BinField.Z_SLICE) {
            cfg.zSliceMode = ZSliceMode.fromConfigToken(rawValue);
            cfg.zSliceConfigPresent = true;
            if (cfg.zSliceMode == ZSliceMode.FULL) {
                cfg.zSliceSelections.clear();
            }
            return;
        }

        List<String> values = splitCommaTokens(rawValue);
        if (field == BinField.CHANNEL_NAMES) {
            if (values.isEmpty()) throw missingParameter(analysisDisplayName, field);
            cfg.channelNames.clear();
            cfg.channelNames.addAll(values);
            return;
        }

        int channelCount = cfg.numChannels();
        if (channelCount <= 0) {
            throw missingParameter(analysisDisplayName, BinField.CHANNEL_NAMES);
        }
        if (values.size() != channelCount) {
            throw new IllegalArgumentException("Cannot run " + cleanAnalysisName(analysisDisplayName)
                    + ": parameter `" + CLIConfig.binFieldCliKey(field)
                    + "` must provide " + channelCount
                    + " comma-separated value(s). Pass `" + CLIConfig.binFieldCliKey(field)
                    + "=...` on the command line, or run interactively first.");
        }

        switch (field) {
            case CHANNEL_COLORS:
                replace(cfg.channelColors, values);
                break;
            case OBJECT_THRESHOLDS:
                replace(cfg.channelThresholds, values);
                break;
            case PARTICLE_SIZES:
                replace(cfg.channelSizes, values);
                break;
            case DISPLAY_MIN_MAX:
                replace(cfg.channelMinMax, values);
                break;
            case INTENSITY_THRESHOLDS:
                replace(cfg.channelIntensityThresholds, values);
                break;
            case SEGMENTATION_METHODS:
                replace(cfg.segmentationMethods, values);
                break;
            case FILTER_PRESETS:
                replace(cfg.channelFilterPresets, values);
                break;
            default:
                break;
        }
    }

    private static List<String> splitCommaTokens(String rawValue) {
        List<String> values = new ArrayList<String>();
        if (rawValue == null || rawValue.trim().isEmpty()) return values;
        String[] parts = rawValue.split(",");
        for (int i = 0; i < parts.length; i++) {
            String trimmed = parts[i].trim();
            if (!trimmed.isEmpty()) values.add(trimmed);
        }
        return values;
    }

    private static void replace(List<String> target, List<String> values) {
        target.clear();
        target.addAll(values);
    }

    private static IllegalArgumentException missingParameter(String analysisDisplayName, BinField field) {
        String key = CLIConfig.binFieldCliKey(field);
        return new IllegalArgumentException("Cannot run " + cleanAnalysisName(analysisDisplayName)
                + ": missing parameter `" + key + "`. Pass `" + key
                + "=...` on the command line, or run interactively first.");
    }

    private static String cleanAnalysisName(String analysisDisplayName) {
        return analysisDisplayName == null || analysisDisplayName.trim().isEmpty()
                ? "this analysis" : analysisDisplayName.trim();
    }

    static EnumSet<BinField> missingFields(BinConfig existing, Set<BinField> required) {
        EnumSet<BinField> missing = EnumSet.noneOf(BinField.class);
        if (required == null || required.isEmpty()) return missing;
        BinConfig cfg = existing == null ? new BinConfig() : existing;
        for (BinField field : required) {
            if (!hasField(cfg, field)) missing.add(field);
        }
        return missing;
    }

    private static EnumMap<BinField, String> loadedSources(BinConfig existing, Set<BinField> required) {
        EnumMap<BinField, String> sources = new EnumMap<BinField, String>(BinField.class);
        if (required == null || required.isEmpty()) return sources;
        BinConfig cfg = existing == null ? new BinConfig() : existing;
        for (BinField field : required) {
            if (hasField(cfg, field)) {
                sources.put(field, SOURCE_LOADED);
            }
        }
        return sources;
    }

    private static void recordMissingSources(EnumMap<BinField, String> sources,
                                             Set<BinField> fields,
                                             String source) {
        if (sources == null || fields == null || source == null) return;
        for (BinField field : fields) {
            sources.put(field, source);
        }
    }

    private static void recordOutcome(Outcome outcome, EnumMap<BinField, String> sources) {
        recordOutcome(outcome, sources, "");
    }

    private static void recordOutcome(Outcome outcome,
                                      EnumMap<BinField, String> sources,
                                      String reason) {
        lastOutcome.set(outcome);
        lastOutcomeReason.set(reason == null ? "" : reason.trim());
        lastFieldSources.set(sources == null
                ? new EnumMap<BinField, String>(BinField.class)
                : new EnumMap<BinField, String>(sources));
    }

    private static String actionableReason(RuntimeException failure,
                                           String analysisDisplayName,
                                           Set<BinField> missing) {
        String detail = failure == null ? "" : failure.getMessage();
        if (detail != null && !detail.trim().isEmpty()) {
            return detail.trim();
        }
        return "Cannot run " + cleanAnalysisName(analysisDisplayName)
                + ": required setup is incomplete (missing " + copyOf(missing) + ").";
    }

    private static ChannelConfig readRawConfiguration(String directory) {
        File settingsDir = FlashProjectLayout.forDirectory(directory).configurationWriteDir();
        return ChannelConfigIO.read(settingsDir);
    }

    private static ChannelConfigIO.ValidationResult relevantSemanticValidation(
            String directory, ChannelConfig cfg, Set<BinField> required) {
        if (cfg == null) {
            return new ChannelConfigIO.ValidationResult(
                    Collections.<ChannelConfigIO.ValidationIssue>emptyList());
        }
        ChannelConfigIO.ValidationResult all = ChannelConfigIO.validateForCompletion(
                cfg, validationContextProvider.context(directory, cfg));
        if (all.isValid() || Boolean.TRUE.equals(cfg.complete)) return all;
        List<ChannelConfigIO.ValidationIssue> relevant =
                new ArrayList<ChannelConfigIO.ValidationIssue>();
        for (ChannelConfigIO.ValidationIssue issue : all.issues()) {
            // Marker identity is optional for partial analysis setup. A fully
            // published config still has to resolve its marker status above.
            if (ChannelConfig.P_MARKER.equals(issue.field)) continue;
            if (isStructurallyRelevant(issue, required)
                    || (required != null && required.contains(fieldForIssue(issue)))) {
                relevant.add(issue);
            }
        }
        return new ChannelConfigIO.ValidationResult(relevant);
    }

    private static void addSemanticMissing(EnumSet<BinField> missing,
                                           EnumMap<BinField, String> sources,
                                           ChannelConfigIO.ValidationResult validation) {
        if (validation == null || validation.isValid()) return;
        for (ChannelConfigIO.ValidationIssue issue : validation.issues()) {
            BinField field = fieldForIssue(issue);
            missing.add(field);
            if (sources != null) sources.remove(field);
        }
    }

    private static boolean isStructurallyRelevant(ChannelConfigIO.ValidationIssue issue,
                                                  Set<BinField> required) {
        if (issue == null) return false;
        if ("sourceZSlices".equals(issue.field)) return true;
        boolean needsChannels = requiresChannelConfiguration(required);
        return needsChannels && ("channels".equals(issue.field)
                || "channel".equals(issue.field)
                || "channelCount".equals(issue.field)
                || "index".equals(issue.field)
                || ChannelConfig.P_NAME.equals(issue.field)
                || "sourceChannelCount".equals(issue.field)
                || "sourceSeriesIndex".equals(issue.field));
    }

    private static boolean requiresChannelConfiguration(Set<BinField> required) {
        if (required == null) return false;
        for (BinField field : required) {
            if (field != BinField.Z_SLICE) return true;
        }
        return false;
    }

    private static BinField fieldForIssue(ChannelConfigIO.ValidationIssue issue) {
        String field = issue == null ? "" : issue.field;
        if (ChannelConfig.P_COLOR.equals(field)) return BinField.CHANNEL_COLORS;
        if (ChannelConfig.P_THRESHOLD.equals(field)) return BinField.OBJECT_THRESHOLDS;
        if (ChannelConfig.P_SIZE.equals(field)) return BinField.PARTICLE_SIZES;
        if (ChannelConfig.P_MINMAX.equals(field)) return BinField.DISPLAY_MIN_MAX;
        if (ChannelConfig.P_INTENSITY.equals(field)) return BinField.INTENSITY_THRESHOLDS;
        if (ChannelConfig.P_SEGMENTATION.equals(field)) return BinField.SEGMENTATION_METHODS;
        if (ChannelConfig.P_FILTER.equals(field)
                || "filterAvailabilityCount".equals(field)) return BinField.FILTER_PRESETS;
        if ("zSliceSelections".equals(field)
                || "sourceZSlices".equals(field)) return BinField.Z_SLICE;
        return BinField.CHANNEL_NAMES;
    }

    private static IllegalArgumentException invalidConfiguration(
            String analysisDisplayName, ChannelConfigIO.ValidationResult validation) {
        return new IllegalArgumentException("Cannot run "
                + cleanAnalysisName(analysisDisplayName)
                + ": invalid channel configuration: " + validation.diagnostic()
                + " Pass corrected setup values on the command line, or run interactively first.");
    }

    private static boolean hasField(BinConfig cfg, BinField field) {
        switch (field) {
            case CHANNEL_NAMES: return cfg.hasChannelNames();
            case CHANNEL_COLORS: return cfg.hasChannelColors();
            case OBJECT_THRESHOLDS: return cfg.hasChannelThresholds();
            case PARTICLE_SIZES: return cfg.hasChannelSizes();
            case DISPLAY_MIN_MAX: return cfg.hasChannelMinMax();
            case INTENSITY_THRESHOLDS: return cfg.hasChannelIntensityThresholds();
            case SEGMENTATION_METHODS: return cfg.hasSegmentationMethods();
            case FILTER_PRESETS: return cfg.hasChannelFilterPresets();
            case Z_SLICE: return cfg.hasZSliceConfig();
            default: return false;
        }
    }

    private static void addChannelNamesIfContextNeedsThem(BinConfig existing, EnumSet<BinField> missing) {
        if (missing.isEmpty() || (existing != null && existing.hasChannelNames())) return;
        for (BinField field : missing) {
            if (field != BinField.Z_SLICE) {
                missing.add(BinField.CHANNEL_NAMES);
                return;
            }
        }
    }

    private static EnumSet<BinField> copyOf(Set<BinField> fields) {
        return fields == null || fields.isEmpty()
                ? EnumSet.noneOf(BinField.class)
                : EnumSet.copyOf(fields);
    }

    static void setHeadlessProbeForTest(HeadlessProbe probe) {
        headlessProbe = probe;
    }

    static void setChooserForTest(Chooser testChooser) {
        chooser = testChooser;
    }

    static void setWizardRunnerForTest(WizardRunner runner) {
        wizardRunner = runner;
    }

    static void setBypassRunnerForTest(BypassRunner runner) {
        bypassRunner = runner;
    }

    static void setValidationContextProviderForTest(ValidationContextProvider provider) {
        validationContextProvider = provider;
    }

    static void resetForTest() {
        clearLastFieldSources();
        headlessProbe = new HeadlessProbe() {
            @Override public boolean isHeadlessOrMacro() {
                return GraphicsEnvironment.isHeadless()
                        || IJ.getInstance() == null
                        || Macro.getOptions() != null;
            }
        };
        chooser = new Chooser() {
            @Override public BinSetupChooser.Choice show(String analysisDisplayName,
                                                         Set<BinField> missing,
                                                         boolean showRoiTip) {
                return BinSetupChooser.show(analysisDisplayName, missing, showRoiTip);
            }
        };
        wizardRunner = new WizardRunner() {
            @Override public void run(String directory, Set<BinField> fields) {
                new CreateBinFileAnalysis().executeFiltered(directory, fields);
            }
        };
        bypassRunner = new BypassRunner() {
            @Override public boolean show(String directory, Set<BinField> fields) {
                return BinBypassDialog.show(directory, fields);
            }
        };
        validationContextProvider = new ValidationContextProvider() {
            @Override public ChannelConfigIO.ValidationContext context(
                    String directory, ChannelConfig cfg) {
                return BinConfigIO.completionValidationContext(directory, cfg, false);
            }
        };
    }
}
