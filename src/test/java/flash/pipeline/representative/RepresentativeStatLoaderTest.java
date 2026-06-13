package flash.pipeline.representative;

import flash.pipeline.io.ConditionManifestIO;
import flash.pipeline.io.CsvSupport;
import flash.pipeline.io.FlashProjectLayout;
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
