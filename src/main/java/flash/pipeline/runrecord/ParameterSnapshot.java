package flash.pipeline.runrecord;

import flash.pipeline.audit.RunSettingsSnapshot;
import flash.pipeline.bin.BinConfig;
import flash.pipeline.bin.ChannelConfig;
import flash.pipeline.cli.CLIConfig;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Flattens FLASH configuration objects into a stable {@code Map<String,Object>}
 * suitable for the {@code parameters} field of a {@link RunRecord}.
 *
 * <p>This is a one-way dump, NOT a reversible codec. Values are limited to
 * JSON-friendly types (booleans, numbers, strings, and lists/maps thereof) so
 * they serialise cleanly. The per-dialog adapters in later phases are
 * responsible for turning a parameters map back into a concrete preset/config.
 */
public final class ParameterSnapshot {

    /** Known-private keys to strip. None today; the hook exists for future credentials. */
    private static final Set<String> REDACTED_KEYS = Collections.emptySet();

    private ParameterSnapshot() {
    }

    public static Map<String, Object> fromChannelConfig(ChannelConfig cfg) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        if (cfg == null || cfg.channels == null) {
            return out;
        }
        List<Object> names = new ArrayList<Object>();
        List<Object> colors = new ArrayList<Object>();
        List<Object> thresholds = new ArrayList<Object>();
        List<Object> sizes = new ArrayList<Object>();
        List<Object> minmax = new ArrayList<Object>();
        List<Object> intensityThresholds = new ArrayList<Object>();
        List<Object> segmentationMethods = new ArrayList<Object>();
        List<Object> filterPresets = new ArrayList<Object>();
        for (ChannelConfig.Channel channel : cfg.channels) {
            if (channel == null) {
                continue;
            }
            names.add(channel.name);
            colors.add(channel.color);
            thresholds.add(channel.threshold);
            sizes.add(channel.size);
            minmax.add(channel.minmax);
            intensityThresholds.add(channel.intensityThreshold);
            segmentationMethods.add(channel.segmentationMethod);
            filterPresets.add(channel.filterPreset);
        }
        out.put("channel_names", names);
        out.put("channel_colors", colors);
        out.put("object_thresholds", thresholds);
        out.put("particle_sizes", sizes);
        out.put("display_min_max", minmax);
        out.put("intensity_thresholds", intensityThresholds);
        out.put("segmentation_methods", segmentationMethods);
        out.put("filter_presets", filterPresets);
        out.put("z_slice_mode", String.valueOf(cfg.zSliceMode));
        out.put("click_capture_used", Boolean.valueOf(cfg.clickCaptureUsed));
        return out;
    }

    public static Map<String, Object> fromBinConfig(BinConfig cfg) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        if (cfg == null) {
            return out;
        }
        out.put("channel_names", copyList(cfg.channelNames));
        out.put("channel_colors", copyList(cfg.channelColors));
        out.put("object_thresholds", copyList(cfg.channelThresholds));
        out.put("particle_sizes", copyList(cfg.channelSizes));
        out.put("display_min_max", copyList(cfg.channelMinMax));
        out.put("intensity_thresholds", copyList(cfg.channelIntensityThresholds));
        out.put("segmentation_methods", copyList(cfg.segmentationMethods));
        out.put("filter_presets", copyList(cfg.channelFilterPresets));
        out.put("z_slice_mode", String.valueOf(cfg.zSliceMode));
        out.put("z_slice_config_present", Boolean.valueOf(cfg.zSliceConfigPresent));
        out.put("click_config_present", Boolean.valueOf(cfg.clickConfigPresent));
        return out;
    }

    public static Map<String, Object> fromCliConfig(CLIConfig cfg) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        if (cfg == null) {
            return out;
        }
        out.put("overwrite_behavior", cfg.getOverwriteBehavior());
        out.put("skip_existing", Boolean.valueOf(safe(cfg.getOverwriteBehavior()).toLowerCase().contains("skip")));
        out.put("parallel", Boolean.valueOf(cfg.isParallel()));
        out.put("threads", Integer.valueOf(cfg.getThreads()));
        out.put("loader_threads", Integer.valueOf(cfg.getLoaderThreads()));
        out.put("loader_percent", Integer.valueOf(cfg.getLoaderPercent()));
        out.put("gpu_permits", Integer.valueOf(cfg.getGpuPermits()));
        out.put("tif_cache", Boolean.valueOf(cfg.isTifCache()));
        out.put("qc_report", Boolean.valueOf(cfg.isQcReport()));
        out.put("auto_aggregate", Boolean.valueOf(cfg.isAutoAggregate()));
        out.put("verbose", Boolean.valueOf(cfg.isVerbose()));
        out.put("headless", Boolean.valueOf(cfg.isHeadless()));
        out.put("split_merge_use_deconv", Boolean.valueOf(cfg.isSplitMergeUseDeconv()));
        out.put("split_merge_apply_orientation_transforms",
                Boolean.valueOf(cfg.isSplitMergeApplyOrientationTransforms()));
        out.put("three_d_use_deconv", Boolean.valueOf(cfg.isThreeDUseDeconv()));
        out.put("intensity_v2_use_deconv", Boolean.valueOf(cfg.isIntensityV2UseDeconv()));
        return out;
    }

    public static Map<String, Object> fromRunSettingsSnapshot(RunSettingsSnapshot snapshot) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        if (snapshot == null) {
            return out;
        }
        out.put("flash_version", snapshot.flashVersion);
        out.put("analysis", snapshot.analysis);
        out.put("analysis_index", Integer.valueOf(snapshot.analysisIndex));
        out.put("directory", snapshot.directory);
        out.put("bin_config", fromBinConfig(snapshot.binConfig));
        if (snapshot.fieldSources != null) {
            out.put("field_sources", new LinkedHashMap<String, Object>(snapshot.fieldSources));
        }
        return out;
    }

    /**
     * Wrap an analysis preset's own JSON object (e.g. {@code preset.toJsonObject()})
     * as a parameters map. {@code analysisKey} is accepted for future namespacing.
     */
    public static Map<String, Object> fromAnalysisPresetMap(String analysisKey,
                                                            Map<String, Object> presetJsonObject) {
        return redact(presetJsonObject);
    }

    /** Merge maps left-to-right; later maps override earlier keys, insertion order preserved. */
    @SafeVarargs
    public static LinkedHashMap<String, Object> merged(Map<String, Object>... maps) {
        LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
        if (maps != null) {
            for (Map<String, Object> map : maps) {
                if (map != null) {
                    out.putAll(map);
                }
            }
        }
        return out;
    }

    /** Strip known-private keys. No-op today; the hook exists for future credentials. */
    public static Map<String, Object> redact(Map<String, Object> params) {
        return redact(params, REDACTED_KEYS);
    }

    static Map<String, Object> redact(Map<String, Object> params, Set<String> keysToRedact) {
        LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
        if (params == null) {
            return out;
        }
        Set<String> redact = keysToRedact == null ? REDACTED_KEYS : keysToRedact;
        for (Map.Entry<String, Object> entry : params.entrySet()) {
            if (!redact.contains(entry.getKey())) {
                out.put(entry.getKey(), entry.getValue());
            }
        }
        return out;
    }

    private static List<Object> copyList(List<String> values) {
        List<Object> out = new ArrayList<Object>();
        if (values != null) {
            out.addAll(values);
        }
        return out;
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
