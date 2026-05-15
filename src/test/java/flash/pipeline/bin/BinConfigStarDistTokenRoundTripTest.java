package flash.pipeline.bin;

import flash.pipeline.segmentation.SegmentationMethod;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertEquals;

public class BinConfigStarDistTokenRoundTripTest {
    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void tokenWithModelRoundTrips() throws Exception {
        String token = "stardist:0.5:0.3:linking=5.0:gapClosing=5.0:"
                + "area=20-2000:quality=0.2:intensity=50:"
                + "model=user_microglia_iba1_v3";
        File dir = writeLegacyBin(token);

        BinConfig cfg = BinConfigIO.readFromDirectory(dir.getAbsolutePath());
        BinConfigIO.writeFromConfig(dir.getAbsolutePath(), cfg);
        BinConfig reread = BinConfigIO.readFromDirectory(dir.getAbsolutePath());

        assertEquals(token, reread.segmentationMethods.get(0));
        assertEquals("user_microglia_iba1_v3",
                reread.segmentationMethod(0).modelKey().get());
    }

    @Test
    public void tokenWithoutModelRoundTripsAndParserFillsDefaultKey() throws Exception {
        String token = "stardist:0.5:0.3:linking=5.0:gapClosing=5.0:"
                + "area=20-2000:quality=0.2:intensity=50";
        File dir = writeLegacyBin(token);

        BinConfig cfg = BinConfigIO.readFromDirectory(dir.getAbsolutePath());
        assertEquals(SegmentationMethod.DEFAULT_STARDIST_MODEL_KEY,
                cfg.segmentationMethod(0).modelKey().get());
        BinConfigIO.writeFromConfig(dir.getAbsolutePath(), cfg);
        BinConfig reread = BinConfigIO.readFromDirectory(dir.getAbsolutePath());

        assertEquals(token, reread.segmentationMethods.get(0));
        assertEquals(SegmentationMethod.DEFAULT_STARDIST_MODEL_KEY,
                reread.segmentationMethod(0).modelKey().get());
    }

    private File writeLegacyBin(String segmentationToken) throws Exception {
        File dir = temp.newFolder();
        File bin = new File(dir, ".bin");
        if (!bin.mkdirs()) {
            throw new IllegalStateException("Could not create " + bin);
        }
        String content = "DAPI\n"
                + "Blue\n"
                + "default\n"
                + "100-Infinity\n"
                + "None\n"
                + "default\n"
                + segmentationToken + "\n"
                + "default\n"
                + "zslice:full\n";
        Files.write(new File(bin, "Channel_Data.txt").toPath(),
                content.getBytes(StandardCharsets.UTF_8));
        return dir;
    }
}
