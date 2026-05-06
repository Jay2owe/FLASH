package flash.pipeline.ui.sandbox;

import flash.pipeline.image.FilterMacroEditorModel;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

public class RecorderParameterProbeTest {

    @Test
    public void parsesLastRecordedRunLineOptions() {
        String recorderText = ""
                + "run(\"Earlier\", \"value=1\");\n"
                + "run(\"Test Macro Command\", \"radius=5 method=[Default dark] stack\");\n";

        RecorderParameterProbe.ProbeResult result =
                RecorderParameterProbe.parseLastRunLine(recorderText, "Test Macro Command");
        List<FilterMacroEditorModel.Parameter> params =
                RecorderParameterProbe.parseOptions(result.optionsString);

        assertFalse(result.userCancelled);
        assertEquals("Test Macro Command", result.commandName);
        assertEquals("radius=5 method=[Default dark] stack", result.optionsString);
        assertEquals(2, params.size());
        assertEquals("radius", params.get(0).key);
        assertEquals("5", params.get(0).getValue());
        assertEquals("method", params.get(1).key);
        assertEquals("[Default dark]", params.get(1).getValue());
    }

    @Test
    public void commandWithoutDialogRecordsEmptyOptions() {
        RecorderParameterProbe.ProbeResult result =
                RecorderParameterProbe.parseLastRunLine("run(\"Invert\");\n", "Invert");

        assertFalse(result.userCancelled);
        assertEquals("Invert", result.commandName);
        assertEquals("", result.optionsString);
        assertEquals(0, RecorderParameterProbe.parseOptions(result.optionsString).size());
    }
}
