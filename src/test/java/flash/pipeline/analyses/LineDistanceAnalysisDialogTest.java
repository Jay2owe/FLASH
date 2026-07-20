package flash.pipeline.analyses;

import flash.pipeline.lines.LineVocab;
import org.junit.Test;

import javax.swing.JComboBox;
import javax.swing.JTextField;

import static org.junit.Assert.assertEquals;

/**
 * Unit tests for the inline landmark name resolver used by the
 * Line Distance Analysis main dialog.
 */
public class LineDistanceAnalysisDialogTest {

    @Test
    public void resolveTargetNameReturnsDropdownLabelWhenNotCustom() {
        JComboBox<String> combo = new JComboBox<String>(new String[]{
                "Ventricle wall", LineVocab.CUSTOM_LABEL });
        combo.setSelectedItem("Ventricle wall");
        JTextField field = new JTextField("ignored-because-not-custom");

        assertEquals("Ventricle wall",
                LineDistanceAnalysis.resolveTargetName(combo, field));
    }

    @Test
    public void resolveTargetNameUsesCustomFieldWhenSentinelSelected() {
        JComboBox<String> combo = new JComboBox<String>(new String[]{
                "Ventricle wall", LineVocab.CUSTOM_LABEL });
        combo.setSelectedItem(LineVocab.CUSTOM_LABEL);
        JTextField field = new JTextField("MyOddName");

        assertEquals("MyOddName",
                LineDistanceAnalysis.resolveTargetName(combo, field));
    }

    @Test
    public void resolveTargetNameTrimsWhitespace() {
        JComboBox<String> combo = new JComboBox<String>(new String[]{
                "Ventricle wall", LineVocab.CUSTOM_LABEL });
        combo.setSelectedItem(LineVocab.CUSTOM_LABEL);
        JTextField field = new JTextField("   trimmed   ");
        assertEquals("trimmed",
                LineDistanceAnalysis.resolveTargetName(combo, field));
    }

    @Test
    public void resolveTargetNameEmptyWhenCustomFieldBlank() {
        JComboBox<String> combo = new JComboBox<String>(new String[]{
                LineVocab.CUSTOM_LABEL });
        combo.setSelectedItem(LineVocab.CUSTOM_LABEL);
        JTextField field = new JTextField("");
        assertEquals("", LineDistanceAnalysis.resolveTargetName(combo, field));
    }
}
