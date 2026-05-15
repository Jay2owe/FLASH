package flash.pipeline.bin;

import flash.pipeline.zslice.ZSliceMode;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

import static org.junit.Assert.*;

public class BinConfigIORoundTripTest {
    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void oldNineLineBinStabilizesAfterCanonicalCellposeWrite() throws Exception {
        File dir = writeLegacyBin(
                "DAPI IBA1 GFAP NeuN",
                "Blue Green Magenta Red",
                "default 500 750 900",
                "100-Infinity 50-5000 60-6000 70-7000",
                "None 0-4095 100-3000 200-3500",
                "default 250 350 450",
                "classical stardist:0.5:0.4:linking=4.5:gapClosing=6.5:frameGap=2:area=50.0-Infinity:quality=0.3:intensity=100.0 "
                        + "cellpose:30:cyto3:0.4:0.0:gpu=true:chan2=0 enhanced_classical:thresh=12:minSize=25:maxSize=500:morph=sphericity%3E%3D0.6",
                "default clustered_large custom default",
                "zslice:full");

        BinConfig first = BinConfigIO.readFromDirectory(dir.getAbsolutePath());
        BinConfigIO.writeFromConfig(dir.getAbsolutePath(), first);
        BinConfig second = BinConfigIO.readFromDirectory(dir.getAbsolutePath());
        BinConfigIO.writeFromConfig(dir.getAbsolutePath(), second);
        BinConfig third = BinConfigIO.readFromDirectory(dir.getAbsolutePath());

        assertSameFields(first, second, false);
        assertSameFields(second, third);
        assertEquals("cellpose:30:0.4:0.0:gpu=true:chan2=0:model=cellpose_cyto3",
                third.segmentationMethods.get(2));
        assertTrue(third.isStarDist(1));
        assertTrue(third.isCellpose(2));
        assertTrue(third.segmentationMethod(3).isEnhancedClassical());
    }

    @Test
    public void futureUnknownSegmentationTokenPreservesRawStringOnWrite() throws Exception {
        File dir = writeLegacyBin(
                "DAPI",
                "Blue",
                "default",
                "100-Infinity",
                "None",
                "default",
                "future_engine:some=value",
                "default",
                "zslice:full");

        BinConfig cfg = BinConfigIO.readFromDirectory(dir.getAbsolutePath());
        assertTrue(cfg.segmentationMethod(0).isClassical());
        assertEquals("future_engine:some=value", cfg.segmentationMethods.get(0));

        BinConfigIO.writeFromConfig(dir.getAbsolutePath(), cfg);

        File written = new File(dir, "FLASH/Set Up Configuration/.settings/Channel_Data.txt");
        List<String> lines = Files.readAllLines(written.toPath(), StandardCharsets.UTF_8);
        assertEquals("future_engine:some=value", lines.get(6));
        BinConfig reread = BinConfigIO.readFromDirectory(dir.getAbsolutePath());
        assertEquals("future_engine:some=value", reread.segmentationMethods.get(0));
        assertTrue(reread.segmentationMethod(0).isClassical());
    }

    private File writeLegacyBin(String... lines) throws IOException {
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

    private static void assertSameFields(BinConfig expected, BinConfig actual) {
        assertSameFields(expected, actual, true);
    }

    private static void assertSameFields(BinConfig expected, BinConfig actual,
                                         boolean includeSegmentationMethods) {
        assertEquals(expected.channelNames, actual.channelNames);
        assertEquals(expected.channelColors, actual.channelColors);
        assertEquals(expected.channelThresholds, actual.channelThresholds);
        assertEquals(expected.channelSizes, actual.channelSizes);
        assertEquals(expected.channelMinMax, actual.channelMinMax);
        assertEquals(expected.channelIntensityThresholds, actual.channelIntensityThresholds);
        if (includeSegmentationMethods) {
            assertEquals(expected.segmentationMethods, actual.segmentationMethods);
        }
        assertEquals(expected.channelFilterPresets, actual.channelFilterPresets);
        assertEquals(expected.zSliceMode, actual.zSliceMode);
        assertEquals(expected.zSliceConfigPresent, actual.zSliceConfigPresent);
        assertEquals(ZSliceMode.FULL, actual.zSliceMode);
    }
}
