package flash.pipeline.analyses;

import flash.pipeline.io.CalibrationIO;
import flash.pipeline.io.CsvTableIO;
import flash.pipeline.io.FlashProjectLayout;
import ij.gui.PolygonRoi;
import ij.gui.Roi;
import ij.io.RoiEncoder;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.Assert.*;

/** Known physical answers rather than expectations copied from the implementation. */
public class MeasurementCalibrationAuditTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();

    @Test public void processVoxelSpacingUsesMicronsAndDoesNotInventPhysicalUnits() {
        ij.measure.Calibration cal = new ij.measure.Calibration();
        cal.setUnit("nm");
        cal.pixelWidth = 2000;
        cal.pixelHeight = 3000;
        cal.pixelDepth = 4000;
        assertEquals(Math.cbrt(24), ThreeDObjectAnalysis.processVoxelLengthScaleMicrons(cal), 1e-12);
        cal.setUnit("pixel");
        assertTrue(Double.isNaN(ThreeDObjectAnalysis.processVoxelLengthScaleMicrons(cal)));
    }

    @Test public void physical3DShapeMeasurementsRejectUnsupportedXYAndMissingUnits() {
        assertTrue(SpatialAnalysis.supportsPhysical3DMorphometry(
                new CalibrationIO.PixelCalibration(2000, 2000, 4000, "nm")));
        assertFalse(SpatialAnalysis.supportsPhysical3DMorphometry(
                new CalibrationIO.PixelCalibration(2, 3, 4, "micron")));
        assertFalse(SpatialAnalysis.supportsPhysical3DMorphometry(
                new CalibrationIO.PixelCalibration(1, 1, 1, "pixel")));
        assertFalse(SpatialAnalysis.supportsPhysical3DMorphometry(
                new CalibrationIO.PixelCalibration(1, 1, Double.NaN, "micron")));
    }

    @Test public void lineDistanceScalesAxesBeforeProjectingInPhysicalSpace() throws Exception {
        // Point (0,1) to diagonal (0,0)-(1,1), with 2 x 3 micron pixels:
        // cross-product magnitude / segment length = 6 / sqrt(13).
        assertEquals(6 / Math.sqrt(13), lineDistance("micron", 2, 3,
                new float[]{0, 1}, new float[]{0, 1}, 0, 1), 1e-6);
    }

    @Test public void lineDistanceConvertsNanometresAndPreservesFractionalRoiVertices() throws Exception {
        assertEquals(1.5, lineDistance("nm", 2000, 3000,
                new float[]{0, 10}, new float[]{0.5f, 0.5f}, 3, 1), 1e-6);
    }

    @Test public void lineDistanceUsesValidXYWhenZCalibrationIsUnavailable() throws Exception {
        File root = temp.newFolder();
        File objects = FlashProjectLayout.forDirectory(root.getPath()).tablesObjectsWriteDir();
        assertTrue(objects.mkdirs());
        CalibrationIO.write(objects, 2, 3, Double.NaN, "micron");
        assertEquals(3, lineDistance(root, new float[]{0, 10}, new float[]{0, 0}, 3, 1), 1e-6);
    }

    @Test public void aggregationConvertsUnitsUsesYAxisScaleAndKeepsSourceLineage() throws Exception {
        File root = temp.newFolder();
        FlashProjectLayout layout = FlashProjectLayout.forDirectory(root.getPath());
        File objects = layout.tablesObjectsWriteDir();
        assertTrue(objects.mkdirs());
        CalibrationIO.write(objects, 2000, 3000, 4000, 20000, "nm");
        Files.write(new File(objects, "Marker.csv").toPath(), (
                "Animal Name,Region,ROI,SCN,Volume (micron^3),Surface (micron^2),IntDen,Mean,XM,YM,run_id,source_run_id\n"
                + "Mouse1,SCN,SCN1,1,24,1,2,2,3,4,update,original;earlier;original\n"
        ).getBytes(StandardCharsets.UTF_8));
        MasterAggregationAnalysis analysis = new MasterAggregationAnalysis();
        analysis.setSuppressDialogs(true);
        analysis.execute(root.getPath());
        CsvTableIO.ChannelData result = CsvTableIO.loadChannelCsv(
                new File(layout.tablesProjectSummaryWriteDir(), "3D Objects.csv"), "Marker");
        assertNotNull(result);
        assertEquals(6, result.getDouble(0, "Marker_RawXMMean"), 1e-6);
        assertEquals(12, result.getDouble(0, "Marker_RawYMMean"), 1e-6);
        String[] lineage = result.get(0, "source_run_id").split(";");
        assertEquals(3, lineage.length);
        assertTrue(Arrays.asList(lineage).containsAll(Arrays.asList("update", "original", "earlier")));
        assertFalse(result.colIdx.containsKey("Marker_source_run_idMean"));
    }

    @Test public void spatialCentroidColumnsAreMicronsEvenForNanometreMetadata() throws Exception {
        CsvTableIO.ChannelData data = new CsvTableIO.ChannelData("Marker",
                new ArrayList<String>(), new ArrayList<List<String>>(), new HashMap<String, Integer>());
        data.addColumn("XM");
        data.addColumn("YM");
        data.addColumn("ZM");
        data.rows.add(new ArrayList<String>(Arrays.asList("3", "4", "5")));
        Map<String, CsvTableIO.ChannelData> channels = new LinkedHashMap<String, CsvTableIO.ChannelData>();
        channels.put("Marker", data);
        Method append = SpatialAnalysis.class.getDeclaredMethod("appendCalibratedCentroids",
                Map.class, CalibrationIO.PixelCalibration.class);
        append.setAccessible(true);
        append.invoke(new SpatialAnalysis(), channels,
                new CalibrationIO.PixelCalibration(2000, 3000, 4000, "nm"));
        assertEquals(6, data.getDouble(0, "XM_um"), 1e-6);
        assertEquals(12, data.getDouble(0, "YM_um"), 1e-6);
        assertEquals(20, data.getDouble(0, "ZM_um"), 1e-6);
    }

    private double lineDistance(String unit, double sx, double sy,
                                float[] x, float[] y, double px, double py) throws Exception {
        File root = temp.newFolder();
        File objects = FlashProjectLayout.forDirectory(root.getPath()).tablesObjectsWriteDir();
        assertTrue(objects.mkdirs());
        CalibrationIO.write(objects, sx, sy, 1, unit);
        return lineDistance(root, x, y, px, py);
    }

    private double lineDistance(File root, float[] x, float[] y, double px, double py) throws Exception {
        File objects = FlashProjectLayout.forDirectory(root.getPath()).tablesObjectsWriteDir();
        Files.write(new File(objects, "Marker.csv").toPath(),
                ("Region,XM,YM\nSCN1," + px + "," + py + "\n").getBytes(StandardCharsets.UTF_8));
        File lines = LineDistanceAnalysis.lineSetWriteDir(root.getPath());
        assertTrue(lines.mkdirs());
        PolygonRoi roi = new PolygonRoi(x, y, x.length, Roi.POLYLINE);
        roi.setName("SCN1");
        ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        new RoiEncoder(encoded).write(roi);
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(new File(lines, "Boundary.zip").toPath()))) {
            zip.putNextEntry(new ZipEntry("0001.roi"));
            zip.write(encoded.toByteArray());
            zip.closeEntry();
        }
        new LineDistanceAnalysis().computeDistances(root.getPath(), lines, Arrays.asList("Boundary"));
        CsvTableIO.ChannelData result = CsvTableIO.loadChannelCsv(
                new File(LineDistanceAnalysis.lineDistanceOutputDir(root.getPath()), "Marker.csv"), "Marker");
        return result.getDouble(0, "Marker_DistTo_Boundary");
    }
}
