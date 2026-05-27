package flash.pipeline.decontamination;

import org.junit.Test;

import static org.junit.Assert.assertTrue;

public class SpectralDecontaminationAnalysisTest {

    @Test
    public void spectralDecontaminationDeclaresHeadedModeForMainGui() {
        assertTrue(new SpectralDecontaminationAnalysis().requiresHeadedMode());
    }
}
