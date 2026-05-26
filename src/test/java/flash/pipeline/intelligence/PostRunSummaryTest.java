package flash.pipeline.intelligence;

import flash.pipeline.io.FlashProjectLayout;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class PostRunSummaryTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void writeIfPossible_persistsSnapshotAndReportsRerunDiff() throws Exception {
        File root = temp.newFolder("post-run-summary");
        File exportDir = FlashProjectLayout.forDirectory(root.getAbsolutePath()).tablesProjectSummaryWriteDir();
        File binDir = new File(root, ".bin");
        assertTrue(exportDir.mkdirs());
        assertTrue(binDir.mkdirs());

        writeBin(root, "default");
        writeObjectsMaster(root, 10);

        PostRunSummary.writeIfPossible(root.getAbsolutePath());

        File json = SummaryHistoryStore.historyWriteFile(root.getAbsolutePath());
        assertTrue(json.isFile());
        assertTrue(json.getAbsolutePath().contains(
                new File("FLASH/Status/" + FlashProjectLayout.SETTINGS_DIR
                        + "/" + SummaryHistoryStore.FILE_NAME).getPath()));
        assertTrue(!new File(root, SummaryHistoryStore.LEGACY_FILE_NAME).exists());

        writeBin(root, "500");
        writeObjectsMaster(root, 15);

        PostRunSummary.writeIfPossible(root.getAbsolutePath());

        File txt = new File(exportDir, "ihf-summary.txt");
        assertTrue(txt.isFile());

        List<String> lines = Files.readAllLines(txt.toPath(), StandardCharsets.UTF_8);
        String joined = String.join("\n", lines);
        assertTrue(joined.contains("R-07 Re-run diff"));
        assertTrue(joined.contains("Object thresholds: default -> 500"));
        assertTrue(joined.contains("DAPI_Count 10.000 -> 15.000"));
    }

    @Test
    public void writeIfPossible_persistsPerImageMetricsForOddImageOut() throws Exception {
        File root = temp.newFolder("post-run-image-metrics");
        File binDir = new File(root, ".bin");
        File objectsDir = FlashProjectLayout.forDirectory(root.getAbsolutePath()).tablesObjectsWriteDir();
        assertTrue(binDir.mkdirs());
        assertTrue(objectsDir.mkdirs());

        writeBin(root, "default");
        writeObjectsMaster(root, 10);
        writeObjectRows(new File(objectsDir, "DAPI.csv"),
                "SCN,Region,Hemisphere,ROI,Animal Name,Label,Volume (micron^3),IntDen,Mean\n"
                        + "1,SCN,LH,SCN1,Mouse1,1,10,100,50\n"
                        + "1,SCN,LH,SCN1,Mouse1,2,12,120,60\n");

        PostRunSummary.writeIfPossible(root.getAbsolutePath());

        SummaryHistoryStore.Snapshot snapshot = SummaryHistoryStore.load(root.getAbsolutePath());
        assertTrue(snapshot != null);

        boolean foundMetrics = false;
        for (Map<String, Object> image : snapshot.imageMetadata.values()) {
            Object metricsObj = image.get("metrics");
            if (!(metricsObj instanceof Map)) continue;
            @SuppressWarnings("unchecked")
            Map<String, Object> metrics = (Map<String, Object>) metricsObj;
            if (metrics.containsKey("DAPI object count")) {
                foundMetrics = true;
                assertEquals(2.0, ((Number) metrics.get("DAPI object count")).doubleValue(), 0.0);
                assertEquals(55.0, ((Number) metrics.get("DAPI object mean intensity")).doubleValue(), 0.0);
            }
        }
        assertTrue(foundMetrics);
    }

    @Test
    public void writeIfPossible_readsIntensityUnfilteredMetrics() throws Exception {
        File root = temp.newFolder("post-run-intensity-metrics");
        File binDir = new File(root, ".bin");
        FlashProjectLayout layout = FlashProjectLayout.forDirectory(root.getAbsolutePath());
        File intensitiesDir = layout.tablesIntensityWriteDir();
        File aggregationDir = layout.tablesProjectSummaryWriteDir();
        assertTrue(binDir.mkdirs());
        assertTrue(intensitiesDir.mkdirs());
        assertTrue(aggregationDir.mkdirs());

        writeBin(root, "default");
        Files.write(new File(aggregationDir, FlashProjectLayout.MASTER_INTENSITIES_FILENAME).toPath(),
                ("AnimalName,DAPI_ROI_IntDen_UnfilteredMean\n"
                        + "Mouse1,70\n").getBytes(StandardCharsets.UTF_8));
        Files.write(new File(intensitiesDir, "GFAP.csv").toPath(),
                ("Animal Name,ROI,Region,SCN,Hemisphere,IntDen,%Area,IntDen_Unfiltered,RawIntDen\n"
                        + "Mouse1,SCN1,SCN,1,LH,10,5,44,999\n").getBytes(StandardCharsets.UTF_8));
        Files.write(new File(intensitiesDir, "DAPI.csv").toPath(),
                ("Animal Name,ROI,Region,SCN,Hemisphere,IntDen,%Area,RawIntDen\n"
                        + "Mouse1,SCN1,SCN,1,LH,20,6,70\n").getBytes(StandardCharsets.UTF_8));

        PostRunSummary.writeIfPossible(root.getAbsolutePath());

        SummaryHistoryStore.Snapshot snapshot = SummaryHistoryStore.load(root.getAbsolutePath());
        assertTrue(snapshot != null);

        boolean foundMetrics = false;
        for (Map<String, Object> image : snapshot.imageMetadata.values()) {
            Object metricsObj = image.get("metrics");
            if (!(metricsObj instanceof Map)) continue;
            @SuppressWarnings("unchecked")
            Map<String, Object> metrics = (Map<String, Object>) metricsObj;
            for (String key : metrics.keySet()) {
                assertFalse(key.contains("mean raw intensity"));
            }
            if (metrics.containsKey("GFAP mean unfiltered intensity")
                    && metrics.containsKey("DAPI mean unfiltered intensity")) {
                foundMetrics = true;
                assertEquals(44.0,
                        ((Number) metrics.get("GFAP mean unfiltered intensity")).doubleValue(), 0.0);
                assertEquals(70.0,
                        ((Number) metrics.get("DAPI mean unfiltered intensity")).doubleValue(), 0.0);
            }
        }
        assertTrue(foundMetrics);
    }

    @Test
    public void writeIfPossible_readsProjectSummaryMasters() throws Exception {
        File root = temp.newFolder("post-run-new-aggregation");
        File binDir = new File(root, ".bin");
        File aggregationDir = FlashProjectLayout.forDirectory(root.getAbsolutePath()).tablesProjectSummaryWriteDir();
        assertTrue(binDir.mkdirs());
        assertTrue(aggregationDir.mkdirs());

        writeBin(root, "default");
        Files.write(new File(aggregationDir, FlashProjectLayout.MASTER_OBJECTS_FILENAME).toPath(),
                ("Animal Name,Region,DAPI_Count\n"
                        + "Mouse1,SCN,22\n").getBytes(StandardCharsets.UTF_8));

        PostRunSummary.writeIfPossible(root.getAbsolutePath());

        File txt = new File(aggregationDir, "ihf-summary.txt");
        assertTrue(txt.isFile());
        SummaryHistoryStore.Snapshot snapshot = SummaryHistoryStore.load(root.getAbsolutePath());
        assertTrue(snapshot != null);
        assertTrue(snapshot.tables.containsKey(FlashProjectLayout.MASTER_OBJECTS_FILENAME));
    }

    @Test
    public void writeIfPossible_handlesIntensityOnlyAggregation() throws Exception {
        File root = temp.newFolder("post-run-intensity-only");
        File binDir = new File(root, ".bin");
        File aggregationDir = FlashProjectLayout.forDirectory(root.getAbsolutePath()).tablesProjectSummaryWriteDir();
        assertTrue(binDir.mkdirs());
        assertTrue(aggregationDir.mkdirs());

        writeBin(root, "default");
        Files.write(new File(aggregationDir, FlashProjectLayout.MASTER_INTENSITIES_FILENAME).toPath(),
                ("Animal Name,Region,DAPI_Mean\n"
                        + "Mouse1,SCN,42\n").getBytes(StandardCharsets.UTF_8));

        PostRunSummary.writeIfPossible(root.getAbsolutePath());

        File txt = new File(aggregationDir, "ihf-summary.txt");
        assertTrue(txt.isFile());
        SummaryHistoryStore.Snapshot snapshot = SummaryHistoryStore.load(root.getAbsolutePath());
        assertTrue(snapshot != null);
        assertTrue(snapshot.tables.containsKey(FlashProjectLayout.MASTER_INTENSITIES_FILENAME));
    }

    private void writeBin(File root, String threshold) throws Exception {
        File channelData = new File(new File(root, ".bin"), "Channel_Data.txt");
        String content = "DAPI\n"
                + "Blue\n"
                + threshold + "\n"
                + "100-Infinity\n";
        Files.write(channelData.toPath(), content.getBytes(StandardCharsets.UTF_8));
    }

    private void writeObjectsMaster(File root, int count) throws Exception {
        File objectsMaster = FlashProjectLayout.forDirectory(root.getAbsolutePath())
                .projectSummaryWriteFile(FlashProjectLayout.MASTER_OBJECTS_FILENAME);
        File parent = objectsMaster.getParentFile();
        assertTrue(parent.isDirectory() || parent.mkdirs());
        String content = "Animal Name,Region,DAPI_Count\n"
                + "Mouse1,SCN," + count + "\n";
        Files.write(objectsMaster.toPath(), content.getBytes(StandardCharsets.UTF_8));
    }

    private void writeObjectRows(File file, String content) throws Exception {
        Files.write(file.toPath(), content.getBytes(StandardCharsets.UTF_8));
    }
}
