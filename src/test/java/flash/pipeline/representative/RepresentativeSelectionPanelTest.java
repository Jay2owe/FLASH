package flash.pipeline.representative;

import org.junit.Test;

import javax.swing.SwingUtilities;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class RepresentativeSelectionPanelTest {

    @Test
    public void selectingRowsReplacesPickWithinConditionAndCompletesAllConditions()
            throws Exception {
        final RepresentativeSeries controlA = series("0", "Control", "Control-A");
        final RepresentativeSeries controlB = series("1", "Control", "Control-B");
        final RepresentativeSeries treatment = series("2", "Treatment", "Treatment-A");

        runOnEdt(new Runnable() {
            @Override
            public void run() {
                RepresentativeSelectionPanel panel = new RepresentativeSelectionPanel(
                        Arrays.asList(controlA, controlB, treatment));
                final List<RepresentativeSelectionPanel.SelectionEvent> events =
                        new ArrayList<RepresentativeSelectionPanel.SelectionEvent>();
                panel.addSelectionListener(
                        new RepresentativeSelectionPanel.SelectionListener() {
                            @Override
                            public void selectionChanged(
                                    RepresentativeSelectionPanel.SelectionEvent event) {
                                events.add(event);
                            }
                        });
                try {
                    assertEquals(Arrays.asList("Control", "Treatment"),
                            panel.conditionNames());
                    assertEquals(2, panel.seriesCount("Control"));
                    assertEquals(1, panel.seriesCount("Treatment"));
                    assertFalse(panel.hasCompleteSelection());

                    assertTrue(panel.selectSeriesForTests("0"));
                    assertEquals(controlA, panel.selectedSeries("Control"));
                    assertFalse(panel.hasCompleteSelection());

                    assertTrue(panel.selectSeriesForTests("1"));
                    assertEquals(controlB, panel.selectedSeries("Control"));
                    assertFalse(panel.hasCompleteSelection());

                    assertTrue(panel.selectSeriesForTests("2"));
                    assertTrue(panel.hasCompleteSelection());

                    RepresentativeSelection selection = panel.createSelection();
                    assertEquals(controlB, selection.seriesForCondition("Control"));
                    assertEquals(treatment, selection.seriesForCondition("Treatment"));
                    assertEquals(3, events.size());
                    assertTrue(events.get(2).isComplete());
                } finally {
                    panel.dispose();
                }
            }
        });
    }

    private static void runOnEdt(final Runnable task) throws Exception {
        if (SwingUtilities.isEventDispatchThread()) {
            task.run();
            return;
        }
        final Throwable[] failure = new Throwable[1];
        SwingUtilities.invokeAndWait(new Runnable() {
            @Override
            public void run() {
                try {
                    task.run();
                } catch (Throwable t) {
                    failure[0] = t;
                }
            }
        });
        if (failure[0] instanceof Exception) {
            throw (Exception) failure[0];
        }
        if (failure[0] instanceof Error) {
            throw (Error) failure[0];
        }
    }

    private static RepresentativeSeries series(String id,
                                               String condition,
                                               String name) {
        BufferedImage image = new BufferedImage(20, 12, BufferedImage.TYPE_INT_RGB);
        return new RepresentativeSeries(id, Integer.parseInt(id),
                Integer.parseInt(id) + 1, name, name, condition,
                "LH", "SCN", new File(name + ".lif"),
                Collections.singletonList(new RepresentativeSeries.ChannelThumbnail(
                        0, "DAPI", image, null)),
                image, null, RepresentativeSeries.PreviewSource.GENERATED, false);
    }
}
