package flash.pipeline.analyses;

import ij.ImagePlus;
import ij.io.FileSaver;
import ij.process.ShortProcessor;
import flash.pipeline.analyses.wizard.SpatialAnalysisWizard;
import flash.pipeline.io.FlashProjectLayout;
import flash.pipeline.ui.PipelineDialog;
import flash.pipeline.io.CalibrationIO;
import flash.pipeline.io.CsvTableIO;
import flash.pipeline.io.CsvTableIO.ChannelData;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import java.awt.Component;
import java.awt.Container;
import java.io.File;
import java.io.PrintWriter;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;
import java.util.HashSet;
import java.util.Locale;

import static org.junit.Assert.*;

public class SpatialAnalysisTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void execute_appendsCalibratedCentroidsAndUsesAllThreeAxes() throws Exception {
        File root = temp.newFolder("spatial-calibrated");
        File objectsDir = objectsDir(root);
        CalibrationIO.write(objectsDir, 2.0, 5.0, 10.0, "um");

        writeChannel(objectsDir, "A.csv",
                "SCN,XM,YM,ZM",
                "1,1,1,1");
        writeChannel(objectsDir, "B.csv",
                "SCN,XM,YM,ZM",
                "1,2,3,4");

        runSuppressed(root);

        ChannelData a = CsvTableIO.loadChannelCsv(new File(objectsDir, "A.csv"), "A");
        ChannelData b = CsvTableIO.loadChannelCsv(new File(objectsDir, "B.csv"), "B");

        assertNotNull(a);
        assertNotNull(b);
        assertEquals(2.0, a.getDouble(0, "XM_um"), 1e-9);
        assertEquals(5.0, a.getDouble(0, "YM_um"), 1e-9);
        assertEquals(10.0, a.getDouble(0, "ZM_um"), 1e-9);
        assertEquals(4.0, b.getDouble(0, "XM_um"), 1e-9);
        assertEquals(15.0, b.getDouble(0, "YM_um"), 1e-9);
        assertEquals(40.0, b.getDouble(0, "ZM_um"), 1e-9);

        double expected = Math.sqrt((2.0 * 2.0) + (10.0 * 10.0) + (30.0 * 30.0));
        assertEquals(expected, a.getDouble(0, "A_DistToClosest_B"), 1e-6);
        assertEquals(expected, b.getDouble(0, "B_DistToClosest_A"), 1e-6);
    }

    @Test
    public void execute_prefersExistingMicronColumnsWhenPresent() throws Exception {
        File root = temp.newFolder("spatial-existing-um");
        File objectsDir = objectsDir(root);
        CalibrationIO.write(objectsDir, 2.0, 2.0, 2.0, "um");

        writeChannel(objectsDir, "A.csv",
                "SCN,XM,YM,ZM,XM_um,YM_um,ZM_um",
                "1,0,0,1,100,0,10");
        writeChannel(objectsDir, "B.csv",
                "SCN,XM,YM,ZM,XM_um,YM_um,ZM_um",
                "1,1,0,1,160,0,10");

        runSuppressed(root);

        ChannelData a = CsvTableIO.loadChannelCsv(new File(objectsDir, "A.csv"), "A");
        assertNotNull(a);
        assertEquals(100.0, a.getDouble(0, "XM_um"), 1e-9);
        assertEquals(60.0, a.getDouble(0, "A_DistToClosest_B"), 1e-6);
    }

    @Test
    public void execute_rerunDoesNotDuplicateHeaders() throws Exception {
        File root = temp.newFolder("spatial-rerun");
        File objectsDir = objectsDir(root);
        CalibrationIO.write(objectsDir, 1.0, 1.0, 1.0, "um");

        writeChannel(objectsDir, "A.csv",
                "SCN,XM,YM,ZM",
                "1,1,1,1");
        writeChannel(objectsDir, "B.csv",
                "SCN,XM,YM,ZM",
                "1,2,2,2");

        runSuppressed(root);
        runSuppressed(root);

        ChannelData a = CsvTableIO.loadChannelCsv(new File(objectsDir, "A.csv"), "A");
        assertNotNull(a);
        assertEquals(a.header.size(), new HashSet<String>(a.header).size());
        assertEquals(1, countOccurrences(a, "XM_um"));
        assertEquals(1, countOccurrences(a, "A_DistToClosest_B"));
    }

    @Test
    public void execute_withoutCalibrationFallsBackToPixelDistances() throws Exception {
        File root = temp.newFolder("spatial-pixel-fallback");
        File objectsDir = objectsDir(root);

        writeChannel(objectsDir, "A.csv",
                "SCN,XM,YM,ZM",
                "1,1,1,1");
        writeChannel(objectsDir, "B.csv",
                "SCN,XM,YM,ZM",
                "1,4,5,1");

        runSuppressed(root);

        ChannelData a = CsvTableIO.loadChannelCsv(new File(objectsDir, "A.csv"), "A");
        assertNotNull(a);
        assertFalse(a.colIdx.containsKey("XM_um"));
        assertEquals(5.0, a.getDouble(0, "A_DistToClosest_B"), 1e-6);
        assertFalse(new File(root, "FLASH/Results/Tables/Spatial/Spatial_Statistics_A.csv").exists());
    }

    @Test
    public void execute_computesPixelDistancesForSingleSliceZeroZVolumes() throws Exception {
        File root = temp.newFolder("spatial-single-slice-zero-z");
        File objectsDir = objectsDir(root);

        writeChannel(objectsDir, "A.csv",
                "SCN,XM,YM,ZM",
                "1,1,1,0");
        writeChannel(objectsDir, "B.csv",
                "SCN,XM,YM,ZM",
                "1,4,5,0");

        runSuppressed(root);

        ChannelData a = CsvTableIO.loadChannelCsv(new File(objectsDir, "A.csv"), "A");
        ChannelData b = CsvTableIO.loadChannelCsv(new File(objectsDir, "B.csv"), "B");
        assertNotNull(a);
        assertNotNull(b);
        assertEquals(5.0, a.getDouble(0, "A_DistToClosest_B"), 1e-6);
        assertEquals(5.0, b.getDouble(0, "B_DistToClosest_A"), 1e-6);
    }

    @Test
    public void execute_skipsNonFiniteCentroidsDuringSpatialMatching() throws Exception {
        File root = temp.newFolder("spatial-non-finite-centroids");
        File objectsDir = objectsDir(root);

        writeChannel(objectsDir, "A.csv",
                "SCN,XM,YM,ZM",
                "1,NaN,1,0\n" +
                "1,1,1,0");
        writeChannel(objectsDir, "B.csv",
                "SCN,XM,YM,ZM",
                "1,4,5,0");

        runSuppressed(root);

        ChannelData a = CsvTableIO.loadChannelCsv(new File(objectsDir, "A.csv"), "A");
        ChannelData b = CsvTableIO.loadChannelCsv(new File(objectsDir, "B.csv"), "B");
        assertNotNull(a);
        assertNotNull(b);
        assertEquals("Inf", a.get(0, "A_DistToClosest_B"));
        assertEquals(5.0, a.getDouble(1, "A_DistToClosest_B"), 1e-6);
        assertEquals(1.0, b.getDouble(0, "B_ClosestTo_A"), 1e-6);
    }

    @Test
    public void execute_writesDotDecimalDistancesUnderGermanLocale() throws Exception {
        Locale original = Locale.getDefault();
        Locale.setDefault(Locale.GERMANY);
        try {
            File root = temp.newFolder("spatial-german-locale");
            File objectsDir = objectsDir(root);
            CalibrationIO.write(objectsDir, 1.0, 1.0, 1.0, "um");

            writeChannel(objectsDir, "A.csv",
                    "SCN,XM,YM,ZM",
                    "1,0,0,1");
            writeChannel(objectsDir, "B.csv",
                    "SCN,XM,YM,ZM",
                    "1,1,1,1");

            runSuppressed(root);

            File aFile = new File(objectsDir, "A.csv");
            List<String> lines = Files.readAllLines(aFile.toPath(), StandardCharsets.UTF_8);
            assertEquals(2, lines.size());

            String[] header = CsvTableIO.parseCsvLine(lines.get(0));
            String[] row = CsvTableIO.parseCsvLine(lines.get(1));
            assertEquals(header.length, row.length);

            ChannelData a = CsvTableIO.loadChannelCsv(aFile, "A");
            assertNotNull(a);

            String formattedDistance = row[a.colIdx.get("A_DistToClosest_B")];
            assertEquals("1.414214", formattedDistance);
            assertEquals("1.414214", a.get(0, "A_DistToClosest_B"));
            assertEquals(Math.sqrt(2.0), a.getDouble(0, "A_DistToClosest_B"), 1e-6);
        } finally {
            Locale.setDefault(original);
        }
    }

    @Test
    public void execute_writesSpatialStatisticsCsv() throws Exception {
        File root = temp.newFolder("spatial-stats-output");
        File objectsDir = objectsDir(root);
        CalibrationIO.write(objectsDir, 1.0, 1.0, 1.0, "um");

        writeChannel(objectsDir, "A.csv",
                "SCN,Animal Name,Hemisphere,Region,Volume (micron^3),Surface (micron^2),XM,YM,ZM,XM_um,YM_um,ZM_um",
                "1,Mouse1,LH,SCN,10,5,0,0,1,0.10,0.10,1\n" +
                "1,Mouse1,LH,SCN,10,5,0,0,1,0.20,0.10,1\n" +
                "1,Mouse1,LH,SCN,10,5,0,0,1,0.20,0.20,1\n" +
                "1,Mouse1,LH,SCN,10,5,0,0,1,0.10,0.20,1");
        writeChannel(objectsDir, "B.csv",
                "SCN,Animal Name,Hemisphere,Region,Volume (micron^3),Surface (micron^2),XM,YM,ZM,XM_um,YM_um,ZM_um",
                "1,Mouse1,LH,SCN,10,5,0,0,1,0.60,0.60,1\n" +
                "1,Mouse1,LH,SCN,10,5,0,0,1,0.70,0.60,1\n" +
                "1,Mouse1,LH,SCN,10,5,0,0,1,0.70,0.70,1\n" +
                "1,Mouse1,LH,SCN,10,5,0,0,1,0.60,0.70,1");

        SpatialAnalysisWizard.DerivedConfig config = new SpatialAnalysisWizard.DerivedConfig();
        config.doDistances = true;
        config.doSpatialStats = true;
        SpatialAnalysis sa = new SpatialAnalysis();
        sa.setSuppressDialogs(true);
        sa.setWizardConfig(config);
        sa.execute(root.getAbsolutePath());

        File statsFile = new File(root, "FLASH/Results/Tables/Spatial/Spatial_Statistics_A.csv");
        assertTrue(statsFile.exists());

        List<String> lines = Files.readAllLines(statsFile.toPath(), StandardCharsets.UTF_8);
        assertEquals(11, lines.size());
        assertTrue(lines.get(0).contains("WindowSource"));
        assertTrue(lines.get(0).contains("Radius_um"));
        assertTrue(lines.get(1).contains("Mouse1"));
        assertTrue(lines.get(1).contains("derived_from_centroids"));
    }

    @Test
    public void execute_defaultOptionsDoNotWriteSpatialStatisticsCsv() throws Exception {
        File root = temp.newFolder("spatial-stats-default-off");
        File objectsDir = objectsDir(root);
        CalibrationIO.write(objectsDir, 1.0, 1.0, 1.0, "um");

        writeChannel(objectsDir, "A.csv",
                "SCN,Animal Name,Hemisphere,Region,Volume (micron^3),Surface (micron^2),XM,YM,ZM,XM_um,YM_um,ZM_um",
                "1,Mouse1,LH,SCN,10,5,0,0,1,0.10,0.10,1\n" +
                "1,Mouse1,LH,SCN,10,5,0,0,1,0.20,0.10,1\n" +
                "1,Mouse1,LH,SCN,10,5,0,0,1,0.20,0.20,1\n" +
                "1,Mouse1,LH,SCN,10,5,0,0,1,0.10,0.20,1");

        runSuppressed(root);

        assertFalse(new File(root,
                "FLASH/Results/Tables/Spatial/Spatial_Statistics_A.csv").exists());
    }

    @Test
    public void execute_readsAndUpdatesObjectCsvsFromFlashObjectFolder() throws Exception {
        File root = temp.newFolder("spatial-new-objects");
        File objectsDir = flashObjectsDir(root);
        CalibrationIO.write(objectsDir, 1.0, 1.0, 1.0, "um");

        writeChannel(objectsDir, "A.csv",
                "SCN,XM,YM,ZM",
                "1,0,0,1");
        writeChannel(objectsDir, "B.csv",
                "SCN,XM,YM,ZM",
                "1,3,4,1");

        runSuppressed(root);

        ChannelData a = CsvTableIO.loadChannelCsv(new File(objectsDir, "A.csv"), "A");
        assertNotNull(a);
        assertEquals(5.0, a.getDouble(0, "A_DistToClosest_B"), 1e-6);
    }

    @Test
    public void execute_writesHeatmapsUnderSpatialImageOutputs() throws Exception {
        File root = temp.newFolder("spatial-heatmap-output");
        File objectsDir = flashObjectsDir(root);
        CalibrationIO.write(objectsDir, 1.0, 1.0, 1.0, "um");

        File animalDir = new File(FlashProjectLayout.forDirectory(root.getAbsolutePath())
                .analysisImagesObjectsMasksDir(), "Mouse1");
        assertTrue(animalDir.mkdirs());
        writeLabelImage(new File(animalDir, "A_objects_LH_SCN.tif"), 20, 20, 2);
        writeChannel(objectsDir, "A.csv",
                "SCN,Animal Name,Hemisphere,Region,ROI,Volume (micron^3),Surface (micron^2),XM,YM,ZM,XM_um,YM_um,ZM_um",
                "1,Mouse1,LH,SCN,SCN,10,5,0,0,1,2,2,1\n" +
                "1,Mouse1,LH,SCN,SCN,10,5,0,0,1,8,8,1");

        SpatialAnalysisWizard.DerivedConfig config = new SpatialAnalysisWizard.DerivedConfig();
        config.doHeatmaps = true;
        SpatialAnalysis sa = new SpatialAnalysis();
        sa.setSuppressDialogs(true);
        sa.setWizardConfig(config);
        sa.execute(root.getAbsolutePath());

        File heatmapDir = new File(root,
                "FLASH/Results/Analysis Images/Spatial Heatmaps/Mouse1/Heatmaps");
        assertTrue(new File(heatmapDir, "Density_A_LH_SCN.tif").isFile());
        assertTrue(new File(heatmapDir, "Density_A_LH_SCN.png").isFile());
    }

    @Test
    public void lineSetNamesReadsExistingLineRoiZips() throws Exception {
        File linesDir = temp.newFolder("line-sets");
        assertTrue(new File(linesDir, "Ventricle.zip").createNewFile());
        assertTrue(new File(linesDir, "Boundary.zip").createNewFile());
        assertTrue(new File(linesDir, "notes.txt").createNewFile());

        assertEquals(Arrays.asList("Boundary", "Ventricle"),
                SpatialAnalysis.lineSetNames(linesDir));
    }

    @Test
    public void execute_lineDistanceDiscoversLineSetsFromFlashLayout() throws Exception {
        File root = temp.newFolder("spatial-line-distance-new-layout");
        File objectsDir = objectsDir(root);
        writeChannel(objectsDir, "A.csv",
                "Region,XM,YM",
                "SCN1,10,20");

        File lineSets = LineDistanceAnalysis.lineSetWriteDir(root.getAbsolutePath());
        assertTrue(lineSets.mkdirs());
        assertTrue(new File(lineSets, "Boundary.zip").createNewFile());

        SpatialAnalysisWizard.DerivedConfig config = new SpatialAnalysisWizard.DerivedConfig();
        config.doLineDistance = true;
        SpatialAnalysis analysis = new SpatialAnalysis();
        analysis.setSuppressDialogs(true);
        analysis.setWizardConfig(config);
        analysis.execute(root.getAbsolutePath());

        assertTrue(new File(root,
                "FLASH/Results/Tables/Line Distance/A.csv").isFile());
    }

    @Test
    public void spatialPresetSaveButtonIsAvailableAndWired() throws Exception {
        SpatialAnalysis analysis = new SpatialAnalysis();
        PipelineDialog dialog = new PipelineDialog("Spatial Preset Save");
        try {
            Class<?> bindingsClass = Class.forName(
                    "flash.pipeline.analyses.SpatialAnalysis$SpatialDialogBindings");
            Class<?> applierClass = Class.forName(
                    "flash.pipeline.analyses.SpatialAnalysis$SpatialConfigApplier");
            Method method = SpatialAnalysis.class.getDeclaredMethod(
                    "addSpatialSetupControls",
                    PipelineDialog.class,
                    String.class,
                    bindingsClass,
                    applierClass);
            method.setAccessible(true);
            method.invoke(analysis, dialog, temp.getRoot().getAbsolutePath(), null, null);

            JButton saveButton = findButton(contentPanel(dialog), "Save as preset...");
            assertTrue("Spatial Save as preset button should be present", saveButton != null);
            assertTrue("Spatial Save as preset button should be enabled", saveButton.isEnabled());
            assertTrue("Spatial Save as preset button should save current options",
                    saveButton.getActionListeners().length > 0);
        } finally {
            backingDialog(dialog).dispose();
        }
    }

    @Test
    public void morphometricShapeControlsAreOutsideAdvancedSection() throws Exception {
        boolean advancedGlobal = ij.Prefs.get("flash.advanced.global", false);
        boolean advancedSpatial = ij.Prefs.get("flash.advanced.spatial", false);
        ij.Prefs.set("flash.advanced.global", false);
        ij.Prefs.set("flash.advanced.spatial", false);

        SpatialAnalysis analysis = new SpatialAnalysis();
        PipelineDialog dialog = new PipelineDialog("Spatial Morph Controls");
        try {
            Class<?> bindingsClass = Class.forName(
                    "flash.pipeline.analyses.SpatialAnalysis$SpatialDialogBindings");
            Constructor<?> bindingsConstructor = bindingsClass.getDeclaredConstructor();
            bindingsConstructor.setAccessible(true);
            Object bindings = bindingsConstructor.newInstance();
            Method method = SpatialAnalysis.class.getDeclaredMethod(
                    "addMorphometricControls",
                    PipelineDialog.class,
                    bindingsClass,
                    boolean.class,
                    boolean.class,
                    boolean.class,
                    boolean.class,
                    boolean.class);
            method.setAccessible(true);
            method.invoke(analysis, dialog, bindings, false, false, false, false, false);

            JPanel content = contentPanel(dialog);
            assertFalse("3D shape features should be visible without opening advanced options",
                    hasHiddenAncestor(findLabel(content, "3D shape features"), content));
            assertFalse("Complex shape analysis should be visible without opening advanced options",
                    hasHiddenAncestor(findLabel(content, "Complex shape analysis"), content));
            assertTrue("Population morphometric scoring should remain advanced",
                    hasHiddenAncestor(findLabel(content, "Population morphometric scoring"), content));
            assertTrue("Spatial-morphometric analysis should remain advanced",
                    hasHiddenAncestor(findLabel(content, "Spatial-morphometric analysis"), content));
        } finally {
            backingDialog(dialog).dispose();
            ij.Prefs.set("flash.advanced.global", advancedGlobal);
            ij.Prefs.set("flash.advanced.spatial", advancedSpatial);
        }
    }

    @Test
    public void phenotypingAndHeatmapTitlesStayVisibleWithCollapsedControls() throws Exception {
        boolean advancedGlobal = ij.Prefs.get("flash.advanced.global", false);
        boolean advancedSpatial = ij.Prefs.get("flash.advanced.spatial", false);
        ij.Prefs.set("flash.advanced.global", false);
        ij.Prefs.set("flash.advanced.spatial", false);

        SpatialAnalysis analysis = new SpatialAnalysis();
        PipelineDialog dialog = new PipelineDialog("Spatial Advanced Controls");
        try {
            Class<?> bindingsClass = Class.forName(
                    "flash.pipeline.analyses.SpatialAnalysis$SpatialDialogBindings");
            Constructor<?> bindingsConstructor = bindingsClass.getDeclaredConstructor();
            bindingsConstructor.setAccessible(true);
            Object bindings = bindingsConstructor.newInstance();
            Method method = SpatialAnalysis.class.getDeclaredMethod(
                    "addAdvancedPhenotypingAndHeatmapControls",
                    PipelineDialog.class,
                    bindingsClass,
                    boolean.class,
                    int.class,
                    boolean.class,
                    double.class,
                    String.class);
            method.setAccessible(true);
            method.invoke(analysis, dialog, bindings, false, 0, false, 0.0, "Fire");

            JPanel content = contentPanel(dialog);
            JLabel phenotypingHeader = findLabel(content, "Cell Phenotyping");
            JLabel heatmapHeader = findLabel(content, "Density Heatmaps");
            assertFalse("Cell Phenotyping title should be visible before expansion",
                    hasHiddenAncestor(phenotypingHeader, content));
            assertFalse("Density Heatmaps title should be visible before expansion",
                    hasHiddenAncestor(heatmapHeader, content));
            assertTrue("K-means clustering should be hidden until Cell Phenotyping is expanded",
                    hasHiddenAncestor(findLabel(content, "K-means clustering"), content));
            assertTrue("Clusters input should be hidden until Cell Phenotyping is expanded",
                    hasHiddenAncestor(findLabel(content, "Clusters (k, 0=auto)"), content));
            assertTrue("Density heatmap toggle should be hidden until Density Heatmaps is expanded",
                    hasHiddenAncestor(findLabel(content, "Generate density heatmaps"), content));
            assertTrue("KDE bandwidth input should be hidden until Density Heatmaps is expanded",
                    hasHiddenAncestor(findLabel(content, "KDE bandwidth (um, 0=auto)"), content));
            assertTrue("Heatmap LUT choice should be hidden until Density Heatmaps is expanded",
                    hasHiddenAncestor(findLabel(content, "Heatmap LUT"), content));

            click(phenotypingHeader);
            assertFalse("K-means clustering should be visible after expanding Cell Phenotyping",
                    hasHiddenAncestor(findLabel(content, "K-means clustering"), content));
            assertFalse("Clusters input should be visible after expanding Cell Phenotyping",
                    hasHiddenAncestor(findLabel(content, "Clusters (k, 0=auto)"), content));

            click(heatmapHeader);
            assertFalse("Density heatmap toggle should be visible after expanding Density Heatmaps",
                    hasHiddenAncestor(findLabel(content, "Generate density heatmaps"), content));
            assertFalse("KDE bandwidth input should be visible after expanding Density Heatmaps",
                    hasHiddenAncestor(findLabel(content, "KDE bandwidth (um, 0=auto)"), content));
            assertFalse("Heatmap LUT choice should be visible after expanding Density Heatmaps",
                    hasHiddenAncestor(findLabel(content, "Heatmap LUT"), content));
        } finally {
            backingDialog(dialog).dispose();
            ij.Prefs.set("flash.advanced.global", advancedGlobal);
            ij.Prefs.set("flash.advanced.spatial", advancedSpatial);
        }
    }

    @Test
    public void execute_excludesZeroVolumePlaceholderRowsFromSpatialMatching() throws Exception {
        File root = temp.newFolder("spatial-placeholder-rows");
        File objectsDir = objectsDir(root);
        CalibrationIO.write(objectsDir, 1.0, 1.0, 1.0, "um");

        writeChannel(objectsDir, "A.csv",
                "SCN,Animal Name,Hemisphere,Region,Volume (micron^3),Surface (micron^2),XM,YM,ZM",
                "1,Mouse1,LH,SCN,0,0,0,0,0");
        writeChannel(objectsDir, "B.csv",
                "SCN,Animal Name,Hemisphere,Region,Volume (micron^3),Surface (micron^2),XM,YM,ZM",
                "1,Mouse1,LH,SCN,12,6,5,0,1");

        runSuppressed(root);

        ChannelData a = CsvTableIO.loadChannelCsv(new File(objectsDir, "A.csv"), "A");
        ChannelData b = CsvTableIO.loadChannelCsv(new File(objectsDir, "B.csv"), "B");

        assertNotNull(a);
        assertNotNull(b);
        assertEquals("Inf", a.get(0, "A_DistToClosest_B"));
        assertEquals("Inf", b.get(0, "B_DistToClosest_A"));
        assertEquals(0.0, a.getDouble(0, "A_ClosestTo_B"), 0.0);
        assertEquals(0.0, a.getDouble(0, "A_VolContains30_B"), 0.0);
        assertEquals(0.0, b.getDouble(0, "B_ClosestTo_A"), 0.0);
        assertEquals(0.0, b.getDouble(0, "B_VolContains30_A"), 0.0);
    }

    @Test
    public void chooseCpcThreads_clampsToAvailableSectionsWhenUnestimated() {
        assertEquals(3, SpatialAnalysis.chooseCpcThreads(0L, 8, 3));
    }

    @Test
    public void chooseCpcThreads_reducesToSingleWorkerWhenMemoryEstimateIsHuge() {
        assertEquals(1, SpatialAnalysis.chooseCpcThreads(Long.MAX_VALUE / 4L, 8, 8));
    }

    @Test
    public void resolveImageAnalysisAnimalDir_matchesWeekWordVariant() throws Exception {
        File imageAnalysisRoot = temp.newFolder("image-analysis-root");
        File digitNamed = new File(imageAnalysisRoot, "hAPP1Week2");
        assertTrue(digitNamed.mkdir());

        File resolved = SpatialAnalysis.resolveImageAnalysisAnimalDir(imageAnalysisRoot, "hAPP1WeekTwo");

        assertEquals(digitNamed.getCanonicalPath(), resolved.getCanonicalPath());
        assertEquals("happ1week2", SpatialAnalysis.normalizeAnimalDirectoryKey("hAPP1WeekTwo"));
    }

    @Test
    public void resolveCpcGroupSuffix_fallsBackToHemiFromScnWhenMetadataMissing() {
        assertEquals("LH_SCN", SpatialAnalysis.resolveCpcGroupSuffix("", "", "", "1"));
        assertEquals("RH_SCN", SpatialAnalysis.resolveCpcGroupSuffix("", "", "", "2"));
    }

    @Test
    public void cpcLabelSuffixCandidates_includeScnSpecificAndBaseFallbacks() {
        assertEquals(Arrays.asList("LH_SCN5", "LH_SCN", "SCN5", "SCN"),
                SpatialAnalysis.cpcLabelSuffixCandidates("", "", "", "5").subList(0, 4));
    }

    @Test
    public void candidateNeedsScnParityVerification_identifiesParityBasedSuffixes() {
        assertTrue(SpatialAnalysis.candidateNeedsScnParityVerification("LH_SCN", "5"));
        assertTrue(SpatialAnalysis.candidateNeedsScnParityVerification("LH_SCN5", "5"));
        assertFalse(SpatialAnalysis.candidateNeedsScnParityVerification("SCN5", "5"));
        assertFalse(SpatialAnalysis.candidateNeedsScnParityVerification("", "5"));
    }

    @Test
    public void resolveCpcLabelFile_verifiesBaseHemispheresFromObjectCounts() throws Exception {
        File root = temp.newFolder("spatial-scn-base-verified");
        File objectsDir = objectsDir(root);
        File animalDir = new File(FlashProjectLayout.forDirectory(root.getAbsolutePath()).analysisImagesObjectsMasksDir(), "Mouse1Week2");
        assertTrue(animalDir.mkdirs());

        writeLabelImage(new File(animalDir, "A_objects_LH_SCN.tif"), 2);
        writeLabelImage(new File(animalDir, "A_objects_RH_SCN.tif"), 3);
        writeChannel(objectsDir, "A.csv",
                "SCN,Animal Name",
                "1,Mouse1WeekTwo\n" +
                "1,Mouse1WeekTwo\n" +
                "2,Mouse1WeekTwo\n" +
                "2,Mouse1WeekTwo\n" +
                "2,Mouse1WeekTwo");

        ChannelData a = CsvTableIO.loadChannelCsv(new File(objectsDir, "A.csv"), "A");
        assertNotNull(a);

        SpatialAnalysis sa = new SpatialAnalysis();
        File oddFile = sa.resolveCpcLabelFile(root.getAbsolutePath(), "A", a, 0);
        File evenFile = sa.resolveCpcLabelFile(root.getAbsolutePath(), "A", a, 2);

        assertNotNull(oddFile);
        assertNotNull(evenFile);
        assertEquals("A_objects_LH_SCN.tif", oddFile.getName());
        assertEquals("A_objects_RH_SCN.tif", evenFile.getName());
    }

    @Test
    public void resolveCpcLabelFile_rejectsAmbiguousBaseHemisphereFallback() throws Exception {
        File root = temp.newFolder("spatial-scn-base-ambiguous");
        File objectsDir = objectsDir(root);
        File animalDir = new File(FlashProjectLayout.forDirectory(root.getAbsolutePath()).analysisImagesObjectsMasksDir(), "Mouse1Week2");
        assertTrue(animalDir.mkdirs());

        writeLabelImage(new File(animalDir, "A_objects_LH_SCN.tif"), 2);
        writeLabelImage(new File(animalDir, "A_objects_RH_SCN.tif"), 2);
        writeChannel(objectsDir, "A.csv",
                "SCN,Animal Name",
                "1,Mouse1WeekTwo\n" +
                "1,Mouse1WeekTwo\n" +
                "2,Mouse1WeekTwo\n" +
                "2,Mouse1WeekTwo");

        ChannelData a = CsvTableIO.loadChannelCsv(new File(objectsDir, "A.csv"), "A");
        assertNotNull(a);

        SpatialAnalysis sa = new SpatialAnalysis();
        assertNull(sa.resolveCpcLabelFile(root.getAbsolutePath(), "A", a, 0));
        assertNull(sa.resolveCpcLabelFile(root.getAbsolutePath(), "A", a, 2));
    }

    @Test
    public void resolveCpcLabelFile_prefersScnSpecificNumberedSuffixes() throws Exception {
        File root = temp.newFolder("spatial-scn-numbered");
        File objectsDir = objectsDir(root);
        File animalDir = new File(FlashProjectLayout.forDirectory(root.getAbsolutePath()).analysisImagesObjectsMasksDir(), "Mouse1Week2");
        assertTrue(animalDir.mkdirs());

        writeLabelImage(new File(animalDir, "A_objects_LH_SCN1.tif"), 1);
        writeLabelImage(new File(animalDir, "A_objects_LH_SCN3.tif"), 1);
        writeChannel(objectsDir, "A.csv",
                "SCN,Animal Name",
                "1,Mouse1WeekTwo\n" +
                "3,Mouse1WeekTwo");

        ChannelData a = CsvTableIO.loadChannelCsv(new File(objectsDir, "A.csv"), "A");
        assertNotNull(a);

        SpatialAnalysis sa = new SpatialAnalysis();
        File scn1 = sa.resolveCpcLabelFile(root.getAbsolutePath(), "A", a, 0);
        File scn3 = sa.resolveCpcLabelFile(root.getAbsolutePath(), "A", a, 1);

        assertNotNull(scn1);
        assertNotNull(scn3);
        assertEquals("A_objects_LH_SCN1.tif", scn1.getName());
        assertEquals("A_objects_LH_SCN3.tif", scn3.getName());
    }

    @Test
    public void resolveCpcLabelFile_acceptsSingleLhBaseFileWhenOnlyOddRowsExist() throws Exception {
        File root = temp.newFolder("spatial-scn-single-lh");
        File objectsDir = objectsDir(root);
        File animalDir = new File(FlashProjectLayout.forDirectory(root.getAbsolutePath()).analysisImagesObjectsMasksDir(), "Mouse1Week2");
        assertTrue(animalDir.mkdirs());

        writeLabelImage(new File(animalDir, "A_objects_LH_SCN.tif"), 2);
        writeChannel(objectsDir, "A.csv",
                "SCN,Animal Name,Volume (micron^3),Surface (micron^2),XM,YM,ZM",
                "13,Mouse1WeekTwo,10,5,1,1,1\n" +
                "13,Mouse1WeekTwo,12,5,2,2,1\n" +
                "14,Mouse1WeekTwo,0,0,0,0,0");

        ChannelData a = CsvTableIO.loadChannelCsv(new File(objectsDir, "A.csv"), "A");
        assertNotNull(a);

        SpatialAnalysis sa = new SpatialAnalysis();
        File oddFile = sa.resolveCpcLabelFile(root.getAbsolutePath(), "A", a, 0);

        assertNotNull(oddFile);
        assertEquals("A_objects_LH_SCN.tif", oddFile.getName());
    }

    @Test
    public void resolveCpcLabelFile_acceptsSingleRhBaseFileWhenOnlyEvenRowsExist() throws Exception {
        File root = temp.newFolder("spatial-scn-single-rh");
        File objectsDir = objectsDir(root);
        File animalDir = new File(FlashProjectLayout.forDirectory(root.getAbsolutePath()).analysisImagesObjectsMasksDir(), "Mouse1Week2");
        assertTrue(animalDir.mkdirs());

        writeLabelImage(new File(animalDir, "A_objects_RH_SCN.tif"), 2);
        writeChannel(objectsDir, "A.csv",
                "SCN,Animal Name,Volume (micron^3),Surface (micron^2),XM,YM,ZM",
                "14,Mouse1WeekTwo,10,5,1,1,1\n" +
                "14,Mouse1WeekTwo,12,5,2,2,1\n" +
                "13,Mouse1WeekTwo,0,0,0,0,0");

        ChannelData a = CsvTableIO.loadChannelCsv(new File(objectsDir, "A.csv"), "A");
        assertNotNull(a);

        SpatialAnalysis sa = new SpatialAnalysis();
        File evenFile = sa.resolveCpcLabelFile(root.getAbsolutePath(), "A", a, 0);

        assertNotNull(evenFile);
        assertEquals("A_objects_RH_SCN.tif", evenFile.getName());
    }

    @Test
    public void execute_reusesCpcSectionGroupingWhenCpcColumnsAlreadyExist() throws Exception {
        File root = temp.newFolder("spatial-cpc-group-cache");
        File objectsDir = objectsDir(root);
        File animalDir = new File(FlashProjectLayout.forDirectory(root.getAbsolutePath()).analysisImagesObjectsMasksDir(), "Mouse1");
        assertTrue(animalDir.mkdirs());

        writeLabelImage(new File(animalDir, "A_objects_LH_SCN.tif"), 2);
        writeLabelImage(new File(animalDir, "A_objects_RH_SCN.tif"), 1);
        writeLabelImage(new File(animalDir, "B_objects_LH_SCN.tif"), 2);
        writeLabelImage(new File(animalDir, "B_objects_RH_SCN.tif"), 1);

        writeChannel(objectsDir, "A.csv",
                "Animal Name,Hemisphere,ROI,Volume (micron^3),XM,YM,ZM,A_CPCColoc_B,A_CPCContains_B,A_CPCTargetsHit,A_CPCPattern",
                "Mouse1,LH,SCN,10,1,1,1,1,1,1,B\n" +
                "Mouse1,LH,SCN,12,2,1,1,0,0,0,None\n" +
                "Mouse1,RH,SCN,14,1,1,1,1,1,1,B");
        writeChannel(objectsDir, "B.csv",
                "Animal Name,Hemisphere,ROI,Volume (micron^3),XM,YM,ZM,B_CPCColoc_A,B_CPCContains_A,B_CPCTargetsHit,B_CPCPattern",
                "Mouse1,LH,SCN,11,1,1,1,1,1,1,A\n" +
                "Mouse1,LH,SCN,13,2,1,1,0,0,0,None\n" +
                "Mouse1,RH,SCN,15,1,1,1,1,1,1,A");

        CountingSpatialAnalysis sa = new CountingSpatialAnalysis();
        sa.setSuppressDialogs(true);
        sa.execute(root.getAbsolutePath());

        assertEquals(4, sa.resolveCalls);
        assertTrue(new File(root, "FLASH/Results/Tables/Spatial/CPC_Spatial_Summary.csv").isFile());
        assertTrue(new File(root, "FLASH/Results/Tables/Spatial/CPC_Multi_Target_Summary.csv").isFile());
    }

    @Test
    public void execute_reusesExisting3DObjectOutputsWhenSelected() throws Exception {
        File root = temp.newFolder("spatial-reuse-existing-object-data");
        File objectsDir = objectsDir(root);

        String morph2D = "Morph_Area_um2,Morph_Perimeter_um,Morph_Circularity,Morph_Solidity,"
                + "Morph_AspectRatio,Morph_Feret_um,Morph_Extent,Morph_ConvexHullArea_um2";
        String morph3D = "Morph_Sphericity,Morph_Compactness,Morph_Elongation,Morph_Flatness,"
                + "Morph_Spareness,Morph_MajorRadius_um,Morph_Feret3D_um,"
                + "Morph_Moment1,Morph_Moment2,Morph_Moment3,Morph_Moment4,Morph_Moment5,"
                + "Morph_DistCenter_Min_um,Morph_DistCenter_Max_um,"
                + "Morph_DistCenter_Mean_um,Morph_DistCenter_SD_um";
        String composites = "Morph_RI,Morph_SRI,Morph_PB,Morph_MP,Morph_VSD";
        String population = "Morph_CMS,Morph_SMSD,Morph_IMDI";
        String spatialMorph = "Morph_TDR,Morph_FEV_Mag";
        String commonMeasurements = "SCN,Animal Name,Hemisphere,ROI,Label,Volume (micron^3),"
                + "Surface (micron^2),IntDen,Mean,XM,YM,ZM,Length";

        writeChannel(objectsDir, "A.csv",
                commonMeasurements
                        + ",Colocalisation with B,A_VolColoc30_B,A_VolContains30_B,"
                        + "A_DistToClosest_B,A_ClosestTo_B,A_CPCColoc_B,A_CPCContains_B,"
                        + "A_CPCTargetsHit,A_CPCPattern," + morph2D + "," + morph3D + ","
                        + composites + "," + population + "," + spatialMorph,
                "1,Mouse1,LH,SCN,1,10,5,100,20,0,0,0,123,55,1,7,99,4,1,1,1,B,"
                        + "11,12,0.9,0.8,1.2,6,0.7,13,"
                        + "0.42,0.2,1.3,1.1,0.8,4,9,0.1,0.2,0.3,0.4,0.5,1,2,1.5,0.2,"
                        + "2.3,0.4,0.5,0.6,0.7,0.8,1.1,0.2,1.3,1.4");
        writeChannel(objectsDir, "B.csv",
                commonMeasurements
                        + ",Colocalisation with A,B_VolColoc30_A,B_VolContains30_A,"
                        + "B_DistToClosest_A,B_ClosestTo_A,B_CPCColoc_A,B_CPCContains_A,"
                        + "B_CPCTargetsHit,B_CPCPattern," + morph2D + "," + morph3D + ","
                        + composites + "," + population + "," + spatialMorph,
                "1,Mouse1,LH,SCN,1,12,6,120,22,3,4,0,456,50,1,8,88,5,1,1,1,A,"
                        + "21,22,0.8,0.7,1.1,5,0.6,23,"
                        + "0.52,0.3,1.4,1.2,0.7,5,10,0.2,0.3,0.4,0.5,0.6,1,2,1.5,0.2,"
                        + "2.4,0.5,0.6,0.7,0.8,0.9,1.2,0.3,1.4,1.5");

        SpatialAnalysisWizard.DerivedConfig config = new SpatialAnalysisWizard.DerivedConfig();
        config.doDistances = true;
        config.doVolColoc = true;
        config.doCpc = true;
        config.do2DMorphology = true;
        config.do3DMorphology = true;
        config.doCompositeIndices = true;
        config.doPopMorphometrics = true;
        config.doSpatialMorphometrics = true;

        SpatialAnalysis sa = new SpatialAnalysis();
        sa.setSuppressDialogs(true);
        sa.setWizardConfig(config);
        sa.execute(root.getAbsolutePath());

        ChannelData a = CsvTableIO.loadChannelCsv(new File(objectsDir, "A.csv"), "A");
        ChannelData b = CsvTableIO.loadChannelCsv(new File(objectsDir, "B.csv"), "B");
        assertNotNull(a);
        assertNotNull(b);
        assertEquals("99", a.get(0, "A_DistToClosest_B"));
        assertEquals("7", a.get(0, "A_VolContains30_B"));
        assertEquals("1", a.get(0, "A_CPCColoc_B"));
        assertEquals("123", a.get(0, "Length"));
        assertEquals("0.42", a.get(0, "Morph_Sphericity"));
        assertEquals("88", b.get(0, "B_DistToClosest_A"));
        assertFalse(new File(root, "FLASH/Results/Tables/Morphometry").exists());
    }

    @Test
    public void reorderManagedSpatialColumns_groupsByAnalysisPhaseAndPartner() throws Exception {
        File root = temp.newFolder("spatial-column-order");
        File objectsDir = objectsDir(root);

        writeChannel(objectsDir, "A.csv",
                "Region,Hemisphere,ROI,Animal Name,Volume (micron^3),Surface (micron^2),XM,YM,ZM,Colocalisation with B,A_DistToClosest_C,A_ClosestTo_C,A_CPCColoc_B,A_CPCContains_B,Label,A_DistTo_Line1,IntDen,Mean,Colocalisation with C,A_VolColoc30_B,A_VolContains30_B,A_DistToClosest_B,A_ClosestTo_B,A_VolColoc30_C,A_VolContains30_C,A_CPCColoc_C,A_CPCContains_C,A_CPCTargetsHit,A_CPCPattern,XM_um,YM_um,ZM_um,Morph_Area_um2",
                "SCN,RH,SCN1,Mouse1,101,55,1,2,3,12,5.0,6,1,2,7,15.5,333,4,34,1,2,3.0,4,7,8,3,4,2,B + C,10,20,30,99");

        ChannelData a = CsvTableIO.loadChannelCsv(new File(objectsDir, "A.csv"), "A");
        assertNotNull(a);

        SpatialAnalysis.reorderManagedSpatialColumns(a, Arrays.asList("A", "B", "C"));

        assertEquals(Arrays.asList(
                "Region",
                "Hemisphere",
                "ROI",
                "Animal Name",
                "Label",
                "Volume (micron^3)",
                "Surface (micron^2)",
                "IntDen",
                "Mean",
                "XM",
                "YM",
                "ZM",
                "XM_um",
                "YM_um",
                "ZM_um",
                "A_DistTo_Line1",
                "Colocalisation with B",
                "A_VolColoc30_B",
                "Colocalisation with C",
                "A_VolColoc30_C",
                "A_DistToClosest_B",
                "A_ClosestTo_B",
                "A_VolContains30_B",
                "A_DistToClosest_C",
                "A_ClosestTo_C",
                "A_VolContains30_C",
                "A_CPCColoc_B",
                "A_CPCContains_B",
                "A_CPCColoc_C",
                "A_CPCContains_C",
                "A_CPCTargetsHit",
                "A_CPCPattern",
                "Morph_Area_um2"
        ), a.header);

        assertEquals("7", a.get(0, "Label"));
        assertEquals("10", a.get(0, "XM_um"));
        assertEquals("3.0", a.get(0, "A_DistToClosest_B"));
        assertEquals("4", a.get(0, "A_ClosestTo_B"));
        assertEquals("7", a.get(0, "A_VolColoc30_C"));
        assertEquals("B + C", a.get(0, "A_CPCPattern"));
    }

    private void runSuppressed(File root) {
        SpatialAnalysis sa = new SpatialAnalysis();
        sa.setSuppressDialogs(true);
        sa.execute(root.getAbsolutePath());
    }

    private static class CountingSpatialAnalysis extends SpatialAnalysis {
        int resolveCalls;

        @Override
        File resolveCpcLabelFile(String directory, String channelName, ChannelData cd, int row) {
            resolveCalls++;
            return super.resolveCpcLabelFile(directory, channelName, cd, row);
        }
    }

    private File objectsDir(File root) {
        return flashObjectsDir(root);
    }

    private File flashObjectsDir(File root) {
        File objects = FlashProjectLayout.forDirectory(root.getAbsolutePath()).tablesObjectsWriteDir();
        assertTrue(objects.mkdirs());
        return objects;
    }

    private void writeChannel(File objectsDir, String filename, String header, String row) throws Exception {
        File csv = new File(objectsDir, filename);
        PrintWriter pw = new PrintWriter(csv, "UTF-8");
        try {
            pw.println(header);
            pw.print(row);
            if (!row.endsWith("\n")) {
                pw.println();
            }
        } finally {
            pw.close();
        }
    }

    private void writeLabelImage(File file, int objectCount) {
        writeLabelImage(file, Math.max(1, objectCount), 1, objectCount);
    }

    private void writeLabelImage(File file, int width, int height, int objectCount) {
        ShortProcessor sp = new ShortProcessor(Math.max(1, width), Math.max(1, height));
        for (int x = 0; x < objectCount; x++) {
            sp.set(x, 0, x + 1);
        }
        ImagePlus imp = new ImagePlus(file.getName(), sp);
        try {
            assertTrue(new FileSaver(imp).saveAsTiff(file.getAbsolutePath()));
        } finally {
            imp.close();
            imp.flush();
        }
    }

    private int countOccurrences(ChannelData data, String column) {
        int count = 0;
        for (String header : data.header) {
            if (column.equals(header)) count++;
        }
        return count;
    }

    private static JPanel contentPanel(PipelineDialog dialog) throws Exception {
        Field field = PipelineDialog.class.getDeclaredField("contentPanel");
        field.setAccessible(true);
        return (JPanel) field.get(dialog);
    }

    private static JDialog backingDialog(PipelineDialog dialog) throws Exception {
        Field field = PipelineDialog.class.getDeclaredField("dialog");
        field.setAccessible(true);
        return (JDialog) field.get(dialog);
    }

    private static JButton findButton(Container container, String text) {
        for (Component component : container.getComponents()) {
            if (component instanceof JButton && text.equals(((JButton) component).getText())) {
                return (JButton) component;
            }
            if (component instanceof Container) {
                JButton nested = findButton((Container) component, text);
                if (nested != null) {
                    return nested;
                }
            }
        }
        return null;
    }

    private static JLabel findLabel(Container container, String text) {
        for (Component component : container.getComponents()) {
            if (component instanceof JLabel && text.equals(((JLabel) component).getText())) {
                return (JLabel) component;
            }
            if (component instanceof Container) {
                JLabel nested = findLabel((Container) component, text);
                if (nested != null) {
                    return nested;
                }
            }
        }
        return null;
    }

    private static void click(Component component) {
        assertNotNull(component);
        java.awt.event.MouseEvent event = new java.awt.event.MouseEvent(
                component,
                java.awt.event.MouseEvent.MOUSE_CLICKED,
                System.currentTimeMillis(),
                0,
                1,
                1,
                1,
                false);
        java.awt.event.MouseListener[] listeners = component.getMouseListeners();
        for (int i = 0; i < listeners.length; i++) {
            listeners[i].mouseClicked(event);
        }
    }

    private static boolean hasHiddenAncestor(Component component, Container stopAt) {
        assertNotNull(component);
        Component current = component;
        while (current != null && current != stopAt) {
            if (!current.isVisible()) {
                return true;
            }
            current = current.getParent();
        }
        return false;
    }
}
