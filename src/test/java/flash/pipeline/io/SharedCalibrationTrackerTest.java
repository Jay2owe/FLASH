package flash.pipeline.io;

import ij.ImagePlus;
import ij.ImageStack;
import ij.measure.Calibration;
import ij.process.ByteProcessor;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.Assert.*;

public class SharedCalibrationTrackerTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void nanometresAndMicrometresRegisterAsTheSamePhysicalScale() throws Exception {
        File output = temp.newFolder("equivalent");
        CalibrationIO.SharedCalibrationTracker tracker = new CalibrationIO.SharedCalibrationTracker();
        assertTrue(tracker.register(output, image("nm", 500, 750, 2000, 4)));
        assertFalse(tracker.register(output, image("um", 0.5, 0.75, 2, 4)));
        CalibrationIO.PixelCalibration saved = CalibrationIO.read(output);
        assertEquals("um", saved.unit);
        assertEquals(0.5, saved.pixelWidth, 1e-12);
        assertEquals(0.75, saved.pixelHeight, 1e-12);
        assertEquals(2, saved.pixelDepth, 1e-12);
        assertEquals(8, saved.stackDepth, 1e-12);
    }

    @Test
    public void varyingStackDepthRemovesOnlyTheSharedDepthAndCannotRestoreIt() throws Exception {
        File output = temp.newFolder("depths");
        CalibrationIO.SharedCalibrationTracker tracker = new CalibrationIO.SharedCalibrationTracker();
        tracker.register(output, image("um", 0.5, 0.75, 2, 4));
        assertTrue(tracker.register(output, image("um", 0.5, 0.75, 2, 7)));
        assertFalse(tracker.register(output, image("um", 0.5, 0.75, 2, 4)));
        CalibrationIO.PixelCalibration saved = CalibrationIO.read(output);
        assertTrue(saved.isCalibrated());
        assertFalse(saved.hasStackDepth());
        assertTrue(Double.isNaN(saved.stackDepth));
        assertEquals(2, saved.pixelDepth, 0);
    }

    @Test
    public void mixedScalesInvalidatePersistedCalibrationAndCannotBeOverwrittenWithinTheRun() throws Exception {
        File output = temp.newFolder("mixed");
        CalibrationIO.SharedCalibrationTracker tracker = new CalibrationIO.SharedCalibrationTracker();
        ImagePlus first = image("um", 0.5, 0.75, 2, 4);
        tracker.register(output, first);
        try {
            tracker.register(output, image("um", 0.6, 0.75, 2, 4));
            fail("mixed scales must fail");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("Mixed image calibration"));
        }
        assertInvalid(output);
        try {
            tracker.register(output, first);
            fail("a later worker must not restore invalidated metadata");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("Mixed image calibration"));
        }
        assertInvalid(output);
        String invalidContents = new String(Files.readAllBytes(new File(output,
                "calibration.properties").toPath()), StandardCharsets.UTF_8);
        assertTrue(invalidContents.contains("pixelWidth=NaN"));
    }

    @Test
    public void nonfiniteAndNonpositiveScaleInvalidateEvenPreviouslyValidMetadata() throws Exception {
        for (double invalid : new double[]{Double.NaN, Double.POSITIVE_INFINITY, 0, -1}) {
            File output = temp.newFolder();
            CalibrationIO.SharedCalibrationTracker tracker = new CalibrationIO.SharedCalibrationTracker();
            tracker.register(output, image("um", 0.5, 0.75, 2, 4));
            try {
                tracker.register(output, image("um", invalid, 0.75, 2, 4));
                fail("invalid physical scale must fail");
            } catch (IllegalStateException expected) {
                assertTrue(expected.getMessage().contains("invalid or unknown-unit"));
            }
            assertInvalid(output);
        }
    }

    @Test
    public void homogeneousPixelCalibrationPreservesCountingWithoutInventingPhysicalScale() throws Exception {
        File output = temp.newFolder("pixels");
        CalibrationIO.SharedCalibrationTracker tracker = new CalibrationIO.SharedCalibrationTracker();
        assertTrue(tracker.register(output, image("pixel", 1, 1, 1, 4)));
        assertFalse(tracker.register(output, image("pixel", 1, 1, 1, 4)));
        CalibrationIO.PixelCalibration saved = CalibrationIO.read(output);
        assertEquals("pixel", saved.unit);
        assertFalse(saved.isCalibrated());
        assertTrue(saved.canonical().x().isPixelUnit());
        assertEquals(1, saved.pixelWidth, 0);
    }

    @Test
    public void mixingPixelAndPhysicalCalibrationFailsEvenWithEqualNumericScales() throws Exception {
        File output = temp.newFolder("pixel-physical");
        CalibrationIO.SharedCalibrationTracker tracker = new CalibrationIO.SharedCalibrationTracker();
        tracker.register(output, image("pixel", 1, 1, 1, 4));
        try {
            tracker.register(output, image("um", 1, 1, 1, 4));
            fail("pixel and physical measurements cannot share metadata");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("Mixed image calibration"));
        }
        assertInvalid(output);
    }

    @Test
    public void extendingEarlierMeasurementsChecksThePersistedPhysicalScale() throws Exception {
        File output = temp.newFolder("existing-measurements");
        CalibrationIO.write(output, 500, 750, 2000, 8000, "nm");
        CalibrationIO.SharedCalibrationTracker tracker = new CalibrationIO.SharedCalibrationTracker();
        tracker.seedFromExisting(output);
        assertFalse(tracker.register(output, image("um", 0.5, 0.75, 2, 4)));
        try {
            tracker.register(output, image("um", 0.6, 0.75, 2, 4));
            fail("existing measurement scales must not be reinterpreted");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("Mixed image calibration"));
        }
        assertInvalid(output);
    }

    @Test
    public void extendingLegacyUnknownStackDepthDoesNotInventACommonDepth() throws Exception {
        File output = temp.newFolder("unknown-old-depth");
        CalibrationIO.write(output, 0.5, 0.75, 2, "um");
        CalibrationIO.SharedCalibrationTracker tracker = new CalibrationIO.SharedCalibrationTracker();
        tracker.seedFromExisting(output);
        assertFalse(tracker.register(output, image("um", 0.5, 0.75, 2, 4)));
        assertTrue(CalibrationIO.read(output).isCalibrated());
        assertFalse(CalibrationIO.read(output).hasStackDepth());
    }

    @Test
    public void extendingMeasurementsWithoutOldCalibrationFailsClosed() throws Exception {
        File output = temp.newFolder("missing-old-calibration");
        try {
            new CalibrationIO.SharedCalibrationTracker().seedFromExisting(output);
            fail("existing measurements without known calibration cannot be extended safely");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("Cannot extend existing measurements"));
        }
        assertInvalid(output);
    }

    @Test
    public void unknownUnitsAndInvalidPixelScalesAreRejected() throws Exception {
        for (ImagePlus invalid : new ImagePlus[]{image("unknown-unit", 1, 1, 1, 4),
                image("pixel", Double.NaN, 1, 1, 4), image("pixel", -1, 1, 1, 4)}) {
            File output = temp.newFolder();
            try {
                new CalibrationIO.SharedCalibrationTracker().register(output, invalid);
                fail("unsupported or invalid calibration must fail");
            } catch (IllegalStateException expected) {
                assertTrue(expected.getMessage().contains("invalid or unknown-unit"));
            }
            assertInvalid(output);
        }
    }

    @Test
    public void aNewInvocationStartsWithFreshCalibrationAndStackDepthState() throws Exception {
        File output = temp.newFolder("runs");
        CalibrationIO.SharedCalibrationTracker earlier = new CalibrationIO.SharedCalibrationTracker();
        earlier.register(output, image("um", 0.5, 0.75, 2, 4));
        earlier.register(output, image("um", 0.5, 0.75, 2, 7));
        new CalibrationIO.SharedCalibrationTracker().register(output, image("um", 1, 1, 3, 5));
        CalibrationIO.PixelCalibration saved = CalibrationIO.read(output);
        assertTrue(saved.hasStackDepth());
        assertEquals(1, saved.pixelWidth, 0);
        assertEquals(15, saved.stackDepth, 0);
    }

    @Test
    public void concurrentRegistrationCannotRacePastMixedCalibrationInvalidation() throws Exception {
        File output = temp.newFolder("parallel");
        CalibrationIO.SharedCalibrationTracker tracker = new CalibrationIO.SharedCalibrationTracker();
        ExecutorService workers = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<Boolean> first = workers.submit(() -> registerAfter(start, tracker, output, 0.5));
            Future<Boolean> second = workers.submit(() -> registerAfter(start, tracker, output, 0.75));
            start.countDown();
            assertNotEquals(first.get(), second.get());
            assertInvalid(output);
        } finally {
            workers.shutdownNow();
        }
    }

    @Test
    public void infinitePersistedStackDepthIsNotUsable() {
        assertFalse(new CalibrationIO.PixelCalibration(1, 1, 1,
                Double.POSITIVE_INFINITY, "um").hasStackDepth());
    }

    private static boolean registerAfter(CountDownLatch start,
                                          CalibrationIO.SharedCalibrationTracker tracker,
                                          File output, double scale) throws Exception {
        start.await();
        try {
            tracker.register(output, image("um", scale, scale, 2, 4));
            return true;
        } catch (IllegalStateException expected) {
            return false;
        }
    }

    private static void assertInvalid(File output) {
        try {
            CalibrationIO.read(output);
            fail("downstream readers must reject the invalid shared calibration marker");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("Shared image calibration is invalid"));
        }
    }

    private static ImagePlus image(String unit, double x, double y, double z, int slices) {
        ImageStack stack = new ImageStack(1, 1);
        for (int index = 0; index < slices; index++) stack.addSlice(new ByteProcessor(1, 1));
        ImagePlus image = new ImagePlus("sample", stack);
        Calibration cal = new Calibration();
        cal.setUnit(unit);
        cal.pixelWidth = x;
        cal.pixelHeight = y;
        cal.pixelDepth = z;
        image.setCalibration(cal);
        return image;
    }
}
