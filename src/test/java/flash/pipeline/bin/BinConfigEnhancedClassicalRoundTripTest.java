package flash.pipeline.bin;

import flash.pipeline.segmentation.SegmentationMethod;
import flash.pipeline.segmentation.SegmentationTokenParser;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.*;

public class BinConfigEnhancedClassicalRoundTripTest {
    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void tokenRoundTripThroughChannelData() throws Exception {
        String token = "enhanced_classical:thresh=120:minSize=200:maxSize=10000:"
                + "morph=sphericity%3E%3D0.6%2Celongation%3C%3D2.0";
        File dir = temp.newFolder();
        BinConfig cfg = baseConfig();
        cfg.addSegmentationMethod(SegmentationTokenParser.parse(token));

        BinConfigIO.writeFromConfig(dir.getAbsolutePath(), cfg);
        BinConfig back = BinConfigIO.readFromDirectory(dir.getAbsolutePath());

        assertEquals(token, back.segmentationMethods.get(0));
        assertTrue(back.isEnhancedClassical(0));
        assertEquals(2, back.getEnhancedClassicalMorphPredicates(0).size());
    }

    @Test
    public void backwardCompatNineLineFileWithoutNewTokenLoadsWithNewMethodAbsent() throws Exception {
        File dir = writeLegacyBin(
                "DAPI IBA1",
                "Blue Green",
                "default 500",
                "100-Infinity 50-5000",
                "None None",
                "default default",
                "classical stardist:0.5:0.4",
                "default default",
                "zslice:full");

        BinConfig cfg = BinConfigIO.readFromDirectory(dir.getAbsolutePath());

        assertFalse(cfg.isEnhancedClassical(0));
        assertFalse(cfg.isEnhancedClassical(1));
        assertEquals("classical", cfg.segmentationMethods.get(0));
    }

    @Test
    public void commaEncodedMorphSegmentRoundTripsLosslessly() throws Exception {
        String token = "enhanced_classical:thresh=12:minSize=25:maxSize=500:"
                + "morph=volume%3E%3D100.0%2Cferet_diameter_max%3C50.0%2Cmax_intensity%3E200.0";
        File dir = temp.newFolder();
        BinConfig cfg = baseConfig();
        SegmentationMethod method = SegmentationTokenParser.parse(token);
        cfg.addSegmentationMethod(method);

        BinConfigIO.writeFromConfig(dir.getAbsolutePath(), cfg);
        BinConfig back = BinConfigIO.readFromDirectory(dir.getAbsolutePath());
        BinConfigIO.writeFromConfig(dir.getAbsolutePath(), back);
        BinConfig reread = BinConfigIO.readFromDirectory(dir.getAbsolutePath());

        assertEquals(token, reread.segmentationMethods.get(0));
        assertEquals(3, reread.getEnhancedClassicalMorphPredicates(0).size());
    }

    private static BinConfig baseConfig() {
        BinConfig cfg = new BinConfig();
        cfg.channelNames.add("IBA1");
        cfg.channelColors.add("Green");
        cfg.channelThresholds.add("120");
        cfg.channelSizes.add("200-10000");
        cfg.channelMinMax.add("None");
        cfg.channelIntensityThresholds.add("default");
        cfg.channelFilterPresets.add("Default");
        return cfg;
    }

    private File writeLegacyBin(String... lines) throws Exception {
        File dir = temp.newFolder();
        File bin = new File(dir, ".bin");
        assertTrue(bin.mkdirs());
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            sb.append(lines[i]).append('\n');
        }
        Files.write(new File(bin, "Channel_Data.txt").toPath(),
                sb.toString().getBytes(StandardCharsets.UTF_8));
        return dir;
    }
}
