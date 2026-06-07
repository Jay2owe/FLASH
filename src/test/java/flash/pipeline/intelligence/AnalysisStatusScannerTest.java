package flash.pipeline.intelligence;

import flash.pipeline.FLASH_Pipeline;
import flash.pipeline.TestConfigFiles;
import flash.pipeline.bin.BinConfigIO;
import flash.pipeline.bin.ChannelConfig;
import flash.pipeline.bin.ChannelConfigIO;
import flash.pipeline.io.FlashProjectLayout;
import flash.pipeline.io.OrientationManifestIO;
import flash.pipeline.io.ProjectStatusStore;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collections;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class AnalysisStatusScannerTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void scan_foldsOrientationManifestIntoDrawRoisTooltip() throws Exception {
        File dir = temp.newFolder("orientation-done");
        OrientationManifestIO.saveRows(dir.getAbsolutePath(), Collections.emptyList());

        AnalysisStatusScanner scanner = new AnalysisStatusScanner();
        Map<Integer, AnalysisStatus> statuses = scanner.scan(dir);

        assertEquals(AnalysisStatus.NOT_STARTED,
                statuses.get(Integer.valueOf(FLASH_Pipeline.IDX_DRAW_ROIS)));
        assertTrue(scanner.tooltipFor(FLASH_Pipeline.IDX_DRAW_ROIS).contains("orientation transforms"));
    }

    @Test
    public void scan_doesNotRequireHiddenOrientationStatusWhenManifestIsMissing() throws Exception {
        File dir = temp.newFolder("orientation-missing");

        Map<Integer, AnalysisStatus> statuses = new AnalysisStatusScanner().scan(dir);

        assertEquals(AnalysisStatus.NOT_STARTED,
                statuses.get(Integer.valueOf(FLASH_Pipeline.IDX_DRAW_ROIS)));
    }

    @Test
    public void scan_detectsSavedRoiOutputsInCurrentFlashLayout() throws Exception {
        File dir = temp.newFolder("current-rois-done");
        File roiSets = new File(dir, "FLASH/Results/Analysis Images/ROIs");
        File attributes = new File(dir, "FLASH/Results/Tables/ROIs");
        assertTrue(roiSets.mkdirs());
        assertTrue(attributes.mkdirs());
        writeRoiZip(new File(roiSets, "SCN ROIs.zip"));
        Files.write(new File(attributes, "SCN ROI Properties.csv").toPath(),
                ("Image,ROI,Area,Volume (mm^3)\n"
                        + "SCN_001,LH,123.0,0.001\n").getBytes(StandardCharsets.UTF_8));

        AnalysisStatusScanner scanner = new AnalysisStatusScanner();
        Map<Integer, AnalysisStatus> statuses = scanner.scan(dir);

        assertEquals(AnalysisStatus.DONE,
                statuses.get(Integer.valueOf(FLASH_Pipeline.IDX_DRAW_ROIS)));
        assertTrue(scanner.tooltipFor(FLASH_Pipeline.IDX_DRAW_ROIS)
                .contains("Draw ROIs and Orientate Images outputs"));
    }

    @Test
    public void scan_ignoresOldRoiFolders() throws Exception {
        File dir = temp.newFolder("old-rois-ignored");
        File oldRois = new File(dir, "ROIs");
        assertTrue(oldRois.mkdirs());
        writeRoiZip(new File(oldRois, "Old ROIs.zip"));

        Map<Integer, AnalysisStatus> statuses = new AnalysisStatusScanner().scan(dir);

        assertEquals(AnalysisStatus.NOT_STARTED,
                statuses.get(Integer.valueOf(FLASH_Pipeline.IDX_DRAW_ROIS)));
    }

    @Test
    public void writeSidecar_usesProjectStatusJson() throws Exception {
        File dir = temp.newFolder("sidecar-new");

        AnalysisStatusScanner.writeSidecar(dir, AnalysisStatusScanner.CREATE_BIN_ID, 3);

        File status = ProjectStatusStore.statusFile(dir);
        assertEquals(true, status.isFile());
        Map<String, Object> createBin = ProjectStatusStore.readAnalysisStatus(dir,
                AnalysisStatusScanner.CREATE_BIN_ID);
        assertEquals(3, ((Number) createBin.get("imageCount")).intValue());
        assertEquals(false, new File(dir, ".flash-status/createBin.json").exists());
        assertEquals(false, new File(dir, "FLASH/Status").exists());
    }

    @Test
    public void scan_detectsNewFlashOutputFolders() throws Exception {
        File dir = temp.newFolder("new-output-done");
        FlashProjectLayout layout = FlashProjectLayout.forDirectory(dir.getAbsolutePath());
        assertEquals(true, layout.tablesObjectsWriteDir().mkdirs());
        assertEquals(true, new File(layout.tablesObjectsWriteDir(), "DAPI.csv").createNewFile());
        File summaryParent = layout.summaryWorkbookWriteFile().getParentFile();
        assertEquals(true, summaryParent.isDirectory() || summaryParent.mkdirs());
        assertEquals(true, layout.summaryWorkbookWriteFile().createNewFile());

        Map<Integer, AnalysisStatus> statuses = new AnalysisStatusScanner().scan(dir);

        assertEquals(AnalysisStatus.DONE,
                statuses.get(Integer.valueOf(FLASH_Pipeline.IDX_3D_OBJECT)));
        assertEquals(AnalysisStatus.DONE,
                statuses.get(Integer.valueOf(FLASH_Pipeline.IDX_EXCEL_EXPORT)));
    }

    @Test
    public void scan_detectsRepresentativeFigureOutput() throws Exception {
        File dir = temp.newFolder("representative-figure-done");
        FlashProjectLayout layout = FlashProjectLayout.forDirectory(dir.getAbsolutePath());
        assertEquals(true, layout.representativeFiguresDir().mkdirs());
        assertEquals(true, new File(layout.representativeFiguresDir(),
                "Representative_Figure.png").createNewFile());

        AnalysisStatusScanner scanner = new AnalysisStatusScanner();
        Map<Integer, AnalysisStatus> statuses = scanner.scan(dir);

        assertEquals(AnalysisStatus.DONE,
                statuses.get(Integer.valueOf(FLASH_Pipeline.IDX_REPRESENTATIVE_FIGURE)));
        assertTrue(scanner.tooltipFor(FLASH_Pipeline.IDX_REPRESENTATIVE_FIGURE)
                .contains("Representative Image Figure outputs"));
    }

    @Test
    public void scan_ignoresNonPngRepresentativeFigureFiles() throws Exception {
        File dir = temp.newFolder("representative-figure-stray-file");
        FlashProjectLayout layout = FlashProjectLayout.forDirectory(dir.getAbsolutePath());
        assertEquals(true, layout.representativeFiguresDir().mkdirs());
        assertEquals(true, new File(layout.representativeFiguresDir(),
                "Representative_Figure.png.tmp").createNewFile());

        Map<Integer, AnalysisStatus> statuses = new AnalysisStatusScanner().scan(dir);

        assertEquals(AnalysisStatus.NOT_STARTED,
                statuses.get(Integer.valueOf(FLASH_Pipeline.IDX_REPRESENTATIVE_FIGURE)));
    }

    @Test
    public void scan_keepsSetupDoneWhenSplitMergeOnlyUpdatesDisplayMinMax() throws Exception {
        File dir = temp.newFolder("split-merge-minmax");
        TestConfigFiles.writeChannelConfig(dir, TestConfigFiles.basicBinConfig("DAPI", "GFAP"));
        AnalysisStatusScanner.writeSidecar(dir, AnalysisStatusScanner.CREATE_BIN_ID, 2);

        BinConfigIO.updateMinMax(dir.getAbsolutePath(), new String[]{"10-200", "20-400"});

        AnalysisStatusScanner scanner = new AnalysisStatusScanner();
        Map<Integer, AnalysisStatus> statuses = scanner.scan(dir);

        assertEquals(AnalysisStatus.DONE,
                statuses.get(Integer.valueOf(FLASH_Pipeline.IDX_CREATE_BIN)));
        assertFalse(scanner.tooltipFor(FLASH_Pipeline.IDX_CREATE_BIN)
                .contains("configuration has changed since"));
    }

    @Test
    public void scan_marksPartiallySavedSetupAsStale() throws Exception {
        File dir = temp.newFolder("partial-setup-status");
        ChannelConfig cfg = new ChannelConfig();
        cfg.complete = Boolean.FALSE;
        ChannelConfig.Channel channel = new ChannelConfig.Channel();
        channel.index = 0;
        channel.name = "DAPI";
        channel.color = "Blue";
        channel.minmax = "10-200";
        channel.status.put(ChannelConfig.P_NAME, ChannelConfig.PropertyStatus.CONFIGURED);
        channel.status.put(ChannelConfig.P_COLOR, ChannelConfig.PropertyStatus.CONFIGURED);
        channel.status.put(ChannelConfig.P_MARKER, ChannelConfig.PropertyStatus.CONFIGURED);
        channel.status.put(ChannelConfig.P_MINMAX, ChannelConfig.PropertyStatus.CONFIGURED);
        channel.status.put(ChannelConfig.P_THRESHOLD, ChannelConfig.PropertyStatus.PENDING);
        channel.status.put(ChannelConfig.P_SIZE, ChannelConfig.PropertyStatus.PENDING);
        channel.status.put(ChannelConfig.P_INTENSITY, ChannelConfig.PropertyStatus.PENDING);
        channel.status.put(ChannelConfig.P_SEGMENTATION, ChannelConfig.PropertyStatus.PENDING);
        channel.status.put(ChannelConfig.P_FILTER, ChannelConfig.PropertyStatus.PENDING);
        cfg.channels.add(channel);
        ChannelConfigIO.write(TestConfigFiles.settingsDir(dir), cfg);

        AnalysisStatusScanner scanner = new AnalysisStatusScanner();
        Map<Integer, AnalysisStatus> statuses = scanner.scan(dir);

        assertEquals(AnalysisStatus.STALE,
                statuses.get(Integer.valueOf(FLASH_Pipeline.IDX_CREATE_BIN)));
        assertTrue(scanner.tooltipFor(FLASH_Pipeline.IDX_CREATE_BIN).contains("partially saved"));
    }

    @Test
    public void scan_marksSetupStaleWhenAnalysisRelevantConfigChanges() throws Exception {
        File dir = temp.newFolder("setup-relevant-change");
        TestConfigFiles.writeChannelConfig(dir, TestConfigFiles.basicBinConfig("DAPI", "GFAP"));
        AnalysisStatusScanner.writeSidecar(dir, AnalysisStatusScanner.CREATE_BIN_ID, 2);

        File settingsDir = TestConfigFiles.settingsDir(dir);
        ChannelConfig cfg = ChannelConfigIO.read(settingsDir);
        cfg.channels.get(1).threshold = "otsu";
        ChannelConfigIO.write(settingsDir, cfg);

        Map<Integer, AnalysisStatus> statuses = new AnalysisStatusScanner().scan(dir);

        assertEquals(AnalysisStatus.STALE,
                statuses.get(Integer.valueOf(FLASH_Pipeline.IDX_CREATE_BIN)));
    }

    private static void writeRoiZip(File file) throws Exception {
        ZipOutputStream out = new ZipOutputStream(new FileOutputStream(file));
        try {
            out.putNextEntry(new ZipEntry("LH.roi"));
            out.write(new byte[]{73, 111, 117, 116});
            out.closeEntry();
        } finally {
            out.close();
        }
    }
}
