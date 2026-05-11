package flash.pipeline.ui.config;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class ConfigQcContextTest {

    @Test
    public void shortDisplayNameDropsContainerPrefix() {
        assertEquals("Mouse1_LH_SCN",
                ConfigQcContext.shortDisplayName("Experiment.lif - Mouse1_LH_SCN"));
        assertEquals("Mouse2_RH_CA1",
                ConfigQcContext.shortDisplayName("Experiment.lif :: Mouse2_RH_CA1"));
    }
}
