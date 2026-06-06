package flash.pipeline.orientation;

import flash.pipeline.bin.BinConfigIO;
import flash.pipeline.io.FlashProjectLayout;
import flash.pipeline.io.IoUtils;
import ij.IJ;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/** Project-scoped persistence for ROI orientation preset buttons. */
public final class OrientationPresetStore {
    public static final String FILE_NAME = "orientation_presets.json";

    private final File settingsDir;
    private final File file;

    public OrientationPresetStore(String directory) {
        this.settingsDir = FlashProjectLayout.forDirectory(directory).configurationWriteDir();
        this.file = new File(settingsDir, FILE_NAME);
    }

    public List<OrientationPreset> load() {
        if (!file.isFile()) {
            return new ArrayList<OrientationPreset>();
        }
        try {
            String text = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            return new ArrayList<OrientationPreset>(OrientationPresetCodec.decode(text));
        } catch (Exception e) {
            logWarning("Could not read orientation presets from "
                    + file.getAbsolutePath() + ": " + e.getMessage());
            return new ArrayList<OrientationPreset>();
        }
    }

    public void save(List<OrientationPreset> presets) throws IOException {
        IoUtils.mustMkdirs(settingsDir);
        String encoded = OrientationPresetCodec.encode(presets);
        BinConfigIO.writeAtomic(file.toPath(), Arrays.asList(encoded));
    }

    public List<OrientationPreset> add(OrientationPreset preset) throws IOException {
        if (preset == null) {
            throw new IllegalArgumentException("Preset must not be null.");
        }
        List<OrientationPreset> presets = load();
        int existing = indexOfName(presets, preset.name);
        if (existing >= 0) {
            presets.set(existing, preset);
        } else {
            presets.add(preset);
        }
        save(presets);
        return presets;
    }

    public List<OrientationPreset> removeByName(String name) throws IOException {
        List<OrientationPreset> presets = load();
        int existing = indexOfName(presets, name);
        if (existing >= 0) {
            presets.remove(existing);
        }
        save(presets);
        return presets;
    }

    private static int indexOfName(List<OrientationPreset> presets, String name) {
        String needle = normalizeName(name);
        if (needle.length() == 0 || presets == null) {
            return -1;
        }
        for (int i = 0; i < presets.size(); i++) {
            OrientationPreset preset = presets.get(i);
            if (preset != null && needle.equals(normalizeName(preset.name))) {
                return i;
            }
        }
        return -1;
    }

    private static String normalizeName(String name) {
        return name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
    }

    private static void logWarning(String message) {
        try {
            IJ.log("[DrawROIs] WARN: " + message);
        } catch (Throwable ignored) {
            // Logging is best-effort; loading malformed presets must never
            // require a live ImageJ UI.
        }
    }
}
