package flash.pipeline.bin;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class ChannelIdentitiesIOTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void roundTrip() throws Exception {
        File bin = temp.newFolder(".bin");
        ChannelIdentities identities = new ChannelIdentities(Arrays.asList(
                new ChannelIdentities.Entry(1, "dapi", "round", false),
                new ChannelIdentities.Entry(2, "iba1", "complex", true)));

        ChannelIdentitiesIO.write(bin, identities);
        ChannelIdentities loaded = ChannelIdentitiesIO.read(bin);

        assertTrue(new File(temp.getRoot(), "FLASH/Set Up Configuration/" + ChannelIdentitiesIO.FILE_NAME).isFile());
        assertEquals(2, loaded.getEntries().size());
        assertEquals("iba1", loaded.findByChannelIndex(2).getMarkerId());
        assertEquals("complex", loaded.findByChannelIndex(2).getShape());
        assertTrue(loaded.findByChannelIndex(2).isCrowdingSensitive());
    }

    @Test
    public void readFallsBackToLegacyBinFile() throws Exception {
        File project = temp.newFolder("legacyProject");
        File bin = new File(project, ".bin");
        assertTrue(bin.mkdirs());
        Files.write(new File(bin, ChannelIdentitiesIO.FILE_NAME).toPath(),
                ("{\"channels\":[{\"channelIndex\":0,\"markerId\":\"dapi\","
                        + "\"shape\":\"round\",\"crowdingSensitive\":false}]}")
                        .getBytes(StandardCharsets.UTF_8));

        ChannelIdentities loaded = ChannelIdentitiesIO.read(bin);

        assertEquals("dapi", loaded.findByChannelIndex(0).getMarkerId());
    }

    @Test
    public void missingFileReturnsEmptyIdentities() throws Exception {
        File bin = temp.newFolder("missing");

        assertTrue(ChannelIdentitiesIO.read(bin).isEmpty());
    }

    @Test
    public void malformedJsonLogsAndReturnsEmpty() throws Exception {
        File bin = temp.newFolder("malformed");
        Files.write(new File(bin, ChannelIdentitiesIO.FILE_NAME).toPath(),
                "{not-json".getBytes(StandardCharsets.UTF_8));

        assertTrue(ChannelIdentitiesIO.read(bin).isEmpty());
    }
}
