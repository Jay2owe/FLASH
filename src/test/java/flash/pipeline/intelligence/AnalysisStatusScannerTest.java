package flash.pipeline.intelligence;

import flash.pipeline.FLASH_Pipeline;
import flash.pipeline.io.FlashProjectLayout;
import flash.pipeline.io.OrientationManifestIO;
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
        assertTrue(scanner.tooltipFor(FLASH_Pipeline.IDX_DRAW_ROIS).contains("ROIs outputs"));
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
    public void writeSidecar_usesFlashStatusDirectory() throws Exception {
        File dir = temp.newFolder("sidecar-new");

        AnalysisStatusScanner.writeSidecar(dir, AnalysisStatusScanner.CREATE_BIN_ID, 3);

        File sidecar = new File(FlashProjectLayout.forDirectory(dir.getAbsolutePath())
                .analysisStatusWriteDir(), AnalysisStatusScanner.CREATE_BIN_ID + ".json");
        assertEquals(true, sidecar.isFile());
        assertEquals(false, new File(dir, ".flash-status/createBin.json").exists());
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
