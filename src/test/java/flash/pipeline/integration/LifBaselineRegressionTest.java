package flash.pipeline.integration;

import ij.IJ;
import flash.pipeline.io.DeferredImageSupplier;
import flash.pipeline.io.ImageSourceDispatcher;
import flash.pipeline.io.LifIO;
import flash.pipeline.io.SeriesMeta;
import org.junit.Ignore;
import org.junit.Test;

import java.io.File;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

/**
 * Baseline regression check for LIF dispatch.
 * <p>
 * This repository currently has no LIF fixture under src/test/resources, so
 * the test is ignored rather than fabricating a non-LIF placeholder. The
 * comparison logic is still present: remove {@link Ignore} after adding a
 * small .lif fixture and the dispatcher will be checked against LifIO for
 * series count and first-series metadata shape.
 */
public class LifBaselineRegressionTest {

    @Ignore("No LIF fixture exists under src/test/resources; TODO: add a small .lif fixture to enable this regression.")
    @Test
    public void dispatcherMatchesLifIoForExistingLifFixture() throws Exception {
        File lif = findFirstLifFixture(new File("src/test/resources"));
        if (lif == null) {
            IJ.log("TODO: LifBaselineRegressionTest skipped because no .lif fixture exists under src/test/resources.");
            return;
        }

        DeferredImageSupplier supplier =
                ImageSourceDispatcher.createSupplier(lif.getParentFile().getAbsolutePath());
        assertEquals(LifIO.openAllImages(lif).size(), supplier.getTotalSeries());

        List<SeriesMeta> lifMetas = LifIO.readAllSeriesMetadata(lif);
        List<SeriesMeta> dispatcherMetas =
                ImageSourceDispatcher.readAllMetadata(lif.getParentFile().getAbsolutePath());

        assertNotNull(lifMetas);
        assertNotNull(dispatcherMetas);
        assertEquals(lifMetas.size(), dispatcherMetas.size());
        assertMetadataShapeEquals(lifMetas.get(0), dispatcherMetas.get(0));
    }

    @Test
    public void noLifFixtureLogsTodoForIgnoredBaseline() {
        if (findFirstLifFixture(new File("src/test/resources")) == null) {
            IJ.log("TODO: LifBaselineRegressionTest comparison is @Ignore because no .lif fixture exists under src/test/resources.");
        }
    }

    private static void assertMetadataShapeEquals(SeriesMeta expected, SeriesMeta actual) {
        assertEquals(expected.index, actual.index);
        assertEquals(expected.name, actual.name);
        assertEquals(expected.nSlices, actual.nSlices);
        assertEquals(expected.pixelWidth, actual.pixelWidth, 0.000001);
        assertEquals(expected.pixelHeight, actual.pixelHeight, 0.000001);
        assertEquals(expected.pixelDepth, actual.pixelDepth, 0.000001);
        assertEquals(expected.unit, actual.unit);
    }

    private static File findFirstLifFixture(File dir) {
        if (dir == null || !dir.exists()) return null;
        File[] files = dir.listFiles();
        if (files == null) return null;
        for (File file : files) {
            if (file.isDirectory()) {
                File nested = findFirstLifFixture(file);
                if (nested != null) return nested;
            } else if (file.getName().toLowerCase().endsWith(".lif")) {
                return file;
            }
        }
        return null;
    }
}
