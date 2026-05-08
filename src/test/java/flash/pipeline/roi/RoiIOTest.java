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
    public void roiZipDiscovery_prefersNewRoiSetFolderAndReadsLegacyFallbacks() throws Exception {
        File dir = temp.newFolder("roiLayout");
        File newRoiSets = new File(dir, "FLASH/Draw and Save ROIs/ROI Sets");
        File newAttributes = new File(dir, "FLASH/Draw and Save ROIs/Attributes");
        File legacyRois = new File(dir, "ROIs");
        File legacyAttributes = new File(dir, "Data Analysis/Attributes");
        assertTrue(newRoiSets.mkdirs());
        assertTrue(newAttributes.mkdirs());
        assertTrue(legacyRois.mkdirs());
        assertTrue(legacyAttributes.mkdirs());

        writeBytes(new File(newRoiSets, "SCN ROIs.zip"));
        writeBytes(new File(newAttributes, "SCN ROIs.zip"));
        writeBytes(new File(legacyRois, "Legacy ROIs.zip"));
        writeBytes(new File(legacyAttributes, "Backup ROIs.zip"));

        List<File> zips = RoiIO.listRoiZipFiles(dir);

        assertEquals(3, zips.size());
        assertEquals(new File(newRoiSets, "SCN ROIs.zip").getAbsolutePath(),
                zips.get(0).getAbsolutePath());
        assertEquals(new File(legacyRois, "Legacy ROIs.zip").getAbsolutePath(),
                zips.get(1).getAbsolutePath());
        assertEquals(new File(legacyAttributes, "Backup ROIs.zip").getAbsolutePath(),
                zips.get(2).getAbsolutePath());
    }

    @Test
    public void roiPropertiesDiscovery_prefersNewAttributesAndReadsLegacyFallbacks() throws Exception {
        File dir = temp.newFolder("roiPropsLayout");
        File newAttributes = new File(dir, "FLASH/Draw and Save ROIs/Attributes");
        File legacyAttributes = new File(dir, "Data Analysis/Attributes");
        assertTrue(newAttributes.mkdirs());
        assertTrue(legacyAttributes.mkdirs());

        writeBytes(new File(newAttributes, "SCN ROI Properties.csv"));
        writeBytes(new File(legacyAttributes, "SCN ROI Properties.csv"));
        writeBytes(new File(legacyAttributes, "Backup ROI Properties.csv"));

        List<File> csvs = RoiIO.listRoiPropertiesCsvFiles(dir);

        assertEquals(2, csvs.size());
        assertEquals(new File(newAttributes, "SCN ROI Properties.csv").getAbsolutePath(),
                csvs.get(0).getAbsolutePath());
        assertEquals(new File(legacyAttributes, "Backup ROI Properties.csv").getAbsolutePath(),
                csvs.get(1).getAbsolutePath());
    }

    @Test
    public void roiImageOutputsWriteUnderFlashRoiFolder() throws Exception {
        File dir = temp.newFolder("roiImageOutputs");

        assertEquals(new File(dir, "FLASH/Draw and Save ROIs/Image Outputs"),
                RoiIO.imageOutputsWriteDir(dir));
        assertEquals(new File(dir, "FLASH/Draw and Save ROIs/Image Outputs/AnimalA"),
                RoiIO.imageOutputsWriteDir(dir, "AnimalA"));
        assertEquals(new File(dir, "FLASH/Draw and Save ROIs/Image Outputs/AnimalA"),
                RoiIO.imageOutputsWriteDir(dir, " AnimalA "));
    }

    private static void writeBytes(File file) throws Exception {
        Files.write(file.toPath(), new byte[]{1, 2, 3});
    }
}
