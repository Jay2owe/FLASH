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

    @Test
    public void recorderDiffWithoutRunLineIsTreatedAsCancelled() {
        RecorderParameterProbe.ProbeResult result =
                RecorderParameterProbe.parseLastRunLine("// non-run macro text\nprint(\"hello\");\n",
                        "Plugin Filter");

        assertEquals(true, result.userCancelled);
        assertEquals("Plugin Filter", result.commandName);
        assertEquals("", result.optionsString);
        assertEquals("", result.errorMessage);
    }

    @Test
    public void parsesEscapedRunLineTextWithoutSplittingOptions() {
        String recorderText = "run(\"Quoted \\\"Command\\\"\", "
                + "\"label=[value with spaces] note=alpha\\\"beta stack\");\n";

        RecorderParameterProbe.ProbeResult result =
                RecorderParameterProbe.parseLastRunLine(recorderText, "Quoted \"Command\"");
        List<FilterMacroEditorModel.Parameter> params =
                RecorderParameterProbe.parseOptions(result.optionsString);

        assertFalse(result.userCancelled);
        assertEquals("Quoted \"Command\"", result.commandName);
        assertEquals("label=[value with spaces] note=alpha\"beta stack", result.optionsString);
        assertEquals(2, params.size());
        assertEquals("label", params.get(0).key);
        assertEquals("[value with spaces]", params.get(0).getValue());
        assertEquals("note", params.get(1).key);
        assertEquals("alpha\"beta", params.get(1).getValue());
    }
}
