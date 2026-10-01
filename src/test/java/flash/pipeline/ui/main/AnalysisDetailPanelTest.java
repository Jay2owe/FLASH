package flash.pipeline.ui.main;

import flash.pipeline.FLASH_Pipeline;
import flash.pipeline.runtime.DependencyId;
import flash.pipeline.runtime.DependencyRegistry;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import javax.swing.JLabel;
import javax.swing.JPanel;
import java.awt.event.MouseEvent;
import java.io.File;
import java.util.EnumMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class AnalysisDetailPanelTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    private static final String[] LABELS = labels();
    private static final String[] DESCRIPTIONS = new String[LABELS.length];

    @Test
    public void everyVisibleAnalysisHasFactsAndAnOutputLocation() throws Exception {
        File project = temp.newFolder("project");
        for (int index : FLASH_Pipeline.visibleAnalysisOrderForTests()) {
            assertTrue("missing facts for analysis " + index, AnalysisFacts.isKnown(index));
            String out = AnalysisFacts.outputForDisplay(project.getAbsolutePath(), index);
            assertFalse("missing output for analysis " + index, out.isEmpty());
            assertFalse("output should be relative: " + out, new File(out).isAbsolute());
            for (DependencyId id : AnalysisFacts.uses(index)) {
                assertNotNull("unregistered dependency " + id, DependencyRegistry.get(id));
            }
        }
    }

    @Test
    public void panelShowsStatusOutputAndCheckingStateBeforeScansFinish() throws Exception {
        File project = temp.newFolder("project");
        AnalysisDetailPanel panel = new AnalysisDetailPanel(LABELS, DESCRIPTIONS, null);
        panel.setProjectDirectory(project.getAbsolutePath());

        panel.show(FLASH_Pipeline.IDX_3D_OBJECT);

        assertEquals("3D Object Analysis", panel.titleForTests());
        assertEquals("Checking...", panel.statusTextForTests());
        assertEquals("Checking add-ons...", panel.usesTextForTests());
        assertFalse(panel.fixVisibleForTests());
        assertTrue(panel.writesTextForTests().startsWith("FLASH"));

        panel.setStatusSource(new AnalysisDetailPanel.StatusSource() {
            @Override public String statusFor(int analysisIndex) {
                return "Not run on this folder";
            }
        });
        assertEquals("Not run on this folder", panel.statusTextForTests());
    }

    @Test
    public void missingRuntimeIsNamedWithWhatItBlocksAndOffersAFix() {
        AnalysisDetailPanel panel = new AnalysisDetailPanel(LABELS, DESCRIPTIONS, null);
        panel.show(FLASH_Pipeline.IDX_3D_OBJECT);
        Map<DependencyId, String> attention = new EnumMap<DependencyId, String>(DependencyId.class);
        attention.put(DependencyId.STARDIST_RUNTIME, "Missing");

        panel.setDependencyAttention(attention);

        String uses = panel.usesTextForTests();
        assertTrue(uses, uses.contains(DependencyRegistry.get(DependencyId.STARDIST_RUNTIME).getDisplayName()));
        assertTrue(uses, uses.contains("missing (needed for StarDist segmentation"));
        assertTrue(uses, uses.contains("6 others installed"));
        assertTrue(panel.fixVisibleForTests());

        panel.setDependencyAttention(new EnumMap<DependencyId, String>(DependencyId.class));
        assertEquals("All 7 add-ons installed", panel.usesTextForTests());
        assertFalse(panel.fixVisibleForTests());
    }

    @Test
    public void attentionOnAnUnusedRuntimeDoesNotFlagTheAnalysis() {
        AnalysisDetailPanel panel = new AnalysisDetailPanel(LABELS, DESCRIPTIONS, null);
        panel.show(FLASH_Pipeline.IDX_STATISTICS);
        Map<DependencyId, String> attention = new EnumMap<DependencyId, String>(DependencyId.class);
        attention.put(DependencyId.STARDIST_RUNTIME, "Missing");

        panel.setDependencyAttention(attention);

        assertEquals("No optional add-ons", panel.usesTextForTests());
        assertFalse(panel.fixVisibleForTests());
    }

    @Test
    public void rowFocusStartsOnFirstRowAndFollowsClicks() {
        AnalysisDetailPanel panel = new AnalysisDetailPanel(LABELS, DESCRIPTIONS, null);
        AnalysisRowFocus focus = new AnalysisRowFocus(panel);
        JPanel first = new JPanel();
        JPanel second = new JPanel();
        JLabel secondLabel = new JLabel("Spatial Analysis");
        second.add(secondLabel);

        focus.register(FLASH_Pipeline.IDX_CREATE_BIN, null, first);
        focus.register(FLASH_Pipeline.IDX_SPATIAL, null, second);

        assertEquals(FLASH_Pipeline.IDX_CREATE_BIN, panel.getShownAnalysis());
        assertTrue(focus.isHighlightedForTests(FLASH_Pipeline.IDX_CREATE_BIN));

        MouseEvent press = new MouseEvent(secondLabel, MouseEvent.MOUSE_PRESSED,
                System.currentTimeMillis(), 0, 2, 2, 1, false);
        for (java.awt.event.MouseListener listener : secondLabel.getMouseListeners()) {
            listener.mousePressed(press);
        }

        assertEquals(FLASH_Pipeline.IDX_SPATIAL, panel.getShownAnalysis());
        assertTrue(focus.isHighlightedForTests(FLASH_Pipeline.IDX_SPATIAL));
        assertFalse(focus.isHighlightedForTests(FLASH_Pipeline.IDX_CREATE_BIN));
    }

    private static String[] labels() {
        String[] out = new String[FLASH_Pipeline.IDX_REPRESENTATIVE_FIGURE + 1];
        for (int i = 0; i < out.length; i++) out[i] = "Analysis " + i;
        out[FLASH_Pipeline.IDX_3D_OBJECT] = "3D Object Analysis";
        return out;
    }
}
