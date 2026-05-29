package flash.pipeline.bin;

import flash.pipeline.TestConfigFiles;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class BinConfigCellposeTokenRoundTripTest {
    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void canonicalTokenRoundTripsThroughChannelConfig() throws Exception {
        String token = "cellpose:22.0:0.6:-0.1:gpu=false:chan2=0:model=user_xyz";
        File dir = writeProject(token);

        BinConfig cfg = BinConfigIO.readFromDirectory(dir.getAbsolutePath());
        TestConfigFiles.writeChannelConfig(dir, cfg);
        BinConfig reread = BinConfigIO.readFromDirectory(dir.getAbsolutePath());

        assertTrue(reread.isCellpose(0));
        assertEquals(token, reread.segmentationMethods.get(0));
        assertEquals("user_xyz", reread.getCellposeModel(0));
        assertFalse(reread.getCellposeUseGpu(0));
        assertEquals(0, reread.getCellposeSecondChannel(0));
    }

    @Test
    public void legacyCellposeTokenStillParsesFromChannelConfig() throws Exception {
        File dir = writeProject("cellpose:30:cyto3:0.4:0.0:gpu=true:chan2=0");

        BinConfig cfg = BinConfigIO.readFromDirectory(dir.getAbsolutePath());

        assertTrue(cfg.isCellpose(0));
        assertEquals("cellpose_cyto3", cfg.getCellposeModel(0));
        assertTrue(cfg.getCellposeUseGpu(0));
        assertEquals(0, cfg.getCellposeSecondChannel(0));
    }

    private File writeProject(String segmentationToken) throws Exception {
        File dir = temp.newFolder();
        BinConfig cfg = new BinConfig();
        cfg.channelNames.add("IBA1");
        cfg.channelColors.add("Green");
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
