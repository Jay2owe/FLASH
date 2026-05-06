package flash.pipeline.io;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.PrintWriter;

import static org.junit.Assert.*;

public class CalibrationIOTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void read_missingCalibration_returnsNull() {
        File objectsDir = new File(temp.getRoot(), "objects");
        assertTrue(objectsDir.mkdirs());

        assertNull(CalibrationIO.read(objectsDir));
    }

    @Test
    public void writeAndRead_roundTripsCalibration() {
        File objectsDir = new File(temp.getRoot(), "objects");
        assertTrue(objectsDir.mkdirs());

        CalibrationIO.write(objectsDir, 1.5, 2.5, 3.5, 14.0, "um");
        CalibrationIO.PixelCalibration cal = CalibrationIO.read(objectsDir);

        assertNotNull(cal);
        assertEquals(1.5, cal.pixelWidth, 0.0);
        assertEquals(2.5, cal.pixelHeight, 0.0);
        assertEquals(3.5, cal.pixelDepth, 0.0);
        assertEquals(14.0, cal.stackDepth, 0.0);
        assertEquals("um", cal.unit);
        assertTrue(cal.isCalibrated());
        assertTrue(cal.hasStackDepth());
    }

    @Test
    public void read_legacyCalibrationWithoutStackDepth_remainsReadable() {
        File objectsDir = new File(temp.getRoot(), "objects");
        assertTrue(objectsDir.mkdirs());

        CalibrationIO.write(objectsDir, 1.5, 2.5, 3.5, "um");
        CalibrationIO.PixelCalibration cal = CalibrationIO.read(objectsDir);

        assertNotNull(cal);
        assertEquals(1.5, cal.pixelWidth, 0.0);
        assertEquals(2.5, cal.pixelHeight, 0.0);
        assertEquals(3.5, cal.pixelDepth, 0.0);
        assertTrue(Double.isNaN(cal.stackDepth));
        assertFalse(cal.hasStackDepth());
    }

    @Test
    public void read_malformedCalibration_returnsNull() throws Exception {
        File objectsDir = new File(temp.getRoot(), "objects");
        assertTrue(objectsDir.mkdirs());

        File malformed = new File(objectsDir, "calibration.properties");
        PrintWriter pw = new PrintWriter(malformed, "UTF-8");
        try {
            pw.println("pixelWidth=abc");
            pw.println("pixelHeight=2.0");
            pw.println("pixelDepth=3.0");
            pw.println("unit=um");
        } finally {
            pw.close();
        }

        assertNull(CalibrationIO.read(objectsDir));
    }

    @Test
    public void readFromDirectory_prefersFlashObjectCalibration() throws Exception {
        File project = temp.newFolder("project-new-calibration");
        File legacyObjects = new File(project, "Data Analysis/Objects");
        File flashObjects = FlashProjectLayout.forDirectory(project.getAbsolutePath()).objectDataWriteDir();
        assertTrue(legacyObjects.mkdirs());
        assertTrue(flashObjects.mkdirs());

        CalibrationIO.write(legacyObjects, 1.0, 1.0, 1.0, "pixel");
        CalibrationIO.write(flashObjects, 2.0, 3.0, 4.0, 20.0, "um");

        CalibrationIO.PixelCalibration cal = CalibrationIO.readFromDirectory(project.getAbsolutePath());

        assertNotNull(cal);
        assertEquals(2.0, cal.pixelWidth, 0.0);
        assertEquals(3.0, cal.pixelHeight, 0.0);
        assertEquals(4.0, cal.pixelDepth, 0.0);
        assertEquals(20.0, cal.stackDepth, 0.0);
        assertEquals("um", cal.unit);
    }

    @Test
    public void readFromDirectory_fallsBackToLegacyObjectCalibration() throws Exception {
        File project = temp.newFolder("project-legacy-calibration");
        File legacyObjects = new File(project, "Data Analysis/Objects");
        assertTrue(legacyObjects.mkdirs());

        CalibrationIO.write(legacyObjects, 1.25, 1.5, 2.5, "um");

        CalibrationIO.PixelCalibration cal = CalibrationIO.readFromDirectory(project.getAbsolutePath());

        assertNotNull(cal);
        assertEquals(1.25, cal.pixelWidth, 0.0);
        assertEquals(1.5, cal.pixelHeight, 0.0);
        assertEquals(2.5, cal.pixelDepth, 0.0);
        assertEquals("um", cal.unit);
    }
}
