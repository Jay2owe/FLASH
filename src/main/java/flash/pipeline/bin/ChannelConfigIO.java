package flash.pipeline.bin;

import flash.pipeline.image.DisplayRangeSetting;
import flash.pipeline.image.ThresholdOps;
import flash.pipeline.naming.ChannelFilenameCodec;
import flash.pipeline.segmentation.SegmentationTokenParser;
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
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class ChannelConfigIO {
    public static final String FILE_NAME = "channel_config.json";
    /** Deliberate admission bound for a single source image. */
    public static final int MAX_CHANNELS = 64;
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

    private static final Set<String> VALID_LUTS = Collections.unmodifiableSet(
            new HashSet<String>(Arrays.asList(
                    "grays", "red", "green", "blue", "cyan", "magenta", "yellow")));

    /** Observed source shape used by the publication-time semantic gate. */
    public static final class SourceSeries {
        public final int seriesIndex;
        public final int channelCount;
        public final int zSlices;

        public SourceSeries(int seriesIndex, int channelCount, int zSlices) {
            this.seriesIndex = seriesIndex;
            this.channelCount = channelCount;
            this.zSlices = zSlices;
        }
    }

    /** Runtime facts which are intentionally not persisted in channel JSON. */
    public static final class ValidationContext {
        public final List<SourceSeries> sourceSeries;
        public final List<Boolean> filterAvailableByChannel;
        public final boolean sourceMetadataRequired;

        public ValidationContext(List<SourceSeries> sourceSeries,
                                 List<Boolean> filterAvailableByChannel,
                                 boolean sourceMetadataRequired) {
            this.sourceSeries = sourceSeries == null
                    ? Collections.<SourceSeries>emptyList()
                    : Collections.unmodifiableList(new ArrayList<SourceSeries>(sourceSeries));
            this.filterAvailableByChannel = filterAvailableByChannel == null
                    ? Collections.<Boolean>emptyList()
                    : Collections.unmodifiableList(new ArrayList<Boolean>(filterAvailableByChannel));
            this.sourceMetadataRequired = sourceMetadataRequired;
        }

        public static ValidationContext persistedOnly() {
            return new ValidationContext(null, null, false);
        }
    }

    /** One field-addressed validation failure. Channel indexes are zero based. */
    public static final class ValidationIssue {
        public final int channelIndex;
        public final int otherChannelIndex;
        public final String field;
        public final String message;

        ValidationIssue(int channelIndex, int otherChannelIndex, String field, String message) {
            this.channelIndex = channelIndex;
            this.otherChannelIndex = otherChannelIndex;
            this.field = field;
            this.message = message;
        }
    }

    public static final class ValidationResult {
        private final List<ValidationIssue> issues;

        ValidationResult(List<ValidationIssue> issues) {
            this.issues = Collections.unmodifiableList(
                    new ArrayList<ValidationIssue>(issues));
        }

        public boolean isValid() {
            return issues.isEmpty();
        }

        public List<ValidationIssue> issues() {
            return issues;
        }

        public String diagnostic() {
            if (issues.isEmpty()) return "";
            StringBuilder out = new StringBuilder();
            for (int i = 0; i < issues.size(); i++) {
                if (i > 0) out.append("; ");
                out.append(issues.get(i).message);
            }
            return out.toString();
        }
    }

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
                    channel = new ChannelConfig.Channel();
                    channel.index = i;
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
                out.addSegmentationMethodToken(valueForStatus(
                        channel, ChannelConfig.P_SEGMENTATION,
                        channel.segmentationMethod, "classical:otsu"));
                out.channelFilterPresets.add(valueForStatus(channel, ChannelConfig.P_FILTER,
                        channel.filterPreset, "Default"));
                out.channelPropertyStatuses.add(copyStatuses(channel));
            }
        }
        out.zSliceMode = cfg.zSliceMode == null ? ZSliceMode.FULL : cfg.zSliceMode;
        out.zSliceConfigPresent = true;
        copyZSliceSelections(cfg, out);
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
                    channel = new ChannelConfig.Channel();
                    channel.index = i;
                }
                out.channelNames.add(value(channel.name, "Channel" + (i + 1)));
                out.channelColors.add(value(channel.color, "Grays"));
                out.channelThresholds.add(value(channel.threshold, "default"));
                out.channelSizes.add(value(channel.size, "100-Infinity"));
                out.channelMinMax.add(value(channel.minmax, "None"));
                out.channelIntensityThresholds.add(value(channel.intensityThreshold, "default"));
                out.addSegmentationMethodToken(
                        value(channel.segmentationMethod, "classical:otsu"));
                out.channelFilterPresets.add(value(channel.filterPreset, "Default"));
                out.channelPropertyStatuses.add(copyStatuses(channel));
            }
        }
        out.zSliceMode = cfg.zSliceMode == null ? ZSliceMode.FULL : cfg.zSliceMode;
        out.zSliceConfigPresent = true;
        copyZSliceSelections(cfg, out);
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
        boolean positionalStatuses = source.hasPositionalChannelStatuses();
        for (int i = 0; i < n; i++) {
            ChannelConfig.Channel channel = new ChannelConfig.Channel();
            channel.index = i;
            channel.name = positionalValue(source, source.channelNames, i, n,
                    "Channel" + (i + 1));
            channel.color = positionalValue(source, source.channelColors, i, n, "Grays");
            channel.threshold = positionalValue(source, source.channelThresholds, i, n, "default");
            channel.size = positionalValue(source, source.channelSizes, i, n, "100-Infinity");
            channel.minmax = positionalValue(source, source.channelMinMax, i, n, "None");
            channel.intensityThreshold = positionalValue(source, source.channelIntensityThresholds,
                    i, n, "default");
            channel.segmentationMethod = positionalValue(
                    source, source.segmentationMethods, i, n, "classical");
            channel.filterPreset = positionalValue(source, source.channelFilterPresets,
                    i, n, "Default");
            for (int p = 0; p < PROPERTIES.size(); p++) {
                String property = PROPERTIES.get(p);
                ChannelConfig.PropertyStatus status = positionalStatuses
                        ? source.channelPropertyStatus(i, property)
                        : inferredLegacyStatus(source, property, i, n);
                channel.status.put(property, status);
            }
            // Marker identity is optional in legacy BinConfig and cannot be
            // represented there, so absence is an explicit empty value rather
            // than a positional omission.
            if (!positionalStatuses) {
                channel.status.put(ChannelConfig.P_MARKER, ChannelConfig.PropertyStatus.COMMITTED);
            }
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
        // A rectangular legacy source (or a projection carrying explicit
        // positional statuses) can be complete. Ambiguous compressed lists
        // remain pending instead of being silently padded and published.
        if (!cfg.channels.isEmpty() && noPendingStatuses(cfg)
                && validateForCompletion(cfg).isValid()) {
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
            channel.status.put(ChannelConfig.P_NAME, statusForUserList(names, i, n));
            channel.status.put(ChannelConfig.P_COLOR, statusForUserList(colors, i, n));
            channel.status.put(ChannelConfig.P_THRESHOLD, statusForUserList(thresholds, i, n));
            channel.status.put(ChannelConfig.P_SIZE, statusForUserList(sizes, i, n));
            channel.status.put(ChannelConfig.P_MINMAX, statusForUserList(minmax, i, n));
            channel.status.put(ChannelConfig.P_INTENSITY, statusForUserList(intensity, i, n));
            channel.status.put(ChannelConfig.P_SEGMENTATION, statusForUserList(segmentation, i, n));
            channel.status.put(ChannelConfig.P_FILTER, statusForUserList(filters, i, n));
            channel.status.put(ChannelConfig.P_MARKER, ChannelConfig.PropertyStatus.COMMITTED);
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
        return allChannelsCommitted(cfg) && validateForCompletion(cfg).isValid();
    }

    static boolean allChannelsCommitted(ChannelConfig cfg) {
        if (cfg == null || cfg.channels == null || cfg.channels.isEmpty()) {
            return false;
        }
        if (Boolean.FALSE.equals(cfg.complete)) {
            return false;
        }
        if (Boolean.TRUE.equals(cfg.complete)) {
            // CONFIGURED is a value-available state; publication paths promote
            // it to COMMITTED only after this semantic gate. PENDING is never
            // allowed to hide behind a manually edited complete=true flag.
            return noPendingStatuses(cfg);
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

    public static ValidationResult validateForCompletion(ChannelConfig cfg) {
        return validateForCompletion(cfg, ValidationContext.persistedOnly());
    }

    /**
     * Single semantic completion gate shared by interactive, filtered, preset,
     * copied-project, raw-JSON and headless publication paths.
     */
    public static ValidationResult validateForCompletion(ChannelConfig cfg,
                                                         ValidationContext context) {
        ValidationContext facts = context == null
                ? ValidationContext.persistedOnly() : context;
        List<ValidationIssue> issues = new ArrayList<ValidationIssue>();
        if (cfg == null || cfg.channels == null) {
            issue(issues, -1, -1, "channels", "Channel configuration field 'channels' is missing.");
            return new ValidationResult(issues);
        }
        int count = cfg.channels.size();
        if (count < 1 || count > MAX_CHANNELS) {
            issue(issues, -1, -1, "channelCount", "Channel configuration field 'channelCount' must be an integer from 1 to "
                    + MAX_CHANNELS + "; found " + count + ".");
            return new ValidationResult(issues);
        }
        if (!facts.filterAvailableByChannel.isEmpty()
                && facts.filterAvailableByChannel.size() != count) {
            issue(issues, -1, -1, "filterAvailabilityCount",
                    "Channel configuration field 'filterAvailabilityCount' is "
                            + facts.filterAvailableByChannel.size()
                            + " but field 'channelCount' is " + count + ".");
        }

        Map<String, Integer> outputNames = new LinkedHashMap<String, Integer>();
        for (int i = 0; i < count; i++) {
            ChannelConfig.Channel channel = cfg.channels.get(i);
            if (channel == null) {
                issue(issues, i, -1, "channel", "Channel " + (i + 1) + " field 'channel' is missing.");
                continue;
            }
            if (channel.index != i) {
                issue(issues, i, -1, "index", "Channel " + (i + 1) + " field 'index' is "
                        + channel.index + "; positional index " + i + " is required (legacy compressed drafts need review).");
            }
            requireAvailable(issues, channel, i, ChannelConfig.P_NAME);
            requireAvailable(issues, channel, i, ChannelConfig.P_COLOR);
            requireAvailable(issues, channel, i, ChannelConfig.P_MARKER);
            requireAvailable(issues, channel, i, ChannelConfig.P_THRESHOLD);
            requireAvailable(issues, channel, i, ChannelConfig.P_SIZE);
            requireAvailable(issues, channel, i, ChannelConfig.P_MINMAX);
            requireAvailable(issues, channel, i, ChannelConfig.P_INTENSITY);
            requireAvailable(issues, channel, i, ChannelConfig.P_SEGMENTATION);
            requireAvailable(issues, channel, i, ChannelConfig.P_FILTER);

            if (!hasText(channel.name)) {
                issue(issues, i, -1, ChannelConfig.P_NAME,
                        "Channel " + (i + 1) + " field 'name' is blank.");
            } else {
                String key = ChannelFilenameCodec.windowsCollisionKey(channel.name);
                Integer previous = outputNames.put(key, Integer.valueOf(i));
                if (previous != null) {
                    issue(issues, previous.intValue(), i, ChannelConfig.P_NAME,
                            "Channels " + (previous.intValue() + 1) + " and " + (i + 1)
                                    + " field 'name' collide on Windows after filename encoding ('" + key + "').");
                }
            }
            if (!validLut(channel.color)) {
                issue(issues, i, -1, ChannelConfig.P_COLOR,
                        "Channel " + (i + 1) + " field 'color' has unsupported LUT '" + safe(channel.color) + "'.");
            }
            if (!validThreshold(channel.threshold)) {
                issue(issues, i, -1, ChannelConfig.P_THRESHOLD,
                        "Channel " + (i + 1) + " field 'threshold' is malformed: '" + safe(channel.threshold) + "'.");
            }
            if (!validSizeRange(channel.size)) {
                issue(issues, i, -1, ChannelConfig.P_SIZE,
                        "Channel " + (i + 1) + " field 'size' is malformed: '" + safe(channel.size) + "'.");
            }
            if (!DisplayRangeSetting.isValidToken(channel.minmax)) {
                issue(issues, i, -1, ChannelConfig.P_MINMAX,
                        "Channel " + (i + 1) + " field 'minmax' is malformed: '" + safe(channel.minmax) + "'.");
            }
            if (!validThreshold(channel.intensityThreshold)) {
                issue(issues, i, -1, ChannelConfig.P_INTENSITY,
                        "Channel " + (i + 1) + " field 'intensityThreshold' is malformed: '"
                                + safe(channel.intensityThreshold) + "'.");
            }
            if (!validSegmentation(channel.segmentationMethod)) {
                issue(issues, i, -1, ChannelConfig.P_SEGMENTATION,
                        "Channel " + (i + 1) + " field 'segmentation' is malformed: '"
                                + safe(channel.segmentationMethod) + "'.");
            }
            if (!hasText(channel.filterPreset)) {
                issue(issues, i, -1, ChannelConfig.P_FILTER,
                        "Channel " + (i + 1) + " field 'filter' is blank.");
            }
            if (!facts.filterAvailableByChannel.isEmpty()
                    && (i >= facts.filterAvailableByChannel.size()
                    || !Boolean.TRUE.equals(facts.filterAvailableByChannel.get(i)))) {
                issue(issues, i, -1, ChannelConfig.P_FILTER,
                        "Channel " + (i + 1) + " field 'filter' is unavailable: '"
                                + safe(channel.filterPreset) + "'.");
            }
        }

        validateSourceAndZ(cfg, facts, issues);
        return new ValidationResult(issues);
    }

    public static void requireValidForCompletion(ChannelConfig cfg,
                                                 ValidationContext context) throws IOException {
        ValidationResult result = validateForCompletion(cfg, context);
        if (!result.isValid()) {
            throw new IOException("Cannot complete channel configuration: " + result.diagnostic());
        }
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

    private static Map<String, ChannelConfig.PropertyStatus> copyStatuses(
            ChannelConfig.Channel channel) {
        Map<String, ChannelConfig.PropertyStatus> out =
                new LinkedHashMap<String, ChannelConfig.PropertyStatus>();
        for (int i = 0; i < PROPERTIES.size(); i++) {
            String property = PROPERTIES.get(i);
            out.put(property, statusOf(channel, property));
        }
        return out;
    }

    private static boolean noPendingStatuses(ChannelConfig cfg) {
        if (cfg == null || cfg.channels == null || cfg.channels.isEmpty()) return false;
        for (int i = 0; i < cfg.channels.size(); i++) {
            ChannelConfig.Channel channel = cfg.channels.get(i);
            if (channel == null) return false;
            for (int p = 0; p < PROPERTIES.size(); p++) {
                if (statusOf(channel, PROPERTIES.get(p)) == ChannelConfig.PropertyStatus.PENDING) {
                    return false;
                }
            }
        }
        return true;
    }

    private static String positionalValue(BinConfig source, List<String> values,
                                          int index, int channelCount, String fallback) {
        if (source.hasPositionalChannelStatuses()) {
            return valueAt(values, index, fallback);
        }
        if (legacyCompressed(values, channelCount)) {
            return fallback;
        }
        return valueAt(values, index, fallback);
    }

    private static ChannelConfig.PropertyStatus inferredLegacyStatus(
            BinConfig source, String property, int index, int channelCount) {
        List<String> values = valuesForProperty(source, property);
        if (values == null || values.isEmpty()) {
            return ChannelConfig.PropertyStatus.COMMITTED;
        }
        if (legacyCompressed(values, channelCount)) {
            return ChannelConfig.PropertyStatus.PENDING;
        }
        return index < values.size()
                ? ChannelConfig.PropertyStatus.COMMITTED
                : ChannelConfig.PropertyStatus.PENDING;
    }

    private static ChannelConfig.PropertyStatus statusForUserList(
            List<?> values, int index, int channelCount) {
        if (values == null || values.isEmpty()) {
            return ChannelConfig.PropertyStatus.PENDING;
        }
        if (values.size() != channelCount) {
            return ChannelConfig.PropertyStatus.PENDING;
        }
        return index < values.size()
                ? ChannelConfig.PropertyStatus.COMMITTED
                : ChannelConfig.PropertyStatus.PENDING;
    }

    private static boolean legacyCompressed(List<?> values, int channelCount) {
        return values != null && !values.isEmpty() && values.size() != channelCount;
    }

    private static List<String> valuesForProperty(BinConfig source, String property) {
        if (ChannelConfig.P_NAME.equals(property)) return source.channelNames;
        if (ChannelConfig.P_COLOR.equals(property)) return source.channelColors;
        if (ChannelConfig.P_THRESHOLD.equals(property)) return source.channelThresholds;
        if (ChannelConfig.P_SIZE.equals(property)) return source.channelSizes;
        if (ChannelConfig.P_MINMAX.equals(property)) return source.channelMinMax;
        if (ChannelConfig.P_INTENSITY.equals(property)) return source.channelIntensityThresholds;
        if (ChannelConfig.P_SEGMENTATION.equals(property)) return source.segmentationMethods;
        if (ChannelConfig.P_FILTER.equals(property)) return source.channelFilterPresets;
        return Collections.emptyList();
    }

    private static void validateSourceAndZ(ChannelConfig cfg, ValidationContext facts,
                                           List<ValidationIssue> issues) {
        if (facts.sourceMetadataRequired && facts.sourceSeries.isEmpty()) {
            issue(issues, -1, -1, "sourceChannelCount",
                    "Channel configuration field 'sourceChannelCount' cannot be verified: no usable source series.");
            return;
        }
        int configuredChannels = cfg.channels.size();
        Map<Integer, SourceSeries> byIndex = new LinkedHashMap<Integer, SourceSeries>();
        for (int i = 0; i < facts.sourceSeries.size(); i++) {
            SourceSeries series = facts.sourceSeries.get(i);
            if (series == null) continue;
            if (series.seriesIndex < 0) {
                issue(issues, -1, -1, "sourceSeriesIndex",
                        "Source field 'seriesIndex' must be nonnegative; found "
                                + series.seriesIndex + ".");
                continue;
            }
            SourceSeries previous = byIndex.put(Integer.valueOf(series.seriesIndex), series);
            if (previous != null) {
                issue(issues, -1, -1, "sourceSeriesIndex",
                        "Source field 'seriesIndex' is duplicated: " + series.seriesIndex + ".");
            }
            if (series.channelCount < 1 || series.channelCount > MAX_CHANNELS) {
                issue(issues, -1, -1, "sourceChannelCount",
                        "Source series " + (series.seriesIndex + 1)
                                + " field 'sourceChannelCount' is outside 1-" + MAX_CHANNELS
                                + ": " + series.channelCount + ".");
            } else if (series.channelCount != configuredChannels) {
                issue(issues, -1, -1, "sourceChannelCount",
                        "Source series " + (series.seriesIndex + 1)
                                + " field 'sourceChannelCount' is " + series.channelCount
                                + " but configuration field 'channelCount' is " + configuredChannels + ".");
            }
            if (series.zSlices < 1) {
                issue(issues, -1, -1, "sourceZSlices",
                        "Source series " + (series.seriesIndex + 1)
                                + " field 'sourceZSlices' must be at least 1; found "
                                + series.zSlices + ".");
            }
        }

        ZSliceMode mode = cfg.zSliceMode == null ? ZSliceMode.FULL : cfg.zSliceMode;
        if (!mode.usesSubset()) return;
        if (cfg.zSliceSelections == null || cfg.zSliceSelections.isEmpty()) {
            issue(issues, -1, -1, "zSliceSelections",
                    "Channel configuration field 'zSliceSelections' is empty for mode " + mode.name() + ".");
            return;
        }

        for (Map.Entry<String, ZSliceRange> selection : cfg.zSliceSelections.entrySet()) {
            Integer seriesIndex = parseInteger(selection.getKey());
            if (seriesIndex == null || seriesIndex.intValue() < 0) {
                issue(issues, -1, -1, "zSliceSelections",
                        "Channel configuration field 'zSliceSelections' has invalid series index '"
                                + selection.getKey() + "'.");
            }
            if (selection.getValue() == null) {
                issue(issues, -1, -1, "zSliceSelections",
                        "Channel configuration field 'zSliceSelections' has no range for series '"
                                + selection.getKey() + "'.");
            }
        }

        Integer expectedCount = null;
        ZSliceRange expectedRange = null;
        for (Map.Entry<Integer, SourceSeries> entry : byIndex.entrySet()) {
            SourceSeries source = entry.getValue();
            ZSliceRange range = cfg.zSliceSelections.get(String.valueOf(entry.getKey()));
            if (range == null) {
                issue(issues, -1, -1, "zSliceSelections",
                        "Source series " + (source.seriesIndex + 1)
                                + " field 'zSliceSelections' is missing.");
                continue;
            }
            if (!range.isValidFor(source.zSlices)) {
                issue(issues, -1, -1, "zSliceSelections",
                        "Source series " + (source.seriesIndex + 1)
                                + " field 'zSliceSelections' range " + range
                                + " is outside 1-" + source.zSlices + ".");
                continue;
            }
            if (mode == ZSliceMode.SAME_COUNT) {
                if (expectedCount == null) expectedCount = Integer.valueOf(range.count());
                else if (expectedCount.intValue() != range.count()) {
                    issue(issues, -1, -1, "zSliceSelections",
                            "Source series " + (source.seriesIndex + 1)
                                    + " field 'zSliceSelections' has " + range.count()
                                    + " slices; SAME_COUNT requires " + expectedCount + ".");
                }
            } else if (mode == ZSliceMode.SAME_ABSOLUTE) {
                if (expectedRange == null) expectedRange = range;
                else if (!expectedRange.equals(range)) {
                    issue(issues, -1, -1, "zSliceSelections",
                            "Source series " + (source.seriesIndex + 1)
                                    + " field 'zSliceSelections' is " + range
                                    + "; SAME_ABSOLUTE requires " + expectedRange + ".");
                }
            }
        }
        if (!byIndex.isEmpty()) {
            for (String key : cfg.zSliceSelections.keySet()) {
                Integer seriesIndex = parseInteger(key);
                if (seriesIndex == null || !byIndex.containsKey(seriesIndex)) {
                    issue(issues, -1, -1, "zSliceSelections",
                            "Channel configuration field 'zSliceSelections' contains unknown series index '"
                                    + key + "'.");
                }
            }
        }
    }

    private static void requireAvailable(List<ValidationIssue> issues,
                                         ChannelConfig.Channel channel,
                                         int channelIndex, String property) {
        if (statusOf(channel, property) == ChannelConfig.PropertyStatus.PENDING) {
            issue(issues, channelIndex, -1, property,
                    "Channel " + (channelIndex + 1) + " field '" + property
                            + "' is pending (legacy compressed positions require review).");
        }
    }

    private static boolean validLut(String value) {
        return value != null && VALID_LUTS.contains(value.trim().toLowerCase(Locale.ROOT));
    }

    private static boolean validThreshold(String token) {
        String value = token == null ? "" : token.trim();
        if ("default".equalsIgnoreCase(value)) return true;
        Double numeric = ThresholdOps.parseNumericThreshold(value);
        if (numeric != null) {
            return Double.isFinite(numeric.doubleValue()) && numeric.doubleValue() >= 0.0d;
        }
        if (!value.regionMatches(true, 0, "auto:", 0, 5)) return false;
        String[] parts = value.substring(5).split(":", -1);
        if (parts.length != 2 || !knownAutoMethod(parts[0])) return false;
        return "dark".equalsIgnoreCase(parts[1].trim())
                || "light".equalsIgnoreCase(parts[1].trim());
    }

    private static boolean knownAutoMethod(String value) {
        String candidate = value == null ? "" : value.trim();
        String[] methods = ThresholdOps.autoMethods();
        for (int i = 0; i < methods.length; i++) {
            if (methods[i].equalsIgnoreCase(candidate)) return true;
        }
        return false;
    }

    private static boolean validSizeRange(String token) {
        String value = token == null ? "" : token.trim();
        int dash = value.indexOf('-');
        if (dash <= 0 || dash >= value.length() - 1) return false;
        try {
            double min = Double.parseDouble(value.substring(0, dash).trim());
            String upper = value.substring(dash + 1).trim();
            boolean unbounded = "Infinity".equalsIgnoreCase(upper);
            double max = unbounded ? Double.POSITIVE_INFINITY : Double.parseDouble(upper);
            return Double.isFinite(min) && min >= 0.0d
                    && (unbounded || Double.isFinite(max)) && max >= min;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static boolean validSegmentation(String token) {
        if (isLegacyClassicalOtsuAlias(token)) return true;
        try {
            SegmentationTokenParser.parse(token);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static boolean isLegacyClassicalOtsuAlias(String token) {
        return token != null && "classical:otsu".equalsIgnoreCase(token.trim());
    }

    private static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static void issue(List<ValidationIssue> issues, int channelIndex,
                              int otherChannelIndex, String field, String message) {
        issues.add(new ValidationIssue(channelIndex, otherChannelIndex, field, message));
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
