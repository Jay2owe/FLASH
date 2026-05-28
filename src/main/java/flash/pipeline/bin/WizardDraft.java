package flash.pipeline.bin;

import flash.pipeline.analyses.CreateBinFileAnalysis;
import flash.pipeline.zslice.ZSliceMode;
import flash.pipeline.zslice.ZSliceRange;
import flash.pipeline.zslice.ZSliceSelection;
import ij.IJ;

import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Properties;

public final class WizardDraft {
    public static final String DRAFT_DIR = ".draft";
    public static final String DRAFT_FILE = "wizard.properties";

    private WizardDraft() {
    }

    public static final class Snapshot {
        public final CreateBinFileAnalysis.BinUserConfig cfg;
        public final boolean[][] customSettings;
        public final int stepIndex;
        public final String stepLabel;
        public final long timestampMillis;

        public Snapshot(CreateBinFileAnalysis.BinUserConfig cfg,
                        boolean[][] customSettings,
                        int stepIndex,
                        String stepLabel,
                        long timestampMillis) {
            this.cfg = cfg;
            this.customSettings = copySettings(customSettings);
            this.stepIndex = stepIndex;
            this.stepLabel = stepLabel == null ? "" : stepLabel;
            this.timestampMillis = timestampMillis;
        }
    }

    public static void write(File binFolder, Snapshot snap) throws IOException {
        if (binFolder == null) throw new IOException("Cannot write wizard draft without a configuration folder.");
        if (snap == null) throw new IOException("Cannot write empty wizard draft.");

        Properties props = new Properties();
        CreateBinFileAnalysis.BinUserConfig cfg = snap.cfg;
        int channelCount = channelCount(cfg);
        props.setProperty("channel.count", String.valueOf(channelCount));
        for (int i = 0; i < channelCount; i++) {
            put(props, "channel." + i + ".name", valueAt(cfg == null ? null : cfg.names, i, ""));
            put(props, "channel." + i + ".color", valueAt(cfg == null ? null : cfg.colors, i, "Grays"));
            put(props, "channel." + i + ".threshold", valueAt(cfg == null ? null : cfg.objectThresholds, i, "default"));
            put(props, "channel." + i + ".size", valueAt(cfg == null ? null : cfg.sizes, i, "100-Infinity"));
            put(props, "channel." + i + ".minmax", valueAt(cfg == null ? null : cfg.minmax, i, "None"));
            put(props, "channel." + i + ".intensityThreshold", valueAt(cfg == null ? null : cfg.intensityThresholds, i, "default"));
            put(props, "channel." + i + ".segmentationMethod", valueAt(cfg == null ? null : cfg.segmentationMethods, i, "classical"));
            put(props, "channel." + i + ".filterPreset", valueAt(cfg == null ? null : cfg.filterPresets, i, "Default"));
        }

        if (cfg != null) {
            put(props, "zslice.mode", cfg.zSliceMode == null ? ZSliceMode.FULL.configToken : cfg.zSliceMode.configToken);
            props.setProperty("zslice.count", String.valueOf(cfg.zSliceSelections.size()));
            int idx = 0;
            for (Map.Entry<Integer, ZSliceSelection> entry : cfg.zSliceSelections.entrySet()) {
                ZSliceSelection selection = entry.getValue();
                if (selection == null || selection.range == null) continue;
                props.setProperty("zslice." + idx + ".seriesIndex", String.valueOf(selection.seriesIndex));
                put(props, "zslice." + idx + ".seriesName", selection.seriesName);
                props.setProperty("zslice." + idx + ".totalSlices", String.valueOf(selection.totalSlices));
                put(props, "zslice." + idx + ".range", selection.range.toToken());
                idx++;
            }
            props.setProperty("zslice.count", String.valueOf(idx));
        }

        writeCustomSettings(props, snap.customSettings);
        props.setProperty("step.index", String.valueOf(snap.stepIndex));
        put(props, "step.label", snap.stepLabel);
        props.setProperty("timestamp", String.valueOf(snap.timestampMillis));

        BinConfigIO.writeAtomic(draftFile(binFolder).toPath(), propertyLines(props));
    }

    public static Snapshot read(File binFolder) {
        File file = draftFile(binFolder);
        if (!file.isFile()) return null;

        try (Reader reader = Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8)) {
            Properties props = new Properties();
            props.load(reader);
            int channelCount = parseInt(required(props, "channel.count"), -1);
            int stepIndex = parseInt(required(props, "step.index"), -1);
            if (channelCount < 0 || stepIndex < 1 || stepIndex > 6) return corrupt(file, null);

            CreateBinFileAnalysis.BinUserConfig cfg = new CreateBinFileAnalysis.BinUserConfig(
                    readList(props, channelCount, "name", "Channel"),
                    readList(props, channelCount, "color", "Grays"),
                    readList(props, channelCount, "threshold", "default"),
                    readList(props, channelCount, "size", "100-Infinity"),
                    readList(props, channelCount, "minmax", "None"),
                    readList(props, channelCount, "filterPreset", "Default"),
                    readList(props, channelCount, "intensityThreshold", "default"));
            cfg.segmentationMethods.clear();
            cfg.segmentationMethods.addAll(readList(props, channelCount, "segmentationMethod", "classical"));
            cfg.zSliceMode = ZSliceMode.fromConfigToken(props.getProperty("zslice.mode", ZSliceMode.FULL.configToken));
            readZSliceSelections(props, cfg);

            return new Snapshot(cfg,
                    readCustomSettings(props),
                    stepIndex,
                    props.getProperty("step.label", ""),
                    parseLong(props.getProperty("timestamp"), 0L));
        } catch (Exception e) {
            return corrupt(file, e);
        }
    }

    public static void delete(File binFolder) {
        File file = draftFile(binFolder);
        if (file.isFile()) {
            try {
                Files.deleteIfExists(file.toPath());
            } catch (IOException e) {
                IJ.log("[WizardDraft] Failed to delete draft: " + file.getAbsolutePath());
            }
        }
        File dir = file.getParentFile();
        if (dir != null && dir.isDirectory()) {
            String[] names = dir.list();
            if (names != null && names.length == 0) {
                try {
                    Files.deleteIfExists(dir.toPath());
                } catch (IOException e) {
                    IJ.log("[WizardDraft] Failed to delete empty draft directory: " + dir.getAbsolutePath());
                }
            }
        }
    }

    public static boolean exists(File binFolder) {
        return draftFile(binFolder).isFile();
    }

    private static void writeCustomSettings(Properties props, boolean[][] settings) {
        int rows = settings == null ? 0 : settings.length;
        props.setProperty("customSettings.rows", String.valueOf(rows));
        for (int r = 0; r < rows; r++) {
            int cols = settings[r] == null ? 0 : settings[r].length;
            props.setProperty("customSettings." + r + ".cols", String.valueOf(cols));
            for (int c = 0; c < cols; c++) {
                props.setProperty("customSettings." + r + "." + c, String.valueOf(settings[r][c]));
            }
        }
    }

    private static boolean[][] readCustomSettings(Properties props) {
        int rows = parseInt(props.getProperty("customSettings.rows"), 0);
        if (rows <= 0) return null;
        boolean[][] settings = new boolean[rows][];
        for (int r = 0; r < rows; r++) {
            int cols = parseInt(props.getProperty("customSettings." + r + ".cols"), 0);
            settings[r] = new boolean[Math.max(0, cols)];
            for (int c = 0; c < settings[r].length; c++) {
                settings[r][c] = Boolean.parseBoolean(props.getProperty("customSettings." + r + "." + c, "false"));
            }
        }
        return settings;
    }

    private static void readZSliceSelections(Properties props, CreateBinFileAnalysis.BinUserConfig cfg) {
        int count = parseInt(props.getProperty("zslice.count"), 0);
        for (int i = 0; i < count; i++) {
            int seriesIndex = parseInt(props.getProperty("zslice." + i + ".seriesIndex"), -1);
            int totalSlices = parseInt(props.getProperty("zslice." + i + ".totalSlices"), -1);
            ZSliceRange range = ZSliceRange.parse(props.getProperty("zslice." + i + ".range"));
            if (seriesIndex < 0 || totalSlices < 1 || range == null || !range.isValidFor(totalSlices)) continue;
            cfg.zSliceSelections.put(Integer.valueOf(seriesIndex),
                    new ZSliceSelection(seriesIndex,
                            props.getProperty("zslice." + i + ".seriesName", ""),
                            totalSlices,
                            range));
        }
    }

    private static File draftFile(File binFolder) {
        File root = binFolder == null ? new File("") : binFolder;
        return new File(new File(root, DRAFT_DIR), DRAFT_FILE);
    }

    private static int channelCount(CreateBinFileAnalysis.BinUserConfig cfg) {
        if (cfg == null) return 0;
        int max = 0;
        max = Math.max(max, size(cfg.names));
        max = Math.max(max, size(cfg.colors));
        max = Math.max(max, size(cfg.objectThresholds));
        max = Math.max(max, size(cfg.sizes));
        max = Math.max(max, size(cfg.minmax));
        max = Math.max(max, size(cfg.filterPresets));
        max = Math.max(max, size(cfg.intensityThresholds));
        max = Math.max(max, size(cfg.segmentationMethods));
        return max;
    }

    private static int size(List<?> values) {
        return values == null ? 0 : values.size();
    }

    private static List<String> readList(Properties props, int count, String suffix, String fallback) {
        List<String> out = new ArrayList<String>();
        for (int i = 0; i < count; i++) {
            out.add(props.getProperty("channel." + i + "." + suffix,
                    "Channel".equals(fallback) ? "Channel" + (i + 1) : fallback));
        }
        return out;
    }

    private static String valueAt(List<String> values, int index, String fallback) {
        if (values == null || index < 0 || index >= values.size()) return fallback;
        String value = values.get(index);
        return value == null ? fallback : value;
    }

    private static void put(Properties props, String key, String value) {
        props.setProperty(key, value == null ? "" : value);
    }

    private static String required(Properties props, String key) {
        String value = props.getProperty(key);
        if (value == null) throw new IllegalArgumentException("Missing " + key);
        return value;
    }

    private static int parseInt(String value, int fallback) {
        try {
            return Integer.parseInt(value);
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    private static long parseLong(String value, long fallback) {
        try {
            return Long.parseLong(value);
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    private static Snapshot corrupt(File file, Exception e) {
        IJ.log("[WizardDraft] Ignoring corrupt draft: " + (file == null ? "" : file.getAbsolutePath()));
        return null;
    }

    private static boolean[][] copySettings(boolean[][] source) {
        if (source == null) return null;
        boolean[][] copy = new boolean[source.length][];
        for (int i = 0; i < source.length; i++) {
            copy[i] = source[i] == null ? null : source[i].clone();
        }
        return copy;
    }

    private static List<String> propertyLines(Properties props) {
        List<String> keys = new ArrayList<String>(props.stringPropertyNames());
        Collections.sort(keys);
        List<String> lines = new ArrayList<String>();
        for (int i = 0; i < keys.size(); i++) {
            String key = keys.get(i);
            lines.add(escapeProperty(key, true) + "="
                    + escapeProperty(props.getProperty(key, ""), false));
        }
        return lines;
    }

    private static String escapeProperty(String value, boolean key) {
        String text = value == null ? "" : value;
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch == '\\') out.append("\\\\");
            else if (ch == '\n') out.append("\\n");
            else if (ch == '\r') out.append("\\r");
            else if (ch == '\t') out.append("\\t");
            else if (ch == '=' || ch == ':' || ch == '#' || ch == '!') out.append('\\').append(ch);
            else if ((key || i == 0) && ch == ' ') out.append("\\ ");
            else out.append(ch);
        }
        return out.toString();
    }
}
