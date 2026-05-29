package flash.pipeline.decontamination.wizard;

import flash.pipeline.bin.BinConfig;
import flash.pipeline.bin.ChannelConfigIO;
import flash.pipeline.bin.ChannelIdentities;
import flash.pipeline.decontamination.CorrectionFeatureRegistry;
import flash.pipeline.decontamination.CorrectionPipeline;
import flash.pipeline.decontamination.SpectralDecontaminationConfig;
import flash.pipeline.decontamination.features.FullForwardModelFeature;
import flash.pipeline.decontamination.features.GlobalRatioCorrectionFeature;
import flash.pipeline.decontamination.features.LinearUnmixingFeature;
import flash.pipeline.decontamination.features.LocalCorrelationVetoFeature;
import flash.pipeline.decontamination.features.LocalKCorrectionFeature;
import flash.pipeline.decontamination.features.RocThresholdSearchFeature;
import flash.pipeline.decontamination.features.SaturationExclusionFeature;
import flash.pipeline.decontamination.features.SizeFilterFeature;
import flash.pipeline.decontamination.features.ThresholdCorrectedTargetFeature;
import flash.pipeline.decontamination.features.VetoMasksFeature;
import flash.pipeline.io.ConditionManifestIO;
import flash.pipeline.io.FlashProjectLayout;
import flash.pipeline.ui.PipelineDialog;
import ij.IJ;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/**
 * Intent-based setup helper for Spectral Decontamination.
 */
public class SpectralDecontaminationWizard {

    private static final String TITLE = "Decontamination Helper";

    private final File projectRoot;
    private final BinConfig binConfig;
    private final SpectralDecontaminationConfig startingConfig;

    public SpectralDecontaminationWizard(File projectRoot,
                                         BinConfig binConfig,
                                         SpectralDecontaminationConfig startingConfig) {
        this.projectRoot = projectRoot;
        this.binConfig = binConfig;
        this.startingConfig = startingConfig == null
                ? SpectralDecontaminationConfig.defaults(binConfig == null ? 0 : binConfig.numChannels())
                : startingConfig.copy();
    }

    public SpectralDecontaminationConfig showDialog() {
        if (IJ.getInstance() == null) {
            return null;
        }
        if (binConfig == null || binConfig.numChannels() <= 0) {
            IJ.showMessage(TITLE, "No channels are available. Run Set Up Configuration first.");
            return null;
        }

        AutoDetection auto = autoDetect(projectRoot, binConfig);
        Selection selection = new Selection();
        selection.contaminationType = askContaminationType(auto.hasExistingObjects);
        if (selection.contaminationType == null) return null;

        selection.targetChannelIndex = auto.targetChannelIndex >= 0
                ? auto.targetChannelIndex
                : Math.max(0, startingConfig.getTargetChannelIndex());
        selection.bleedThroughChannels.addAll(auto.bleedThroughChannels);
        selection.autofluorescenceChannels.addAll(auto.autofluorescenceChannels);
        if (!askChannels(selection, auto)) return null;

        selection.goal = askGoal(selection.contaminationType, auto.hasExistingObjects);
        if (selection.goal == null) return null;

        selection.hasControls = auto.hasControlConditions;
        if (shouldAskCalibration(selection)) {
            selection.calibration = askCalibration();
            if (selection.calibration == null) return null;
        }

        if (selection.contaminationType == ContaminationType.BOTH) {
            selection.strength = askStrength();
            if (selection.strength == null) return null;
        }

        if (!askGuards(selection)) return null;
        return derive(binConfig, auto, selection).config;
    }

    public static DerivedConfig derive(BinConfig binConfig, AutoDetection auto, Selection selection) {
        Selection chosen = selection == null ? new Selection() : selection.copy();
        AutoDetection detected = auto == null ? new AutoDetection() : auto;
        SpectralDecontaminationConfig config = new SpectralDecontaminationConfig();
        config.setVersion(SpectralDecontaminationConfig.CURRENT_VERSION);
        config.setGoal(goalFor(chosen));
        config.setTargetChannelIndex(resolveTarget(binConfig, detected, chosen));
        config.setBleedThroughChannelIndexes(chosen.bleedThroughChannels);
        config.setAutofluorescenceChannelIndexes(chosen.autofluorescenceChannels);
        config.setExcludedChannelIndexes(chosen.excludedChannels);
        config.setControlConditionNames(detected.controlConditionNames);

        CorrectionPipeline pipeline = new CorrectionPipeline();
        pipeline.setExpertMode(chosen.strength == Strength.AGGRESSIVE);
        pipeline.setPresetId(presetIdFor(chosen));
        pipeline.setFeatureIds(featureStack(chosen));
        config.setCorrectionPipeline(pipeline);

        if (chosen.sizeFilter) {
            config.setFeatureSettings(SizeFilterFeature.ID,
                    new SizeFilterFeature.Settings()
                            .setMinSizeVoxels(chosen.minimumVoxels)
                            .toPipelineSettings());
        }

        DerivedConfig derived = new DerivedConfig();
        derived.config = config;
        derived.screen4Visible = shouldAskCalibration(chosen);
        derived.screen5Visible = chosen.contaminationType == ContaminationType.BOTH;
        derived.recommendedFeatureIds.addAll(pipeline.getFeatureIds());
        return derived;
    }

    public static AutoDetection autoDetect(File projectRoot, BinConfig binConfig) {
        AutoDetection detection = new AutoDetection();
        if (binConfig == null) {
            return detection;
        }
        ChannelIdentities identities = ChannelConfigIO.readChannelIdentities(
                projectRoot == null ? null
                        : FlashProjectLayout.forDirectory(projectRoot.getAbsolutePath()).configurationWriteDir());
        for (int i = 0; i < binConfig.numChannels(); i++) {
            String markerId = markerId(identities, i);
            String name = channelName(binConfig, i);
            String marker = markerId.toLowerCase(Locale.US);
            String lowerName = normalizeScienceText(name);

            if (isAutofluorescenceMarker(marker) || hasAny(lowerName, "405", "af", "autofluorescence")) {
                detection.autofluorescenceChannels.add(Integer.valueOf(i));
                detection.autoDetectedAutofluorescenceChannels.add(Integer.valueOf(i));
            }
            if (isAmyloidMarker(marker) || hasAny(lowerName, "561", "mcherry", "alexa594", "abeta", "amyloid")) {
                detection.bleedThroughChannels.add(Integer.valueOf(i));
                detection.autoDetectedBleedThroughChannels.add(Integer.valueOf(i));
            }
            if (isTargetMarker(marker) && detection.targetChannelIndex < 0
                    && !detection.bleedThroughChannels.contains(Integer.valueOf(i))
                    && !detection.autofluorescenceChannels.contains(Integer.valueOf(i))) {
                detection.targetChannelIndex = i;
                detection.autoDetectedTarget = true;
            }
        }

        if (detection.targetChannelIndex < 0) {
            for (int i = 0; i < binConfig.numChannels(); i++) {
                Integer idx = Integer.valueOf(i);
                if (!detection.bleedThroughChannels.contains(idx)
                        && !detection.autofluorescenceChannels.contains(idx)
                        && !isNuclearName(channelName(binConfig, i))) {
                    detection.targetChannelIndex = i;
                    break;
                }
            }
        }

        detection.hasExistingObjects = hasExistingObjectCsv(projectRoot);
        detection.controlConditionNames.addAll(controlConditions(projectRoot));
        detection.hasControlConditions = !detection.controlConditionNames.isEmpty();
        detection.defaultMinimumVoxels = defaultMinimumVoxels(binConfig, detection.targetChannelIndex);
        return detection;
    }

    public static SpectralDecontaminationConfig applyCliOverrides(
            SpectralDecontaminationConfig config,
            String presetName,
            String goal,
            Integer target,
            List<Integer> bleedThrough,
            List<Integer> autofluorescence,
            String contaminationType,
            String calibration,
            String strength,
            File projectRoot) throws IOException {
        SpectralDecontaminationConfig out = config == null
                ? new SpectralDecontaminationConfig()
                : config.copy();
        if (presetName != null && !presetName.trim().isEmpty()) {
            out = new SpectralDecontamPresetIO(projectRoot).load(presetName).getPayload();
        }

        Selection selection = Selection.fromConfig(out);
        if (contaminationType != null && !contaminationType.trim().isEmpty()) {
            selection.contaminationType = ContaminationType.fromCli(contaminationType);
        }
        if (calibration != null && !calibration.trim().isEmpty()) {
            selection.calibration = "roc".equalsIgnoreCase(calibration.trim())
                    ? Calibration.ROC
                    : Calibration.MANUAL;
        }
        if (strength != null && !strength.trim().isEmpty()) {
            selection.strength = "aggressive".equalsIgnoreCase(strength.trim())
                    ? Strength.AGGRESSIVE
                    : Strength.STANDARD;
        }
        if (goal != null && !goal.trim().isEmpty()) {
            selection.goal = goalFromCli(goal);
        }
        if (target != null) {
            out.setTargetChannelIndex(target.intValue());
            selection.targetChannelIndex = target.intValue();
        }
        if (bleedThrough != null) {
            out.setBleedThroughChannelIndexes(bleedThrough);
            selection.bleedThroughChannels.clear();
            selection.bleedThroughChannels.addAll(bleedThrough);
        }
        if (autofluorescence != null) {
            out.setAutofluorescenceChannelIndexes(autofluorescence);
            selection.autofluorescenceChannels.clear();
            selection.autofluorescenceChannels.addAll(autofluorescence);
        }
        if (contaminationType != null || calibration != null || strength != null || goal != null) {
            AutoDetection auto = new AutoDetection();
            auto.controlConditionNames.addAll(out.getControlConditionNames());
            out = derive(null, auto, selection).config;
        }
        if (target != null) {
            out.setTargetChannelIndex(target.intValue());
        }
        if (bleedThrough != null) {
            out.setBleedThroughChannelIndexes(bleedThrough);
        }
        if (autofluorescence != null) {
            out.setAutofluorescenceChannelIndexes(autofluorescence);
        }
        return out;
    }

    private ContaminationType askContaminationType(boolean hasExistingObjects) {
        List<String> labels = new ArrayList<String>();
        labels.add(ContaminationType.BLEED_THROUGH.label);
        labels.add(ContaminationType.BROAD_AF.label);
        labels.add(ContaminationType.PATCHY_AF.label);
        labels.add(ContaminationType.BOTH.label);
        if (hasExistingObjects) {
            labels.add(ContaminationType.SCORE_EXISTING.label);
        }
        PipelineDialog dialog = screen("What kind of false signal are you trying to remove?");
        dialog.addChoice("False signal", labels.toArray(new String[labels.size()]), labels.get(0));
        if (!dialog.showDialog()) return null;
        return ContaminationType.fromLabel(dialog.getNextChoice());
    }

    private boolean askChannels(Selection selection, AutoDetection auto) {
        AutoDetection detected = auto == null ? new AutoDetection() : auto;
        PipelineDialog dialog = screen("Which channel is the source of the contamination?");
        String[] channels = channelChoices(binConfig);
        dialog.addChoice("Target channel", channels, channels[Math.max(0,
                Math.min(selection.targetChannelIndex, channels.length - 1))]);
        if (selection.contaminationType == ContaminationType.BLEED_THROUGH
                || selection.contaminationType == ContaminationType.BOTH
                || selection.contaminationType == ContaminationType.SCORE_EXISTING) {
            dialog.addHeader("Bleed-through source channels");
            for (int i = 0; i < binConfig.numChannels(); i++) {
                dialog.addToggle(autoDetectedChannelLabel(binConfig, i,
                                detected.autoDetectedBleedThroughChannels),
                        selection.bleedThroughChannels.contains(Integer.valueOf(i)));
            }
        }
        if (selection.contaminationType == ContaminationType.BROAD_AF
                || selection.contaminationType == ContaminationType.PATCHY_AF
                || selection.contaminationType == ContaminationType.BOTH
                || selection.contaminationType == ContaminationType.SCORE_EXISTING) {
            dialog.addHeader("Autofluorescence channels");
            for (int i = 0; i < binConfig.numChannels(); i++) {
                dialog.addToggle(autoDetectedChannelLabel(binConfig, i,
                                detected.autoDetectedAutofluorescenceChannels),
                        selection.autofluorescenceChannels.contains(Integer.valueOf(i)));
            }
        }
        if (!dialog.showDialog()) return false;
        selection.targetChannelIndex = indexFromChoice(dialog.getNextChoice());
        selection.bleedThroughChannels.clear();
        if (selection.contaminationType == ContaminationType.BLEED_THROUGH
                || selection.contaminationType == ContaminationType.BOTH
                || selection.contaminationType == ContaminationType.SCORE_EXISTING) {
            selection.bleedThroughChannels.addAll(readToggles(dialog, binConfig.numChannels()));
        }
        selection.autofluorescenceChannels.clear();
        if (selection.contaminationType == ContaminationType.BROAD_AF
                || selection.contaminationType == ContaminationType.PATCHY_AF
                || selection.contaminationType == ContaminationType.BOTH
                || selection.contaminationType == ContaminationType.SCORE_EXISTING) {
            selection.autofluorescenceChannels.addAll(readToggles(dialog, binConfig.numChannels()));
        }
        return true;
    }

    private DownstreamUse askGoal(ContaminationType type, boolean hasExistingObjects) {
        if (type == ContaminationType.SCORE_EXISTING) {
            return DownstreamUse.SCORE_OBJECTS;
        }
        List<String> labels = new ArrayList<String>();
        labels.add(DownstreamUse.CLEANED_IMAGE.label);
        labels.add(DownstreamUse.CLEANED_MASK.label);
        if (hasExistingObjects) {
            labels.add(DownstreamUse.SCORE_OBJECTS.label);
        }
        labels.add(DownstreamUse.MEASURE_ONLY.label);
        PipelineDialog dialog = screen("What are you going to do with the cleaned output?");
        dialog.addChoice("Downstream use", labels.toArray(new String[labels.size()]), labels.get(0));
        if (!dialog.showDialog()) return null;
        return DownstreamUse.fromLabel(dialog.getNextChoice());
    }

    private Calibration askCalibration() {
        PipelineDialog dialog = screen("You have negative control images. Use them to calibrate the threshold?");
        dialog.addChoice("Calibration", new String[]{Calibration.ROC.label, Calibration.MANUAL.label},
                Calibration.ROC.label);
        if (!dialog.showDialog()) return null;
        return Calibration.fromLabel(dialog.getNextChoice());
    }

    private Strength askStrength() {
        PipelineDialog dialog = screen("How aggressive should the correction be?");
        dialog.addMessage("Aggressive correction uses a per-image forward model combining autofluorescence and bleed-through terms. Use it when contamination varies by object shape, field position, or channel overlap pattern.");
        dialog.addChoice("Strength", new String[]{Strength.STANDARD.label, Strength.AGGRESSIVE.label},
                Strength.STANDARD.label);
        if (!dialog.showDialog()) return null;
        return Strength.fromLabel(dialog.getNextChoice());
    }

    private boolean askGuards(Selection selection) {
        PipelineDialog dialog = screen("Handle saturated pixels and small noise specks?");
        dialog.addToggle("Exclude saturated pixels from correction fitting", selection.saturationExclusion);
        dialog.addToggle("Remove small connected components from the final mask", selection.sizeFilter);
        dialog.addNumericField("Minimum voxels", selection.minimumVoxels, 0);
        if (!dialog.showDialog()) return false;
        selection.saturationExclusion = dialog.getNextBoolean();
        selection.sizeFilter = dialog.getNextBoolean();
        selection.minimumVoxels = Math.max(1, (int) Math.round(dialog.getNextNumber()));
        return true;
    }

    private static PipelineDialog screen(String title) {
        PipelineDialog dialog = new PipelineDialog(TITLE);
        dialog.addHeader(title);
        return dialog;
    }

    private static boolean shouldAskCalibration(Selection selection) {
        return selection != null
                && selection.hasControls
                && selection.goal == DownstreamUse.CLEANED_MASK;
    }

    private static SpectralDecontaminationConfig.Goal goalFor(Selection selection) {
        return selection.goal == DownstreamUse.CLEANED_MASK
                ? SpectralDecontaminationConfig.Goal.CREATE_CLEANED_MASK
                : selection.goal == DownstreamUse.SCORE_OBJECTS
                ? SpectralDecontaminationConfig.Goal.SCORE_EXISTING_OBJECTS
                : selection.goal == DownstreamUse.MEASURE_ONLY
                ? SpectralDecontaminationConfig.Goal.MEASURE_CLEANED_SIGNAL_ONLY
                : SpectralDecontaminationConfig.Goal.CREATE_CLEANED_IMAGE;
    }

    private static String presetIdFor(Selection selection) {
        if (selection.strength == Strength.AGGRESSIVE) {
            return CorrectionFeatureRegistry.PRESET_EXPERT;
        }
        if (selection.calibration == Calibration.ROC) {
            return CorrectionFeatureRegistry.PRESET_ROC_THRESHOLD;
        }
        if (selection.contaminationType == ContaminationType.BROAD_AF
                || selection.contaminationType == ContaminationType.PATCHY_AF) {
            return CorrectionFeatureRegistry.PRESET_AUTOFLUORESCENCE;
        }
        if (selection.contaminationType == ContaminationType.BLEED_THROUGH) {
            return CorrectionFeatureRegistry.PRESET_BLEED_THROUGH;
        }
        if (selection.contaminationType == ContaminationType.BOTH) {
            return CorrectionFeatureRegistry.PRESET_BASIC;
        }
        return CorrectionPipeline.CUSTOM_PRESET_ID;
    }

    private static List<String> featureStack(Selection selection) {
        List<String> features = new ArrayList<String>();
        if (selection.saturationExclusion) {
            features.add(SaturationExclusionFeature.ID);
        }
        if (selection.contaminationType == ContaminationType.BROAD_AF) {
            features.add(GlobalRatioCorrectionFeature.ID);
        } else if (selection.contaminationType == ContaminationType.PATCHY_AF) {
            features.add(LocalKCorrectionFeature.ID);
            features.add(LocalCorrelationVetoFeature.ID);
        } else if (selection.contaminationType == ContaminationType.BOTH
                && selection.strength == Strength.AGGRESSIVE) {
            features.add(FullForwardModelFeature.ID);
        } else if (selection.contaminationType != ContaminationType.SCORE_EXISTING
                || !selection.bleedThroughChannels.isEmpty()
                || !selection.autofluorescenceChannels.isEmpty()) {
            features.add(LinearUnmixingFeature.ID);
        }

        if (selection.goal == DownstreamUse.CLEANED_IMAGE || selection.goal == DownstreamUse.CLEANED_MASK) {
            features.add(selection.calibration == Calibration.ROC
                    ? RocThresholdSearchFeature.ID
                    : ThresholdCorrectedTargetFeature.ID);
        }
        if (selection.contaminationType == ContaminationType.PATCHY_AF
                && (selection.goal == DownstreamUse.CLEANED_IMAGE || selection.goal == DownstreamUse.CLEANED_MASK)) {
            features.add(VetoMasksFeature.ID);
        }
        if (selection.sizeFilter
                && (selection.goal == DownstreamUse.CLEANED_IMAGE || selection.goal == DownstreamUse.CLEANED_MASK)) {
            features.add(SizeFilterFeature.ID);
        }
        return features;
    }

    private static int resolveTarget(BinConfig binConfig, AutoDetection auto, Selection selection) {
        if (selection.targetChannelIndex >= 0) return selection.targetChannelIndex;
        if (auto.targetChannelIndex >= 0) return auto.targetChannelIndex;
        return binConfig != null && binConfig.numChannels() > 0 ? 0 : -1;
    }

    private static DownstreamUse goalFromCli(String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.US);
        if ("cleaned_mask".equals(normalized)) return DownstreamUse.CLEANED_MASK;
        if ("score_objects".equals(normalized)) return DownstreamUse.SCORE_OBJECTS;
        if ("measure_only".equals(normalized)) return DownstreamUse.MEASURE_ONLY;
        return DownstreamUse.CLEANED_IMAGE;
    }

    private static String markerId(ChannelIdentities identities, int channelIndex) {
        ChannelIdentities.Entry entry = identities == null ? null : identities.findByChannelIndex(channelIndex);
        return entry == null ? "" : entry.getMarkerId();
    }

    private static boolean isAutofluorescenceMarker(String marker) {
        return marker != null && marker.toLowerCase(Locale.US).contains("autofluorescence");
    }

    private static boolean isAmyloidMarker(String marker) {
        return marker != null && (marker.contains("amyloid") || marker.contains("abeta"));
    }

    private static boolean isTargetMarker(String marker) {
        if (marker == null || marker.trim().isEmpty()) return false;
        return hasAny(marker, "microglia", "gfap", "astro", "neun", "synap", "amyloid", "abeta");
    }

    private static boolean isNuclearName(String name) {
        return hasAny(name == null ? "" : name.toLowerCase(Locale.US), "dapi", "hoechst", "nuclei");
    }

    private static boolean hasAny(String text, String... needles) {
        String value = normalizeScienceText(text);
        for (String needle : needles) {
            if (value.contains(needle.toLowerCase(Locale.US))) return true;
        }
        return false;
    }

    private static String normalizeScienceText(String text) {
        return (text == null ? "" : text.toLowerCase(Locale.US))
                .replace("\u03b2", "beta")
                .replace("\u0392", "beta");
    }

    private static boolean hasExistingObjectCsv(File projectRoot) {
        File dir = projectRoot == null ? null
                : FlashProjectLayout.forDirectory(projectRoot.getAbsolutePath()).tablesObjectsWriteDir();
        File[] files = dir == null ? null : dir.listFiles((parent, name) ->
                name != null && name.toLowerCase(Locale.US).endsWith(".csv"));
        return files != null && files.length > 0;
    }

    private static List<String> controlConditions(File projectRoot) {
        List<String> controls = new ArrayList<String>();
        if (projectRoot == null) return controls;
        LinkedHashMap<String, String> assignments =
                ConditionManifestIO.readAssignmentsIfExists(projectRoot.getAbsolutePath());
        try {
            LinkedHashSet<String> unique = new LinkedHashSet<String>();
            for (String condition : assignments.values()) {
                String lower = condition.toLowerCase(Locale.US);
                if (lower.contains("control") || lower.contains("negative")
                        || lower.contains("secondary_only") || lower.contains("noprimary")) {
                    unique.add(condition);
                }
            }
            controls.addAll(unique);
        } catch (Exception ignored) {
        }
        return controls;
    }

    private static int defaultMinimumVoxels(BinConfig binConfig, int targetChannel) {
        if (binConfig != null && targetChannel >= 0 && targetChannel < binConfig.channelSizes.size()) {
            String token = binConfig.channelSizes.get(targetChannel);
            if (token != null) {
                int dash = token.indexOf('-');
                String first = dash >= 0 ? token.substring(0, dash) : token;
                try {
                    return Math.max(1, Integer.parseInt(first.trim()));
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return 50;
    }

    private static String channelName(BinConfig binConfig, int index) {
        if (binConfig == null || index < 0 || index >= binConfig.channelNames.size()) {
            return "";
        }
        String name = binConfig.channelNames.get(index);
        return name == null ? "" : name;
    }

    private static String channelLabel(BinConfig binConfig, int index) {
        String name = channelName(binConfig, index);
        if (name.trim().isEmpty()) {
            name = "Channel " + (index + 1);
        }
        return (index + 1) + " - " + name;
    }

    private static String autoDetectedChannelLabel(BinConfig binConfig,
                                                   int index,
                                                   List<Integer> autoDetected) {
        String label = channelLabel(binConfig, index);
        return autoDetected != null && autoDetected.contains(Integer.valueOf(index))
                ? label + " (auto-detected)"
                : label;
    }

    private static String[] channelChoices(BinConfig binConfig) {
        String[] choices = new String[binConfig.numChannels()];
        for (int i = 0; i < choices.length; i++) {
            choices[i] = channelLabel(binConfig, i);
        }
        return choices;
    }

    private static int indexFromChoice(String choice) {
        if (choice == null) return -1;
        int separator = choice.indexOf(" - ");
        if (separator > 0) {
            try {
                return Integer.parseInt(choice.substring(0, separator).trim()) - 1;
            } catch (NumberFormatException ignored) {
            }
        }
        return -1;
    }

    private static List<Integer> readToggles(PipelineDialog dialog, int count) {
        List<Integer> selected = new ArrayList<Integer>();
        for (int i = 0; i < count; i++) {
            if (dialog.getNextBoolean()) selected.add(Integer.valueOf(i));
        }
        return selected;
    }

    public enum ContaminationType {
        BLEED_THROUGH("Bleed-through from a bright neighbouring channel"),
        BROAD_AF("Broad tissue autofluorescence (uniform across the slice)"),
        PATCHY_AF("Patchy autofluorescence (varies region to region)"),
        BOTH("Both bleed-through AND autofluorescence"),
        SCORE_EXISTING("I have 3D Object Analysis results already and want to flag contaminated objects");

        final String label;

        ContaminationType(String label) {
            this.label = label;
        }

        static ContaminationType fromLabel(String label) {
            for (ContaminationType type : values()) {
                if (type.label.equals(label)) return type;
            }
            return BLEED_THROUGH;
        }

        static ContaminationType fromCli(String value) {
            String normalized = value == null ? "" : value.trim().toLowerCase(Locale.US);
            if ("broad_af".equals(normalized)) return BROAD_AF;
            if ("patchy_af".equals(normalized)) return PATCHY_AF;
            if ("both".equals(normalized)) return BOTH;
            if ("score_existing".equals(normalized)) return SCORE_EXISTING;
            return BLEED_THROUGH;
        }
    }

    public enum DownstreamUse {
        CLEANED_IMAGE("Cleaner image for downstream segmentation / re-run 3D Object Analysis"),
        CLEANED_MASK("Binary mask of genuine target signal for QC or overlay"),
        SCORE_OBJECTS("Flag or remove contaminated objects in my existing results"),
        MEASURE_ONLY("Just measure intensity in the cleaned version, no mask needed");

        final String label;

        DownstreamUse(String label) {
            this.label = label;
        }

        static DownstreamUse fromLabel(String label) {
            for (DownstreamUse use : values()) {
                if (use.label.equals(label)) return use;
            }
            return CLEANED_IMAGE;
        }
    }

    public enum Calibration {
        ROC("Yes - reject pixels that look like my controls (ROC threshold search)"),
        MANUAL("No - threshold manually");

        final String label;

        Calibration(String label) {
            this.label = label;
        }

        static Calibration fromLabel(String label) {
            return ROC.label.equals(label) ? ROC : MANUAL;
        }
    }

    public enum Strength {
        STANDARD("Standard (linear unmixing + thresholding)"),
        AGGRESSIVE("Aggressive (full forward model for mixed contamination patterns)");

        final String label;

        Strength(String label) {
            this.label = label;
        }

        static Strength fromLabel(String label) {
            return AGGRESSIVE.label.equals(label) ? AGGRESSIVE : STANDARD;
        }
    }

    public static class Selection {
        public ContaminationType contaminationType = ContaminationType.BLEED_THROUGH;
        public DownstreamUse goal = DownstreamUse.CLEANED_IMAGE;
        public Calibration calibration = Calibration.MANUAL;
        public Strength strength = Strength.STANDARD;
        public int targetChannelIndex = -1;
        public boolean hasControls = false;
        public boolean saturationExclusion = true;
        public boolean sizeFilter = true;
        public int minimumVoxels = 50;
        public final List<Integer> bleedThroughChannels = new ArrayList<Integer>();
        public final List<Integer> autofluorescenceChannels = new ArrayList<Integer>();
        public final List<Integer> excludedChannels = new ArrayList<Integer>();

        Selection copy() {
            Selection copy = new Selection();
            copy.contaminationType = contaminationType;
            copy.goal = goal;
            copy.calibration = calibration;
            copy.strength = strength;
            copy.targetChannelIndex = targetChannelIndex;
            copy.hasControls = hasControls;
            copy.saturationExclusion = saturationExclusion;
            copy.sizeFilter = sizeFilter;
            copy.minimumVoxels = minimumVoxels;
            copy.bleedThroughChannels.addAll(bleedThroughChannels);
            copy.autofluorescenceChannels.addAll(autofluorescenceChannels);
            copy.excludedChannels.addAll(excludedChannels);
            return copy;
        }

        static Selection fromConfig(SpectralDecontaminationConfig config) {
            Selection selection = new Selection();
            if (config == null) return selection;
            selection.targetChannelIndex = config.getTargetChannelIndex();
            selection.bleedThroughChannels.addAll(config.getBleedThroughChannelIndexes());
            selection.autofluorescenceChannels.addAll(config.getAutofluorescenceChannelIndexes());
            selection.excludedChannels.addAll(config.getExcludedChannelIndexes());
            selection.hasControls = !config.getControlConditionNames().isEmpty();
            SpectralDecontaminationConfig.Goal goal = config.getGoal();
            selection.goal = goal == SpectralDecontaminationConfig.Goal.CREATE_CLEANED_MASK
                    ? DownstreamUse.CLEANED_MASK
                    : goal == SpectralDecontaminationConfig.Goal.SCORE_EXISTING_OBJECTS
                    ? DownstreamUse.SCORE_OBJECTS
                    : goal == SpectralDecontaminationConfig.Goal.MEASURE_CLEANED_SIGNAL_ONLY
                    ? DownstreamUse.MEASURE_ONLY
                    : DownstreamUse.CLEANED_IMAGE;
            CorrectionPipeline pipeline = config.getCorrectionPipeline();
            if (pipeline.isExpertMode() || pipeline.getFeatureIds().contains(FullForwardModelFeature.ID)) {
                selection.strength = Strength.AGGRESSIVE;
                selection.contaminationType = ContaminationType.BOTH;
            } else if (pipeline.getFeatureIds().contains(LocalKCorrectionFeature.ID)) {
                selection.contaminationType = ContaminationType.PATCHY_AF;
            } else if (pipeline.getFeatureIds().contains(GlobalRatioCorrectionFeature.ID)) {
                selection.contaminationType = ContaminationType.BROAD_AF;
            } else if (!selection.bleedThroughChannels.isEmpty() && !selection.autofluorescenceChannels.isEmpty()) {
                selection.contaminationType = ContaminationType.BOTH;
            }
            if (pipeline.getFeatureIds().contains(RocThresholdSearchFeature.ID)) {
                selection.calibration = Calibration.ROC;
            }
            selection.saturationExclusion = pipeline.getFeatureIds().contains(SaturationExclusionFeature.ID);
            selection.sizeFilter = pipeline.getFeatureIds().contains(SizeFilterFeature.ID);
            selection.minimumVoxels = config.getFeatureSettings(SizeFilterFeature.ID)
                    .getInt("min_size_voxels", selection.minimumVoxels);
            return selection;
        }
    }

    public static class AutoDetection {
        public int targetChannelIndex = -1;
        public boolean autoDetectedTarget = false;
        public boolean hasExistingObjects = false;
        public boolean hasControlConditions = false;
        public int defaultMinimumVoxels = 50;
        public final List<Integer> bleedThroughChannels = new ArrayList<Integer>();
        public final List<Integer> autofluorescenceChannels = new ArrayList<Integer>();
        public final List<Integer> autoDetectedBleedThroughChannels = new ArrayList<Integer>();
        public final List<Integer> autoDetectedAutofluorescenceChannels = new ArrayList<Integer>();
        public final List<String> controlConditionNames = new ArrayList<String>();
    }

    public static class DerivedConfig {
        public SpectralDecontaminationConfig config;
        public boolean screen4Visible;
        public boolean screen5Visible;
        public final List<String> recommendedFeatureIds = new ArrayList<String>();
    }

    public static Selection selection(ContaminationType type,
                                      DownstreamUse goal,
                                      Calibration calibration,
                                      Strength strength,
                                      boolean saturation,
                                      boolean sizeFilter,
                                      Integer... contaminants) {
        Selection selection = new Selection();
        selection.contaminationType = type;
        selection.goal = goal;
        selection.calibration = calibration;
        selection.strength = strength;
        selection.saturationExclusion = saturation;
        selection.sizeFilter = sizeFilter;
        if (contaminants != null) {
            selection.bleedThroughChannels.addAll(Arrays.asList(contaminants));
        }
        return selection;
    }
}
