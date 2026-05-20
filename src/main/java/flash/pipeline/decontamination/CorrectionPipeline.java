package flash.pipeline.decontamination;

import ij.ImagePlus;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Persisted ordered stack of Spectral Decontamination features.
 */
public class CorrectionPipeline {

    public static final String CUSTOM_PRESET_ID = "custom";

    private String presetId = CUSTOM_PRESET_ID;
    private boolean expertMode = false;
    private final List<String> featureIds = new ArrayList<String>();

    public static CorrectionPipeline empty() {
        return new CorrectionPipeline();
    }

    public String getPresetId() {
        return presetId;
    }

    public void setPresetId(String presetId) {
        String cleaned = presetId == null ? "" : presetId.trim();
        this.presetId = cleaned.isEmpty() ? CUSTOM_PRESET_ID : cleaned;
    }

    public boolean isExpertMode() {
        return expertMode;
    }

    public void setExpertMode(boolean expertMode) {
        this.expertMode = expertMode;
    }

    public List<String> getFeatureIds() {
        return Collections.unmodifiableList(featureIds);
    }

    public void setFeatureIds(List<String> ids) {
        featureIds.clear();
        if (ids == null) return;
        Set<String> unique = new LinkedHashSet<String>();
        for (String id : ids) {
            String cleaned = cleanId(id);
            if (!cleaned.isEmpty()) unique.add(cleaned);
        }
        featureIds.addAll(unique);
    }

    public boolean isEmpty() {
        return featureIds.isEmpty();
    }

    public CorrectionPipeline copy() {
        CorrectionPipeline copy = new CorrectionPipeline();
        copy.setPresetId(presetId);
        copy.setExpertMode(expertMode);
        copy.setFeatureIds(featureIds);
        return copy;
    }

    public List<String> validate(CorrectionFeatureRegistry registry,
                                 SpectralDecontaminationConfig config,
                                 boolean existingObjectMapsAvailable) {
        List<String> errors = new ArrayList<String>();
        if (featureIds.isEmpty()) {
            errors.add("Select at least one correction feature.");
            return errors;
        }
        evaluate(registry, config, existingObjectMapsAvailable, errors);
        return errors;
    }

    public ExecutionState execute(CorrectionFeatureRegistry registry,
                                  SpectralDecontaminationConfig config,
                                  ImagePlus sourceImage) {
        return execute(registry, ExecutionState.create(sourceImage, config));
    }

    public ExecutionState execute(CorrectionFeatureRegistry registry,
                                  ExecutionState state) {
        if (registry == null) {
            throw new IllegalArgumentException("Correction feature registry is not available.");
        }
        if (state == null) {
            throw new IllegalArgumentException("Execution state is required.");
        }
        if (featureIds.isEmpty()) {
            return state;
        }

        List<String> errors = validate(registry, state.getConfig(), false);
        if (!errors.isEmpty()) {
            throw new IllegalStateException(join(errors, " | "));
        }

        for (String featureId : featureIds) {
            CorrectionFeature feature = registry.getFeature(featureId);
            if (feature == null) {
                throw new IllegalStateException("Unknown correction feature: " + featureId);
            }
            if (!(feature instanceof ExecutableFeature)) {
                throw new UnsupportedOperationException(
                        "Correction feature is not executable yet: " + feature.getDisplayName());
            }
            ((ExecutableFeature) feature).apply(state);
        }
        return state;
    }

    CorrectionResult evaluate(CorrectionFeatureRegistry registry,
                              SpectralDecontaminationConfig config,
                              boolean existingObjectMapsAvailable,
                              List<String> errors) {
        CorrectionResult result = CorrectionResult.initial();
        Set<String> usedIds = new LinkedHashSet<String>();
        if (registry == null) {
            if (errors != null) errors.add("Correction feature registry is not available.");
            return result;
        }

        for (String featureId : featureIds) {
            CorrectionFeature feature = registry.getFeature(featureId);
            if (feature == null) {
                if (errors != null) {
                    errors.add("Unknown correction feature: " + featureId);
                }
                return result;
            }

            List<String> featureErrors = validateFeature(
                    feature, result, usedIds, config, expertMode, existingObjectMapsAvailable);
            if (!featureErrors.isEmpty()) {
                if (errors != null) {
                    for (String featureError : featureErrors) {
                        errors.add(feature.getDisplayName() + ": " + featureError);
                    }
                }
                return result;
            }

            usedIds.add(feature.getId());
            result = result.after(feature);
        }

        return result;
    }

    static boolean canAppendFeature(CorrectionFeature feature,
                                    CorrectionResult state,
                                    Set<String> usedIds,
                                    SpectralDecontaminationConfig config,
                                    boolean expertMode,
                                    boolean existingObjectMapsAvailable) {
        return validateFeature(feature, state, usedIds, config, expertMode, existingObjectMapsAvailable).isEmpty();
    }

    static List<String> validateFeature(CorrectionFeature feature,
                                        CorrectionResult state,
                                        Set<String> usedIds,
                                        SpectralDecontaminationConfig config,
                                        boolean expertMode,
                                        boolean existingObjectMapsAvailable) {
        List<String> errors = new ArrayList<String>();
        if (feature == null) {
            errors.add("Feature metadata is missing.");
            return errors;
        }

        if (usedIds != null && usedIds.contains(feature.getId())) {
            errors.add("This feature is already in the correction stack.");
        }
        if (feature.isExpertOnly() && !expertMode) {
            errors.add("Enable expert mode to use this feature.");
        }
        if (state == null) {
            errors.add("Pipeline state is missing.");
            return errors;
        }
        if (!state.hasInput(feature.getRequiredInputType())) {
            errors.add(feature.getRequiredInputType().getLabel() + " is not available yet.");
        }
        if (feature.requiresVetoMask() && !state.hasVetoMask()) {
            errors.add("Add a veto-mask feature earlier in the stack.");
        }
        if (feature.isThresholdFeature() && state.getThresholdFeatureCount() > 0 && !expertMode) {
            errors.add("Only one threshold-based mask feature is allowed unless expert mode is enabled.");
        }

        if (config == null) return errors;

        Set<CorrectionFeature.RequiredChannel> requiredChannels = feature.getRequiredChannels();
        if (requiredChannels.contains(CorrectionFeature.RequiredChannel.TARGET)
                && config.getTargetChannelIndex() < 0) {
            errors.add("Select a target channel first.");
        }
        if (requiredChannels.contains(CorrectionFeature.RequiredChannel.BLEED_THROUGH)
                && config.getBleedThroughChannelIndexes().isEmpty()) {
            errors.add("Select at least one bleed-through channel first.");
        }
        if (requiredChannels.contains(CorrectionFeature.RequiredChannel.AUTOFLUORESCENCE)
                && config.getAutofluorescenceChannelIndexes().isEmpty()) {
            errors.add("Select at least one autofluorescence channel first.");
        }
        if (requiredChannels.contains(CorrectionFeature.RequiredChannel.CONTAMINANT)
                && !config.hasContaminantChannels()) {
            errors.add("Select at least one bleed-through or autofluorescence channel first.");
        }
        if (feature.requiresConditions()
                && config.getControlConditionNames().isEmpty()
                && config.getExperimentalConditionNames().isEmpty()) {
            errors.add("Assign control or experimental conditions first.");
        }
        if (feature.requiresControls() && config.getControlConditionNames().isEmpty()) {
            errors.add("Select at least one control condition first.");
        }
        if (feature.requiresConditions() && config.getExperimentalConditionNames().isEmpty()) {
            errors.add("Select at least one experimental condition first.");
        }
        if (feature.requiresExistingObjectMaps() && !existingObjectMapsAvailable) {
            errors.add("Existing object maps are required for this feature.");
        }

        return errors;
    }

    public String describe(CorrectionFeatureRegistry registry) {
        List<String> labels = new ArrayList<String>();
        if (registry != null) {
            for (String featureId : featureIds) {
                CorrectionFeature feature = registry.getFeature(featureId);
                labels.add(feature == null ? featureId : feature.getDisplayName());
            }
        } else {
            labels.addAll(featureIds);
        }
        return labels.isEmpty() ? "none" : join(labels, " -> ");
    }

    public interface ExecutableFeature extends CorrectionFeature {
        void apply(ExecutionState state);
    }

    public static final class ExecutionState {
        private final ImagePlus sourceImage;
        private final SpectralDecontaminationConfig config;
        private final LinkedHashMap<String, Settings> settingsByFeatureId =
                new LinkedHashMap<String, Settings>();
        private final LinkedHashMap<String, ImagePlus> parameterMaps =
                new LinkedHashMap<String, ImagePlus>();
        private final List<FeatureSummary> featureSummaries = new ArrayList<FeatureSummary>();

        private ImagePlus correctedImage;
        private ImagePlus maskImage;
        private ImagePlus vetoMaskImage;

        private ExecutionState(ImagePlus sourceImage, SpectralDecontaminationConfig config) {
            if (sourceImage == null) {
                throw new IllegalArgumentException("Source image is required.");
            }
            if (config == null) {
                throw new IllegalArgumentException("Spectral Decontamination config is required.");
            }
            this.sourceImage = sourceImage;
            this.config = config;
            for (Map.Entry<String, Settings> entry : config.getFeatureSettingsById().entrySet()) {
                settingsByFeatureId.put(entry.getKey(), entry.getValue().copy());
            }
        }

        public static ExecutionState create(ImagePlus sourceImage,
                                            SpectralDecontaminationConfig config) {
            return new ExecutionState(sourceImage, config);
        }

        public ImagePlus getSourceImage() {
            return sourceImage;
        }

        public SpectralDecontaminationConfig getConfig() {
            return config;
        }

        public Settings getFeatureSettings(String featureId) {
            Settings settings = settingsByFeatureId.get(cleanId(featureId));
            return settings == null ? new Settings() : settings.copy();
        }

        public void setFeatureSettings(String featureId, Settings settings) {
            String cleaned = cleanId(featureId);
            if (cleaned.isEmpty()) return;
            settingsByFeatureId.put(cleaned, settings == null ? new Settings() : settings.copy());
        }

        public ImagePlus getCorrectedImage() {
            return correctedImage;
        }

        public void setCorrectedImage(ImagePlus correctedImage) {
            this.correctedImage = correctedImage;
        }

        public ImagePlus getMaskImage() {
            return maskImage;
        }

        public void setMaskImage(ImagePlus maskImage) {
            this.maskImage = maskImage;
        }

        public ImagePlus getVetoMaskImage() {
            return vetoMaskImage;
        }

        public void setVetoMaskImage(ImagePlus vetoMaskImage) {
            this.vetoMaskImage = vetoMaskImage;
        }

        public List<FeatureSummary> getFeatureSummaries() {
            return Collections.unmodifiableList(featureSummaries);
        }

        public void addSummary(FeatureSummary summary) {
            if (summary != null) {
                featureSummaries.add(summary);
            }
        }

        public Map<String, ImagePlus> getParameterMaps() {
            return Collections.unmodifiableMap(parameterMaps);
        }

        public void putParameterMap(String key, ImagePlus parameterMap) {
            String cleaned = Settings.cleanKey(key);
            if (cleaned.isEmpty() || parameterMap == null) {
                return;
            }
            parameterMaps.put(cleaned, parameterMap);
        }
    }

    public static final class Settings {
        private final LinkedHashMap<String, String> values = new LinkedHashMap<String, String>();

        public Settings put(String key, String value) {
            String cleaned = cleanKey(key);
            if (!cleaned.isEmpty()) {
                values.put(cleaned, value == null ? "" : value.trim());
            }
            return this;
        }

        public Settings putDouble(String key, double value) {
            return put(key, Double.toString(value));
        }

        public Settings putInt(String key, long value) {
            return put(key, String.valueOf(value));
        }

        public String get(String key, String defaultValue) {
            String cleaned = cleanKey(key);
            if (cleaned.isEmpty()) return defaultValue;
            String value = values.get(cleaned);
            return value == null ? defaultValue : value;
        }

        public double getDouble(String key, double defaultValue) {
            String value = get(key, null);
            if (value == null || value.trim().isEmpty()) return defaultValue;
            try {
                return Double.parseDouble(value.trim());
            } catch (NumberFormatException e) {
                return defaultValue;
            }
        }

        public int getInt(String key, int defaultValue) {
            String value = get(key, null);
            if (value == null || value.trim().isEmpty()) return defaultValue;
            try {
                return Integer.parseInt(value.trim());
            } catch (NumberFormatException e) {
                return defaultValue;
            }
        }

        public Map<String, String> getValues() {
            return Collections.unmodifiableMap(values);
        }

        public Settings copy() {
            Settings copy = new Settings();
            copy.values.putAll(values);
            return copy;
        }

        private static String cleanKey(String key) {
            if (key == null) return "";
            return key.trim().toLowerCase(Locale.US);
        }
    }

    public static final class FeatureSummary {
        private final String featureId;
        private final String featureName;
        private final LinkedHashMap<String, String> values = new LinkedHashMap<String, String>();

        public FeatureSummary(String featureId, String featureName) {
            this.featureId = cleanId(featureId);
            this.featureName = featureName == null ? "" : featureName.trim();
        }

        public String getFeatureId() {
            return featureId;
        }

        public String getFeatureName() {
            return featureName;
        }

        public FeatureSummary put(String key, String value) {
            String cleaned = Settings.cleanKey(key);
            if (!cleaned.isEmpty()) {
                values.put(cleaned, value == null ? "" : value.trim());
            }
            return this;
        }

        public FeatureSummary putDouble(String key, double value) {
            if (Double.isNaN(value) || Double.isInfinite(value)) {
                return put(key, "");
            }
            return put(key, String.format(Locale.US, "%.6f", value));
        }

        public FeatureSummary putInt(String key, long value) {
            return put(key, String.valueOf(value));
        }

        public Map<String, String> getValues() {
            return Collections.unmodifiableMap(values);
        }
    }

    private static String cleanId(String value) {
        if (value == null) return "";
        return value.trim().toLowerCase(Locale.US);
    }

    private static String join(List<String> values, String separator) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) sb.append(separator);
            sb.append(values.get(i));
        }
        return sb.toString();
    }
}
