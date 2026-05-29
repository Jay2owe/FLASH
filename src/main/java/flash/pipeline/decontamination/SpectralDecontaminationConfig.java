package flash.pipeline.decontamination;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Persistent channel-role settings for Spectral Decontamination.
 */
public class SpectralDecontaminationConfig {

    public static final int CURRENT_VERSION = 4;

    private int version = CURRENT_VERSION;
    private int targetChannelIndex = 0;
    private Goal goal = Goal.CREATE_CLEANED_IMAGE;
    private ConditionSource conditionSource = ConditionSource.USE_EXISTING_CONDITION_FILE;
    private final List<Integer> bleedThroughChannelIndexes = new ArrayList<Integer>();
    private final List<Integer> autofluorescenceChannelIndexes = new ArrayList<Integer>();
    private final List<Integer> excludedChannelIndexes = new ArrayList<Integer>();
    private final List<String> controlConditionNames = new ArrayList<String>();
    private final List<String> experimentalConditionNames = new ArrayList<String>();
    private CorrectionPipeline correctionPipeline = CorrectionPipeline.empty();
    private final LinkedHashMap<String, CorrectionPipeline.Settings> featureSettingsById =
            new LinkedHashMap<String, CorrectionPipeline.Settings>();

    public enum Goal {
        CREATE_CLEANED_IMAGE("create_cleaned_image", "Create cleaned image", true),
        CREATE_CLEANED_MASK("create_cleaned_mask", "Create cleaned mask", true),
        SCORE_EXISTING_OBJECTS("score_existing_objects", "Score existing objects", false),
        MEASURE_CLEANED_SIGNAL_ONLY("measure_cleaned_signal_only", "Measure cleaned signal only", true);

        private final String key;
        private final String label;
        private final boolean requiresContaminant;

        Goal(String key, String label, boolean requiresContaminant) {
            this.key = key;
            this.label = label;
            this.requiresContaminant = requiresContaminant;
        }

        public String getKey() {
            return key;
        }

        public String getLabel() {
            return label;
        }

        public boolean requiresContaminant() {
            return requiresContaminant;
        }

        public static Goal fromKeyOrLabel(String value) {
            if (value == null || value.trim().isEmpty()) return CREATE_CLEANED_IMAGE;
            String normalized = value.trim();
            for (Goal goal : values()) {
                if (goal.key.equalsIgnoreCase(normalized)
                        || goal.label.equalsIgnoreCase(normalized)
                        || goal.name().equalsIgnoreCase(normalized)) {
                    return goal;
                }
            }
            return CREATE_CLEANED_IMAGE;
        }

        public static Goal fromLabel(String label) {
            return fromKeyOrLabel(label);
        }

        public static String[] labels() {
            Goal[] goals = values();
            String[] labels = new String[goals.length];
            for (int i = 0; i < goals.length; i++) {
                labels[i] = goals[i].getLabel();
            }
            return labels;
        }
    }

    public enum ConditionSource {
        USE_EXISTING_CONDITION_FILE("use_existing_condition_file", "Use existing condition file"),
        INFER_FROM_IMAGE_NAMES("infer_from_image_names", "Infer from image names"),
        ASSIGN_MANUALLY("assign_manually", "Assign manually");

        private final String key;
        private final String label;

        ConditionSource(String key, String label) {
            this.key = key;
            this.label = label;
        }

        public String getKey() {
            return key;
        }

        public String getLabel() {
            return label;
        }

        public static ConditionSource fromKeyOrLabel(String value) {
            if (value == null || value.trim().isEmpty()) return USE_EXISTING_CONDITION_FILE;
            String normalized = value.trim();
            for (ConditionSource source : values()) {
                if (source.key.equalsIgnoreCase(normalized)
                        || source.label.equalsIgnoreCase(normalized)
                        || source.name().equalsIgnoreCase(normalized)) {
                    return source;
                }
            }
            return USE_EXISTING_CONDITION_FILE;
        }

        public static ConditionSource fromLabel(String label) {
            return fromKeyOrLabel(label);
        }

        public static String[] labels() {
            ConditionSource[] sources = values();
            String[] labels = new String[sources.length];
            for (int i = 0; i < sources.length; i++) {
                labels[i] = sources[i].getLabel();
            }
            return labels;
        }
    }

    public static SpectralDecontaminationConfig defaults(int channelCount) {
        SpectralDecontaminationConfig config = new SpectralDecontaminationConfig();
        config.setTargetChannelIndex(channelCount > 0 ? 0 : -1);
        return config;
    }

    public int getVersion() {
        return version;
    }

    public void setVersion(int version) {
        this.version = version <= 0 ? CURRENT_VERSION : version;
    }

    public int getTargetChannelIndex() {
        return targetChannelIndex;
    }

    public void setTargetChannelIndex(int targetChannelIndex) {
        this.targetChannelIndex = targetChannelIndex;
    }

    public Goal getGoal() {
        return goal;
    }

    public void setGoal(Goal goal) {
        this.goal = goal == null ? Goal.CREATE_CLEANED_IMAGE : goal;
    }

    public ConditionSource getConditionSource() {
        return conditionSource;
    }

    public void setConditionSource(ConditionSource conditionSource) {
        this.conditionSource = conditionSource == null
                ? ConditionSource.USE_EXISTING_CONDITION_FILE
                : conditionSource;
    }

    public List<Integer> getBleedThroughChannelIndexes() {
        return Collections.unmodifiableList(bleedThroughChannelIndexes);
    }

    public void setBleedThroughChannelIndexes(List<Integer> indexes) {
        replaceIndexes(bleedThroughChannelIndexes, indexes);
    }

    public List<Integer> getAutofluorescenceChannelIndexes() {
        return Collections.unmodifiableList(autofluorescenceChannelIndexes);
    }

    public void setAutofluorescenceChannelIndexes(List<Integer> indexes) {
        replaceIndexes(autofluorescenceChannelIndexes, indexes);
    }

    public List<Integer> getExcludedChannelIndexes() {
        return Collections.unmodifiableList(excludedChannelIndexes);
    }

    public void setExcludedChannelIndexes(List<Integer> indexes) {
        replaceIndexes(excludedChannelIndexes, indexes);
    }

    public List<String> getControlConditionNames() {
        return Collections.unmodifiableList(controlConditionNames);
    }

    public void setControlConditionNames(List<String> names) {
        replaceStrings(controlConditionNames, names);
    }

    public List<String> getExperimentalConditionNames() {
        return Collections.unmodifiableList(experimentalConditionNames);
    }

    public void setExperimentalConditionNames(List<String> names) {
        replaceStrings(experimentalConditionNames, names);
    }

    public CorrectionPipeline getCorrectionPipeline() {
        return correctionPipeline == null ? CorrectionPipeline.empty() : correctionPipeline.copy();
    }

    public void setCorrectionPipeline(CorrectionPipeline correctionPipeline) {
        this.correctionPipeline = correctionPipeline == null
                ? CorrectionPipeline.empty()
                : correctionPipeline.copy();
    }

    public boolean hasCorrectionPipeline() {
        return correctionPipeline != null && !correctionPipeline.isEmpty();
    }

    public CorrectionPipeline.Settings getFeatureSettings(String featureId) {
        String cleaned = cleanFeatureId(featureId);
        CorrectionPipeline.Settings settings = featureSettingsById.get(cleaned);
        return settings == null ? new CorrectionPipeline.Settings() : settings.copy();
    }

    public void setFeatureSettings(String featureId, CorrectionPipeline.Settings settings) {
        String cleaned = cleanFeatureId(featureId);
        if (cleaned.isEmpty()) return;
        if (settings == null || settings.getValues().isEmpty()) {
            featureSettingsById.remove(cleaned);
            return;
        }
        featureSettingsById.put(cleaned, settings.copy());
    }

    public void setFeatureSettings(Map<String, CorrectionPipeline.Settings> settingsByFeatureId) {
        featureSettingsById.clear();
        if (settingsByFeatureId == null) return;
        for (Map.Entry<String, CorrectionPipeline.Settings> entry : settingsByFeatureId.entrySet()) {
            setFeatureSettings(entry.getKey(), entry.getValue());
        }
    }

    public Map<String, CorrectionPipeline.Settings> getFeatureSettingsById() {
        LinkedHashMap<String, CorrectionPipeline.Settings> copy =
                new LinkedHashMap<String, CorrectionPipeline.Settings>();
        for (Map.Entry<String, CorrectionPipeline.Settings> entry : featureSettingsById.entrySet()) {
            copy.put(entry.getKey(), entry.getValue().copy());
        }
        return Collections.unmodifiableMap(copy);
    }

    public boolean hasContaminantChannels() {
        return !bleedThroughChannelIndexes.isEmpty() || !autofluorescenceChannelIndexes.isEmpty();
    }

    public List<String> validate(int channelCount) {
        List<String> errors = new ArrayList<String>();

        if (channelCount <= 0) {
            errors.add("No channels are available. Run Set Up Configuration first.");
            return errors;
        }

        if (!isInRange(targetChannelIndex, channelCount)) {
            errors.add("Target channel must be one of the channels in channel_config.json.");
        }

        validateIndexes("Bleed-through channel", bleedThroughChannelIndexes, channelCount, errors);
        validateIndexes("Autofluorescence channel", autofluorescenceChannelIndexes, channelCount, errors);
        validateIndexes("Excluded channel", excludedChannelIndexes, channelCount, errors);

        if (bleedThroughChannelIndexes.contains(Integer.valueOf(targetChannelIndex))) {
            errors.add("Target channel cannot also be selected as a bleed-through channel.");
        }
        if (autofluorescenceChannelIndexes.contains(Integer.valueOf(targetChannelIndex))) {
            errors.add("Target channel cannot also be selected as an autofluorescence channel.");
        }
        if (excludedChannelIndexes.contains(Integer.valueOf(targetChannelIndex))) {
            errors.add("Target channel cannot be excluded.");
        }

        for (Integer excluded : excludedChannelIndexes) {
            if (bleedThroughChannelIndexes.contains(excluded)) {
                errors.add("Excluded channel cannot also be selected as a bleed-through channel.");
                break;
            }
        }
        for (Integer excluded : excludedChannelIndexes) {
            if (autofluorescenceChannelIndexes.contains(excluded)) {
                errors.add("Excluded channel cannot also be selected as an autofluorescence channel.");
                break;
            }
        }

        if (goal.requiresContaminant() && !hasContaminantChannels()) {
            errors.add("Select at least one bleed-through or autofluorescence channel for this goal.");
        }

        return errors;
    }

    public SpectralDecontaminationConfig copy() {
        SpectralDecontaminationConfig copy = new SpectralDecontaminationConfig();
        copy.setVersion(version);
        copy.setTargetChannelIndex(targetChannelIndex);
        copy.setGoal(goal);
        copy.setConditionSource(conditionSource);
        copy.setBleedThroughChannelIndexes(bleedThroughChannelIndexes);
        copy.setAutofluorescenceChannelIndexes(autofluorescenceChannelIndexes);
        copy.setExcludedChannelIndexes(excludedChannelIndexes);
        copy.setControlConditionNames(controlConditionNames);
        copy.setExperimentalConditionNames(experimentalConditionNames);
        copy.setCorrectionPipeline(correctionPipeline);
        copy.setFeatureSettings(featureSettingsById);
        return copy;
    }

    private static void replaceIndexes(List<Integer> destination, List<Integer> source) {
        destination.clear();
        if (source == null) return;
        Set<Integer> unique = new LinkedHashSet<Integer>();
        for (Integer value : source) {
            if (value != null) unique.add(value);
        }
        destination.addAll(unique);
    }

    private static void replaceStrings(List<String> destination, List<String> source) {
        destination.clear();
        if (source == null) return;
        Set<String> unique = new LinkedHashSet<String>();
        for (String value : source) {
            if (value == null) continue;
            String trimmed = value.trim();
            if (!trimmed.isEmpty()) unique.add(trimmed);
        }
        destination.addAll(unique);
    }

    private static void validateIndexes(String label, List<Integer> indexes, int channelCount, List<String> errors) {
        for (Integer index : indexes) {
            if (index == null || !isInRange(index.intValue(), channelCount)) {
                errors.add(label + " index is outside the channel list.");
                return;
            }
        }
    }

    private static boolean isInRange(int index, int channelCount) {
        return index >= 0 && index < channelCount;
    }

    private static String cleanFeatureId(String featureId) {
        return featureId == null ? "" : featureId.trim().toLowerCase(Locale.ROOT);
    }
}
