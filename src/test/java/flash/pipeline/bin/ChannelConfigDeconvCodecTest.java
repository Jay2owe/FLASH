package flash.pipeline.bin;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Stage 03: per-channel deconvolution fields + shared optics round-trip through the codec as
 * lenient raw primitives, staying schema v1 and fully backward compatible.
 */
public class ChannelConfigDeconvCodecTest {

    @Test
    public void deconvFieldsAndOpticsRoundTrip() throws Exception {
        ChannelConfig cfg = new ChannelConfig();
        cfg.deconvOptics = new ChannelConfig.DeconvOptics();
        cfg.deconvOptics.na = Double.valueOf(1.4);
        cfg.deconvOptics.immersionRi = Double.valueOf(1.515);
        cfg.deconvOptics.sampleRi = Double.valueOf(1.47);
        cfg.deconvOptics.scopeModality = "CONFOCAL";
        cfg.deconvOptics.pinholeAiryUnits = Double.valueOf(1.0);

        ChannelConfig.Channel channel = new ChannelConfig.Channel();
        channel.index = 0;
        channel.name = "DAPI";
        channel.deconvEngineKey = "clij2fft";
        channel.deconvAlgorithm = "RICHARDSON_LUCY";
        channel.deconvPsfModel = "GIBSON_LANNI";
        channel.deconvIterations = Integer.valueOf(25);
        channel.deconvRegularization = Double.valueOf(0.0020);
        channel.emissionWavelengthNm = Double.valueOf(461.0);
        channel.routeAnalysis = "deconv";
        channel.routeDisplay = "raw";
        cfg.channels.add(channel);

        ChannelConfig back = ChannelConfigCodec.decode(ChannelConfigCodec.encode(cfg));

        assertNotNull(back.deconvOptics);
        assertEquals(Double.valueOf(1.4), back.deconvOptics.na);
        assertEquals(Double.valueOf(1.515), back.deconvOptics.immersionRi);
        assertEquals(Double.valueOf(1.47), back.deconvOptics.sampleRi);
        assertEquals("CONFOCAL", back.deconvOptics.scopeModality);
        assertEquals(Double.valueOf(1.0), back.deconvOptics.pinholeAiryUnits);

        ChannelConfig.Channel decoded = back.channels.get(0);
        assertEquals("clij2fft", decoded.deconvEngineKey);
        assertEquals("RICHARDSON_LUCY", decoded.deconvAlgorithm);
        assertEquals("GIBSON_LANNI", decoded.deconvPsfModel);
        assertEquals(Integer.valueOf(25), decoded.deconvIterations);
        assertEquals(Double.valueOf(0.0020), decoded.deconvRegularization);
        assertEquals(Double.valueOf(461.0), decoded.emissionWavelengthNm);
        assertEquals("deconv", decoded.routeAnalysis);
        assertEquals("raw", decoded.routeDisplay);
    }

    @Test
    public void oldConfigWithoutDeconvKeysDecodesWithNullDeconv() throws Exception {
        String json = "{"
                + "\"schemaVersion\":1,"
                + "\"writerId\":\"FLASH-old\","
                + "\"writtenAtMillis\":1,"
                + "\"channels\":[{\"index\":0,\"name\":\"DAPI\",\"color\":\"Blue\"}],"
                + "\"zSliceMode\":\"FULL\","
                + "\"zSliceSelections\":{},"
                + "\"clickCaptureUsed\":false"
                + "}";

        ChannelConfig decoded = ChannelConfigCodec.decode(json);

        assertNull(decoded.deconvOptics);
        ChannelConfig.Channel channel = decoded.channels.get(0);
        assertNull(channel.deconvEngineKey);
        assertNull(channel.deconvAlgorithm);
        assertNull(channel.deconvPsfModel);
        assertNull(channel.deconvIterations);
        assertNull(channel.deconvRegularization);
        assertNull(channel.emissionWavelengthNm);
        assertNull(channel.routeAnalysis);
        assertNull(channel.routeDisplay);
    }

    @Test
    public void nonDeconvConfigWritesNoDeconvKeys() {
        ChannelConfig cfg = new ChannelConfig();
        ChannelConfig.Channel channel = new ChannelConfig.Channel();
        channel.index = 0;
        channel.name = "DAPI";
        cfg.channels.add(channel);

        String encoded = ChannelConfigCodec.encode(cfg);

        assertFalse(encoded.contains("deconv"));
        assertFalse(encoded.contains("routeAnalysis"));
        assertFalse(encoded.contains("emissionWavelengthNm"));
    }

    @Test
    public void malformedDeconvNumbersDegradeToNullWithoutThrowing() throws Exception {
        String json = "{"
                + "\"schemaVersion\":1,"
                + "\"channels\":[{"
                + "\"index\":0,"
                + "\"deconvEngineKey\":\"clij2fft\","
                + "\"deconvIterations\":\"not-a-number\","
                + "\"deconvRegularization\":\"bad\","
                + "\"emissionWavelengthNm\":\"???\""
                + "}],"
                + "\"deconvOptics\":{\"na\":\"oops\",\"immersionRi\":1.515},"
                + "\"zSliceMode\":\"FULL\","
                + "\"zSliceSelections\":{},"
                + "\"clickCaptureUsed\":false"
                + "}";

        // The decode must not throw — a bad number degrades the field to null, never corrupting
        // the whole config.
        ChannelConfig decoded = ChannelConfigCodec.decode(json);

        ChannelConfig.Channel channel = decoded.channels.get(0);
        assertEquals("clij2fft", channel.deconvEngineKey);
        assertNull(channel.deconvIterations);
        assertNull(channel.deconvRegularization);
        assertNull(channel.emissionWavelengthNm);
        assertNotNull(decoded.deconvOptics);
        assertNull(decoded.deconvOptics.na);
        assertEquals(Double.valueOf(1.515), decoded.deconvOptics.immersionRi);
    }

    @Test
    public void partialDeconvChannelRoundTripsOnlySetKeys() throws Exception {
        ChannelConfig cfg = new ChannelConfig();
        ChannelConfig.Channel channel = new ChannelConfig.Channel();
        channel.index = 0;
        channel.name = "DAPI";
        channel.routeAnalysis = "deconv"; // only this deconv key set
        cfg.channels.add(channel);

        String encoded = ChannelConfigCodec.encode(cfg);
        assertTrue(encoded.contains("routeAnalysis"));
        assertFalse(encoded.contains("routeDisplay"));
        assertFalse(encoded.contains("deconvEngineKey"));

        ChannelConfig.Channel back = ChannelConfigCodec.decode(encoded).channels.get(0);
        assertEquals("deconv", back.routeAnalysis);
        assertNull(back.routeDisplay);
        assertNull(back.deconvEngineKey);
    }

    @Test
    public void unknownDeconvAdjacentKeyStillRoundTripsViaExtras() throws Exception {
        String json = "{"
                + "\"schemaVersion\":1,"
                + "\"channels\":[{\"index\":0,\"deconvEngineKey\":\"clij2fft\",\"deconvFutureKnob\":\"keep\"}],"
                + "\"zSliceMode\":\"FULL\","
                + "\"zSliceSelections\":{},"
                + "\"clickCaptureUsed\":false"
                + "}";

        ChannelConfig decoded = ChannelConfigCodec.decode(json);
        String encoded = ChannelConfigCodec.encode(decoded);
        ChannelConfig back = ChannelConfigCodec.decode(encoded);

        assertEquals("clij2fft", back.channels.get(0).deconvEngineKey);
        assertEquals("keep", back.channels.get(0).extras.get("deconvFutureKnob"));
        assertTrue(encoded.contains("deconvFutureKnob"));
    }
}
