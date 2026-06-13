package flash.pipeline.ui;

import org.junit.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Locks the selection logic of {@link SetupSettingsPanel} (Set Up Configuration
 * Screen 2): "Start from scratch" pre-ticks everything, "Redo" starts empty, and
 * tiles toggle independently.
 */
public class SetupSettingsPanelTest {

    private static SetupSettingsPanel.SettingTile[] tiles() {
        return new SetupSettingsPanel.SettingTile[]{
                new SetupSettingsPanel.SettingTile("filter", "Filters", "tags",
                        SetupSettingsPanel.Status.FULL),
                new SetupSettingsPanel.SettingTile("minmax", "Display", "sun",
                        SetupSettingsPanel.Status.PARTIAL),
                new SetupSettingsPanel.SettingTile("threshold", "Thresholds", "ruler-2",
                        SetupSettingsPanel.Status.NONE),
        };
    }

    @Test
    public void redoStartsWithNothingSelected() {
        SetupSettingsPanel panel = new SetupSettingsPanel(tiles(), false);
        assertFalse(panel.hasSelection());
        assertTrue(panel.getSelectedSettings().isEmpty());
    }

    @Test
    public void scratchPreselectsEveryTile() {
        SetupSettingsPanel panel = new SetupSettingsPanel(tiles(), true);
        assertTrue(panel.hasSelection());
        assertEquals(3, panel.getSelectedSettings().size());
        assertTrue(panel.isSettingSelected("filter"));
        assertTrue(panel.isSettingSelected("minmax"));
        assertTrue(panel.isSettingSelected("threshold"));
    }

    @Test
    public void tilesToggleIndependently() {
        SetupSettingsPanel panel = new SetupSettingsPanel(tiles(), false);
        panel.toggleTileForTests("minmax");
        assertTrue(panel.isSettingSelected("minmax"));
        assertFalse(panel.isSettingSelected("filter"));
        assertEquals(1, panel.getSelectedSettings().size());
        panel.toggleTileForTests("minmax");
        assertFalse(panel.isSettingSelected("minmax"));
        assertFalse(panel.hasSelection());
    }

    @Test
    public void untickingFromScratchLeavesTheRest() {
        SetupSettingsPanel panel = new SetupSettingsPanel(tiles(), true);
        panel.toggleTileForTests("filter");
        assertFalse(panel.isSettingSelected("filter"));
        assertEquals(2, panel.getSelectedSettings().size());
        assertTrue(panel.hasSelection());
    }

    @Test
    public void selectionListenerFiresOnToggle() {
        SetupSettingsPanel panel = new SetupSettingsPanel(tiles(), false);
        final AtomicInteger hits = new AtomicInteger(0);
        panel.addSelectionListener(new Runnable() {
            @Override public void run() {
                hits.incrementAndGet();
            }
        });
        panel.toggleTileForTests("threshold");
        assertEquals(1, hits.get());
        panel.toggleTileForTests("threshold");
        assertEquals(2, hits.get());
    }
}
