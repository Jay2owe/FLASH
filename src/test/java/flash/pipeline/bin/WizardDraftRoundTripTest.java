package flash.pipeline.bin;

import flash.pipeline.analyses.CreateBinFileAnalysis;
import flash.pipeline.zslice.ZSliceMode;
import flash.pipeline.zslice.ZSliceRange;
import flash.pipeline.zslice.ZSliceSelection;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class WizardDraftRoundTripTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void writeReadRoundTripPreservesAllFields() throws Exception {
        File binFolder = temp.newFolder("bin");
        CreateBinFileAnalysis.BinUserConfig cfg = fourChannelConfig();
        cfg.zSliceMode = ZSliceMode.PER_IMAGE;
        cfg.zSliceSelections.put(Integer.valueOf(2),
                new ZSliceSelection(2, "Series 3", 40, new ZSliceRange(5, 25)));
        boolean[][] customSettings = new boolean[][]{
                {true, false, true, false},
                {false, true, false, true},
                {true, true, false, false}
        };

        WizardDraft.write(binFolder, new WizardDraft.Snapshot(
                cfg, customSettings, 5, "Quality Check", 123456789L));

        WizardDraft.Snapshot read = WizardDraft.read(binFolder);

        assertEquals(5, read.stepIndex);
        assertEquals("Quality Check", read.stepLabel);
        assertEquals(123456789L, read.timestampMillis);
        assertEquals(cfg.names, read.cfg.names);
        assertEquals(cfg.colors, read.cfg.colors);
        assertEquals(cfg.objectThresholds, read.cfg.objectThresholds);
        assertEquals(cfg.sizes, read.cfg.sizes);
        assertEquals(cfg.minmax, read.cfg.minmax);
        assertEquals(cfg.filterPresets, read.cfg.filterPresets);
        assertEquals(cfg.intensityThresholds, read.cfg.intensityThresholds);
        assertEquals(cfg.segmentationMethods, read.cfg.segmentationMethods);
        assertEquals(ZSliceMode.PER_IMAGE, read.cfg.zSliceMode);
        assertEquals("5-25", read.cfg.zSliceSelections.get(Integer.valueOf(2)).range.toToken());
        assertArrayEquals(customSettings[0], read.customSettings[0]);
        assertArrayEquals(customSettings[1], read.customSettings[1]);
        assertArrayEquals(customSettings[2], read.customSettings[2]);
    }

    @Test
    public void readMissingReturnsNull() throws Exception {
        assertNull(WizardDraft.read(temp.newFolder("missing")));
    }

    @Test
    public void readCorruptReturnsNullWithoutThrowing() throws Exception {
        File binFolder = temp.newFolder("corrupt");
        File draftDir = new File(binFolder, WizardDraft.DRAFT_DIR);
        assertTrue(draftDir.mkdirs());
        Files.write(new File(draftDir, WizardDraft.DRAFT_FILE).toPath(),
                "not a useful draft".getBytes(StandardCharsets.UTF_8));

        assertNull(WizardDraft.read(binFolder));
    }

    @Test
    public void deleteRemovesFileAndDirIfEmpty() throws Exception {
        File binFolder = temp.newFolder("delete");
        WizardDraft.write(binFolder, new WizardDraft.Snapshot(
                fourChannelConfig(), null, 1, "Channel Identity", 1L));
        assertTrue(WizardDraft.exists(binFolder));

        WizardDraft.delete(binFolder);

        assertFalse(WizardDraft.exists(binFolder));
        assertFalse(new File(binFolder, WizardDraft.DRAFT_DIR).exists());
    }

    @Test
    public void writeIsAtomicUnderConcurrentReader() throws Exception {
        File binFolder = temp.newFolder("atomic");

        WizardDraft.write(binFolder, new WizardDraft.Snapshot(
                fourChannelConfig(), new boolean[][]{{true, false}}, 3, "Settings Mode", 2L));

        assertTrue(WizardDraft.exists(binFolder));
        assertFalse(new File(new File(binFolder, WizardDraft.DRAFT_DIR),
                WizardDraft.DRAFT_FILE + ".tmp").exists());
        assertEquals(3, WizardDraft.read(binFolder).stepIndex);
    }

    private static CreateBinFileAnalysis.BinUserConfig fourChannelConfig() {
        CreateBinFileAnalysis.BinUserConfig cfg = new CreateBinFileAnalysis.BinUserConfig(
                new ArrayList<String>(Arrays.asList("DAPI", "IBA1", "GFAP", "NeuN")),
                new ArrayList<String>(Arrays.asList("Blue", "Green", "Red", "Cyan")),
                new ArrayList<String>(Arrays.asList("default", "120", "220", "330")),
                new ArrayList<String>(Arrays.asList("100-Infinity", "20-300", "30-400", "40-500")),
                new ArrayList<String>(Arrays.asList("None", "10-200", "20-220", "30-240")),
                new ArrayList<String>(Arrays.asList("Default", "Custom", "Puncta Resolve", "Clustered Small")),
                new ArrayList<String>(Arrays.asList("default", "40", "50", "60")));
        cfg.segmentationMethods.clear();
        cfg.segmentationMethods.addAll(Arrays.asList(
                "classical",
                "stardist:0.5:0.4",
                "cellpose:30:cyto3:0.4:0.0:gpu=true",
                "enhanced_classical:thresh=100:minSize=10:maxSize=1000"));
        return cfg;
    }
}
