package flash.pipeline.deconv.qc;

import org.junit.After;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class DeconvPreviewDialogTest {

    @After
    public void tearDown() {
        DeconvPreviewDialog.resetForTest();
    }

    @Test
    public void headlessModeReturnsRunFullBatchWithoutShowingDialog() {
        DeconvPreviewDialog.setHeadlessProbeForTest(new DeconvPreviewDialog.HeadlessProbe() {
            @Override
            public boolean isHeadless() {
                return true;
            }
        });

        assertEquals(DeconvPreviewDialog.Decision.RUN_FULL_BATCH,
                DeconvPreviewDialog.show(null, false));
    }

    @Test
    public void skipPreviewReturnsRunFullBatch() {
        DeconvPreviewDialog.setHeadlessProbeForTest(new DeconvPreviewDialog.HeadlessProbe() {
            @Override
            public boolean isHeadless() {
                return false;
            }
        });

        assertEquals(DeconvPreviewDialog.Decision.RUN_FULL_BATCH,
                DeconvPreviewDialog.show(null, true));
    }
}
