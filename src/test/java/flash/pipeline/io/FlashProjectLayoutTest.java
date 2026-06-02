package flash.pipeline.io;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class FlashProjectLayoutTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void configurationDirIsConfig() {
        assertEquals("Config", FlashProjectLayout.CONFIGURATION_DIR);
    }

    @Test
    public void writePaths_areUnderFlashRoot() throws Exception {
        File project = temp.newFolder("project");
        FlashProjectLayout layout = FlashProjectLayout.forDirectory(project.getAbsolutePath());

        assertPath(new File(project, "FLASH"), layout.flashRoot());
        assertPath(new File(project, "FLASH/Config"), layout.visibleConfigurationDir());
        assertPath(new File(project, "FLASH/Config/Segmentation models"), layout.segmentationModelsRoot());
        assertPath(new File(project, "FLASH/Config/Training Datasets"), layout.trainingDatasetsRoot());
        assertPath(new File(project, "FLASH/Config/.settings"), layout.configurationWriteDir());
        assertPath(new File(project, "FLASH/Results/Tables/Project Summary/Conditions.csv"),
                layout.projectSummaryWriteFile(FlashProjectLayout.CONDITIONS_FILENAME));
        assertPath(new File(project, "FLASH/Results/Tables/Project Summary/Statistics.csv"),
                layout.projectSummaryWriteFile(FlashProjectLayout.STATISTICS_FILENAME));
        assertPath(new File(project, "FLASH/Results/Summary.xlsx"),
                layout.summaryWorkbookWriteFile());
        assertPath(new File(project, "FLASH/.settings/Presets"), layout.presetsRoot());
        assertPath(new File(project, "FLASH/Cache"), layout.cacheRoot());
        assertPath(new File(project, "FLASH/Cache/TIF"), layout.tifCacheWriteDir());
        assertPath(new File(project, "FLASH/.settings/status.json"), layout.statusWriteFile());
    }

    @Test
    public void resultsWritePaths_areUnderResultsRoot() throws Exception {
        File project = temp.newFolder("project");
        FlashProjectLayout layout = FlashProjectLayout.forDirectory(project.getAbsolutePath());

        assertPath(new File(project, "FLASH/Results"), layout.resultsRoot());
        assertPath(new File(project, "FLASH/Results/Tables"), layout.tablesRoot());
        assertPath(new File(project, "FLASH/Results/Tables/Project Summary"),
                layout.tablesProjectSummaryWriteDir());
        assertPath(new File(project, "FLASH/Results/Tables/ROIs"), layout.tablesRoiWriteDir());
        assertPath(new File(project, "FLASH/Results/Tables/Objects"), layout.tablesObjectsWriteDir());
        assertPath(new File(project, "FLASH/Results/Tables/Intensity"), layout.tablesIntensityWriteDir());
        assertPath(new File(project, "FLASH/Results/Tables/Spatial"), layout.tablesSpatialWriteDir());
        assertPath(new File(project, "FLASH/Results/Tables/Morphometry"), layout.tablesMorphometryWriteDir());
        assertPath(new File(project, "FLASH/Results/Tables/Line Distance"), layout.tablesLineDistanceWriteDir());
        assertPath(new File(project, "FLASH/Results/Tables/Spectral Decontamination"),
                layout.tablesSpectralWriteDir());
        assertPath(new File(project, "FLASH/Results/Presentation Images"), layout.presentationImagesRoot());
        assertPath(new File(project, "FLASH/Results/Presentation Images/Images"),
                layout.presentationImagesDir());
        assertPath(new File(project, "FLASH/Results/Presentation Images/Annotated Images"),
                layout.presentationAnnotatedDir());
        assertPath(new File(project, "FLASH/Results/Presentation Images/Tiles"),
                layout.presentationTilesDir());
        assertPath(new File(project, "FLASH/Results/Presentation Images/OME-TIFF"),
                layout.presentationOmeTiffDir());
        assertPath(new File(project, "FLASH/Results/Analysis Images"), layout.analysisImagesRoot());
        assertPath(new File(project, "FLASH/Results/Analysis Images/ROIs"), layout.analysisImagesRoiDir());
        assertPath(new File(project, "FLASH/Results/Analysis Images/Objects"),
                layout.analysisImagesObjectsRoot());
        assertPath(new File(project, "FLASH/Results/Analysis Images/Objects/Masks and Label Maps"),
                layout.analysisImagesObjectsMasksDir());
        assertPath(new File(project, "FLASH/Results/Analysis Images/Objects/Masked Images"),
                layout.analysisImagesObjectsMaskedDir());
        assertPath(new File(project, "FLASH/Results/Analysis Images/Objects/Filtered Inputs"),
                layout.analysisImagesObjectsFilteredDir());
        assertPath(new File(project, "FLASH/Results/Analysis Images/Intensity Overlays"),
                layout.analysisImagesIntensityOverlaysDir());
        assertPath(new File(project, "FLASH/Results/Analysis Images/Spatial Heatmaps"),
                layout.analysisImagesSpatialHeatmapsDir());
        assertPath(new File(project, "FLASH/Results/Analysis Images/Spectral Decontamination"),
                layout.analysisImagesSpectralDir());
        assertPath(new File(project, "FLASH/Results/Analysis Images/Deconvolution"),
                layout.analysisImagesDeconvolutionDir());
        assertPath(new File(project, "FLASH/Results/QC"), layout.qcRoot());
        assertPath(new File(project, "FLASH/Results/QC/QC_Report.html"), layout.qcReportWriteFile());
        assertPath(new File(project, "FLASH/Results/QC/overlays"), layout.qcOverlaysWriteDir());
        assertPath(new File(project, "FLASH/Results/Run Records"), layout.runRecordsRoot());
        assertPath(new File(project, "FLASH/Results/Run Records/run_history.csv"),
                layout.runHistoryWriteFile());
        assertPath(new File(project, "FLASH/Results/Run Records/settings_snapshots"),
                layout.settingsSnapshotsWriteDir());
        assertPath(new File(project, "FLASH/Results/Run Records/replay_commands"),
                layout.replayCommandsWriteDir());
        assertPath(new File(project, "FLASH/Results/Run Records/analysis_details"),
                layout.analysisDetailsWriteDir());
        assertPath(new File(project, "FLASH/Results/Run Records/runs"),
                layout.runJsonlWriteDir());
        assertPath(new File(project, "FLASH/Results/Run Records/replays"),
                layout.replayWorkspacesWriteDir());
        assertPath(new File(project, "FLASH/Results/Summary.xlsx"), layout.summaryWorkbookWriteFile());
        assertPath(new File(project, "FLASH/Results/START_HERE.html"), layout.startHereWriteFile());
    }

    @Test(expected = IllegalArgumentException.class)
    public void forDirectory_rejectsBlankDirectory() {
        FlashProjectLayout.forDirectory("");
    }

    @Test
    public void configurationReadPathsUseOnlyCanonicalSettingsDirAndDoNotCreateDirectories() throws Exception {
        File project = temp.newFolder("project");
        FlashProjectLayout layout = FlashProjectLayout.forDirectory(project.getAbsolutePath());

        assertPaths(layout.configurationReadDirs(),
                new File(project, "FLASH/Config/.settings"));
        assertNull(layout.existingConfigurationDir());
        assertFalse(new File(project, "FLASH").exists());

        File settings = new File(project, "FLASH/Config/.settings");
        assertTrue(settings.mkdirs());
        assertPath(settings, layout.existingConfigurationDir());
    }

    @Test
    public void resultsProjectSummary_hasSingleWritePath() throws Exception {
        File project = temp.newFolder("project");
        FlashProjectLayout layout = FlashProjectLayout.forDirectory(project.getAbsolutePath());

        assertPath(new File(project, "FLASH/Results/Tables/Project Summary/3D Objects.csv"),
                layout.projectSummaryWriteFile(FlashProjectLayout.MASTER_OBJECTS_FILENAME));
        assertPath(new File(project, "FLASH/Results/Tables/Project Summary/Image Intensities.csv"),
                layout.projectSummaryWriteFile(FlashProjectLayout.MASTER_INTENSITIES_FILENAME));
        assertPath(new File(project, "FLASH/Results/Tables/Project Summary/Image Orientation.csv"),
                layout.projectSummaryWriteFile(FlashProjectLayout.ORIENTATION_MANIFEST_FILENAME));
    }

    @Test
    public void rootReadDirsUseOnlyCanonicalPaths() throws Exception {
        File project = temp.newFolder("project");
        FlashProjectLayout layout = FlashProjectLayout.forDirectory(project.getAbsolutePath());

        assertPaths(layout.presetsReadDirs(),
                new File(project, "FLASH/.settings/Presets"));
        assertPath(new File(project, "FLASH/.settings/Presets/Custom Filter Presets"),
                layout.customFilterPresetWriteDir());
        assertPaths(layout.customFilterPresetReadDirs(),
                new File(project, "FLASH/.settings/Presets/Custom Filter Presets"));
        assertPaths(layout.tifCacheReadDirs(),
                new File(project, "FLASH/Cache/TIF"));
    }

    private static void assertPaths(List<File> actual, File... expected) {
        assertEquals(expected.length, actual.size());
        for (int i = 0; i < expected.length; i++) {
            assertPath(expected[i], actual.get(i));
        }
    }

    private static void assertPath(File expected, File actual) {
        assertEquals(expected.getAbsolutePath(), actual.getAbsolutePath());
    }
}
