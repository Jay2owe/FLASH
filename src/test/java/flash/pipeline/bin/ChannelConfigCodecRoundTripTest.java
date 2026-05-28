package flash.pipeline.bin;

import flash.pipeline.zslice.ZSliceMode;
import org.junit.Test;

import java.io.IOException;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class ChannelConfigCodecRoundTripTest {

    @Test
    public void emptyConfigRoundTrip() throws Exception {
        ChannelConfig cfg = new ChannelConfig();

        ChannelConfig back = ChannelConfigCodec.decode(ChannelConfigCodec.encode(cfg));

        assertEquals(1, back.schemaVersion);
        assertEquals(0, back.channels.size());
        assertEquals(ZSliceMode.FULL, back.zSliceMode);
        assertTrue(back.zSliceSelections.isEmpty());
    }

    @Test
    public void singleChannelAllPendingRoundTrip() throws Exception {
        ChannelConfig cfg = new ChannelConfig();
        cfg.writerId = "FLASH-test";
        cfg.writtenAtMillis = 1716912000000L;
        ChannelConfig.Channel channel = new ChannelConfig.Channel();
        channel.index = 0;
        channel.name = "DAPI";
        channel.color = "Blue";
        channel.status.put(ChannelConfig.P_NAME, ChannelConfig.PropertyStatus.PENDING);
        channel.status.put(ChannelConfig.P_COLOR, ChannelConfig.PropertyStatus.PENDING);
        channel.status.put(ChannelConfig.P_THRESHOLD, ChannelConfig.PropertyStatus.PENDING);
        cfg.channels.add(channel);

        ChannelConfig back = ChannelConfigCodec.decode(ChannelConfigCodec.encode(cfg));

        assertEquals("FLASH-test", back.writerId);
        assertEquals(1716912000000L, back.writtenAtMillis);
        assertEquals(1, back.channels.size());
        ChannelConfig.Channel decoded = back.channels.get(0);
        assertEquals(0, decoded.index);
        assertEquals("DAPI", decoded.name);
        assertEquals("Blue", decoded.color);
        assertNull(decoded.threshold);
        assertEquals(ChannelConfig.PropertyStatus.PENDING, decoded.statusOf(ChannelConfig.P_THRESHOLD));
    }

    @Test
    public void multiChannelMixedStatusRoundTrip() throws Exception {
        ChannelConfig cfg = exampleConfig();

        String json = ChannelConfigCodec.encode(cfg);
        ChannelConfig back = ChannelConfigCodec.decode(json);

        assertTrue(json.contains("\"threshold\": null"));
        assertTrue(json.contains("\"threshold\": \"committed\""));
        assertEquals(2, back.channels.size());
        assertChannelEquals(cfg.channels.get(0), back.channels.get(0));
        assertChannelEquals(cfg.channels.get(1), back.channels.get(1));
        assertEquals(ZSliceMode.FULL, back.zSliceMode);
        assertEquals(false, back.clickCaptureUsed);
    }

    @Test
    public void extrasArePreservedOnRoundTrip() throws Exception {
        String json = "{"
                + "\"schemaVersion\":1,"
                + "\"writerId\":\"FLASH-test\","
                + "\"writtenAtMillis\":1,"
                + "\"channels\":[{\"index\":0,\"name\":\"DAPI\",\"futureChannelKey\":\"keep-me\"}],"
                + "\"zSliceMode\":\"FULL\","
                + "\"zSliceSelections\":{},"
                + "\"clickCaptureUsed\":false,"
                + "\"futureRootKey\":{\"nested\":true}"
                + "}";

        ChannelConfig decoded = ChannelConfigCodec.decode(json);
        String encoded = ChannelConfigCodec.encode(decoded);
        ChannelConfig back = ChannelConfigCodec.decode(encoded);

        assertEquals("keep-me", back.channels.get(0).extras.get("futureChannelKey"));
        assertTrue(back.extras.containsKey("futureRootKey"));
        assertTrue(encoded.contains("\"futureRootKey\""));
        assertTrue(encoded.contains("\"futureChannelKey\""));
    }

    @Test
    public void unknownStatusValueDecodesAsPending() throws Exception {
        String json = "{"
                + "\"schemaVersion\":1,"
                + "\"channels\":[{\"index\":0,\"status\":{\"threshold\":\"future_status\"}}],"
                + "\"zSliceMode\":\"FULL\","
                + "\"zSliceSelections\":{},"
                + "\"clickCaptureUsed\":false"
                + "}";

        ChannelConfig decoded = ChannelConfigCodec.decode(json);

        assertEquals(ChannelConfig.PropertyStatus.PENDING,
                decoded.channels.get(0).statusOf(ChannelConfig.P_THRESHOLD));
    }

    @Test
    public void decodeOrNullReturnsNullOnGarbage() {
        assertNull(ChannelConfigCodec.decodeOrNull("{not json"));
    }

    @Test
    public void decodeRejectsWrongSchemaVersion() throws Exception {
        try {
            ChannelConfigCodec.decode("{\"schemaVersion\":2,\"channels\":[]}");
            fail("Expected IOException");
        } catch (IOException e) {
            assertTrue(e.getMessage().contains("schemaVersion"));
            assertTrue(e.getMessage().contains("2"));
        }
    }

    private static ChannelConfig exampleConfig() {
        ChannelConfig cfg = new ChannelConfig();
        cfg.writerId = "FLASH-1.4.2";
        cfg.writtenAtMillis = 1716912000000L;
        cfg.channels.add(channel(0, "Cd11b", "Red", "Cd11b", "punctate", false,
                "180-255", "100-Infinity", "0-4095", "default",
                "classical:otsu", "Default", committedStatuses()));
        cfg.channels.add(channel(1, "DAPI", "Blue", "DAPI", "nuclear", true,
                null, null, null, null, null, null, mixedStatuses()));
        return cfg;
    }

    private static ChannelConfig.Channel channel(int index,
                                                 String name,
                                                 String color,
                                                 String markerId,
                                                 String markerShape,
                                                 boolean crowdingSensitive,
                                                 String threshold,
                                                 String size,
                                                 String minmax,
                                                 String intensityThreshold,
                                                 String segmentationMethod,
                                                 String filterPreset,
                                                 Map<String, ChannelConfig.PropertyStatus> status) {
        ChannelConfig.Channel channel = new ChannelConfig.Channel();
        channel.index = index;
        channel.name = name;
        channel.color = color;
        channel.markerId = markerId;
        channel.markerShape = markerShape;
        channel.markerCrowdingSensitive = crowdingSensitive;
        channel.threshold = threshold;
        channel.size = size;
        channel.minmax = minmax;
        channel.intensityThreshold = intensityThreshold;
        channel.segmentationMethod = segmentationMethod;
        channel.filterPreset = filterPreset;
        channel.status.putAll(status);
        return channel;
    }

    private static Map<String, ChannelConfig.PropertyStatus> committedStatuses() {
        Map<String, ChannelConfig.PropertyStatus> statuses =
                new LinkedHashMap<String, ChannelConfig.PropertyStatus>();
        for (String key : propertyKeys()) {
            statuses.put(key, ChannelConfig.PropertyStatus.COMMITTED);
        }
        return statuses;
    }

    private static Map<String, ChannelConfig.PropertyStatus> mixedStatuses() {
        Map<String, ChannelConfig.PropertyStatus> statuses =
                new LinkedHashMap<String, ChannelConfig.PropertyStatus>();
        statuses.put(ChannelConfig.P_NAME, ChannelConfig.PropertyStatus.COMMITTED);
        statuses.put(ChannelConfig.P_COLOR, ChannelConfig.PropertyStatus.COMMITTED);
        statuses.put(ChannelConfig.P_MARKER, ChannelConfig.PropertyStatus.COMMITTED);
        statuses.put(ChannelConfig.P_THRESHOLD, ChannelConfig.PropertyStatus.PENDING);
        statuses.put(ChannelConfig.P_SIZE, ChannelConfig.PropertyStatus.PENDING);
        statuses.put(ChannelConfig.P_MINMAX, ChannelConfig.PropertyStatus.PENDING);
        statuses.put(ChannelConfig.P_INTENSITY, ChannelConfig.PropertyStatus.PENDING);
        statuses.put(ChannelConfig.P_SEGMENTATION, ChannelConfig.PropertyStatus.PENDING);
        statuses.put(ChannelConfig.P_FILTER, ChannelConfig.PropertyStatus.PENDING);
        return statuses;
    }

    private static Iterable<String> propertyKeys() {
        return Arrays.asList(
                ChannelConfig.P_NAME,
                ChannelConfig.P_COLOR,
                ChannelConfig.P_MARKER,
                ChannelConfig.P_THRESHOLD,
                ChannelConfig.P_SIZE,
                ChannelConfig.P_MINMAX,
                ChannelConfig.P_INTENSITY,
                ChannelConfig.P_SEGMENTATION,
                ChannelConfig.P_FILTER);
    }

    private static void assertChannelEquals(ChannelConfig.Channel expected,
                                            ChannelConfig.Channel actual) {
        assertNotNull(actual);
        assertEquals(expected.index, actual.index);
        assertEquals(expected.name, actual.name);
        assertEquals(expected.color, actual.color);
        assertEquals(expected.markerId, actual.markerId);
        assertEquals(expected.markerShape, actual.markerShape);
        assertEquals(expected.markerCrowdingSensitive, actual.markerCrowdingSensitive);
        assertEquals(expected.threshold, actual.threshold);
        assertEquals(expected.size, actual.size);
        assertEquals(expected.minmax, actual.minmax);
        assertEquals(expected.intensityThreshold, actual.intensityThreshold);
        assertEquals(expected.segmentationMethod, actual.segmentationMethod);
        assertEquals(expected.filterPreset, actual.filterPreset);
        assertEquals(expected.status, actual.status);
    }
}
