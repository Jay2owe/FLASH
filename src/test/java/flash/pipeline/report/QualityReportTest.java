package flash.pipeline.report;

import flash.pipeline.analyses.wizard.IntensitySpatialConfig;
import ij.ImagePlus;
import ij.process.ByteProcessor;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.*;

/**
 * Lifecycle tests for QualityReport — proves that fresh instances
 * do not inherit state from previous runs.
 */
public class QualityReportTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void freshInstance_hasNoSectionsOrImageQcData() {
        QualityReport report = new QualityReport();
        assertTrue(report.getSections().isEmpty());
        assertTrue(report.getImageQcData().isEmpty());
        assertTrue(report.getSpectralPreviewData().isEmpty());
    }

    @Test
    public void freshInstance_hasCurrentStartTime() {
        long before = System.currentTimeMillis();
        QualityReport report = new QualityReport();
        long after = System.currentTimeMillis();
        assertTrue(report.getStartTime() >= before);
        assertTrue(report.getStartTime() <= after);
    }

    @Test
    public void secondInstance_doesNotInheritSectionsFromFirst() {
        QualityReport a = new QualityReport();
        a.setEnabled(true);
        a.setDirectory("/tmp/a");
        Map<String, String> params = new LinkedHashMap<String, String>();
        params.put("key", "value");
        a.addSection("Split and Merge", params);

        assertEquals(1, a.getSections().size());

        QualityReport b = new QualityReport();
        b.setEnabled(true);
        b.setDirectory("/tmp/b");

        assertTrue("Second report must not inherit sections from first",
                b.getSections().isEmpty());
    }

    @Test
    public void secondInstance_hasIndependentStartTime() throws Exception {
        QualityReport a = new QualityReport();
        Thread.sleep(20);
        QualityReport b = new QualityReport();
        assertTrue("Second report must have a later start time",
                b.getStartTime() > a.getStartTime());
    }

    @Test
    public void secondInstance_doesNotInheritProjectDir() {
        QualityReport a = new QualityReport();
        a.setDirectory("/dir/alpha");

        QualityReport b = new QualityReport();
        b.setDirectory("/dir/beta");

        assertEquals("/dir/beta", b.getProjectDir());
        assertNotEquals(a.getProjectDir(), b.getProjectDir());
    }

    @Test
    public void addSection_noOpWhenDisabled() {
        QualityReport report = new QualityReport();
        report.setEnabled(false);
        Map<String, String> params = new LinkedHashMap<String, String>();
        params.put("k", "v");
        report.addSection("Test", params);
        assertTrue(report.getSections().isEmpty());
    }

    @Test
    public void multipleAnalyses_accumulateInOneReport() {
        QualityReport report = new QualityReport();
        report.setEnabled(true);
        report.setDirectory("/tmp/x");

        Map<String, String> p1 = new LinkedHashMap<String, String>();
        p1.put("a", "1");
        report.addSection("Analysis A", p1);

        Map<String, String> p2 = new LinkedHashMap<String, String>();
        p2.put("b", "2");
        report.addSection("Analysis B", p2);

        assertEquals(2, report.getSections().size());
        assertEquals("Analysis A", report.getSections().get(0).name);
        assertEquals("Analysis B", report.getSections().get(1).name);
    }

    @Test
    public void writeReport_usesFlashQualityReportFolder() {
        QualityReport report = new QualityReport();
        report.setEnabled(true);
        report.setDirectory(tmp.getRoot().getAbsolutePath());

        report.addGenericAnalysis("Test Analysis", 1200);

        assertTrue(new File(tmp.getRoot(),
                "FLASH/Reports/Quality Report/QC_Report.html").isFile());
        assertFalse(new File(tmp.getRoot(), "Quality_Report/QC_Report.html").exists());
    }

    @Test
    public void addIntensityParams_recordsSpatialSummary() {
        QualityReport report = new QualityReport();
        report.setEnabled(true);
        report.setDirectory(tmp.getRoot().getAbsolutePath());

        IntensitySpatialConfig spatialConfig = IntensitySpatialConfig.builder()
                .enabled(true)
                .addAnalysis(IntensitySpatialConfig.AnalysisKey.HOTSPOTSCAN)
                .addAnalysis(IntensitySpatialConfig.AnalysisKey.ANISOTROPY_3D)
                .mipEnabled(true)
                .native3dEnabled(true)
                .overlaysEnabled(true)
                .build();

        report.addIntensityParams(
                new String[]{"DAPI"},
                new boolean[]{true},
                new String[]{"100"},
                true,
                "GFAP",
                "DAPI: Basic background and noise removal",
                "Full stack",
                spatialConfig,
                "OrientationJ missing");

        assertEquals(1, report.getSections().size());
        Map<String, String> params = report.getSections().get(0).params;
        assertEquals("true", params.get("Intensity Spatial"));
        assertEquals("hotspots,anisotropy_3d", params.get("Spatial Families"));
        assertEquals("true", params.get("Spatial MIP"));
        assertEquals("true", params.get("Spatial Native 3D"));
        assertEquals("true", params.get("Spatial Overlays"));
        assertEquals("OrientationJ missing", params.get("Spatial Dependency Warnings"));
    }

    @Test
    public void addChannelQC_recordsOriginalWhenMaskMissing() {
        QualityReport report = new QualityReport();
        report.setEnabled(true);

        ByteProcessor processor = new ByteProcessor(4, 4);
        processor.set(1, 1, 255);
        ImagePlus original = new ImagePlus("original", processor);

        report.addChannelQC("Image1", "DAPI", original, null, "Blue");

        assertEquals(1, report.getImageQcData().size());
        QualityReport.ChannelQC qc = report.getImageQcData().get("Image1").get(0);
        assertEquals("DAPI", qc.channelName);
        assertNotNull(qc.originalB64);
        assertFalse(qc.originalB64.isEmpty());
        assertNull(qc.maskB64);
        assertNull(qc.overlayB64);
    }

    @Test
    public void addChannelQC_capsStoredImages() {
        String previous = System.getProperty(QualityReport.MAX_CHANNEL_QC_PROPERTY);
        System.setProperty(QualityReport.MAX_CHANNEL_QC_PROPERTY, "2");
        try {
            QualityReport report = new QualityReport();
            report.setEnabled(true);

            report.addChannelQC("Image1", "DAPI", tinyImage(), null, "Blue");
            report.addChannelQC("Image2", "GFAP", tinyImage(), null, "Green");
            report.addChannelQC("Image3", "IBA1", tinyImage(), null, "Red");

            assertEquals(2, report.getImageQcData().size());
            assertFalse(report.getImageQcData().containsKey("Image3"));
            assertEquals(1, report.getSkippedChannelQcRecords());
        } finally {
            if (previous == null) {
                System.clearProperty(QualityReport.MAX_CHANNEL_QC_PROPERTY);
            } else {
                System.setProperty(QualityReport.MAX_CHANNEL_QC_PROPERTY, previous);
            }
        }
    }

    private static ImagePlus tinyImage() {
        ByteProcessor processor = new ByteProcessor(4, 4);
        processor.set(1, 1, 255);
        return new ImagePlus("original", processor);
    }
}
