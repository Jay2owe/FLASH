package flash.pipeline.ui.variations;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

public class PresetsModeSweepBuildTest {

    @Test
    public void buildsPresetByXValueCartesianSweep() {
        ParameterSweep sweep = MacroVariationsDialog.buildPresetsSweepForTest(
                Arrays.asList("Default", "Punctate", "Clustered Large"),
                "sigma",
                Arrays.<Object>asList(Double.valueOf(0.5d),
                        Double.valueOf(1.0d),
                        Double.valueOf(2.0d)),
                CropSpec.full(),
                "DAPI",
                "source-a",
                "filter:macro:presets");

        assertEquals(9L, sweep.cellCount());
        List<ParameterCombo> combos = sweep.combos();
        PresetSweepCombo decoded = PresetSweepCombo.from(combos.get(0));
        assertNotNull(decoded);
        assertEquals("sigma", decoded.xParamKey());
        assertEquals(Double.valueOf(0.5d), decoded.xValue());
    }
}
