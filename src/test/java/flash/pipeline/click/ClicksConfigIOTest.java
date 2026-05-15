package flash.pipeline.click;

import flash.pipeline.bin.BinConfigIO;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ClicksConfigIOTest {

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    @Test
    public void roundTripWritesJsonAndMarksChannelDataPresence() throws Exception {
        File bin = tempFolder.newFolder("bin");
        writeChannelData(bin);
        ClickStore store = new ClickStore();
        store.add(new ClickStore.Click(
                "WT-Slide12_LH_CA1.lif - Series 3",
                2,
                47,
                12,
                421.5,
                138.0,
                ClickStore.Verdict.NEGATIVE,
                1747850000000L));

        ClicksConfigIO.write(bin, store);
        ClickStore roundTrip = ClicksConfigIO.read(bin);

        assertTrue(ClicksConfigIO.exists(bin));
        assertEquals(1, roundTrip.all().size());
        ClickStore.Click click = roundTrip.all().get(0);
        assertEquals("WT-Slide12_LH_CA1.lif - Series 3", click.imageName);
        assertEquals(2, click.channelOneBased);
        assertEquals(47, click.label);
        assertEquals(12, click.z);
        assertEquals(421.5, click.x, 0.0001);
        assertEquals(ClickStore.Verdict.NEGATIVE, click.verdict);

        List<String> lines = Files.readAllLines(new File(bin, "Channel_Data.txt").toPath(),
                StandardCharsets.UTF_8);
        assertEquals(BinConfigIO.clicksModeLine(true), lines.get(9));
    }

    @Test
    public void missingFileReadsAsEmptyStore() throws Exception {
        File bin = tempFolder.newFolder("missing");

        ClickStore store = ClicksConfigIO.read(bin);

        assertFalse(ClicksConfigIO.exists(bin));
        assertEquals(0, store.all().size());
    }

    @Test
    public void malformedJsonReadsAsEmptyStore() throws Exception {
        File bin = tempFolder.newFolder("malformed");
        Files.write(new File(bin, ClicksConfigIO.FILE_NAME).toPath(),
                "{not-json".getBytes(StandardCharsets.UTF_8));

        ClickStore store = ClicksConfigIO.read(bin);

        assertEquals(0, store.all().size());
    }

    private static void writeChannelData(File bin) throws Exception {
        Files.write(new File(bin, "Channel_Data.txt").toPath(),
                Arrays.asList(
                        "DAPI\tIBA1",
                        "Blue\tGreen",
                        "default\tdefault",
                        "100-Infinity\t100-Infinity",
                        "None\tNone",
                        "default\tdefault",
                        "classical\tstardist:0.5:0.4",
                        "default\tdefault",
                        "zslice:full"),
                StandardCharsets.UTF_8);
    }
}
