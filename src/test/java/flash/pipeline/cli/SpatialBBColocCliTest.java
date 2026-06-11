package flash.pipeline.cli;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * Stage 06 — bounding-box spatial options survive the CLI serialize/parse round trip.
 */
public class SpatialBBColocCliTest {

    @Test
    public void spatialBBOptionsParseAndSurviveRoundTrip() {
        CLIConfig parsed = CLIArgumentParser.parse(
                "dir=[/tmp] spatial.bb_overlap=true spatial.bb_cpc=false spatial.bb_vol=true "
                        + "spatial.bb_threshold=45");
        assertEquals(Boolean.TRUE, parsed.getSpatial().getDoBBOverlap());
        assertEquals(Boolean.FALSE, parsed.getSpatial().getDoBBCpc());
        assertEquals(Boolean.TRUE, parsed.getSpatial().getDoBBVol());
        assertEquals(45.0, parsed.getSpatial().getBBColocThresholdPercent(), 0.0);

        CLIConfig reparsed = CLIArgumentParser.parse(parsed.toMacroOptions());
        assertEquals(Boolean.TRUE, reparsed.getSpatial().getDoBBOverlap());
        assertEquals(Boolean.FALSE, reparsed.getSpatial().getDoBBCpc());
        assertEquals(Boolean.TRUE, reparsed.getSpatial().getDoBBVol());
        assertEquals(45.0, reparsed.getSpatial().getBBColocThresholdPercent(), 0.0);
    }
}
