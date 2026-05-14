package flash.pipeline.ui.variations;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

public class MacroVariationTest {

    @Test
    public void crlfAndTrailingWhitespaceDoNotChangeScriptIdentity() {
        String lf = "run(\"Gaussian Blur...\", \"sigma=2 stack\");\n"
                + "// keep comment";
        String crlf = "run(\"Gaussian Blur...\", \"sigma=2 stack\");   \r\n"
                + "// keep comment\t\r\n";

        MacroVariation first = MacroVariation.pasted("Blur", lf);
        MacroVariation second = MacroVariation.pasted("Blur", crlf);

        assertEquals(first.token(), second.token());
        assertEquals(first.normalizedScriptHash(),
                second.normalizedScriptHash());
        assertTrue(first.token().startsWith("macro:adhoc:"));
    }

    @Test
    public void differentScriptTextProducesDifferentIdentity() {
        MacroVariation sigmaTwo = MacroVariation.pasted("Blur 2",
                "run(\"Gaussian Blur...\", \"sigma=2 stack\");");
        MacroVariation sigmaThree = MacroVariation.pasted("Blur 3",
                "run(\"Gaussian Blur...\", \"sigma=3 stack\");");

        assertNotEquals(sigmaTwo.token(), sigmaThree.token());
        assertNotEquals(sigmaTwo.normalizedScriptHash(),
                sigmaThree.normalizedScriptHash());
    }

    @Test
    public void noOpMacroIsReservedAndResolvable() {
        MacroVariationSet set = MacroVariationSet.of(
                MacroVariation.pasted("Blank", "  \r\n\t "));

        MacroVariation noOp = set.resolve(MacroToken.NONE_VALUE);

        assertEquals(MacroToken.NONE_VALUE, noOp.token());
        assertEquals("", noOp.scriptText());
        assertTrue(set.tokens().contains(MacroToken.NONE_VALUE));
    }

    @Test
    public void macroAndModelAreCategoricalNonOrderableAxes() {
        assertFalse(ParameterId.MODEL.orderable());
        assertFalse(ParameterId.MACRO.orderable());
        assertEquals(ParameterKey.ValueKind.STRING, ParameterId.MACRO.valueKind());
    }

    @Test
    public void macroChipPanelReusesExistingTokenForSameScript() {
        String script = "run(\"Gaussian Blur...\", \"sigma=2 stack\");";
        MacroVariation first = MacroVariation.pasted("Blur", script);
        MacroVariation duplicate = MacroVariation.pasted("Blur copy",
                script + "\r\n");
        MacroVariationChipPanel panel = new MacroVariationChipPanel(
                ParameterValueList.ofStrings(MacroToken.NONE_VALUE),
                MacroVariationCatalog.empty());

        panel.addVariationForTest(first);
        panel.addVariationForTest(duplicate);

        assertEquals(2, panel.currentValueList().size());
        assertEquals(first.token(), panel.currentValueList().get(1));
    }
}
