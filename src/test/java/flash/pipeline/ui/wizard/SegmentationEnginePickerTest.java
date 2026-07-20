package flash.pipeline.ui.wizard;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class SegmentationEnginePickerTest {

    @Test
    public void decisionTableRows() {
        avail(false, false);
        assertPick("puncta_like", true, "crowded", true, true, SegmentationEnginePicker.Engine.Classical);
        assertPick("diffuse", true, "crowded", true, true, SegmentationEnginePicker.Engine.Classical);
        assertPick("round", false, "crowded", true, true, SegmentationEnginePicker.Engine.Classical);
        assertPick("complex", false, "crowded", true, true, SegmentationEnginePicker.Engine.Classical);
        assertPick("round", true, "sparse", true, true, SegmentationEnginePicker.Engine.Classical);
        assertPick("round", true, "crowded", true, false, SegmentationEnginePicker.Engine.StarDist);
        assertPick("round", true, "crowded", false, false, SegmentationEnginePicker.Engine.Classical);
        assertPick("complex", true, "sparse", true, true, SegmentationEnginePicker.Engine.Classical);
        assertPick("complex", true, "crowded", true, true, SegmentationEnginePicker.Engine.Cellpose);
        assertPick("complex", true, "crowded", true, false, SegmentationEnginePicker.Engine.StarDist);
        assertPick("complex", true, "crowded", false, false, SegmentationEnginePicker.Engine.Classical);
        assertPick("round", true, null, true, false, false, SegmentationEnginePicker.Engine.Classical);
        assertPick("complex", true, null, true, true, true, SegmentationEnginePicker.Engine.Cellpose);
    }

    @Test
    public void complexCrowdedFallbacksWarn() {
        RecordingWarnings warnings = new RecordingWarnings();

        SegmentationEnginePicker.Engine degraded = SegmentationEnginePicker.pick("complex", true, "crowded",
                avail(true, false), true, warnings);
        SegmentationEnginePicker.Engine fallback = SegmentationEnginePicker.pick("complex", true, "crowded",
                avail(false, false), true, warnings);

        assertEquals(SegmentationEnginePicker.Engine.StarDist, degraded);
        assertEquals(SegmentationEnginePicker.Engine.Classical, fallback);
        assertEquals(2, warnings.messages.size());
        assertTrue(warnings.messages.get(0).contains("Cellpose"));
        assertTrue(warnings.messages.get(1).contains("falling back"));
    }

    @Test
    public void nullAndEmptyInputsUseClassical() {
        assertEquals(SegmentationEnginePicker.Engine.Classical,
                SegmentationEnginePicker.pick((MarkerLibrary.Entry) null, "crowded", avail(true, true)));
        assertPick("", true, "crowded", true, true, SegmentationEnginePicker.Engine.Classical);
    }

    private static void assertPick(String shape,
                                   boolean crowdingSensitive,
                                   String answer,
                                   boolean stardist,
                                   boolean cellpose,
                                   SegmentationEnginePicker.Engine expected) {
        assertEquals(expected, SegmentationEnginePicker.pick(shape, crowdingSensitive, answer,
                avail(stardist, cellpose), false, new RecordingWarnings()));
    }

    private static void assertPick(String shape,
                                   boolean crowdingSensitive,
                                   String answer,
                                   boolean stardist,
                                   boolean cellpose,
                                   boolean crowdedByDefault,
                                   SegmentationEnginePicker.Engine expected) {
        assertEquals(expected, SegmentationEnginePicker.pick(shape, crowdingSensitive, answer,
                avail(stardist, cellpose), crowdedByDefault, new RecordingWarnings()));
    }

    private static SegmentationEnginePicker.EngineAvailability avail(boolean stardist, boolean cellpose) {
        return new SegmentationEnginePicker.EngineAvailability(stardist, cellpose);
    }

    private static final class RecordingWarnings implements SegmentationEnginePicker.WarningSink {
        final List<String> messages = new ArrayList<String>();

        @Override
        public void warn(String message) {
            messages.add(message);
        }
    }
}
