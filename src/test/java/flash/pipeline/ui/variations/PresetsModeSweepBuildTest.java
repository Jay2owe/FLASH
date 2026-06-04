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
        List<ParameterCombo> combos = sweep.combos();
        assertEquals(3, combos.size());
        PresetSweepCombo decoded = PresetSweepCombo.from(combos.get(0));
        assertNotNull(decoded);
        assertEquals("Default", decoded.presetName());
        assertNull(decoded.xParamKey());
        assertNull(decoded.xValue());
    }

    @Test
    public void buildsPresetByXAxisSweepWithSharedNumericValues() {
        ParameterSweep sweep = MacroVariationsDialog.buildPresetsSweepForTest(
                Arrays.asList("Default", "Punctate", "Clustered Large"),
                "sigma",
                Arrays.<Object>asList(Double.valueOf(1.0d),
                        Double.valueOf(2.0d), Double.valueOf(3.0d)),
                CropSpec.full(),
                "DAPI",
                "source-a",
                "filter:macro:presets");

        assertEquals(9L, sweep.cellCount());
        List<ParameterCombo> combos = sweep.combos();
        assertEquals(9, combos.size());

        PresetSweepCombo first = PresetSweepCombo.from(combos.get(0));
        assertNotNull(first);
        assertEquals("Default", first.presetName());
        assertEquals("sigma", first.xParamKey());
        assertEquals(Double.valueOf(1.0d), first.xValue());

        PresetSweepCombo last = PresetSweepCombo.from(combos.get(combos.size() - 1));
        assertNotNull(last);
        assertEquals("Clustered Large", last.presetName());
        assertEquals("sigma", last.xParamKey());
        assertEquals(Double.valueOf(3.0d), last.xValue());
    }
}
