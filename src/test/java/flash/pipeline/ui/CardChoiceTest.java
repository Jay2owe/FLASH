package flash.pipeline.ui;

import org.junit.Test;

import javax.swing.JPanel;
import java.awt.Component;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Locks the drop-in contract that lets {@link CardChoice} replace a
 * {@code PipelineDialog.addChoice} dropdown: the backing combo is the single
 * source of truth in both directions, so {@code getNextChoice()} and any
 * existing combo listeners keep working unchanged.
 */
public class CardChoiceTest {

    private static CardChoice.Option[] threeOptions() {
        return new CardChoice.Option[]{
                new CardChoice.Option("none", "None", "Show as stored", "close-x"),
                new CardChoice.Option("auto", "Automatic", "Auto B&C", "wand", "Default"),
                new CardChoice.Option("manual", "Manual", "Set B&C yourself", "sliders"),
        };
    }

    @Test
    public void defaultValueIsSelectedInBackingCombo() {
        CardChoice cc = new CardChoice(threeOptions(), "auto");
        assertEquals("auto", cc.getSelectedValue());
        assertEquals("auto", cc.comboBox().getSelectedItem());
    }

    @Test
    public void unknownDefaultFallsBackToFirstOption() {
        CardChoice cc = new CardChoice(threeOptions(), "does-not-exist");
        assertEquals("none", cc.getSelectedValue());
    }

    @Test
    public void setSelectedValueUpdatesComboAndCards() {
        CardChoice cc = new CardChoice(threeOptions(), "none");
        cc.setSelectedValue("manual");
        assertEquals("manual", cc.getSelectedValue());
        assertEquals("manual", cc.comboBox().getSelectedItem());
    }

    @Test
    public void externalComboSetSyncsBackToSelection() {
        // External code (e.g. "load settings from previous run") drives the combo.
        CardChoice cc = new CardChoice(threeOptions(), "none");
        cc.comboBox().setSelectedItem("auto");
        assertEquals("auto", cc.getSelectedValue());
    }

    @Test
    public void selectionListenerFiresOnChange() {
        CardChoice cc = new CardChoice(threeOptions(), "none");
        final AtomicInteger hits = new AtomicInteger(0);
        cc.addSelectionListener(new Runnable() {
            @Override public void run() {
                hits.incrementAndGet();
            }
        });
        cc.setSelectedValue("manual");
        assertEquals(1, hits.get());
    }

    @Test
    public void reselectingSameValueDoesNotFire() {
        CardChoice cc = new CardChoice(threeOptions(), "auto");
        final AtomicInteger hits = new AtomicInteger(0);
        cc.addSelectionListener(new Runnable() {
            @Override public void run() {
                hits.incrementAndGet();
            }
        });
        cc.setSelectedValue("auto"); // already selected — no-op, like the combo
        assertEquals(0, hits.get());
        cc.setSelectedValue("manual"); // real change — fires once
        assertEquals(1, hits.get());
    }

    @Test
    public void comboItemOrderMatchesOptionOrder() {
        CardChoice cc = new CardChoice(threeOptions(), "none");
        assertEquals(3, cc.comboBox().getItemCount());
        assertEquals("none", cc.comboBox().getItemAt(0));
        assertEquals("auto", cc.comboBox().getItemAt(1));
        assertEquals("manual", cc.comboBox().getItemAt(2));
        assertNotNull(cc.comboBox());
    }

    @Test
    public void cardRowExpandsToFitWrappedTextAndChip() {
        CardChoice cc = new CardChoice(new CardChoice.Option[]{
                new CardChoice.Option("long",
                        "Long title label that wraps",
                        "This deliberately long description should wrap onto multiple lines and remain visible.",
                        "settings",
                        "Recommended")
        }, "long");

        JPanel row = (JPanel) cc.getComponent(0);
        assertTrue("Card row should grow beyond the legacy fixed tile height",
                row.getPreferredSize().height > 104);
        for (Component child : row.getComponents()) {
            assertTrue("Card content should fit inside the row height",
                    child.getPreferredSize().height <= row.getPreferredSize().height);
        }
    }
}
