package flash.pipeline.bin;

import flash.pipeline.zslice.ZSliceMode;
import flash.pipeline.zslice.ZSliceRange;
import flash.pipeline.zslice.ZSliceSelection;
import ij.IJ;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ChannelConfigIO {
    public static final String FILE_NAME = "channel_config.json";

    private static final List<String> PROPERTIES = Arrays.asList(
            ChannelConfig.P_NAME,
            ChannelConfig.P_COLOR,
            ChannelConfig.P_MARKER,
            ChannelConfig.P_THRESHOLD,
            ChannelConfig.P_SIZE,
            ChannelConfig.P_MINMAX,
            ChannelConfig.P_INTENSITY,
            ChannelConfig.P_SEGMENTATION,
            ChannelConfig.P_FILTER);

    private ChannelConfigIO() {
    }

    public static void write(File settingsDir, ChannelConfig cfg) throws IOException {
        if (settingsDir == null) {
            throw new IOException("Cannot write channel_config.json without a settings directory.");
        }
        BinConfigIO.writeAtomic(new File(settingsDir, FILE_NAME).toPath(),
                Arrays.asList(ChannelConfigCodec.encode(cfg)));
    }

    public static ChannelConfig read(File settingsDir) {
        File file = file(settingsDir);
        if (file == null || !file.isFile()) {
            return null;
        }
        try {
            return ChannelConfigCodec.decode(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
        } catch (IOException e) {
            IJ.log("[FLASH] Could not read " + file.getAbsolutePath() + ": " + e.getMessage());
            return null;
        }
    }

    public static boolean exists(File settingsDir) {
        File file = file(settingsDir);
        return file != null && file.isFile();
    }

    public static BinConfig toBinConfig(ChannelConfig cfg) {
        BinConfig out = new BinConfig();
        if (cfg == null) {
            return out;
        }
        if (cfg.channels != null) {
            for (int i = 0; i < cfg.channels.size(); i++) {
                ChannelConfig.Channel channel = cfg.channels.get(i);
                if (channel == null) {
                    continue;
                }
                out.channelNames.add(value(channel.name, "Channel" + (i + 1)));
                out.channelColors.add(value(channel.color, "Grays"));
                out.channelThresholds.add(valueForStatus(channel, ChannelConfig.P_THRESHOLD,
                        channel.threshold, "default"));
                out.channelSizes.add(valueForStatus(channel, ChannelConfig.P_SIZE,
                        channel.size, "100-Infinity"));
                out.channelMinMax.add(valueForStatus(channel, ChannelConfig.P_MINMAX,
                        channel.minmax, "None"));
                out.channelIntensityThresholds.add(valueForStatus(channel, ChannelConfig.P_INTENSITY,
                        channel.intensityThreshold, "default"));
                out.addSegmentationMethodToken(valueForStatus(channel, ChannelConfig.P_SEGMENTATION,
                        channel.segmentationMethod, "classical:otsu"));
                out.channelFilterPresets.add(valueForStatus(channel, ChannelConfig.P_FILTER,
                        channel.filterPreset, "Default"));
            }
        }
        out.zSliceMode = cfg.zSliceMode == null ? ZSliceMode.FULL : cfg.zSliceMode;
        out.zSliceConfigPresent = true;
        out.clickConfigPresent = cfg.clickCaptureUsed;
        copyZSliceSelections(cfg, out);
        return out;
    }

    public static ChannelConfig fromBinUserConfig(Object user) {
        ChannelConfig cfg = new ChannelConfig();
        cfg.writerId = "FLASH";
        cfg.writtenAtMillis = System.currentTimeMillis();
        if (user == null) {
            return cfg;
        }

        List<String> names = stringList(field(user, "names"));
        List<String> colors = stringList(field(user, "colors"));
        List<String> thresholds = stringList(field(user, "objectThresholds"));
        List<String> sizes = stringList(field(user, "sizes"));
        List<String> minmax = stringList(field(user, "minmax"));
        List<String> filters = stringList(field(user, "filterPresets"));
        List<String> intensity = stringList(field(user, "intensityThresholds"));
        List<String> segmentation = stringList(field(user, "segmentationMethods"));
        List<String> markerIds = stringList(field(user, "markerIds"));
        List<String> markerShapes = stringList(field(user, "markerShapes"));
        List<Boolean> crowding = booleanList(field(user, "markerCrowdingSensitive"));

        int n = max(names, colors, thresholds, sizes, minmax, filters, intensity, segmentation);
        for (int i = 0; i < n; i++) {
            ChannelConfig.Channel channel = new ChannelConfig.Channel();
            channel.index = i;
            channel.name = valueAt(names, i, "Channel" + (i + 1));
            channel.color = valueAt(colors, i, "Grays");
            channel.threshold = valueAt(thresholds, i, "default");
            channel.size = valueAt(sizes, i, "100-Infinity");
            channel.minmax = valueAt(minmax, i, "None");
            channel.filterPreset = valueAt(filters, i, "Default");
            channel.intensityThreshold = valueAt(intensity, i, "default");
            channel.segmentationMethod = valueAt(segmentation, i, "classical");
            channel.markerId = valueAt(markerIds, i, "");
            channel.markerShape = valueAt(markerShapes, i, "");
            channel.markerCrowdingSensitive = valueAt(crowding, i, Boolean.FALSE).booleanValue();
            markCommitted(channel);
            cfg.channels.add(channel);
        }

        Object mode = field(user, "zSliceMode");
        cfg.zSliceMode = mode instanceof ZSliceMode ? (ZSliceMode) mode : ZSliceMode.FULL;
        copyUserZSliceSelections(field(user, "zSliceSelections"), cfg);
        return cfg;
    }

    static boolean allChannelsCommitted(ChannelConfig cfg) {
        if (cfg == null || cfg.channels == null || cfg.channels.isEmpty()) {
            return false;
        }
        for (int i = 0; i < cfg.channels.size(); i++) {
            ChannelConfig.Channel channel = cfg.channels.get(i);
            if (channel == null) {
                return false;
            }
            for (int p = 0; p < PROPERTIES.size(); p++) {
                if (statusOf(channel, PROPERTIES.get(p)) != ChannelConfig.PropertyStatus.COMMITTED) {
                    return false;
                }
            }
        }
        return true;
    }

    private static File file(File settingsDir) {
        return settingsDir == null ? null : new File(settingsDir, FILE_NAME);
    }

    private static String valueForStatus(ChannelConfig.Channel channel, String prop,
                                         String value, String pendingValue) {
        if (statusOf(channel, prop) == ChannelConfig.PropertyStatus.PENDING) {
            return pendingValue;
        }
        return value(value, pendingValue);
    }

    private static ChannelConfig.PropertyStatus statusOf(ChannelConfig.Channel channel, String prop) {
        if (channel == null || channel.status == null) {
            return ChannelConfig.PropertyStatus.PENDING;
        }
        return channel.statusOf(prop);
    }

    private static String value(String value, String fallback) {
        return value == null || value.trim().isEmpty() ? fallback : value;
    }

    private static void copyZSliceSelections(ChannelConfig cfg, BinConfig out) {
        if (cfg.zSliceSelections == null) {
            return;
        }
        for (Map.Entry<String, ZSliceRange> entry : cfg.zSliceSelections.entrySet()) {
            Integer seriesIndex = parseInteger(entry.getKey());
            ZSliceRange range = entry.getValue();
            if (seriesIndex == null || range == null) {
                continue;
            }
            out.zSliceSelections.put(seriesIndex, new ZSliceSelection(
                    seriesIndex.intValue(), "", range.endSlice, range));
        }
    }

    private static void copyUserZSliceSelections(Object source, ChannelConfig cfg) {
        if (!(source instanceof Map)) {
            return;
        }
        Map<?, ?> map = (Map<?, ?>) source;
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            Object value = entry.getValue();
            if (value instanceof ZSliceSelection) {
                ZSliceSelection selection = (ZSliceSelection) value;
                cfg.zSliceSelections.put(String.valueOf(selection.seriesIndex), selection.range);
            }
        }
    }

    private static void markCommitted(ChannelConfig.Channel channel) {
        for (int i = 0; i < PROPERTIES.size(); i++) {
            channel.status.put(PROPERTIES.get(i), ChannelConfig.PropertyStatus.COMMITTED);
        }
    }

    private static Object field(Object target, String name) {
        try {
            Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return field.get(target);
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }

    private static List<String> stringList(Object value) {
        if (value instanceof List) {
            @SuppressWarnings("unchecked")
            List<String> list = (List<String>) value;
            return list;
        }
        return java.util.Collections.emptyList();
    }

    private static List<Boolean> booleanList(Object value) {
        if (value instanceof List) {
            @SuppressWarnings("unchecked")
            List<Boolean> list = (List<Boolean>) value;
            return list;
        }
        return java.util.Collections.emptyList();
    }

    @SafeVarargs
    private static int max(List<String>... lists) {
        int max = 0;
        if (lists != null) {
            for (int i = 0; i < lists.length; i++) {
                if (lists[i] != null && lists[i].size() > max) {
                    max = lists[i].size();
                }
            }
        }
        return max;
    }

    private static String valueAt(List<String> values, int index, String fallback) {
        if (values == null || index < 0 || index >= values.size()) {
            return fallback;
        }
        return value(values.get(index), fallback);
    }

    private static Boolean valueAt(List<Boolean> values, int index, Boolean fallback) {
        if (values == null || index < 0 || index >= values.size() || values.get(index) == null) {
            return fallback;
        }
        return values.get(index);
    }

    private static Integer parseInteger(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return null;
        }
        try {
            return Integer.valueOf(raw.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
