package flash.pipeline.ui;

import org.junit.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

/**
 * Locks the drop-in contract that lets {@link CardListChoice} stand in for a
 * {@code JComboBox} dropdown whose items change at runtime: the backing combo
 * is the single source of truth in both directions, and {@link
 * CardListChoice#setRows} rebuilds the list without firing selection listeners.
 */
public class CardListChoiceTest {

    private static CardListChoice.Row[] threeRows() {
        return new CardListChoice.Row[]{
                new CardListChoice.Row("explore", "Exploratory", "All sheets", "Default"),
                new CardListChoice.Row("supervisor", "Supervisor review", "Stats only"),
                new CardListChoice.Row("minimal", "Minimal", "Metric sheets only"),
        };
    }

    @Test
    public void defaultValueIsSelectedInBackingCombo() {
        CardListChoice cl = new CardListChoice(threeRows(), "supervisor");
        assertEquals("supervisor", cl.getSelectedValue());
        assertEquals("supervisor", cl.comboBox().getSelectedItem());
    }

    @Test
    public void unknownDefaultFallsBackToFirstRow() {
        CardListChoice cl = new CardListChoice(threeRows(), "does-not-exist");
        assertEquals("explore", cl.getSelectedValue());
    }

    @Test
    public void setSelectedValueUpdatesCombo() {
        CardListChoice cl = new CardListChoice(threeRows(), "explore");
        cl.setSelectedValue("minimal");
        assertEquals("minimal", cl.getSelectedValue());
        assertEquals("minimal", cl.comboBox().getSelectedItem());
    }

    @Test
    public void externalComboSetSyncsBackToSelection() {
        CardListChoice cl = new CardListChoice(threeRows(), "explore");
        cl.comboBox().setSelectedItem("minimal");
        assertEquals("minimal", cl.getSelectedValue());
    }

    @Test
    public void selectionListenerFiresOnRealChangeOnly() {
        CardListChoice cl = new CardListChoice(threeRows(), "explore");
        final AtomicInteger hits = new AtomicInteger(0);
        cl.addSelectionListener(new Runnable() {
            @Override public void run() {
                hits.incrementAndGet();
            }
        });
        cl.setSelectedValue("explore"); // already selected — no-op
        assertEquals(0, hits.get());
        cl.setSelectedValue("supervisor"); // real change — fires once
        assertEquals(1, hits.get());
    }

    @Test
    public void setRowsDoesNotFireExternalComboListeners() {
        // A host (e.g. the Excel preset loader) attaches directly to comboBox().
        // Rebuilding the list must not fire it, even though the model churns.
        CardListChoice cl = new CardListChoice(threeRows(), "explore");
        final AtomicInteger comboHits = new AtomicInteger(0);
        cl.comboBox().addActionListener(e -> comboHits.incrementAndGet());

        cl.setRows(threeRows(), "minimal");
        assertEquals(0, comboHits.get());
        assertEquals("minimal", cl.getSelectedValue());

        // But a normal selection change (as a card click would cause) still fires it.
        cl.setSelectedValue("supervisor");
        assertEquals(1, comboHits.get());
    }

    @Test
    public void setRowsRebuildsItemsAndDoesNotFireListeners() {
        CardListChoice cl = new CardListChoice(threeRows(), "explore");
        final AtomicInteger hits = new AtomicInteger(0);
        cl.addSelectionListener(new Runnable() {
            @Override public void run() {
                hits.incrementAndGet();
            }
        });
        CardListChoice.Row[] grown = new CardListChoice.Row[]{
                new CardListChoice.Row("explore", "Exploratory", "All sheets", "Default"),
                new CardListChoice.Row("supervisor", "Supervisor review", "Stats only"),
                new CardListChoice.Row("minimal", "Minimal", "Metric sheets only"),
                new CardListChoice.Row("my-saved", "My saved preset", "User saved"),
        };
        cl.setRows(grown, "my-saved");
        assertEquals(0, hits.get()); // rebuild must not fire
        assertEquals("my-saved", cl.getSelectedValue());
        assertEquals(4, cl.comboBox().getItemCount());
        assertEquals("my-saved", cl.comboBox().getItemAt(3));
        assertNotNull(cl.comboBox());
    }
}
