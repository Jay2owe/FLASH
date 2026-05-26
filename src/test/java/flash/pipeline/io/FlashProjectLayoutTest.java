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
    public void writePaths_areUnderFlashRoot() throws Exception {
        File project = temp.newFolder("project");
        FlashProjectLayout layout = FlashProjectLayout.forDirectory(project.getAbsolutePath());

        assertPath(new File(project, "FLASH"), layout.flashRoot());
        assertPath(new File(project, "FLASH/Set Up Configuration"), layout.visibleConfigurationDir());
        assertPath(new File(project, "FLASH/Set Up Configuration/.settings"), layout.configurationWriteDir());
        assertPath(new File(project, "FLASH/Set Up Configuration/.settings/Channel_Data.txt"),
                layout.channelDataWriteFile());
        assertPath(new File(project, "FLASH/Presentation-Ready Images"),
                layout.analysisWriteDir(FlashProjectLayout.AnalysisFolder.SPLIT_MERGE));
        assertPath(new File(project, "FLASH/Image Analysis/3D Objects/Objects"),
                layout.objectDataWriteDir());
        assertPath(new File(project, "FLASH/Image Analysis/Image Intensities"),
                layout.intensityDataWriteDir());
        assertPath(new File(project, "FLASH/Image Analysis/Image Intensities/Analysis Details"),
                layout.intensityAnalysisDetailsWriteDir());
        assertPath(new File(project, "FLASH/Image Analysis/3D Objects/Objects/Analysis Details"),
                layout.objectAnalysisDetailsWriteDir());
        assertPath(new File(project, "FLASH/Image Analysis/3D Objects/Image Outputs"),
                layout.objectImageOutputsWriteDir());
        assertPath(new File(project, "FLASH/Image Analysis/Spatial Analysis/Spatial"),
                layout.spatialDataWriteDir());
        assertPath(new File(project, "FLASH/Image Analysis/Spatial Analysis/Morphometry"),
                layout.spatialMorphometryWriteDir());
        assertPath(new File(project, "FLASH/Image Analysis/Spatial Analysis/Image Outputs"),
                layout.spatialImageOutputsWriteDir());
        assertPath(new File(project, "FLASH/Image Analysis/Line Distance Analysis"),
                layout.lineDistanceWriteDir());
        assertPath(new File(project, "FLASH/Image Analysis/Line Distance Analysis/Line Sets"),
                layout.lineSetWriteDir());
        assertPath(new File(project, "FLASH/Results Export"),
                layout.aggregationWriteDir());
        assertPath(new File(project, "FLASH/Results Export"),
                layout.statisticsWriteDir());
        assertPath(new File(project, "FLASH/Results Export/Conditions.csv"),
                layout.conditionManifestWriteFile());
        assertPath(new File(project, "FLASH/Results Export/Statistics.csv"),
                layout.statisticsWriteFile(FlashProjectLayout.STATISTICS_FILENAME));
        assertPath(new File(project, "FLASH/Results Export"),
                layout.excelWriteDir());
        assertPath(new File(project, "FLASH/Results Export/Summary.xlsx"),
                layout.excelWriteFile(FlashProjectLayout.SUMMARY_WORKBOOK_FILENAME));
        assertPath(new File(project, "FLASH/Spectral Decontamination"),
                layout.analysisWriteDir(FlashProjectLayout.AnalysisFolder.SPECTRAL));
        assertPath(new File(project, "FLASH/.settings/Presets"), layout.presetsRoot());
        assertPath(new File(project, "FLASH/Reports"), layout.reportsRoot());
        assertPath(new File(project, "FLASH/Reports/Quality Report"), layout.qualityReportWriteDir());
        assertPath(new File(project, "FLASH/Cache"), layout.cacheRoot());
        assertPath(new File(project, "FLASH/Cache/TIF"), layout.tifCacheWriteDir());
        assertPath(new File(project, "FLASH/Status"), layout.statusRoot());
        assertPath(new File(project, "FLASH/Status/.settings/Analysis"), layout.analysisStatusWriteDir());
        assertPath(new File(project, "FLASH/Status/.settings/Audit"), layout.auditRoot());
        assertPath(new File(project, "FLASH/Status/.settings/cli_status.txt"),
                layout.statusWriteFile("cli_status.txt"));
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
        assertPath(new File(project, "FLASH/Results/Summary.xlsx"), layout.summaryWorkbookWriteFile());
        assertPath(new File(project, "FLASH/Results/START_HERE.html"), layout.startHereWriteFile());
    }

    @Test(expected = IllegalArgumentException.class)
    public void forDirectory_rejectsBlankDirectory() {
        FlashProjectLayout.forDirectory("");
    }

    @Test
    public void configurationReadPaths_preferNewThenLegacyAndDoNotCreateDirectories() throws Exception {
        File project = temp.newFolder("project");
        FlashProjectLayout layout = FlashProjectLayout.forDirectory(project.getAbsolutePath());

        assertPaths(layout.configurationReadDirs(),
                new File(project, "FLASH/Set Up Configuration/.settings"),
                new File(project, "FLASH/Set Up Configuration"),
                new File(project, "FLASH/00 - Configuration"),
                new File(project, ".bin"));
        assertPaths(layout.channelDataReadFiles(),
                new File(project, "FLASH/Set Up Configuration/.settings/Channel_Data.txt"),
                new File(project, "FLASH/Set Up Configuration/Channel_Data.txt"),
                new File(project, "FLASH/00 - Configuration/Channel_Data.txt"),
                new File(project, ".bin/Channel_Data.txt"));
        assertNull(layout.existingConfigurationDir());
        assertPath(new File(project, "FLASH/Set Up Configuration/.settings/Channel_Data.txt"),
                layout.channelDataReadFile());
        assertFalse(new File(project, "FLASH").exists());
        assertFalse(new File(project, ".bin").exists());

        File legacyBin = new File(project, ".bin");
        assertTrue(legacyBin.mkdirs());
        File legacyChannelData = new File(legacyBin, "Channel_Data.txt");
        assertTrue(legacyChannelData.createNewFile());
        assertPath(legacyBin, layout.existingConfigurationDir());
        assertPath(legacyChannelData, layout.channelDataReadFile());

        File newConfig = new File(project, "FLASH/Set Up Configuration/.settings");
        assertTrue(newConfig.mkdirs());
        File newChannelData = new File(newConfig, "Channel_Data.txt");
        assertTrue(newChannelData.createNewFile());
        assertPath(newConfig, layout.existingConfigurationDir());
        assertPath(newChannelData, layout.channelDataReadFile());
    }

    @Test
    public void analysisReadDirs_includeNewPathThenLegacyFallbacks() throws Exception {
        File project = temp.newFolder("project");
        FlashProjectLayout layout = FlashProjectLayout.forDirectory(project.getAbsolutePath());

        assertPaths(layout.analysisReadDirs(FlashProjectLayout.AnalysisFolder.DECONVOLUTION),
                new File(project, "FLASH/3D Deconvolution"),
                new File(project, "FLASH/02 - 3D Deconvolution"),
                new File(project, "Image Analysis/Deconvolved"));
        assertPaths(layout.analysisReadDirs(FlashProjectLayout.AnalysisFolder.SPLIT_MERGE),
                new File(project, "FLASH/Presentation-Ready Images"),
                new File(project, "FLASH/Make Presentation-Ready Images"),
                new File(project, "FLASH/03 - Split and Merge"),
                new File(project, "Images"));
        assertPaths(layout.analysisReadDirs(FlashProjectLayout.AnalysisFolder.SPATIAL),
                new File(project, "FLASH/Image Analysis/Spatial Analysis"),
                new File(project, "FLASH/06 - Spatial Analysis"),
                new File(project, "Data Analysis/Spatial"),
                new File(project, "Data Analysis/Morphometry"));
        assertPaths(layout.analysisReadDirs(FlashProjectLayout.AnalysisFolder.EXCEL),
                new File(project, "FLASH/Results Export"),
                new File(project, "FLASH/11 - Excel Summary Export"),
                new File(project, "ImageJ Exports"));
        assertPaths(layout.excelReadDirs(),
                new File(project, "FLASH/Results Export"),
                new File(project, "FLASH/11 - Excel Summary Export"),
                new File(project, "ImageJ Exports"));
        assertPaths(layout.objectDataReadDirs(),
                new File(project, "FLASH/Image Analysis/3D Objects/Objects"),
                new File(project, "FLASH/05 - 3D Object Analysis/Objects"),
                new File(project, "Data Analysis/Objects"));
        assertPaths(layout.objectAnalysisDetailsReadDirs(),
                new File(project, "FLASH/Image Analysis/3D Objects/Objects/Analysis Details"),
                new File(project, "FLASH/05 - 3D Object Analysis/Objects/Analysis Details"),
                new File(project, "Data Analysis/Objects/Analysis Details"));
        assertPaths(layout.intensityDataReadDirs(),
                new File(project, "FLASH/Image Analysis/Image Intensities"),
                new File(project, "FLASH/04 - Fluorescence Intensity"),
                new File(project, "Data Analysis/ROI Intensities"));
        assertPaths(layout.intensityAnalysisDetailsReadDirs(),
                new File(project, "FLASH/Image Analysis/Image Intensities/Analysis Details"),
                new File(project, "FLASH/04 - Fluorescence Intensity/Analysis Details"),
                new File(project, "Data Analysis/ROI Intensities/Analysis Details"));
        assertPaths(layout.objectImageOutputReadDirs(),
                new File(project, "FLASH/Image Analysis/3D Objects/Image Outputs"),
                new File(project, "FLASH/05 - 3D Object Analysis/Image Outputs"),
                new File(project, "Image Analysis"));
        assertPaths(layout.spatialDataReadDirs(),
                new File(project, "FLASH/Image Analysis/Spatial Analysis/Spatial"),
                new File(project, "FLASH/06 - Spatial Analysis/Spatial"),
                new File(project, "Data Analysis/Spatial"));
        assertPaths(layout.spatialMorphometryReadDirs(),
                new File(project, "FLASH/Image Analysis/Spatial Analysis/Morphometry"),
                new File(project, "FLASH/06 - Spatial Analysis/Morphometry"),
                new File(project, "Data Analysis/Morphometry"));
        assertPaths(layout.spatialImageOutputReadDirs(),
                new File(project, "FLASH/Image Analysis/Spatial Analysis/Image Outputs"),
                new File(project, "FLASH/06 - Spatial Analysis/Image Outputs"),
                new File(project, "Image Analysis"));
        assertPaths(layout.lineDistanceReadDirs(),
                new File(project, "FLASH/Image Analysis/Line Distance Analysis"),
                new File(project, "FLASH/07 - Line Distance"),
                new File(project, "Data Analysis/Objects"));
        assertPaths(layout.lineSetReadDirs(),
                new File(project, "FLASH/Image Analysis/Line Distance Analysis/Line Sets"),
                new File(project, "FLASH/07 - Line Distance/Line Sets"),
                new File(project, "Data Analysis/Lines"));
        assertPaths(layout.aggregationReadDirs(),
                new File(project, "FLASH/Results Export"),
                new File(project, "FLASH/09 - Result Aggregation"),
                new File(project, "ImageJ Exports"));
        assertPaths(layout.statisticsReadDirs(),
                new File(project, "FLASH/Results Export"),
                new File(project, "FLASH/10 - Statistical Analysis"),
                new File(project, "ImageJ Exports"));
        assertPaths(layout.conditionManifestReadFiles(),
                new File(project, "FLASH/Results Export/Conditions.csv"),
                new File(project, "FLASH/Results Export/Project_Conditions.csv"),
                new File(project, "FLASH/09 - Result Aggregation/Conditions.csv"),
                new File(project, "FLASH/09 - Result Aggregation/Project_Conditions.csv"),
                new File(project, "ImageJ Exports/Conditions.csv"),
                new File(project, "ImageJ Exports/Project_Conditions.csv"));
    }

    @Test
    public void rootReadDirs_includeNewPathThenLegacyFallbacks() throws Exception {
        File project = temp.newFolder("project");
        FlashProjectLayout layout = FlashProjectLayout.forDirectory(project.getAbsolutePath());

        assertPaths(layout.presetsReadDirs(),
                new File(project, "FLASH/.settings/Presets"),
                new File(project, "FLASH/Presets"),
                new File(project, ".bin/Custom Filter Presets"));
        assertPath(new File(project, "FLASH/.settings/Presets/Custom Filter Presets"),
                layout.customFilterPresetWriteDir());
        assertPaths(layout.customFilterPresetReadDirs(),
                new File(project, "FLASH/.settings/Presets/Custom Filter Presets"),
                new File(project, "FLASH/Presets/Custom Filter Presets"),
                new File(project, ".bin/Custom Filter Presets"));
        assertPaths(layout.reportsReadDirs(),
                new File(project, "FLASH/Reports"),
                new File(project, "Quality_Report"));
        assertPaths(layout.qualityReportReadDirs(),
                new File(project, "FLASH/Reports/Quality Report"),
                new File(project, "Quality_Report"));
        assertPaths(layout.tifCacheReadDirs(),
                new File(project, "FLASH/Cache/TIF"),
                new File(project, ".tif_cache"));
        assertPaths(layout.statusReadDirs(),
                new File(project, "FLASH/Status/.settings"),
                new File(project, "FLASH/Status"),
                new File(project, ".flash-status"));
        assertPaths(layout.analysisStatusReadDirs(),
                new File(project, "FLASH/Status/.settings/Analysis"),
                new File(project, "FLASH/Status/Analysis"),
                new File(project, ".flash-status"));
        assertPaths(layout.statusReadFiles(".ihf-no-input-folder"),
                new File(project, "FLASH/Status/.settings/.ihf-no-input-folder"),
                new File(project, "FLASH/Status/.ihf-no-input-folder"),
                new File(project, ".ihf-no-input-folder"));
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
