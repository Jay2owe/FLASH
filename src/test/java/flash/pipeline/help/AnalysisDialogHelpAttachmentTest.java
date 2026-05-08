package flash.pipeline.help;

import flash.pipeline.FLASH_Pipeline;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

import static org.junit.Assert.assertTrue;

public class AnalysisDialogHelpAttachmentTest {

    @Test
    public void requiredAnalysisDialogsAttachCatalogHelpTopics() throws Exception {
        assertSourceContains("src/main/java/flash/pipeline/analyses/CreateBinFileAnalysis.java",
                helpCall("Set Up Configuration", "IDX_CREATE_BIN"));
        assertSourceContains("src/main/java/flash/pipeline/analyses/DrawAndSaveROIsAnalysis.java",
                helpCall("Draw and Save ROIs", "IDX_DRAW_ROIS"));
        assertSourceContains("src/main/java/flash/pipeline/analyses/SplitAndMergeImageChannelsAnalysis.java",
                helpCall("Make Presentation-Ready Images", "IDX_SPLIT_MERGE"));
        assertSourceContains("src/main/java/flash/pipeline/analyses/IntensityAnalysisV2.java",
                helpCall("Fluorescence Intensity Analysis", "IDX_INTENSITY"));
        assertSourceContains("src/main/java/flash/pipeline/analyses/ThreeDObjectAnalysis.java",
                helpCall("3D Object Analysis", "IDX_3D_OBJECT"));
        assertSourceContains("src/main/java/flash/pipeline/analyses/SpatialAnalysis.java",
                helpCall("Spatial Analysis", "IDX_SPATIAL"));
    }

    @Test
    public void multiPageDialogsKeepHelpOnBackPages() throws Exception {
        assertSourceContains("src/main/java/flash/pipeline/analyses/CreateBinFileAnalysis.java",
                "fork.addAnalysisHelpHeader(\"Set Up Configuration\", FLASH_Pipeline.IDX_CREATE_BIN);");
        assertSourceContains("src/main/java/flash/pipeline/analyses/CreateBinFileAnalysis.java",
                "sdDialog.addAnalysisHelpHeader(\"Set Up Configuration\", FLASH_Pipeline.IDX_CREATE_BIN);");
        assertSourceContains("src/main/java/flash/pipeline/analyses/CreateBinFileAnalysis.java",
                "cpDialog.addAnalysisHelpHeader(\"Set Up Configuration\", FLASH_Pipeline.IDX_CREATE_BIN);");
        assertSourceContains("src/main/java/flash/pipeline/analyses/CreateBinFileAnalysis.java",
                "gdSize.addAnalysisHelpHeader(\"Set Up Configuration\", FLASH_Pipeline.IDX_CREATE_BIN);");
        assertSourceContains("src/main/java/flash/pipeline/analyses/IntensityAnalysisV2.java",
                "gd2.addAnalysisHelpHeader(\"Fluorescence Intensity Analysis\", FLASH_Pipeline.IDX_INTENSITY);");
        assertSourceContains("src/main/java/flash/pipeline/analyses/ThreeDObjectAnalysis.java",
                "gdPA.addAnalysisHelpHeader(\"3D Object Analysis\", FLASH_Pipeline.IDX_3D_OBJECT);");
    }

    @Test
    public void attachedDialogTopicsExistInCatalog() {
        assertCatalogTopic(FLASH_Pipeline.IDX_CREATE_BIN);
        assertCatalogTopic(FLASH_Pipeline.IDX_DRAW_ROIS);
        assertCatalogTopic(FLASH_Pipeline.IDX_SPLIT_MERGE);
        assertCatalogTopic(FLASH_Pipeline.IDX_INTENSITY);
        assertCatalogTopic(FLASH_Pipeline.IDX_3D_OBJECT);
        assertCatalogTopic(FLASH_Pipeline.IDX_SPATIAL);
    }

    private static void assertCatalogTopic(int analysisIndex) {
        assertTrue("missing reusable help topic for analysis index " + analysisIndex,
                AnalysisHelpCatalog.hasTopic(analysisIndex));
    }

    private static void assertSourceContains(String path, String expected) throws Exception {
        String source = new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8);
        assertTrue(path + " should contain " + expected, source.contains(expected));
    }

    private static String helpCall(String title, String indexConstant) {
        return "addAnalysisHelpHeader(\"" + title + "\", FLASH_Pipeline." + indexConstant + ")";
    }
}
