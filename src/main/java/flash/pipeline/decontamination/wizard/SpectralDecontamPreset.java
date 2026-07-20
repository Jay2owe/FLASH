package flash.pipeline.decontamination.wizard;

import flash.pipeline.decontamination.CorrectionPipeline;
import flash.pipeline.decontamination.SpectralDecontaminationConfig;
import flash.pipeline.ui.wizard.JsonIO;
import flash.pipeline.ui.wizard.Preset;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Full-run Spectral Decontamination wizard preset.
 */
public final class SpectralDecontamPreset implements Preset<SpectralDecontaminationConfig> {

    public static final String CURRENT_LIBRARY_VERSION = "1";

    private final String name;
    private final String description;
    private final String libraryVersion;
    private final SpectralDecontaminationConfig payload;
    private final String contaminationType;
    private final String calibration;
    private final String strength;

    public SpectralDecontamPreset(String name,
                                  String description,
                                  String libraryVersion,
                                  SpectralDecontaminationConfig payload,
                                  String contaminationType,
                                  String calibration,
                                  String strength) {
        this.name = requireText("name", name);
        this.description = emptyToNull(description);
        this.libraryVersion = emptyToNull(libraryVersion) == null
                ? CURRENT_LIBRARY_VERSION
                : libraryVersion.trim();
        this.payload = payload == null ? new SpectralDecontaminationConfig() : payload.copy();
        this.contaminationType = clean(contaminationType);
        this.calibration = clean(calibration);
        this.strength = clean(strength);
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public String getLibraryVersion() {
        return libraryVersion;
    }

    public SpectralDecontaminationConfig getPayload() {
        return payload.copy();
    }

    public String getContaminationType() {
        return contaminationType;
    }

    public String getCalibration() {
        return calibration;
    }

    public String getStrength() {
        return strength;
    }

    public Map<String, Object> toJsonObject() {
        Map<String, Object> root = new LinkedHashMap<String, Object>();
        root.put("name", name);
        if (description != null) {
            root.put("description", description);
        }
        root.put("libraryVersion", libraryVersion);
        root.put("contaminationType", contaminationType);
        root.put("calibration", calibration);
        root.put("strength", strength);
        root.put("goal", payload.getGoal().getKey());
        root.put("targetChannelIndex", Integer.valueOf(payload.getTargetChannelIndex()));
        root.put("bleedThroughChannelIndexes", integerList(payload.getBleedThroughChannelIndexes()));
        root.put("autofluorescenceChannelIndexes", integerList(payload.getAutofluorescenceChannelIndexes()));
        root.put("excludedChannelIndexes", integerList(payload.getExcludedChannelIndexes()));
        root.put("controlConditionNames", stringList(payload.getControlConditionNames()));
        root.put("experimentalConditionNames", stringList(payload.getExperimentalConditionNames()));

        CorrectionPipeline pipeline = payload.getCorrectionPipeline();
        root.put("correctionPresetId", pipeline.getPresetId());
        root.put("expertMode", Boolean.valueOf(pipeline.isExpertMode()));
        root.put("correctionFeatureIds", stringList(pipeline.getFeatureIds()));

        Map<String, Object> settingsRoot = new LinkedHashMap<String, Object>();
        for (Map.Entry<String, CorrectionPipeline.Settings> entry : payload.getFeatureSettingsById().entrySet()) {
            settingsRoot.put(entry.getKey(), new LinkedHashMap<String, Object>(entry.getValue().getValues()));
        }
        root.put("featureSettings", settingsRoot);
        return root;
    }

    public static SpectralDecontamPreset fromJson(String json) throws IOException {
        return fromJsonObject(JsonIO.parseObject(json));
    }

    public static SpectralDecontamPreset fromJsonObject(Map<String, Object> root) throws IOException {
        if (root == null) {
            throw new IOException("Preset JSON object is required.");
        }
        SpectralDecontaminationConfig config = new SpectralDecontaminationConfig();
        config.setGoal(SpectralDecontaminationConfig.Goal.fromKeyOrLabel(
                stringOr(root.get("goal"), SpectralDecontaminationConfig.Goal.CREATE_CLEANED_IMAGE.getKey())));
        config.setTargetChannelIndex(JsonIO.intValue(root.get("targetChannelIndex"), 0));
        config.setBleedThroughChannelIndexes(intList(root.get("bleedThroughChannelIndexes")));
        config.setAutofluorescenceChannelIndexes(intList(root.get("autofluorescenceChannelIndexes")));
        config.setExcludedChannelIndexes(intList(root.get("excludedChannelIndexes")));
        config.setControlConditionNames(strList(root.get("controlConditionNames")));
        config.setExperimentalConditionNames(strList(root.get("experimentalConditionNames")));

        CorrectionPipeline pipeline = new CorrectionPipeline();
        pipeline.setPresetId(stringOr(root.get("correctionPresetId"), CorrectionPipeline.CUSTOM_PRESET_ID));
        pipeline.setExpertMode(JsonIO.booleanValue(root.get("expertMode"), false));
        pipeline.setFeatureIds(strList(root.get("correctionFeatureIds")));
        config.setCorrectionPipeline(pipeline);
        config.setFeatureSettings(readSettings(root.get("featureSettings")));

        return new SpectralDecontamPreset(
                stringOr(root.get("name"), "Spectral Decontamination Preset"),
                JsonIO.stringValue(root.get("description")),
                stringOr(root.get("libraryVersion"), CURRENT_LIBRARY_VERSION),
                config,
                JsonIO.stringValue(root.get("contaminationType")),
                JsonIO.stringValue(root.get("calibration")),
                JsonIO.stringValue(root.get("strength")));
    }

    private static Map<String, CorrectionPipeline.Settings> readSettings(Object value) {
        Map<String, CorrectionPipeline.Settings> settings =
                new LinkedHashMap<String, CorrectionPipeline.Settings>();
        for (Map.Entry<String, Object> featureEntry : JsonIO.asObject(value).entrySet()) {
            CorrectionPipeline.Settings featureSettings = new CorrectionPipeline.Settings();
            for (Map.Entry<String, Object> settingEntry : JsonIO.asObject(featureEntry.getValue()).entrySet()) {
                featureSettings.put(settingEntry.getKey(), JsonIO.stringValue(settingEntry.getValue()));
            }
            settings.put(featureEntry.getKey(), featureSettings);
        }
        return settings;
    }

    private static List<Object> integerList(List<Integer> values) {
        List<Object> out = new ArrayList<Object>();
        if (values != null) {
            for (Integer value : values) {
                out.add(value);
            }
        }
        return out;
    }

    private static List<Object> stringList(List<String> values) {
        List<Object> out = new ArrayList<Object>();
        if (values != null) {
            out.addAll(values);
        }
        return out;
    }

    private static List<Integer> intList(Object value) {
        List<Integer> out = new ArrayList<Integer>();
        for (Object item : JsonIO.asList(value)) {
            out.add(Integer.valueOf(JsonIO.intValue(item, 0)));
        }
        return out;
    }

    private static List<String> strList(Object value) {
        List<String> out = new ArrayList<String>();
        for (Object item : JsonIO.asList(value)) {
            String text = JsonIO.stringValue(item);
            if (text != null && !text.trim().isEmpty()) {
                out.add(text.trim());
            }
        }
        return out;
    }

    private static String stringOr(Object value, String fallback) {
        String text = JsonIO.stringValue(value);
        return text == null || text.trim().isEmpty() ? fallback : text.trim();
    }

    private static String requireText(String label, String value) {
        String trimmed = emptyToNull(value);
        if (trimmed == null) {
            throw new IllegalArgumentException(label + " is required.");
        }
        return trimmed;
    }

    private static String emptyToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String clean(String value) {
        String text = emptyToNull(value);
        return text == null ? "" : text;
    }
}
