package flash.pipeline.runrecord;

import flash.pipeline.bin.BinConfig;
import flash.pipeline.bin.ChannelConfig;
import flash.pipeline.cli.CLIConfig;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ParameterSnapshotTest {

    @Test
    public void fromChannelConfigFlattensChannels() {
        ChannelConfig cfg = new ChannelConfig();
        cfg.channels.add(channel("DAPI", "Blue", "Cellpose"));
        cfg.channels.add(channel("GFAP", "Green", "Classical"));

        Map<String, Object> params = ParameterSnapshot.fromChannelConfig(cfg);

        assertEquals(Arrays.asList("DAPI", "GFAP"), params.get("channel_names"));
        assertEquals(Arrays.asList("Blue", "Green"), params.get("channel_colors"));
        assertEquals(Arrays.asList("Cellpose", "Classical"), params.get("segmentation_methods"));
        assertTrue(params.containsKey("z_slice_mode"));
        assertTrue(params.containsKey("click_capture_used"));
    }

    @Test
    public void fromChannelConfigNullChannelsYieldsEmpty() {
        ChannelConfig cfg = new ChannelConfig();
        cfg.channels = null;
        assertTrue(ParameterSnapshot.fromChannelConfig(cfg).isEmpty());
        assertTrue(ParameterSnapshot.fromChannelConfig(null).isEmpty());
    }

    @Test
    public void fromBinConfigFlattensLists() {
        BinConfig cfg = new BinConfig();
        cfg.channelNames.addAll(Arrays.asList("DAPI", "GFAP"));
        cfg.channelColors.addAll(Arrays.asList("Blue", "Green"));

        Map<String, Object> params = ParameterSnapshot.fromBinConfig(cfg);

        assertEquals(Arrays.asList("DAPI", "GFAP"), params.get("channel_names"));
        assertEquals(Arrays.asList("Blue", "Green"), params.get("channel_colors"));
        assertTrue(params.containsKey("z_slice_mode"));
        assertEquals(Boolean.FALSE, params.get("z_slice_config_present"));
    }

    @Test
    public void fromCliConfigCapturesRunOptions() {
        Map<String, Object> params = ParameterSnapshot.fromCliConfig(new CLIConfig());

        assertTrue(params.containsKey("overwrite_behavior"));
        assertTrue(params.get("threads") instanceof Integer);
        assertTrue(params.get("parallel") instanceof Boolean);
        assertTrue(params.get("tif_cache") instanceof Boolean);
        assertTrue(params.get("qc_report") instanceof Boolean);
        // default overwrite is "Auto-Overwrite", so skip_existing is false
        assertEquals(Boolean.FALSE, params.get("skip_existing"));
    }

    @Test
    public void mergedKeepsInsertionOrderAndOverrides() {
        Map<String, Object> a = new LinkedHashMap<String, Object>();
        a.put("one", Integer.valueOf(1));
        a.put("two", Integer.valueOf(2));
        Map<String, Object> b = new LinkedHashMap<String, Object>();
        b.put("two", Integer.valueOf(22)); // override, keeps position
        b.put("three", Integer.valueOf(3));

        LinkedHashMap<String, Object> merged = ParameterSnapshot.merged(a, b);

        assertEquals(Arrays.asList("one", "two", "three"), new ArrayList<String>(merged.keySet()));
        assertEquals(Integer.valueOf(22), merged.get("two"));
    }

    @Test
    public void redactStripsKnownPrivateKeys() {
        Map<String, Object> params = new LinkedHashMap<String, Object>();
        params.put("threads", Integer.valueOf(8));
        params.put("apiToken", "secret-value");

        Map<String, Object> redacted = ParameterSnapshot.redact(params,
                Collections.singleton("apiToken"));

        assertTrue(redacted.containsKey("threads"));
        assertFalse(redacted.containsKey("apiToken"));
    }

    @Test
    public void redactNoOpByDefaultButReturnsCopy() {
        Map<String, Object> params = new LinkedHashMap<String, Object>();
        params.put("threads", Integer.valueOf(8));

        Map<String, Object> redacted = ParameterSnapshot.redact(params);

        assertEquals(params, redacted);
        assertFalse("redact returns a defensive copy", params == redacted);
    }

    @Test
    public void fromAnalysisPresetMapCopiesPreset() {
        Map<String, Object> preset = new LinkedHashMap<String, Object>();
        preset.put("doVolumetric", Boolean.TRUE);
        preset.put("nuclearMarkerIndex", Integer.valueOf(0));

        Map<String, Object> params = ParameterSnapshot.fromAnalysisPresetMap("ThreeDObjectAnalysis", preset);

        assertEquals(Boolean.TRUE, params.get("doVolumetric"));
        assertEquals(Integer.valueOf(0), params.get("nuclearMarkerIndex"));
    }

    private static ChannelConfig.Channel channel(String name, String color, String segmentation) {
        ChannelConfig.Channel channel = new ChannelConfig.Channel();
        channel.name = name;
        channel.color = color;
        channel.segmentationMethod = segmentation;
        return channel;
    }
}
