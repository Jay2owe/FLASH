package flash.pipeline.click;

import flash.pipeline.bin.BinConfigIO;
import flash.pipeline.ui.wizard.JsonIO;
import ij.IJ;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class ClicksConfigIO {
    public static final String FILE_NAME = "Clicks.json";
    private static final int VERSION = 1;

    private ClicksConfigIO() {
    }

    public static File file(File binFolder) {
        return binFolder == null ? null : new File(binFolder, FILE_NAME);
    }

    public static boolean exists(File binFolder) {
        File file = file(binFolder);
        return file != null && file.isFile();
    }

    public static ClickStore read(File binFolder) {
        ClickStore store = new ClickStore();
        File file = file(binFolder);
        if (file == null || !file.isFile()) return store;
        try {
            String json = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            Map<String, Object> root = JsonIO.parseObject(json);
            for (Object item : JsonIO.asList(root.get("clicks"))) {
                ClickStore.Click click = parseClick(JsonIO.asObject(item));
                if (click != null) {
                    store.add(click);
                }
            }
        } catch (IOException e) {
            IJ.log("[FLASH] Warning: could not read " + file.getAbsolutePath()
                    + ": " + e.getMessage() + ". Ignoring saved clicks.");
        } catch (RuntimeException e) {
            IJ.log("[FLASH] Warning: malformed " + file.getAbsolutePath()
                    + ": " + e.getMessage() + ". Ignoring saved clicks.");
        }
        return store;
    }

    public static void write(File binFolder, ClickStore store) throws IOException {
        File file = file(binFolder);
        if (file == null) return;
        String json = JsonIO.write(toJsonObject(store)) + "\n";
        List<String> lines = new ArrayList<String>();
        lines.add(json.trim());
        BinConfigIO.writeAtomic(file.toPath(), lines);
        BinConfigIO.updateClickPresence(binFolder, true);
    }

    private static Map<String, Object> toJsonObject(ClickStore store) {
        Map<String, Object> root = JsonIO.object();
        root.put("version", Integer.valueOf(VERSION));
        List<Object> rows = new ArrayList<Object>();
        List<ClickStore.Click> clicks = store == null
                ? new ArrayList<ClickStore.Click>()
                : store.all();
        for (ClickStore.Click click : clicks) {
            if (click == null) continue;
            Map<String, Object> row = JsonIO.object();
            row.put("image", click.imageName);
            row.put("channel", Integer.valueOf(click.channelOneBased));
            row.put("label", Integer.valueOf(click.label));
            row.put("z", Integer.valueOf(click.z));
            row.put("x", Double.valueOf(click.x));
            row.put("y", Double.valueOf(click.y));
            row.put("verdict", click.verdict == ClickStore.Verdict.POSITIVE
                    ? "positive"
                    : "negative");
            row.put("timestamp", Long.valueOf(click.timestampMs));
            rows.add(row);
        }
        root.put("clicks", rows);
        return root;
    }

    private static ClickStore.Click parseClick(Map<String, Object> row) {
        if (row == null || row.isEmpty()) return null;
        String image = JsonIO.stringValue(row.get("image"));
        int channel = JsonIO.intValue(row.get("channel"), -1);
        int label = JsonIO.intValue(row.get("label"), -1);
        int z = JsonIO.intValue(row.get("z"), 1);
        double x = doubleValue(row.get("x"), Double.NaN);
        double y = doubleValue(row.get("y"), Double.NaN);
        long timestamp = longValue(row.get("timestamp"), 0L);
        ClickStore.Verdict verdict = verdict(JsonIO.stringValue(row.get("verdict")));
        if (channel <= 0 || label <= 0 || !Double.isFinite(x) || !Double.isFinite(y)
                || verdict == null) {
            return null;
        }
        return new ClickStore.Click(image, channel, label, z, x, y, verdict, timestamp);
    }

    private static ClickStore.Verdict verdict(String raw) {
        if (raw == null) return null;
        String text = raw.trim().toLowerCase(Locale.ROOT);
        if ("positive".equals(text)) return ClickStore.Verdict.POSITIVE;
        if ("negative".equals(text)) return ClickStore.Verdict.NEGATIVE;
        return null;
    }

    private static double doubleValue(Object value, double fallback) {
        if (value instanceof Number) {
            return ((Number) value).doubleValue();
        }
        if (value == null) return fallback;
        try {
            return Double.parseDouble(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static long longValue(Object value, long fallback) {
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        if (value == null) return fallback;
        try {
            return Long.parseLong(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
