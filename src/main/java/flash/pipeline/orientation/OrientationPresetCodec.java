package flash.pipeline.orientation;

import flash.pipeline.intelligence.MiniJson;
import flash.pipeline.naming.OrientationManifestRow;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** JSON codec for project-scoped ROI orientation presets. */
public final class OrientationPresetCodec {
    private static final int VERSION = 1;

    private static final String K_VERSION = "version";
    private static final String K_PRESETS = "presets";
    private static final String K_NAME = "name";
    private static final String K_ROTATE = "rotate";
    private static final String K_FLIP_H = "flipH";
    private static final String K_FLIP_V = "flipV";

    private OrientationPresetCodec() {
    }

    public static String encode(List<OrientationPreset> presets) {
        Map<String, Object> root = new LinkedHashMap<String, Object>();
        root.put(K_VERSION, Integer.valueOf(VERSION));
        root.put(K_PRESETS, presetsToJson(presets));
        return MiniJson.write(root);
    }

    public static List<OrientationPreset> decode(String json) throws IOException {
        Object parsed = MiniJson.parse(json);
        if (!(parsed instanceof Map)) {
            throw new IOException("Orientation preset JSON root must be an object.");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> root = (Map<String, Object>) parsed;

        int version = intValue(root.get(K_VERSION), -1);
        if (version != VERSION) {
            throw new IOException("Unsupported orientation preset JSON version: " + version);
        }

        Object rawPresets = root.get(K_PRESETS);
        if (!(rawPresets instanceof List)) {
            throw new IOException("Orientation preset JSON presets field must be an array.");
        }
        @SuppressWarnings("unchecked")
        List<Object> rows = (List<Object>) rawPresets;

        List<OrientationPreset> presets = new ArrayList<OrientationPreset>();
        for (int i = 0; i < rows.size(); i++) {
            OrientationPreset preset = presetFromJson(rows.get(i));
            if (preset != null) {
                presets.add(preset);
            }
        }
        return presets;
    }

    private static List<Object> presetsToJson(List<OrientationPreset> presets) {
        List<Object> out = new ArrayList<Object>();
        if (presets == null) {
            return out;
        }
        for (int i = 0; i < presets.size(); i++) {
            OrientationPreset preset = presets.get(i);
            if (preset == null) {
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<String, Object>();
            OrientationTransformState transform = preset.transform == null
                    ? OrientationTransformState.identity()
                    : preset.transform;
            row.put(K_NAME, preset.name);
            row.put(K_ROTATE, Integer.valueOf(transform.rotateDegrees.degrees()));
            row.put(K_FLIP_H, Boolean.valueOf(transform.flipHorizontal));
            row.put(K_FLIP_V, Boolean.valueOf(transform.flipVertical));
            out.add(row);
        }
        return out;
    }

    private static OrientationPreset presetFromJson(Object value) {
        if (!(value instanceof Map)) {
            return null;
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> row = (Map<String, Object>) value;

        String name = stringValue(row.get(K_NAME));
        OrientationManifestRow.RotationDegrees rotation = rotationValue(row.get(K_ROTATE));
        if (name == null || name.trim().isEmpty() || rotation == null) {
            return null;
        }

        try {
            return new OrientationPreset(
                    name,
                    new OrientationTransformState(
                            rotation,
                            booleanValue(row.get(K_FLIP_H), false),
                            booleanValue(row.get(K_FLIP_V), false)));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static OrientationManifestRow.RotationDegrees rotationValue(Object value) {
        Integer degrees = integerToken(value);
        if (degrees == null) {
            return null;
        }
        int raw = degrees.intValue();
        if (raw != 0 && raw != 90 && raw != 180 && raw != 270) {
            return null;
        }
        return OrientationManifestRow.RotationDegrees.fromDegrees(raw);
    }

    private static int intValue(Object value, int fallback) {
        Integer parsed = integerToken(value);
        return parsed == null ? fallback : parsed.intValue();
    }

    private static Integer integerToken(Object value) {
        if (value instanceof Number) {
            Number number = (Number) value;
            double doubleValue = number.doubleValue();
            int intValue = number.intValue();
            return doubleValue == intValue ? Integer.valueOf(intValue) : null;
        }
        if (value == null) {
            return null;
        }
        try {
            return Integer.valueOf(Integer.parseInt(String.valueOf(value).trim()));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static boolean booleanValue(Object value, boolean fallback) {
        if (value instanceof Boolean) {
            return ((Boolean) value).booleanValue();
        }
        return value == null ? fallback : Boolean.parseBoolean(String.valueOf(value));
    }

    private static String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
