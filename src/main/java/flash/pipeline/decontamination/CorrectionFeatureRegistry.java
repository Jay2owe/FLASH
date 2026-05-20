package flash.pipeline.decontamination;

import flash.pipeline.decontamination.features.HardVetoFeature;
import flash.pipeline.decontamination.features.EnvelopeCorrectionFeature;
import flash.pipeline.decontamination.features.FullForwardModelFeature;
import flash.pipeline.decontamination.features.GlobalRatioCorrectionFeature;
import flash.pipeline.decontamination.features.LinearUnmixingFeature;
import flash.pipeline.decontamination.features.LocalCorrelationVetoFeature;
import flash.pipeline.decontamination.features.LocalKCorrectionFeature;
import flash.pipeline.decontamination.features.QuietChannelGateFeature;
import flash.pipeline.decontamination.features.RocThresholdSearchFeature;
import flash.pipeline.decontamination.features.SaturationExclusionFeature;
import flash.pipeline.decontamination.features.SizeFilterFeature;
import flash.pipeline.decontamination.features.ThresholdCorrectedTargetFeature;
import flash.pipeline.decontamination.features.VetoMasksFeature;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Registry of selectable Spectral Decontamination features and presets.
 */
public final class CorrectionFeatureRegistry {

    public static final String PRESET_BASIC = "basic";
    public static final String PRESET_AUTOFLUORESCENCE = "autofluorescence";
    public static final String PRESET_BLEED_THROUGH = "bleed_through";
    public static final String PRESET_ROC_THRESHOLD = "roc_threshold_search";
    public static final String PRESET_EXPERT = "expert";

    private static final CorrectionFeatureRegistry DEFAULT = new CorrectionFeatureRegistry();

    private final LinkedHashMap<String, CorrectionFeature> featuresById =
            new LinkedHashMap<String, CorrectionFeature>();
    private final LinkedHashMap<String, PresetDefinition> presetsById =
            new LinkedHashMap<String, PresetDefinition>();

    private CorrectionFeatureRegistry() {
        register(new SaturationExclusionFeature());
        register(new LinearUnmixingFeature());
        register(new ThresholdCorrectedTargetFeature());
        register(new SizeFilterFeature());
        register(new GlobalRatioCorrectionFeature());
        register(new LocalKCorrectionFeature());
        register(new QuietChannelGateFeature());
        register(new HardVetoFeature());
        register(new LocalCorrelationVetoFeature());
        register(new RocThresholdSearchFeature());
        register(new FullForwardModelFeature());
        register(new EnvelopeCorrectionFeature());
        register(new VetoMasksFeature());

        registerPreset(new PresetDefinition(
                PRESET_BASIC,
                "Basic",
                "Simple baseline correction stack.",
                false,
                Arrays.asList("linear_unmixing", "threshold_corrected_target", "size_filter")));
        registerPreset(new PresetDefinition(
                PRESET_AUTOFLUORESCENCE,
                "Autofluorescence",
                "Local autofluorescence handling with veto support.",
                false,
                Arrays.asList("local_k_correction", "quiet_channel_gate",
                        "local_correlation_veto", "veto_masks", "size_filter")));
        registerPreset(new PresetDefinition(
                PRESET_BLEED_THROUGH,
                "Bleed-through",
                "Bleed-through cleanup followed by mask creation.",
                false,
                Arrays.asList("linear_unmixing", "quiet_channel_gate", "size_filter")));
        registerPreset(new PresetDefinition(
                PRESET_ROC_THRESHOLD,
                "ROC threshold search",
                "Corrected target threshold calibrated against control and experimental conditions.",
                false,
                Arrays.asList("linear_unmixing", "roc_threshold_search", "size_filter")));
        registerPreset(new PresetDefinition(
                PRESET_EXPERT,
                "Expert",
                "Advanced stack with local autofluorescence and per-image bleed-through fitting.",
                true,
                Arrays.asList("full_forward_model", "threshold_corrected_target", "size_filter")));
    }

    public static CorrectionFeatureRegistry getDefault() {
        return DEFAULT;
    }

    public CorrectionFeature getFeature(String id) {
        if (id == null) return null;
        return featuresById.get(id.trim().toLowerCase(Locale.ROOT));
    }

    public List<CorrectionFeature> getFeatures() {
        return new ArrayList<CorrectionFeature>(featuresById.values());
    }

    public List<PresetDefinition> getAvailablePresets(SpectralDecontaminationConfig config,
                                                      boolean expertMode,
                                                      boolean existingObjectMapsAvailable) {
        List<PresetDefinition> available = new ArrayList<PresetDefinition>();
        for (PresetDefinition preset : presetsById.values()) {
            if (preset.isExpertOnly() && !expertMode) continue;
            CorrectionPipeline pipeline = new CorrectionPipeline();
            pipeline.setPresetId(preset.getId());
            pipeline.setExpertMode(expertMode);
            pipeline.setFeatureIds(preset.getFeatureIds());
            if (pipeline.validate(this, config, existingObjectMapsAvailable).isEmpty()) {
                available.add(preset);
            }
        }
        return available;
    }

    public PresetDefinition getPreset(String id) {
        if (id == null) return null;
        return presetsById.get(id.trim().toLowerCase(Locale.ROOT));
    }

    public CorrectionPipeline recommendedPipeline(SpectralDecontaminationConfig config) {
        if (config == null) return CorrectionPipeline.empty();

        String presetId;
        if (!config.getAutofluorescenceChannelIndexes().isEmpty()
                && config.getBleedThroughChannelIndexes().isEmpty()) {
            presetId = PRESET_AUTOFLUORESCENCE;
        } else if (!config.getBleedThroughChannelIndexes().isEmpty()
                && config.getAutofluorescenceChannelIndexes().isEmpty()) {
            presetId = PRESET_BLEED_THROUGH;
        } else if (config.hasContaminantChannels()) {
            presetId = PRESET_BASIC;
        } else {
            return CorrectionPipeline.empty();
        }

        PresetDefinition preset = getPreset(presetId);
        if (preset == null) return CorrectionPipeline.empty();

        CorrectionPipeline pipeline = new CorrectionPipeline();
        pipeline.setPresetId(preset.getId());
        pipeline.setExpertMode(false);
        pipeline.setFeatureIds(preset.getFeatureIds());
        return pipeline;
    }

    public List<CorrectionFeature> getAvailableNextFeatures(List<String> prefixFeatureIds,
                                                            SpectralDecontaminationConfig config,
                                                            boolean expertMode,
                                                            boolean existingObjectMapsAvailable) {
        List<String> prefix = prefixFeatureIds == null
                ? Collections.<String>emptyList()
                : prefixFeatureIds;
        CorrectionPipeline prefixPipeline = new CorrectionPipeline();
        prefixPipeline.setExpertMode(expertMode);
        prefixPipeline.setFeatureIds(prefix);

        List<String> prefixErrors = new ArrayList<String>();
        CorrectionResult state = prefixPipeline.evaluate(this, config, existingObjectMapsAvailable, prefixErrors);
        if (!prefixErrors.isEmpty()) {
            return new ArrayList<CorrectionFeature>();
        }

        LinkedHashSet<String> usedIds = new LinkedHashSet<String>(prefixPipeline.getFeatureIds());
        List<CorrectionFeature> available = new ArrayList<CorrectionFeature>();
        for (CorrectionFeature feature : featuresById.values()) {
            if (CorrectionPipeline.canAppendFeature(
                    feature, state, usedIds, config, expertMode, existingObjectMapsAvailable)) {
                available.add(feature);
            }
        }
        return available;
    }

    public int getMaxPresetSize() {
        int max = 6;
        for (PresetDefinition preset : presetsById.values()) {
            max = Math.max(max, preset.getFeatureIds().size());
        }
        return max;
    }

    private void register(CorrectionFeature feature) {
        featuresById.put(feature.getId(), feature);
    }

    private void registerPreset(PresetDefinition preset) {
        presetsById.put(preset.getId(), preset);
    }

    private static final class FeatureDefinition implements CorrectionFeature {
        private final String id;
        private final String displayName;
        private final String description;
        private final InputType requiredInputType;
        private final OutputType outputType;
        private final Set<RequiredChannel> requiredChannels;
        private final boolean requiresConditions;
        private final boolean requiresControls;
        private final boolean requiresExistingObjectMaps;
        private final boolean canPreviewCheaply;
        private final boolean expertOnly;
        private final boolean thresholdFeature;
        private final boolean requiresVetoMask;

        private FeatureDefinition(String id,
                                  String displayName,
                                  String description,
                                  InputType requiredInputType,
                                  OutputType outputType,
                                  Set<RequiredChannel> requiredChannels,
                                  boolean requiresConditions,
                                  boolean requiresControls,
                                  boolean requiresExistingObjectMaps,
                                  boolean canPreviewCheaply,
                                  boolean expertOnly,
                                  boolean thresholdFeature,
                                  boolean requiresVetoMask) {
            this.id = id;
            this.displayName = displayName;
            this.description = description;
            this.requiredInputType = requiredInputType;
            this.outputType = outputType;
            this.requiredChannels = Collections.unmodifiableSet(new LinkedHashSet<RequiredChannel>(requiredChannels));
            this.requiresConditions = requiresConditions;
            this.requiresControls = requiresControls;
            this.requiresExistingObjectMaps = requiresExistingObjectMaps;
            this.canPreviewCheaply = canPreviewCheaply;
            this.expertOnly = expertOnly;
            this.thresholdFeature = thresholdFeature;
            this.requiresVetoMask = requiresVetoMask;
        }

        @Override
        public String getId() {
            return id;
        }

        @Override
        public String getDisplayName() {
            return displayName;
        }

        @Override
        public String getDescription() {
            return description;
        }

        @Override
        public InputType getRequiredInputType() {
            return requiredInputType;
        }

        @Override
        public OutputType getOutputType() {
            return outputType;
        }

        @Override
        public Set<RequiredChannel> getRequiredChannels() {
            return requiredChannels;
        }

        @Override
        public boolean requiresConditions() {
            return requiresConditions;
        }

        @Override
        public boolean requiresControls() {
            return requiresControls;
        }

        @Override
        public boolean requiresExistingObjectMaps() {
            return requiresExistingObjectMaps;
        }

        @Override
        public boolean canPreviewCheaply() {
            return canPreviewCheaply;
        }

        @Override
        public boolean isExpertOnly() {
            return expertOnly;
        }

        @Override
        public boolean isThresholdFeature() {
            return thresholdFeature;
        }

        @Override
        public boolean requiresVetoMask() {
            return requiresVetoMask;
        }
    }

    public static final class PresetDefinition {
        private final String id;
        private final String displayName;
        private final String description;
        private final boolean expertOnly;
        private final List<String> featureIds;

        public PresetDefinition(String id,
                                String displayName,
                                String description,
                                boolean expertOnly,
                                List<String> featureIds) {
            this.id = id == null ? "" : id.trim().toLowerCase(Locale.ROOT);
            this.displayName = displayName == null ? "" : displayName.trim();
            this.description = description == null ? "" : description.trim();
            this.expertOnly = expertOnly;
            this.featureIds = Collections.unmodifiableList(new ArrayList<String>(featureIds));
        }

        public String getId() {
            return id;
        }

        public String getDisplayName() {
            return displayName;
        }

        public String getDescription() {
            return description;
        }

        public boolean isExpertOnly() {
            return expertOnly;
        }

        public List<String> getFeatureIds() {
            return featureIds;
        }
    }
}
