package flash.pipeline.ui.variations;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

public class PresetsModeSweepBuildTest {

    @Test
    public void buildsOneComboPerPresetWithoutXSubstitution() {
        ParameterSweep sweep = MacroVariationsDialog.buildPresetsSweepForTest(
                Arrays.asList("Default", "Punctate", "Clustered Large"),
                CropSpec.full(),
                "DAPI",
                "source-a",
                "filter:macro:presets");

        assertEquals(3L, sweep.cellCount());
        assertEquals(1, sweep.valueLists().size());
        List<ParameterCombo> combos = sweep.combos();
        assertEquals(3, combos.size());
        PresetSweepCombo decoded = PresetSweepCombo.from(combos.get(0));
        assertNotNull(decoded);
        assertEquals("Default", decoded.presetName());
        assertNull(decoded.xParamKey());
        assertNull(decoded.xValue());
    }
}
