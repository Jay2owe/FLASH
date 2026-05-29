package flash.pipeline.bin;

import org.junit.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

public class ChannelConfigGoldenFixtureTest {
    private static final String FIXTURE_ROOT = "channel-config/fixtures/";

    @Test
    public void threeChannelClassicalCommittedRoundTripIsByteStable() throws Exception {
        assertRoundTripByteStable("3ch_classical_committed.json");
    }

    @Test
    public void twoChannelStarDistPartialRoundTripIsByteStable() throws Exception {
        assertRoundTripByteStable("2ch_stardist_partial.json");
    }

    @Test
    public void fourChannelCellposeMixedStatusRoundTripIsByteStable() throws Exception {
        assertRoundTripByteStable("4ch_cellpose_mixed_status.json");
    }

    @Test
    public void oneChannelZSubsetRoundTripIsByteStable() throws Exception {
        assertRoundTripByteStable("1ch_zsubset.json");
    }

    @Test
    public void extrasPreservationRoundTripIsByteStable() throws Exception {
        assertRoundTripByteStable("extras_preservation.json");
    }

    private static void assertRoundTripByteStable(String fixtureName) throws Exception {
        String source = readResource(fixtureName);
        ChannelConfig decoded = ChannelConfigCodec.decode(source);
        String encoded = ChannelConfigCodec.encode(decoded);

        assertEquals(normalize(source), normalize(encoded));
    }

    private static String readResource(String fixtureName) throws IOException {
        InputStream in = ChannelConfigGoldenFixtureTest.class.getClassLoader()
                .getResourceAsStream(FIXTURE_ROOT + fixtureName);
        assertNotNull("Missing fixture " + fixtureName, in);
        try {
            StringBuilder out = new StringBuilder();
            byte[] buffer = new byte[4096];
            int read;
            while ((read = in.read(buffer)) >= 0) {
                out.append(new String(buffer, 0, read, StandardCharsets.UTF_8));
            }
            return out.toString();
        } finally {
            in.close();
        }
    }

    private static String normalize(String value) {
        String normalized = value == null ? "" : value.replace("\r\n", "\n").replace('\r', '\n');
        while (normalized.endsWith("\n")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }
}
