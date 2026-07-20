package flash.pipeline.roi;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.file.Files;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class RoiIOTest {
    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void roiZipDiscovery_readsAnalysisImagesRoiFolder() throws Exception {
        File dir = temp.newFolder("roiLayout");
        File roiImages = new File(dir, "FLASH/Results/Analysis Images/ROIs");
        assertTrue(roiImages.mkdirs());

        writeBytes(new File(roiImages, "SCN ROIs.zip"));
        writeBytes(new File(roiImages, "PVN ROIs.zip"));

        List<File> zips = RoiIO.listRoiZipFiles(dir);

        assertEquals(2, zips.size());
        assertEquals(new File(roiImages, "PVN ROIs.zip").getAbsolutePath(),
                zips.get(0).getAbsolutePath());
        assertEquals(new File(roiImages, "SCN ROIs.zip").getAbsolutePath(),
                zips.get(1).getAbsolutePath());
    }

    @Test
    public void roiPropertiesDiscovery_readsTablesRoiFolder() throws Exception {
        File dir = temp.newFolder("roiPropsLayout");
        File roiTables = new File(dir, "FLASH/Results/Tables/ROIs");
        assertTrue(roiTables.mkdirs());

        writeBytes(new File(roiTables, "SCN ROI Properties.csv"));
        writeBytes(new File(roiTables, "Backup ROI Properties.csv"));

        List<File> csvs = RoiIO.listRoiPropertiesCsvFiles(dir);

        assertEquals(2, csvs.size());
        assertEquals(new File(roiTables, "Backup ROI Properties.csv").getAbsolutePath(),
                csvs.get(0).getAbsolutePath());
        assertEquals(new File(roiTables, "SCN ROI Properties.csv").getAbsolutePath(),
                csvs.get(1).getAbsolutePath());
    }

    @Test
    public void roiWriteAndReadDirsUseResultsFolders() throws Exception {
        File dir = temp.newFolder("roiImageOutputs");
        File roiImages = new File(dir, "FLASH/Results/Analysis Images/ROIs");
        File roiTables = new File(dir, "FLASH/Results/Tables/ROIs");

        assertEquals(roiImages, RoiIO.roiSetWriteDir(dir));
        assertEquals(roiImages, RoiIO.imageOutputsWriteDir(dir));
        assertEquals(roiImages, RoiIO.imageOutputsWriteDir(dir, " AnimalA "));
        assertEquals(roiImages, RoiIO.partialWriteDir(dir));
        assertEquals(roiTables, RoiIO.attributesWriteDir(dir));
        assertEquals(1, RoiIO.roiSetReadDirs(dir).size());
        assertEquals(roiImages, RoiIO.roiSetReadDirs(dir).get(0));
        assertEquals(1, RoiIO.attributesReadDirs(dir).size());
        assertEquals(roiTables, RoiIO.attributesReadDirs(dir).get(0));
    }

    private static void writeBytes(File file) throws Exception {
        Files.write(file.toPath(), new byte[]{1, 2, 3});
    }
}
