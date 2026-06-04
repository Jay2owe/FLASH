package flash.pipeline.bin;

import flash.pipeline.zslice.ZSliceMode;
import flash.pipeline.zslice.ZSliceRange;
import flash.pipeline.zslice.ZSliceSelection;
import flash.pipeline.click.ClicksConfigIO;
import ij.IJ;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

public final class ChannelConfigIO {
    public static final String FILE_NAME = "channel_config.json";
    /** Rolling copy of the last config that decoded cleanly, for recovery. */
    static final String BAK_FILE_NAME = "channel_config.bak.json";
    private static final String CORRUPT_PREFIX = "channel_config.corrupt-";
    private static final String CORRUPT_SUFFIX = ".json";

    /** Outcome of a typed read, telling the four failure modes apart. */
    public enum ReadState { ABSENT, OK, INCOMPLETE, CORRUPT, NEWER_VERSION }

    /** A typed read result: the state plus the config when one was loaded. */
    public static final class ReadResult {
        public final ReadState state;
        public final ChannelConfig config;

        ReadResult(ReadState state, ChannelConfig config) {
            this.state = state;
            this.config = config;
        }
    }

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
        File target = new File(settingsDir, FILE_NAME);
        // Keep a rolling copy of the previous good config before overwriting it,
        // so a bad write or later corruption is recoverable.
        rollingBackup(settingsDir, target);
        String encoded = ChannelConfigCodec.encode(cfg);
        BinConfigIO.writeAtomic(target.toPath(), Arrays.asList(encoded));
        if (!verifyWritten(target)) {
            // The bytes on disk did not round-trip. Retry once, then surface a
            // clear error rather than leaving a file that reads back as blank.
            BinConfigIO.writeAtomic(target.toPath(), Arrays.asList(encoded));
            if (!verifyWritten(target)) {
                throw new IOException("channel_config.json failed to verify after write: "
                        + target.getAbsolutePath());
            }
        }
    }

    /**
     * Typed read that tells absent / ok / incomplete / corrupt / newer-version
     * apart, so callers can warn or recover instead of treating every problem as
     * "never configured".
     */
    public static ReadResult readResult(File settingsDir) {
        File file = file(settingsDir);
        if (file == null || !file.isFile()) {
            return new ReadResult(ReadState.ABSENT, null);
        }
        String text;
        try {
            text = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        } catch (IOException e) {
            IJ.log("[FLASH] Could not read " + file.getAbsolutePath() + ": " + e.getMessage());
            return new ReadResult(ReadState.CORRUPT, null);
        }
        if (ChannelConfigCodec.peekSchemaVersion(text) > ChannelConfigCodec.schemaVersion()) {
            return new ReadResult(ReadState.NEWER_VERSION, null);
        }
        ChannelConfig cfg;
        try {
            cfg = ChannelConfigCodec.decode(text);
        } catch (NewerSchemaException e) {
            return new ReadResult(ReadState.NEWER_VERSION, null);
        } catch (IOException e) {
            IJ.log("[FLASH] Damaged " + file.getAbsolutePath() + ": " + e.getMessage());
            return new ReadResult(ReadState.CORRUPT, null);
        }
        if (cfg == null) {
            return new ReadResult(ReadState.CORRUPT, null);
        }
        persistMigrationIfNeeded(settingsDir, file, cfg);
        return new ReadResult(isComplete(cfg) ? ReadState.OK : ReadState.INCOMPLETE, cfg);
    }

    public static ChannelConfig read(File settingsDir) {
        ReadResult result = readResult(settingsDir);
        if (result.state == ReadState.OK || result.state == ReadState.INCOMPLETE) {
            return result.config;
        }
        if (result.state == ReadState.CORRUPT) {
            // Primary is unreadable: fall back to the last-good rolling backup so
            // a downstream analysis can still run instead of failing outright.
            ChannelConfig recovered = readBackup(settingsDir);
            if (recovered != null) {
                IJ.log("[FLASH] Recovered previous configuration from " + BAK_FILE_NAME + ".");
                return recovered;
            }
        }
        return null;
    }

    /** Decode the rolling {@code .bak} copy, or null if absent/unreadable. */
    public static ChannelConfig readBackup(File settingsDir) {
        if (settingsDir == null) {
            return null;
        }
        File bak = new File(settingsDir, BAK_FILE_NAME);
        if (!bak.isFile()) {
            return null;
        }
        try {
            return ChannelConfigCodec.decodeOrNull(
                    new String(Files.readAllBytes(bak.toPath()), StandardCharsets.UTF_8));
        } catch (IOException e) {
            return null;
        }
    }

    public static boolean exists(File settingsDir) {
        File file = file(settingsDir);
        return file != null && file.isFile();
    }

    public static void delete(File settingsDir) {
        File file = file(settingsDir);
        if (file == null || !file.isFile()) {
            return;
        }
        try {
            Files.deleteIfExists(file.toPath());
        } catch (IOException e) {
            IJ.log("[FLASH] Could not delete " + file.getAbsolutePath() + ": " + e.getMessage());
        }
    }

    /**
     * Rename the current config to {@code channel_config.corrupt-<stamp>.json}
     * before it is discarded, so a mistaken "Discard &amp; Exit" or a corrupt
     * file is never the only copy. Never falls back to a bare delete: if the
     * backup cannot be made and verified, the file is kept and {@code false} is
     * returned.
     */
    public static boolean backupThenDelete(File settingsDir) {
        return backupThenDelete(settingsDir, DEFAULT_BACKUP_MOVER);
    }

    /** Seam so the never-bare-delete guarantee can be tested under a failing move. */
    interface BackupMover {
        void move(Path source, Path target) throws IOException;
    }

    private static final BackupMover DEFAULT_BACKUP_MOVER = new BackupMover() {
        @Override
        public void move(Path source, Path target) throws IOException {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    };

    static boolean backupThenDelete(File settingsDir, BackupMover mover) {
        File file = file(settingsDir);
        if (file == null || !file.isFile()) {
            return true;
        }
        File backup = corruptBackupTarget(settingsDir, file);
        try {
            mover.move(file.toPath(), backup.toPath());
        } catch (IOException e) {
            IJ.log("[FLASH] Could not back up " + file.getName()
                    + " before delete; keeping it: " + e.getMessage());
            return false;
        }
        if (backup.isFile() && !file.isFile()) {
            return true;
        }
        IJ.log("[FLASH] Backup of " + file.getName()
                + " could not be verified; keeping the file.");
        return false;
    }

    private static void rollingBackup(File settingsDir, File target) {
        if (target == null || !target.isFile()) {
            return;
        }
        try {
            String text = new String(Files.readAllBytes(target.toPath()), StandardCharsets.UTF_8);
            // Only snapshot a config that currently decodes, so .bak is a true
            // last-known-good copy and never a half-written file.
            if (ChannelConfigCodec.decodeOrNull(text) == null) {
                return;
            }
            BinConfigIO.writeAtomic(new File(settingsDir, BAK_FILE_NAME).toPath(),
                    Arrays.asList(text));
        } catch (IOException e) {
            // Best-effort: never block the real write on a backup failure.
            IJ.log("[FLASH] Could not refresh " + BAK_FILE_NAME + ": " + e.getMessage());
        }
    }

    private static boolean verifyWritten(File target) {
        if (target == null || !target.isFile()) {
            return false;
        }
        try {
            // Re-read the PRIMARY file directly (not via the recovery-aware read,
            // which could pass by loading .bak instead of the new bytes).
            return ChannelConfigCodec.decodeOrNull(
                    new String(Files.readAllBytes(target.toPath()), StandardCharsets.UTF_8)) != null;
        } catch (IOException e) {
            return false;
        }
    }

    private static void persistMigrationIfNeeded(File settingsDir, File file, ChannelConfig cfg) {
        if (cfg == null || !cfg.migrated) {
            return;
        }
        try {
            // Persist the upgraded shape exactly once so the migration is durable.
            // write() keeps a .bak of the pre-migration file via rollingBackup.
            write(settingsDir, cfg);
            cfg.migrated = false;
        } catch (IOException e) {
            IJ.log("[FLASH] Could not persist migrated " + file.getName() + ": " + e.getMessage());
        }
    }

    private static File corruptBackupTarget(File settingsDir, File file) {
        long stamp = peekWrittenAtMillis(file);
        String base = CORRUPT_PREFIX + (stamp > 0 ? Long.toString(stamp) : "unknown");
        File candidate = new File(settingsDir, base + CORRUPT_SUFFIX);
        int counter = 1;
        while (candidate.exists()) {
            candidate = new File(settingsDir, base + "-" + counter + CORRUPT_SUFFIX);
            counter++;
        }
        return candidate;
    }

    private static long peekWrittenAtMillis(File file) {
        try {
            ChannelConfig cfg = ChannelConfigCodec.decodeOrNull(
                    new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
            return cfg == null ? 0L : cfg.writtenAtMillis;
        } catch (IOException e) {
            return 0L;
        }
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
        copyZSliceSelections(cfg, out);
        if (out.zSliceMode != null && out.zSliceMode.usesSubset() && out.zSliceSelections.isEmpty()) {
            out.zSliceMode = ZSliceMode.FULL;
            out.zSliceConfigPresent = false;
        }
        out.clickConfigPresent = cfg.clickCaptureUsed;
        return out;
    }

    public static BinConfig toBinConfig(ChannelConfig cfg, File settingsDir) {
        BinConfig out = toBinConfig(cfg);
        if (out.clickConfigPresent) {
            out.clickConfigPresent = ClicksConfigIO.exists(settingsDir);
        }
        return out;
    }

    public static BinConfig toPartialBinConfig(ChannelConfig cfg, File settingsDir) {
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
                addIfAvailable(out.channelNames, channel, ChannelConfig.P_NAME, channel.name, "Channel" + (i + 1));
                addIfAvailable(out.channelColors, channel, ChannelConfig.P_COLOR, channel.color, "Grays");
                addIfAvailable(out.channelThresholds, channel, ChannelConfig.P_THRESHOLD, channel.threshold, "default");
                addIfAvailable(out.channelSizes, channel, ChannelConfig.P_SIZE, channel.size, "100-Infinity");
                addIfAvailable(out.channelMinMax, channel, ChannelConfig.P_MINMAX, channel.minmax, "None");
                addIfAvailable(out.channelIntensityThresholds, channel, ChannelConfig.P_INTENSITY,
                        channel.intensityThreshold, "default");
                if (statusOf(channel, ChannelConfig.P_SEGMENTATION) != ChannelConfig.PropertyStatus.PENDING) {
                    out.addSegmentationMethodToken(value(channel.segmentationMethod, "classical:otsu"));
                }
                addIfAvailable(out.channelFilterPresets, channel, ChannelConfig.P_FILTER,
                        channel.filterPreset, "Default");
            }
        }
        out.zSliceMode = cfg.zSliceMode == null ? ZSliceMode.FULL : cfg.zSliceMode;
        out.zSliceConfigPresent = true;
        copyZSliceSelections(cfg, out);
        if (out.zSliceMode != null && out.zSliceMode.usesSubset() && out.zSliceSelections.isEmpty()) {
            out.zSliceMode = ZSliceMode.FULL;
            out.zSliceConfigPresent = false;
        }
        out.clickConfigPresent = cfg.clickCaptureUsed && ClicksConfigIO.exists(settingsDir);
        return out;
    }

    public static ChannelConfig fromBinConfig(BinConfig source) {
        ChannelConfig cfg = new ChannelConfig();
        cfg.writerId = "FLASH";
        cfg.writtenAtMillis = System.currentTimeMillis();
        if (source == null) {
            return cfg;
        }
        int n = max(source.channelNames,
                source.channelColors,
                source.channelThresholds,
                source.channelSizes,
                source.channelMinMax,
                source.channelIntensityThresholds,
                source.segmentationMethods,
                source.channelFilterPresets);
        for (int i = 0; i < n; i++) {
            ChannelConfig.Channel channel = new ChannelConfig.Channel();
            channel.index = i;
            channel.name = valueAt(source.channelNames, i, "Channel" + (i + 1));
            channel.color = valueAt(source.channelColors, i, "Grays");
            channel.threshold = valueAt(source.channelThresholds, i, "default");
            channel.size = valueAt(source.channelSizes, i, "100-Infinity");
            channel.minmax = valueAt(source.channelMinMax, i, "None");
            channel.intensityThreshold = valueAt(source.channelIntensityThresholds, i, "default");
            channel.segmentationMethod = valueAt(source.segmentationMethods, i, "classical");
            channel.filterPreset = valueAt(source.channelFilterPresets, i, "Default");
            markCommitted(channel);
            cfg.channels.add(channel);
        }
        cfg.zSliceMode = source.zSliceMode == null ? ZSliceMode.FULL : source.zSliceMode;
        for (Map.Entry<Integer, ZSliceSelection> entry : source.zSliceSelections.entrySet()) {
            ZSliceSelection selection = entry.getValue();
            if (entry.getKey() != null && selection != null && selection.range != null) {
                cfg.zSliceSelections.put(String.valueOf(entry.getKey()), selection.range);
            }
        }
        cfg.clickCaptureUsed = source.clickConfigPresent;
        // This factory commits every property, so the resulting config is a
        // finished one (CLI/bypass path). Mark it complete so it is consistent
        // with the wizard's final write. (fromBinUserConfig deliberately does NOT
        // do this, because mergeIntoExisting reuses it for incremental writes.)
        if (!cfg.channels.isEmpty()) {
            cfg.complete = Boolean.TRUE;
        }
        return cfg;
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

    /**
     * Whether a configuration is finished. The single completeness gate used by
     * both downstream consumers and the wizard resume check, so they cannot
     * disagree. Honours the explicit {@code complete} flag when present and
     * falls back to the per-property COMMITTED check for files written before
     * the flag existed.
     */
    public static boolean isComplete(ChannelConfig cfg) {
        return allChannelsCommitted(cfg);
    }

    static boolean allChannelsCommitted(ChannelConfig cfg) {
        if (cfg == null || cfg.channels == null || cfg.channels.isEmpty()) {
            return false;
        }
        if (cfg.complete != null) {
            return cfg.complete.booleanValue();
        }
        // Back-compat fallback for files written before the explicit flag.
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

    public static ChannelIdentities toChannelIdentities(ChannelConfig cfg) {
        List<ChannelIdentities.Entry> entries = new ArrayList<ChannelIdentities.Entry>();
        if (cfg != null && cfg.channels != null) {
            for (int i = 0; i < cfg.channels.size(); i++) {
                ChannelConfig.Channel channel = cfg.channels.get(i);
                if (channel == null || channel.markerId == null || channel.markerId.trim().isEmpty()) {
                    continue;
                }
                entries.add(new ChannelIdentities.Entry(
                        i,
                        channel.markerId,
                        channel.markerShape,
                        channel.markerCrowdingSensitive));
            }
        }
        return new ChannelIdentities(entries);
    }

    public static ChannelIdentities readChannelIdentities(File settingsDir) {
        return toChannelIdentities(read(settingsDir));
    }

    public static void updateClickCaptureUsed(File settingsDir, boolean used) throws IOException {
        ChannelConfig cfg = read(settingsDir);
        if (cfg == null) {
            return;
        }
        cfg.clickCaptureUsed = used;
        cfg.writtenAtMillis = System.currentTimeMillis();
        write(settingsDir, cfg);
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

    private static void addIfAvailable(List<String> target,
                                       ChannelConfig.Channel channel,
                                       String prop,
                                       String configuredValue,
                                       String fallback) {
        if (statusOf(channel, prop) != ChannelConfig.PropertyStatus.PENDING) {
            target.add(value(configuredValue, fallback));
        }
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
