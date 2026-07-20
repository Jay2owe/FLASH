package flash.pipeline.export;

import flash.pipeline.io.ConditionManifestIO;
import flash.pipeline.io.CsvSupport;
import flash.pipeline.io.FlashProjectLayout;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class ExcelSummaryExportAnalysisTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void hideImageWindowsFlagAloneDoesNotSuppressExportConfigDialog() {
        assertTrue(ExcelSummaryExportAnalysis.canShowGuiDialog(false, false, false));
        assertFalse(ExcelSummaryExportAnalysis.canShowGuiDialog(true, false, false));
        assertFalse(ExcelSummaryExportAnalysis.canShowGuiDialog(false, true, false));
        assertFalse(ExcelSummaryExportAnalysis.canShowGuiDialog(false, false, true));
    }

    @Test
    public void execute_readsQuotedStatisticsCsvIntoWorkbook() throws Exception {
        File dir = temp.newFolder("excel-summary");
        FlashProjectLayout layout = FlashProjectLayout.forDirectory(dir.getAbsolutePath());
        File aggregationDir = layout.tablesProjectSummaryWriteDir();
        File statisticsDir = layout.tablesProjectSummaryWriteDir();
        assertTrue(aggregationDir.isDirectory() || aggregationDir.mkdirs());
        assertTrue(statisticsDir.isDirectory() || statisticsDir.mkdirs());

        writeCsv(new File(aggregationDir, FlashProjectLayout.MASTER_OBJECTS_FILENAME),
                Arrays.asList("AnimalName", "GFAP_Count"),
                Arrays.asList(
                        Arrays.asList("Mouse1", "1.0"),
                        Arrays.asList("Mouse2", "2.0")));

        LinkedHashMap<String, String> conditions = new LinkedHashMap<String, String>();
        conditions.put("Mouse1", "CondA");
        conditions.put("Mouse2", "CondB");
        ConditionManifestIO.saveAssignments(dir.getAbsolutePath(), conditions);

        writeCsv(new File(statisticsDir, FlashProjectLayout.STATISTICS_FILENAME),
                Arrays.asList("Metric", "Test", "Statistic", "p-value", "Significant", "NormalityResult",
                        "Group1", "Group2", "PairwiseTest", "PairwiseStatistic",
                        "PairwisePValue", "CorrectedPValue", "Significance", "Notes"),
                Arrays.asList(
                        Arrays.asList(
                                "Metric, \"Quoted\"",
                                "Welch t-test",
                                "1.500000",
                                "0.012345",
                                "*",
                                "normal",
                                "CondA",
                                "CondB",
                                "none",
                                "",
                                "",
                                "",
                                "*",
                                "Line1\nLine2")));

        ExcelSummaryExportAnalysis analysis = new ExcelSummaryExportAnalysis();
        analysis.setHeadless(true);
        analysis.setSuppressDialogs(true);
        analysis.execute(dir.getAbsolutePath());

        File workbookFile = layout.summaryWorkbookWriteFile();
        assertTrue(workbookFile.isFile());

        FileInputStream fis = new FileInputStream(workbookFile);
        try {
            Workbook workbook = new XSSFWorkbook(fis);
            try {
                org.apache.poi.ss.usermodel.Sheet sheet = workbook.getSheet("Statistics");
                assertNotNull(sheet);
                assertEquals("Metric, \"Quoted\"", sheet.getRow(1).getCell(0).getStringCellValue());
                assertEquals(0.012345, sheet.getRow(1).getCell(3).getNumericCellValue(), 0.0);
                assertEquals("Line1\nLine2", sheet.getRow(1).getCell(13).getStringCellValue());
            } finally {
                workbook.close();
            }
        } finally {
            fis.close();
        }
    }

    @Test
    public void execute_readsLegacyAnalysisDetailsIntoNewWorkbook() throws Exception {
        File dir = temp.newFolder("excel-summary-legacy-details");
        FlashProjectLayout layout = FlashProjectLayout.forDirectory(dir.getAbsolutePath());
        File aggregationDir = layout.tablesProjectSummaryWriteDir();
        assertTrue(aggregationDir.isDirectory() || aggregationDir.mkdirs());

        writeCsv(new File(aggregationDir, FlashProjectLayout.MASTER_OBJECTS_FILENAME),
                Arrays.asList("AnimalName", "GFAP_Count"),
                Arrays.asList(
                        Arrays.asList("Mouse1", "1.0"),
                        Arrays.asList("Mouse2", "2.0")));

        LinkedHashMap<String, String> conditions = new LinkedHashMap<String, String>();
        conditions.put("Mouse1", "CondA");
        conditions.put("Mouse2", "CondB");
        ConditionManifestIO.saveAssignments(dir.getAbsolutePath(), conditions);

        File detailsDir = layout.analysisDetailsWriteDir();
        assertTrue(detailsDir.mkdirs());
        Files.write(new File(detailsDir, "objects_GFAP.txt").toPath(), Arrays.asList(
                "<Filter Macro>legacy filter macro</Filter Macro>",
                "<Analysis Macro>legacy analysis macro</Analysis Macro>"));

        ExcelSummaryExportAnalysis analysis = new ExcelSummaryExportAnalysis();
        analysis.setHeadless(true);
        analysis.setSuppressDialogs(true);
        analysis.execute(dir.getAbsolutePath());

        File workbookFile = layout.summaryWorkbookWriteFile();
        assertTrue(workbookFile.isFile());

        FileInputStream fis = new FileInputStream(workbookFile);
        try {
            Workbook workbook = new XSSFWorkbook(fis);
            try {
                org.apache.poi.ss.usermodel.Sheet sheet = workbook.getSheet("Data Summary");
                assertNotNull(sheet);
                assertEquals("GFAP", sheet.getRow(2).getCell(0).getStringCellValue());
                assertEquals("Object", sheet.getRow(2).getCell(1).getStringCellValue());
                assertEquals("legacy filter macro", sheet.getRow(2).getCell(3).getStringCellValue());
                assertEquals("legacy analysis macro", sheet.getRow(2).getCell(4).getStringCellValue());
            } finally {
                workbook.close();
            }
        } finally {
            fis.close();
        }
    }

    @Test
    public void safeSheetNamesAreCaseInsensitiveAndPreserveDisplayCase() throws Exception {
        Set<String> used = new HashSet<String>();
        String first = ExcelNameMap.safeSheetName("GFAP", used);
        String second = ExcelNameMap.safeSheetName("gfap", used);
        String truncatedFirst = ExcelNameMap.safeSheetName(
                "A123456789012345678901234567890X", used);
        String truncatedSecond = ExcelNameMap.safeSheetName(
                "a123456789012345678901234567890Y", used);

        assertEquals("GFAP", first);
        assertTrue(second.toLowerCase(Locale.ROOT).startsWith("gfap_"));
        assertTrue(first.length() <= 31);
        assertTrue(second.length() <= 31);
        assertTrue(truncatedFirst.length() <= 31);
        assertTrue(truncatedSecond.length() <= 31);

        XSSFWorkbook workbook = new XSSFWorkbook();
        try {
            workbook.createSheet(first);
            workbook.createSheet(second);
            workbook.createSheet(truncatedFirst);
            workbook.createSheet(truncatedSecond);
            assertEquals(4, workbook.getNumberOfSheets());
        } finally {
            workbook.close();
        }
    }

    @Test
    public void invalidProbabilitiesAndNonfiniteNumbersStayBlankAndUnstyled() throws Exception {
        File dir = temp.newFolder("excel-invalid-probabilities");
        createMinimalProject(dir);
        FlashProjectLayout layout = FlashProjectLayout.forDirectory(dir.getAbsolutePath());
        List<List<String>> rows = new ArrayList<List<String>>();
        for (String invalid : Arrays.asList("NaN", "Infinity", "-Infinity", "-0.1", "1.1")) {
            rows.add(Arrays.asList("Metric", invalid, "****", invalid, invalid));
        }
        writeCsv(new File(layout.tablesProjectSummaryWriteDir(), FlashProjectLayout.STATISTICS_FILENAME),
                Arrays.asList("Metric", "p-value", "Significant", "Value", "CorrectedPValue"),
                rows);

        final boolean[] disposed = {false};
        final List<String> lifecycle = new ArrayList<String>();
        Set<String> tempFilesBefore = sxssfTempFiles();
        ExcelSummaryExportAnalysis analysis = configuredStatisticsAnalysis();
        analysis.setExportTestHook(new TestHook() {
            @Override
            public void afterWorkbookClose() {
                lifecycle.add("close");
            }

            @Override
            public void afterDispose(boolean allTemporaryFilesDeleted) {
                lifecycle.add("dispose");
                disposed[0] = allTemporaryFilesDeleted;
            }
        });
        analysis.execute(dir.getAbsolutePath());

        assertTrue("SXSSF reported incomplete cleanup", disposed[0]);
        assertEquals(Arrays.asList("close", "dispose"), lifecycle);
        assertEquals(tempFilesBefore, sxssfTempFiles());
        XSSFWorkbook workbook = openWorkbook(layout.summaryWorkbookWriteFile());
        try {
            org.apache.poi.ss.usermodel.Sheet sheet = workbook.getSheet("Statistics");
            assertNotNull(sheet);
            for (int rowIndex = 1; rowIndex <= rows.size(); rowIndex++) {
                org.apache.poi.ss.usermodel.Row row = sheet.getRow(rowIndex);
                assertBlank(row.getCell(1));
                assertBlank(row.getCell(2));
                assertNotErrorCell(row.getCell(3));
                assertBlank(row.getCell(4));
                assertEquals("", row.getCell(5).getStringCellValue());
                for (int column = 0; column <= 5; column++) {
                    if (row.getCell(column) != null) {
                        assertEquals(FillPatternType.NO_FILL,
                                row.getCell(column).getCellStyle().getFillPatternEnum());
                    }
                }
            }
        } finally {
            workbook.close();
        }
    }

    @Test
    public void extremeFiniteSummariesDoNotOverflowRepresentableResults() {
        ExcelMetricSummariser.Summary equal = ExcelMetricSummariser.summarise(
                Double.MAX_VALUE, Double.MAX_VALUE);
        assertEquals(Double.MAX_VALUE, equal.mean, 0.0);
        assertEquals(0.0, equal.sem, 0.0);
        assertEquals(Double.MAX_VALUE, equal.median, 0.0);
        assertEquals(0.0, equal.iqr(), 0.0);

        ExcelMetricSummariser.Summary symmetric = ExcelMetricSummariser.summarise(
                -Double.MAX_VALUE, Double.MAX_VALUE);
        assertEquals(0.0, symmetric.mean, 0.0);
        assertTrue(Double.isFinite(symmetric.sem));
        assertTrue(Double.isFinite(symmetric.median));
        assertTrue(Double.isFinite(symmetric.q1));
        assertTrue(Double.isFinite(symmetric.q3));
        assertTrue(Double.isFinite(symmetric.iqr()));
    }

    @Test
    public void undefinedSingleSampleSemIsAbsentRatherThanNumericZero() throws Exception {
        File dir = temp.newFolder("excel-undefined-sem");
        createMinimalProject(dir);
        ExcelSummaryExportAnalysis analysis = new ExcelSummaryExportAnalysis();
        analysis.setHeadless(true);
        analysis.setSuppressDialogs(true);
        analysis.setPreset(new ExcelExportPreset(
                "Summary-only test", null,
                false, false, true, false,
                ExcelExportPreset.MetricSheetDetail.SUMMARY_STATISTICS,
                false,
                ExcelExportPreset.SignificanceHighlight.OFF,
                ExcelExportPreset.HeaderStyle.STANDARD,
                false));
        analysis.execute(dir.getAbsolutePath());

        XSSFWorkbook workbook = openWorkbook(FlashProjectLayout
                .forDirectory(dir.getAbsolutePath()).summaryWorkbookWriteFile());
        try {
            org.apache.poi.ss.usermodel.Sheet sheet = workbook.getSheetAt(0);
            assertEquals(CellType.NUMERIC, sheet.getRow(1).getCell(0).getCellTypeEnum());
            assertNull(sheet.getRow(3).getCell(0));
        } finally {
            workbook.close();
        }
    }

    @Test
    public void rowFaultAndCancellationDisposeStreamingFilesAndPreserveWorkbook() throws Exception {
        File dir = temp.newFolder("excel-streaming-fault-cancel");
        createMinimalProject(dir);
        FlashProjectLayout layout = FlashProjectLayout.forDirectory(dir.getAbsolutePath());
        File statistics = new File(layout.tablesProjectSummaryWriteDir(),
                FlashProjectLayout.STATISTICS_FILENAME);
        writeCsv(statistics, Arrays.asList("Metric", "p-value"),
                Arrays.asList(Arrays.asList("baseline", "0.5")));
        ExcelSummaryExportAnalysis baseline = configuredStatisticsAnalysis();
        baseline.execute(dir.getAbsolutePath());
        File target = layout.summaryWorkbookWriteFile();
        byte[] baselineBytes = Files.readAllBytes(target.toPath());
        assertWorkbookContains(target, "baseline");

        List<List<String>> manyRows = new ArrayList<List<String>>();
        for (int i = 0; i < 250; i++) {
            manyRows.add(Arrays.asList("replacement-" + i, "0.5"));
        }
        writeCsv(statistics, Arrays.asList("Metric", "p-value"), manyRows);

        Set<String> beforeFault = sxssfTempFiles();
        final boolean[] faultDisposeAttempted = {false};
        ExcelSummaryExportAnalysis faulted = configuredStatisticsAnalysis();
        faulted.setExportTestHook(new TestHook() {
            @Override
            public void afterRowWritten(String sheetName, int rowIndex) throws IOException {
                if ("Statistics".equals(sheetName) && rowIndex == 130) {
                    throw new IOException("injected streaming row failure");
                }
            }

            @Override
            public void afterDispose(boolean allTemporaryFilesDeleted) {
                faultDisposeAttempted[0] = true;
            }
        });
        faulted.execute(dir.getAbsolutePath());
        assertTrue(faultDisposeAttempted[0]);
        assertEquals(beforeFault, sxssfTempFiles());
        assertArrayEquals(baselineBytes, Files.readAllBytes(target.toPath()));
        assertWorkbookContains(target, "baseline");

        Set<String> beforeCancel = sxssfTempFiles();
        final boolean[] cancelDisposeAttempted = {false};
        ExcelSummaryExportAnalysis cancelled = configuredStatisticsAnalysis();
        cancelled.setExportTestHook(new TestHook() {
            @Override
            public void afterRowWritten(String sheetName, int rowIndex) {
                if ("Statistics".equals(sheetName) && rowIndex == 130) {
                    Thread.currentThread().interrupt();
                }
            }

            @Override
            public void afterDispose(boolean allTemporaryFilesDeleted) {
                cancelDisposeAttempted[0] = true;
            }
        });
        try {
            cancelled.execute(dir.getAbsolutePath());
        } finally {
            Thread.interrupted();
        }
        assertTrue(cancelDisposeAttempted[0]);
        assertEquals(beforeCancel, sxssfTempFiles());
        assertArrayEquals(baselineBytes, Files.readAllBytes(target.toPath()));
        assertWorkbookContains(target, "baseline");
    }

    @Test
    public void wideWorkbookCompletesInConstrainedHeapAndReopens() throws Exception {
        File dir = temp.newFolder("excel-constrained-heap");
        int metrics = 180;
        int animals = 600;
        createWideProject(dir, metrics, animals);
        File childTemp = temp.newFolder("excel-constrained-tmp");

        List<String> command = new ArrayList<String>();
        command.add(javaExecutable());
        command.add("-Xmx96m");
        command.add("-Djava.io.tmpdir=" + childTemp.getAbsolutePath());
        command.add("-cp");
        command.add(testClasspath());
        command.add(ConstrainedHeapWorker.class.getName());
        command.add(dir.getAbsolutePath());
        command.add(String.valueOf(metrics));
        command.add(String.valueOf(animals));
        File workerLog = new File(childTemp, "worker.log");
        Process process = new ProcessBuilder(command)
                .redirectErrorStream(true)
                .redirectOutput(workerLog)
                .start();
        if (!process.waitFor(120, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            fail("Constrained-heap Excel worker timed out");
        }
        assertEquals("Constrained-heap Excel worker failed:\n"
                        + new String(Files.readAllBytes(workerLog.toPath()), "UTF-8"),
                0, process.exitValue());
        XSSFWorkbook workbook = openWorkbook(
                FlashProjectLayout.forDirectory(dir.getAbsolutePath()).summaryWorkbookWriteFile());
        try {
            assertEquals(metrics, workbook.getNumberOfSheets());
            for (int sheetIndex = 0; sheetIndex < metrics; sheetIndex++) {
                org.apache.poi.ss.usermodel.Sheet sheet = workbook.getSheetAt(sheetIndex);
                assertEquals(CellType.NUMERIC,
                        sheet.getRow(1).getCell(0).getCellTypeEnum());
                assertTrue(sheet.getLastRowNum() >= animals);
            }
        } finally {
            workbook.close();
        }
        assertTrue(sxssfTempFiles(childTemp.toPath()).isEmpty());
    }

    @Test
    public void excelSafeText_escapesFormulaLeadingText() {
        assertEquals("'=cmd", ExcelSummaryExportAnalysis.excelSafeText("=cmd"));
        assertEquals("'+cmd", ExcelSummaryExportAnalysis.excelSafeText("+cmd"));
        assertEquals("'-cmd", ExcelSummaryExportAnalysis.excelSafeText("-cmd"));
        assertEquals("'@cmd", ExcelSummaryExportAnalysis.excelSafeText("@cmd"));
        assertEquals("safe", ExcelSummaryExportAnalysis.excelSafeText("safe"));
    }

    private static ExcelSummaryExportAnalysis configuredStatisticsAnalysis() {
        ExcelSummaryExportAnalysis analysis = new ExcelSummaryExportAnalysis();
        analysis.setHeadless(true);
        analysis.setSuppressDialogs(true);
        analysis.setPreset(new ExcelExportPreset(
                "Statistics test", null,
                false, false, false, true,
                ExcelExportPreset.MetricSheetDetail.RAW_VALUES,
                false,
                ExcelExportPreset.SignificanceHighlight.P_GRADIENT,
                ExcelExportPreset.HeaderStyle.STANDARD,
                true));
        return analysis;
    }

    private static ExcelSummaryExportAnalysis configuredMetricAnalysis() {
        ExcelSummaryExportAnalysis analysis = new ExcelSummaryExportAnalysis();
        analysis.setHeadless(true);
        analysis.setSuppressDialogs(true);
        analysis.setPreset(new ExcelExportPreset(
                "Streaming metric test", null,
                false, false, true, false,
                ExcelExportPreset.MetricSheetDetail.RAW_VALUES,
                false,
                ExcelExportPreset.SignificanceHighlight.OFF,
                ExcelExportPreset.HeaderStyle.STANDARD,
                false));
        return analysis;
    }

    private static void createMinimalProject(File dir) throws Exception {
        FlashProjectLayout layout = FlashProjectLayout.forDirectory(dir.getAbsolutePath());
        File summaryDir = layout.tablesProjectSummaryWriteDir();
        assertTrue(summaryDir.isDirectory() || summaryDir.mkdirs());
        writeCsv(new File(summaryDir, FlashProjectLayout.MASTER_OBJECTS_FILENAME),
                Arrays.asList("AnimalName", "GFAP_Count"),
                Arrays.asList(Arrays.asList("Mouse1", "1.0")));
        LinkedHashMap<String, String> conditions = new LinkedHashMap<String, String>();
        conditions.put("Mouse1", "CondA");
        ConditionManifestIO.saveAssignments(dir.getAbsolutePath(), conditions);
    }

    private static void createWideProject(File dir, int metricCount, int animalCount) throws Exception {
        FlashProjectLayout layout = FlashProjectLayout.forDirectory(dir.getAbsolutePath());
        File summaryDir = layout.tablesProjectSummaryWriteDir();
        assertTrue(summaryDir.isDirectory() || summaryDir.mkdirs());
        List<String> headers = new ArrayList<String>();
        headers.add("AnimalName");
        for (int metric = 0; metric < metricCount; metric++) {
            headers.add("Marker" + metric + "_Count");
        }
        File master = new File(summaryDir, FlashProjectLayout.MASTER_OBJECTS_FILENAME);
        PrintWriter writer = CsvSupport.newWriter(master);
        try {
            writer.println(CsvSupport.joinRow(headers));
            for (int animal = 0; animal < animalCount; animal++) {
                List<String> row = new ArrayList<String>(metricCount + 1);
                row.add("Mouse" + animal);
                for (int metric = 0; metric < metricCount; metric++) {
                    row.add(String.valueOf((animal + 1) * (metric + 1)));
                }
                writer.println(CsvSupport.joinRow(row));
            }
        } finally {
            writer.close();
        }
        LinkedHashMap<String, String> conditions = new LinkedHashMap<String, String>();
        for (int animal = 0; animal < animalCount; animal++) {
            conditions.put("Mouse" + animal, "CondA");
        }
        ConditionManifestIO.saveAssignments(dir.getAbsolutePath(), conditions);
    }

    private static XSSFWorkbook openWorkbook(File file) throws Exception {
        FileInputStream input = new FileInputStream(file);
        try {
            return new XSSFWorkbook(input);
        } finally {
            input.close();
        }
    }

    private static void assertWorkbookContains(File target, String firstMetric) throws Exception {
        XSSFWorkbook workbook = openWorkbook(target);
        try {
            assertEquals(firstMetric,
                    workbook.getSheet("Statistics").getRow(1).getCell(0).getStringCellValue());
        } finally {
            workbook.close();
        }
    }

    private static void assertBlank(Cell cell) {
        if (cell == null) return;
        CellType type = cell.getCellTypeEnum();
        assertTrue("Expected a blank/non-numeric cell but found " + type,
                type == CellType.BLANK
                        || (type == CellType.STRING && cell.getStringCellValue().isEmpty()));
    }

    private static void assertNotErrorCell(Cell cell) {
        if (cell == null) return;
        CellType type = cell.getCellTypeEnum();
        assertTrue("Invalid numeric source became an Excel error cell: " + type,
                type != CellType.ERROR);
        if (type == CellType.NUMERIC) {
            assertTrue(Double.isFinite(cell.getNumericCellValue()));
        }
    }

    private static Set<String> sxssfTempFiles() throws IOException {
        return sxssfTempFiles(Paths.get(System.getProperty("java.io.tmpdir")));
    }

    private static Set<String> sxssfTempFiles(Path root) throws IOException {
        Set<String> files = new HashSet<String>();
        if (!Files.exists(root)) return files;
        java.util.stream.Stream<Path> stream = Files.walk(root);
        try {
            stream.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().startsWith("poi-sxssf-sheet"))
                    .forEach(path -> files.add(path.toAbsolutePath().normalize().toString()));
        } finally {
            stream.close();
        }
        return files;
    }

    private static String javaExecutable() {
        String suffix = System.getProperty("os.name", "").toLowerCase(Locale.ROOT)
                .contains("win") ? "java.exe" : "java";
        return Paths.get(System.getProperty("java.home"), "bin", suffix)
                .toAbsolutePath().normalize().toString();
    }

    private static String testClasspath() {
        String classpath = System.getProperty("surefire.test.class.path");
        if (classpath == null || classpath.trim().isEmpty()) {
            classpath = System.getProperty("java.class.path");
        }
        if (classpath == null || classpath.trim().isEmpty()) {
            throw new IllegalStateException("No test classpath is available");
        }
        return classpath;
    }

    private abstract static class TestHook implements ExcelSummaryExportAnalysis.ExportTestHook {
        @Override
        public void afterRowWritten(String sheetName, int rowIndex) throws IOException {
        }

        @Override
        public void beforePublish(File temporaryWorkbook, File targetWorkbook) throws IOException {
        }

        @Override
        public void afterWorkbookClose() {
        }

        @Override
        public void afterDispose(boolean allTemporaryFilesDeleted) {
        }
    }

    public static final class ConstrainedHeapWorker {
        private ConstrainedHeapWorker() {
        }

        public static void main(String[] args) throws Exception {
            File directory = new File(args[0]);
            int metricCount = Integer.parseInt(args[1]);
            int animalCount = Integer.parseInt(args[2]);
            ExcelSummaryExportAnalysis analysis = configuredMetricAnalysis();
            analysis.execute(directory.getAbsolutePath());
            File workbookFile = FlashProjectLayout.forDirectory(directory.getAbsolutePath())
                    .summaryWorkbookWriteFile();
            if (!workbookFile.isFile() || workbookFile.length() == 0L) {
                throw new AssertionError("Constrained export did not publish a workbook");
            }
        }
    }

    private static void writeCsv(File file, java.util.List<String> header,
                                 java.util.List<java.util.List<String>> rows) throws Exception {
        PrintWriter pw = CsvSupport.newWriter(file);
        try {
            pw.println(CsvSupport.joinRow(header));
            for (java.util.List<String> row : rows) {
                pw.println(CsvSupport.joinRow(row));
            }
        } finally {
            pw.close();
        }
    }
}
