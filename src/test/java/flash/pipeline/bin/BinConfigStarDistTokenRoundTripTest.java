package flash.pipeline.bin;

import flash.pipeline.TestConfigFiles;
import flash.pipeline.segmentation.SegmentationMethod;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;

import static org.junit.Assert.assertEquals;

public class BinConfigStarDistTokenRoundTripTest {
    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void tokenWithModelRoundTrips() throws Exception {
        String token = "stardist:0.5:0.3:linking=5.0:gapClosing=5.0:"
                + "area=20-2000:quality=0.2:intensity=50:"
                + "model=user_microglia_iba1_v3";
        File dir = writeProject(token);

        BinConfig cfg = BinConfigIO.readFromDirectory(dir.getAbsolutePath());
        TestConfigFiles.writeChannelConfig(dir, cfg);
        BinConfig reread = BinConfigIO.readFromDirectory(dir.getAbsolutePath());

        assertEquals(token, reread.segmentationMethods.get(0));
        assertEquals("user_microglia_iba1_v3",
                reread.segmentationMethod(0).modelKey().get());
    }

    @Test
    public void tokenWithoutModelRoundTripsAndParserFillsDefaultKey() throws Exception {
        String token = "stardist:0.5:0.3:linking=5.0:gapClosing=5.0:"
                + "area=20-2000:quality=0.2:intensity=50";
        File dir = writeProject(token);

        BinConfig cfg = BinConfigIO.readFromDirectory(dir.getAbsolutePath());
        assertEquals(SegmentationMethod.DEFAULT_STARDIST_MODEL_KEY,
                cfg.segmentationMethod(0).modelKey().get());
        TestConfigFiles.writeChannelConfig(dir, cfg);
        BinConfig reread = BinConfigIO.readFromDirectory(dir.getAbsolutePath());

        assertEquals(token, reread.segmentationMethods.get(0));
        assertEquals(SegmentationMethod.DEFAULT_STARDIST_MODEL_KEY,
                reread.segmentationMethod(0).modelKey().get());
    }

    private File writeProject(String segmentationToken) throws Exception {
        File dir = temp.newFolder();
        BinConfig cfg = new BinConfig();
        cfg.channelNames.add("DAPI");
        cfg.channelColors.add("Blue");
        cfg.channelThresholds.add("default");
        cfg.channelSizes.add("100-Infinity");
        cfg.channelMinMax.add("None");
        cfg.channelIntensityThresholds.add("default");
        cfg.segmentationMethods.add(segmentationToken);
        cfg.channelFilterPresets.add("Default");
        TestConfigFiles.writeChannelConfig(dir, cfg);
        return dir;
    }
}
