package flash.pipeline.bin;

import org.junit.Test;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayOutputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

public class ChannelConfigGoldenFixtureTest {
    private static final String FIXTURE_ROOT = "channel-config/fixtures/";

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

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

    @Test
    public void threeChannelClassicalCommittedDerivedChannelDataMatchesGolden() throws Exception {
        ChannelConfig channelConfig = ChannelConfigCodec.decode(readResource("3ch_classical_committed.json"));
        BinConfig binConfig = ChannelConfigIO.toBinConfig(channelConfig);

        assertEquals(readResourceLines("3ch_classical_committed.Channel_Data.txt"),
                BinConfigIO.toLines(binConfig));
    }

    @Test
    public void threeChannelClassicalCommittedFixtureMatchesLegacyWriterBytes() throws Exception {
        ChannelConfig channelConfig = ChannelConfigCodec.decode(readResource("3ch_classical_committed.json"));
        File project = temp.newFolder("legacy-writer");

        BinConfigIO.writeFromConfig(project.getAbsolutePath(), ChannelConfigIO.toBinConfig(channelConfig));

        assertArrayEquals(readResourceBytes("3ch_classical_committed.Channel_Data.txt"),
                Files.readAllBytes(new File(project,
                        "FLASH/Config/.settings/Channel_Data.txt").toPath()));
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

    private static byte[] readResourceBytes(String fixtureName) throws IOException {
        InputStream in = ChannelConfigGoldenFixtureTest.class.getClassLoader()
                .getResourceAsStream(FIXTURE_ROOT + fixtureName);
        assertNotNull("Missing fixture " + fixtureName, in);
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int read;
            while ((read = in.read(buffer)) >= 0) {
                out.write(buffer, 0, read);
            }
            return out.toByteArray();
        } finally {
            in.close();
        }
    }

    private static List<String> readResourceLines(String fixtureName) throws IOException {
        InputStream in = ChannelConfigGoldenFixtureTest.class.getClassLoader()
                .getResourceAsStream(FIXTURE_ROOT + fixtureName);
        assertNotNull("Missing fixture " + fixtureName, in);
        BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        try {
            List<String> lines = new ArrayList<String>();
            String line;
            while ((line = reader.readLine()) != null) {
                lines.add(line);
            }
            return lines;
        } finally {
            reader.close();
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
