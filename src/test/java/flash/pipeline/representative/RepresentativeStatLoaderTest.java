package flash.pipeline.representative;

import flash.pipeline.io.ConditionManifestIO;
import flash.pipeline.io.CsvSupport;
import flash.pipeline.io.FlashProjectLayout;
import flash.pipeline.io.ImageSourceDispatcher;
import flash.pipeline.io.SeriesMeta;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.PrintWriter;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class RepresentativeStatLoaderTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test(expected = java.io.IOException.class)
    public void loadExistingResult_rejectsConditionAxisColumn() throws Exception {
        // A per-axis condition column must never be loadable as the representative
        // statistic, even from a stale/hand-edited saved config.
        File csv = temp.newFile("Master_Image Objects.csv");
        PrintWriter pw = new PrintWriter(csv, "UTF-8");
        try {
            pw.println("AnimalName,Condition,Condition_Timepoint,count");
            pw.println("M1,hAPP_WeekFour,WeekFour,5");
        } finally {
            pw.close();
        }
        RepresentativeStatLoader.ExistingResultOption option =
                RepresentativeStatLoader.ExistingResultOption.externalImport(
                        csv, "Condition_Timepoint");
        RepresentativeStatLoader.loadExistingResult(
                temp.getRoot().getAbsolutePath(), option, new java.util.ArrayList<SeriesMeta>());
    }

    @Test
    public void loadQuickScoresCsv_populatesPerSeriesChannelValuesAndMetadata() throws Exception {
        File scores = temp.newFile("QC_MinMaxPerCondition_Selection.csv");
        writeCsv(scores,
                Arrays.asList("Condition", "SeriesIndex", "SeriesNumber", "SeriesName",
                        "AnimalName", "CompositeRank", "SelectedRole",
                        "Channel1Score", "Channel2Score"),
                Arrays.asList(
                        Arrays.asList("Ctrl", "0", "1", "Exp-Mouse1_LH_SCN",
                                "Mouse1", "1.0", "min", "12.5", "4.0"),
                        Arrays.asList("Treat", "1", "2", "Exp-Mouse2_RH_SCN",
                                "Mouse2", "2.0", "max", "20.0", "")));

        Map<Integer, String> channelNames = new LinkedHashMap<Integer, String>();
        channelNames.put(Integer.valueOf(1), "DAPI");
        channelNames.put(Integer.valueOf(2), "GFAP");

        RepresentativeStatTable table =
                RepresentativeStatLoader.loadQuickScoresCsv(scores, channelNames);

        assertFalse(table.isEmpty());
        assertEquals(2, table.rowCount());
        assertEquals(12.5, table.value("0", "DAPI").doubleValue(), 0.0001);
        assertEquals(4.0, table.value("0", "GFAP").doubleValue(), 0.0001);
        assertEquals(20.0, table.value("1", "DAPI").doubleValue(), 0.0001);
        assertNull(table.value("1", "GFAP"));
        assertEquals("Ctrl", table.row("0").conditionName);
        assertEquals("Mouse1", table.row("0").animalName);
        assertEquals("SCN", table.row("0").region);
    }

    @Test
    public void loadExistingResult_mapsRowsToSeriesAndAveragesRepeatedRows() throws Exception {
        File project = temp.newFolder("project");
        Map<String, String> assignments = new LinkedHashMap<String, String>();
        assignments.put("Mouse1", "Ctrl");
        assignments.put("Mouse2", "Treat");
        ConditionManifestIO.saveAssignments(project.getAbsolutePath(), assignments);

        File intensityDir = FlashProjectLayout.forDirectory(project.getAbsolutePath())
                .tablesIntensityWriteDir();
        File resultCsv = new File(intensityDir, "GFAP.csv");
        writeCsv(resultCsv,
                Arrays.asList("Animal Name", "Region", "Hemisphere", "MeanIntDen"),
                Arrays.asList(
                        Arrays.asList("Mouse1", "SCN", "LH", "10"),
                        Arrays.asList("Mouse1", "SCN", "LH", "20"),
                        Arrays.asList("Mouse2", "SCN", "RH", "5")));

        List<SeriesMeta> metas = Arrays.asList(
                new SeriesMeta(0, "Exp-Mouse1_LH_SCN", 10, 10, 1, 2,
                        1.0, 1.0, 1.0, "micron"),
                new SeriesMeta(1, "Exp-Mouse2_RH_SCN", 10, 10, 1, 2,
                        1.0, 1.0, 1.0, "micron"));
        RepresentativeStatLoader.ExistingResultOption option =
                new RepresentativeStatLoader.ExistingResultOption(resultCsv, "MeanIntDen");

        RepresentativeStatTable table = RepresentativeStatLoader.loadExistingResult(
                project.getAbsolutePath(), option, metas);

        assertEquals(2, table.rowCount());
        assertEquals(15.0, table.value("0", "GFAP").doubleValue(), 0.0001);
        assertEquals(5.0, table.value("1", "GFAP").doubleValue(), 0.0001);
        assertEquals("Ctrl", table.row("0").conditionName);
        assertEquals("Treat", table.row("1").conditionName);
    }

    @Test
    public void discoverExistingResultOptions_listsNumericResultColumnsOnly() throws Exception {
        File project = temp.newFolder("project");
        FlashProjectLayout layout = FlashProjectLayout.forDirectory(project.getAbsolutePath());

        writeCsv(layout.projectSummaryWriteFile(FlashProjectLayout.CONDITIONS_FILENAME),
                Arrays.asList("AnimalName", "Condition"),
                Arrays.asList(Arrays.asList("Mouse1", "Ctrl")));
        writeCsv(layout.projectSummaryWriteFile(FlashProjectLayout.MASTER_INTENSITIES_FILENAME),
                Arrays.asList("AnimalName", "numSections", "DAPI_ROI_IntDenMean", "run_id"),
                Arrays.asList(Arrays.asList("Mouse1", "3", "42.5", "R1")));

        List<RepresentativeStatLoader.ExistingResultOption> options =
                RepresentativeStatLoader.discoverExistingResultOptions(project.getAbsolutePath());

        boolean foundIntensity = false;
        boolean foundNumSections = false;
        boolean foundConditions = false;
        for (RepresentativeStatLoader.ExistingResultOption option : options) {
            if ("DAPI_ROI_IntDenMean".equals(option.columnName)) foundIntensity = true;
            if ("numSections".equals(option.columnName)) foundNumSections = true;
            if (FlashProjectLayout.CONDITIONS_FILENAME.equals(option.file.getName())) {
                foundConditions = true;
            }
        }

        assertTrue(foundIntensity);
        assertFalse(foundNumSections);
        assertFalse(foundConditions);
    }

    @Test
    public void savedLocalResultRebindsToCopiedProjectsResultsEvenWhenOriginalExists()
            throws Exception {
        File original = temp.newFolder("result-original");
        File copied = temp.newFolder("result-copy");
        File originalCsv = new File(FlashProjectLayout.forDirectory(original.getAbsolutePath())
                .tablesIntensityWriteDir(), "GFAP.csv");
        File copiedCsv = new File(FlashProjectLayout.forDirectory(copied.getAbsolutePath())
                .tablesIntensityWriteDir(), "GFAP.csv");
        writeCsv(originalCsv, Arrays.asList("AnimalName", "Mean"),
                Arrays.asList(Arrays.asList("Mouse1", "1")));
        writeCsv(copiedCsv, Arrays.asList("AnimalName", "Mean"),
                Arrays.asList(Arrays.asList("Mouse1", "99")));
        RepresentativeStatLoader.ExistingResultOption remembered =
                new RepresentativeStatLoader.ExistingResultOption(originalCsv, "Mean");

        RepresentativeStatLoader.ExistingResultOption rebound =
                RepresentativeStatLoader.rebindExistingResult(
                        copied.getAbsolutePath(), remembered);

        assertEquals(copiedCsv.getCanonicalFile(), rebound.file.getCanonicalFile());
        assertEquals("Tables" + File.separator + "Intensity" + File.separator + "GFAP.csv",
                rebound.relativePath);
        assertFalse(rebound.externalImport);
    }

    @Test
    public void sameFileAndColumnLabelInTwoResultFoldersBindsTheSavedRelativeFile()
            throws Exception {
        File project = temp.newFolder("result-relative-disambiguation");
        FlashProjectLayout layout = FlashProjectLayout.forDirectory(project.getAbsolutePath());
        File intensity = new File(layout.tablesIntensityWriteDir(), "Measurements.csv");
        File objects = new File(layout.tablesObjectsWriteDir(), "Measurements.csv");
        writeCsv(intensity, Arrays.asList("AnimalName", "Mean"),
                Arrays.asList(Arrays.asList("Mouse1", "1")));
        writeCsv(objects, Arrays.asList("AnimalName", "Mean"),
                Arrays.asList(Arrays.asList("Mouse1", "2")));

        RepresentativeStatLoader.ExistingResultOption rebound =
                RepresentativeStatLoader.rebindExistingResult(
                        project.getAbsolutePath(),
                        new RepresentativeStatLoader.ExistingResultOption(objects, "Mean"));

        assertEquals(objects.getCanonicalFile(), rebound.file.getCanonicalFile());
        assertTrue(rebound.relativePath.contains("Objects"));
    }

    @Test
    public void absoluteResultPathRequiresExplicitExternalImportProvenance()
            throws Exception {
        File project = temp.newFolder("result-external-project");
        File external = temp.newFile("external-result.csv");
        writeCsv(external, Arrays.asList("AnimalName", "Mean"),
                Arrays.asList(Arrays.asList("Mouse1", "3")));

        try {
            RepresentativeStatLoader.rebindExistingResult(
                    project.getAbsolutePath(),
                    new RepresentativeStatLoader.ExistingResultOption(
                            external, "Mean", external.getAbsolutePath()));
            org.junit.Assert.fail("Untrusted absolute provenance must not be opened");
        } catch (java.io.IOException expected) {
            assertTrue(expected.getMessage().contains("beneath"));
        }

        RepresentativeStatLoader.ExistingResultOption rebound =
                RepresentativeStatLoader.rebindExistingResult(
                        project.getAbsolutePath(),
                        RepresentativeStatLoader.ExistingResultOption.externalImport(
                                external, "Mean"));
        assertTrue(rebound.externalImport);
        assertEquals(external.getCanonicalFile(), rebound.file.getCanonicalFile());
    }

    @Test
    public void deserializedExternalPathWithoutRelativeProvenanceCannotBindSameNamedLocalResult()
            throws Exception {
        File project = temp.newFolder("result-missing-relative-project");
        File externalDirectory = temp.newFolder("result-missing-relative-external");
        File oldExternal = new File(externalDirectory, "Measurements.csv");
        File sameNamedLocal = new File(
                FlashProjectLayout.forDirectory(project.getAbsolutePath()).resultsRoot(),
                oldExternal.getName());
        writeCsv(oldExternal, Arrays.asList("AnimalName", "Mean"),
                Arrays.asList(Arrays.asList("Mouse1", "3")));
        writeCsv(sameNamedLocal, Arrays.asList("AnimalName", "Mean"),
                Arrays.asList(Arrays.asList("Mouse1", "99")));

        Map<String, Object> savedResult = new LinkedHashMap<String, Object>();
        savedResult.put("columnName", "Mean");
        savedResult.put("path", oldExternal.getAbsolutePath());
        Map<String, Object> savedConfig = new LinkedHashMap<String, Object>();
        savedConfig.put("existingResult", savedResult);
        RepresentativeStatLoader.ExistingResultOption remembered =
                RepresentativeFigureConfig.fromMap(savedConfig).existingResult;

        assertEquals("", remembered.relativePath);
        try {
            RepresentativeStatLoader.rebindExistingResult(
                    project.getAbsolutePath(), remembered);
            org.junit.Assert.fail("Missing local provenance must not guess by basename");
        } catch (java.io.IOException expected) {
            assertTrue(expected.getMessage().contains("missing its path relative"));
        }
    }

    @Test
    public void resultRebindRequiresContainedRelativePathAndExactFiniteColumn()
            throws Exception {
        File project = temp.newFolder("result-rebind-validation");
        File csv = new File(FlashProjectLayout.forDirectory(project.getAbsolutePath())
                .tablesIntensityWriteDir(), "GFAP.csv");
        writeCsv(csv, Arrays.asList("AnimalName", "Mean"),
                Arrays.asList(Arrays.asList("Mouse1", "not-a-number")));

        try {
            RepresentativeStatLoader.rebindExistingResult(
                    project.getAbsolutePath(),
                    new RepresentativeStatLoader.ExistingResultOption(
                            csv, "Mean", ".." + File.separator + "outside.csv"));
            org.junit.Assert.fail("Traversal must be rejected");
        } catch (java.io.IOException expected) {
            assertTrue(expected.getMessage().contains("beneath"));
        }

        try {
            RepresentativeStatLoader.rebindExistingResult(
                    project.getAbsolutePath(),
                    new RepresentativeStatLoader.ExistingResultOption(csv, "mean"));
            org.junit.Assert.fail("Column identity must be exact");
        } catch (java.io.IOException expected) {
            assertTrue(expected.getMessage().contains("Exact column"));
        }

        try {
            RepresentativeStatLoader.rebindExistingResult(
                    project.getAbsolutePath(),
                    new RepresentativeStatLoader.ExistingResultOption(csv, "Mean"));
            org.junit.Assert.fail("Non-numeric column must fail before recommendations");
        } catch (java.io.IOException expected) {
            assertTrue(expected.getMessage().contains("finite numeric"));
        }
    }

    @Test
    public void legacyRowWhoseIndexConflictsWithBiologyIsRejected() throws Exception {
        File project = temp.newFolder("result-index-conflict");
        File csv = new File(FlashProjectLayout.forDirectory(project.getAbsolutePath())
                .tablesIntensityWriteDir(), "GFAP.csv");
        writeCsv(csv,
                Arrays.asList("SeriesIndex", "AnimalName", "Hemisphere", "Region", "Mean"),
                Arrays.asList(Arrays.asList("0", "Mouse2", "RH", "SCN", "5")));
        List<SeriesMeta> metas = Arrays.asList(
                new SeriesMeta(0, "Exp-Mouse1_LH_SCN", 1, 1, 1, 1,
                        1, 1, 1, "um"),
                new SeriesMeta(1, "Exp-Mouse2_RH_SCN", 1, 1, 1, 1,
                        1, 1, 1, "um"));

        try {
            RepresentativeStatLoader.loadExistingResult(
                    project.getAbsolutePath(),
                    new RepresentativeStatLoader.ExistingResultOption(csv, "Mean"), metas);
            org.junit.Assert.fail("Conflicting legacy index must not win over biology");
        } catch (java.io.IOException expected) {
            assertTrue(expected.getMessage().contains("conflicts with animal/image identity"));
        }
    }

    @Test
    public void explicitCsvConditionIsCrossCheckedInsteadOfOverwrittenByManifest()
            throws Exception {
        File project = temp.newFolder("result-condition-conflict");
        Map<String, String> assignments = new LinkedHashMap<String, String>();
        assignments.put("Mouse1", "Control");
        ConditionManifestIO.saveAssignments(project.getAbsolutePath(), assignments);
        File csv = new File(FlashProjectLayout.forDirectory(project.getAbsolutePath())
                .tablesIntensityWriteDir(), "GFAP.csv");
        writeCsv(csv,
                Arrays.asList("AnimalName", "Condition", "Hemisphere", "Region", "Mean"),
                Arrays.asList(Arrays.asList("Mouse1", "StaleCondition", "LH", "SCN", "5")));
        List<SeriesMeta> metas = Arrays.asList(
                new SeriesMeta(0, "Exp-Mouse1_LH_SCN", 1, 1, 1, 1,
                        1, 1, 1, "um"));

        try {
            RepresentativeStatLoader.loadExistingResult(
                    project.getAbsolutePath(),
                    new RepresentativeStatLoader.ExistingResultOption(csv, "Mean"), metas);
            org.junit.Assert.fail("Explicit stale row condition must fail identity validation");
        } catch (java.io.IOException expected) {
            assertTrue(expected.getMessage().contains("no current source series matches"));
        }
    }

    @Test
    public void durableRowIdentitySurvivesReorderedIndexHint() throws Exception {
        File project = temp.newFolder("result-durable-reorder");
        File input = new File(project, "input");
        assertTrue(input.mkdirs());
        File sourceA = new File(input, "a.tif");
        File sourceB = new File(input, "b.tif");
        assertTrue(sourceA.createNewFile());
        assertTrue(sourceB.createNewFile());
        assertEquals(ImageSourceDispatcher.SourceMode.TIFF_INPUT_SUBFOLDER,
                ImageSourceDispatcher.detectMode(project.getAbsolutePath()));
        String nameA = "Exp-Mouse1_LH_SCN";
        String nameB = "Exp-Mouse2_RH_SCN";
        File csv = new File(FlashProjectLayout.forDirectory(project.getAbsolutePath())
                .tablesIntensityWriteDir(), "GFAP.csv");
        writeCsv(csv,
                Arrays.asList("SeriesIndex", "SourceKey", "ImageKey", "SeriesName",
                        "AnimalName", "Hemisphere", "Region", "Mean"),
                Arrays.asList(Arrays.asList(
                        "0",
                        RepresentativeFigureConfig.sourceKey(
                                project.getAbsolutePath(), sourceB),
                        RepresentativeFigureConfig.imageKey(nameB),
                        nameB, "Mouse2", "RH", "SCN", "7")));
        List<SeriesMeta> metas = Arrays.asList(
                new SeriesMeta(0, nameA, 1, 1, 1, 1, 1, 1, 1, "um"),
                new SeriesMeta(1, nameB, 1, 1, 1, 1, 1, 1, 1, "um"));

        RepresentativeStatTable table = RepresentativeStatLoader.loadExistingResult(
                project.getAbsolutePath(),
                new RepresentativeStatLoader.ExistingResultOption(csv, "Mean"), metas);

        assertEquals(7.0, table.value("1", "GFAP").doubleValue(), 0.0001);
        assertNull(table.value("0", "GFAP"));
    }

    @Test
    public void loadNone_returnsEmptyTableWithoutReadingProject() throws Exception {
        RepresentativeStatTable table = RepresentativeStatLoader.load(
                "not-a-real-project", RepresentativeStatistic.NONE, null, 1);

        assertTrue(table.isEmpty());
        assertEquals(0, table.rowCount());
    }

    @Test
    public void quickContainerResolverAcceptsLooseTiffProjects() throws Exception {
        File project = temp.newFolder("loose-tiff-quick");
        assertTrue(new File(project, "MouseA_LH_SCN.tif").createNewFile());

        Method method = RepresentativeStatLoader.class.getDeclaredMethod(
                "resolveQuickContainerFile", String.class);
        method.setAccessible(true);

        assertNull(method.invoke(null, project.getAbsolutePath()));
    }

    private static void writeCsv(File file, List<String> header, List<List<String>> rows)
            throws Exception {
        File parent = file.getParentFile();
        if (parent != null && !parent.isDirectory()) {
            assertTrue(parent.mkdirs());
        }
        PrintWriter writer = CsvSupport.newWriter(file);
        try {
            writer.println(CsvSupport.joinRow(header));
            for (List<String> row : rows) {
                writer.println(CsvSupport.joinRow(row));
            }
        } finally {
            writer.close();
        }
    }
}
