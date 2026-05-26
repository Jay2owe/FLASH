package flash.pipeline.intensity.spatial;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class IntensitySpatialOverlayWriterTest {
    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void overlayFileUsesIntensitySpatialOverlayRoot() throws Exception {
        File project = temp.newFolder("project");
        IntensitySpatialOverlayWriter writer = new IntensitySpatialOverlayWriter();

        File overlay = writer.overlayFile(project, "Mouse 1", "SCN1",
                "LH ROI", "DAPI", "HOTSPOTSCAN");

        assertEquals(new File(project,
                "FLASH/Results/Analysis Images/Intensity Overlays/Mouse_1/"
                        + "SCN1_LH_ROI_DAPI_HOTSPOTSCAN.png").getAbsolutePath(),
                overlay.getAbsolutePath());
    }

    @Test
    public void writeFailureIsCollectedAndDoesNotThrow() throws Exception {
        File project = temp.newFolder("project");
        IntensitySpatialOverlayWriter writer = new IntensitySpatialOverlayWriter();
        final AtomicReference<File> failedFile = new AtomicReference<File>();
        final AtomicReference<Exception> failure = new AtomicReference<Exception>();

        boolean written = writer.writePngBestEffort(project, "Mouse", "SCN1",
                "LH", "DAPI", "HOTSPOTSCAN", (java.awt.image.BufferedImage) null,
                new IntensitySpatialOverlayWriter.FailureSink() {
                    @Override
                    public void accept(File target, Exception exception) {
                        failedFile.set(target);
                        failure.set(exception);
                    }
                });

        assertFalse(written);
        assertTrue(failedFile.get().getName().endsWith("_HOTSPOTSCAN.png"));
        assertTrue(failure.get().getMessage().contains("null"));
    }
}
