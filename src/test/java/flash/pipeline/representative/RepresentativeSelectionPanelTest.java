package flash.pipeline.representative;

import org.junit.Test;

import javax.swing.JLabel;
import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Container;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
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

    @Test
    public void statisticPanelHiddenForNoneAndHighlightsSelectedSeries()
            throws Exception {
        final RepresentativeSeries controlA = series("0", "Control", "Control-A");
        final RepresentativeSeries controlB = series("1", "Control", "Control-B");
        final RepresentativeStatTable table = new RepresentativeStatTable();
        table.putValue("0", 0, 1, "Control-A", "Control-A",
                "Control", "LH", "SCN", "DAPI", 10.0);
        table.putValue("1", 1, 2, "Control-B", "Control-B",
                "Control", "LH", "SCN", "DAPI", 14.0);

        runOnEdt(new Runnable() {
            @Override
            public void run() {
                RepresentativeSelectionPanel nonePanel =
                        new RepresentativeSelectionPanel(
                                Arrays.asList(controlA, controlB),
                                RepresentativeStatistic.NONE, table);
                try {
                    assertNull(nonePanel.statsPanelForTests());
                } finally {
                    nonePanel.dispose();
                }

                RepresentativeSelectionPanel statsPanel =
                        new RepresentativeSelectionPanel(
                                Arrays.asList(controlA, controlB),
                                RepresentativeStatistic.QUICK, table);
                try {
                    assertNotNull(statsPanel.statsPanelForTests());

                    assertTrue(statsPanel.selectSeriesForTests("1"));

                    assertEquals("1", statsPanel.statsPanelForTests()
                            .highlightedSeriesIdForTest());
                } finally {
                    statsPanel.dispose();
                }
            }
        });
    }

    @Test
    public void rememberedSelectionPreselectsMatchingRenderedRows()
            throws Exception {
        final RepresentativeSeries controlA = series("0", "Control", "Control-A");
        final RepresentativeSeries controlB = series("1", "Control", "Control-B");
        final RepresentativeSeries treatment = series("2", "Treatment", "Treatment-A");
        Map<String, RepresentativeSeries> remembered =
                new LinkedHashMap<String, RepresentativeSeries>();
        remembered.put("Control", series("1", "Control", "Control-B"));
        remembered.put("Treatment", series("2", "Treatment", "Treatment-A"));
        final RepresentativeSelection rememberedSelection =
                new RepresentativeSelection(Arrays.asList("Control", "Treatment"),
                        remembered);

        runOnEdt(new Runnable() {
            @Override
            public void run() {
                RepresentativeSelectionPanel panel =
                        new RepresentativeSelectionPanel(
                                Arrays.asList(controlA, controlB, treatment),
                                RepresentativeStatistic.NONE, null,
                                rememberedSelection);
                try {
                    assertTrue(panel.hasCompleteSelection());
                    assertEquals(controlB, panel.selectedSeries("Control"));
                    assertEquals(treatment, panel.selectedSeries("Treatment"));

                    RepresentativeSelection selection = panel.createSelection();
                    assertEquals(controlB,
                            selection.seriesForCondition("Control"));
                    assertEquals(treatment,
                            selection.seriesForCondition("Treatment"));
                } finally {
                    panel.dispose();
                }
            }
        });
    }

    @Test
    public void cachedPreviewRowsDoNotShowInternalCacheSourceText()
            throws Exception {
        final RepresentativeSeries cached = series("0", "Control", "Control-A",
                RepresentativeSeries.PreviewSource.CACHE, true);

        runOnEdt(new Runnable() {
            @Override
            public void run() {
                RepresentativeSelectionPanel panel = new RepresentativeSelectionPanel(
                        Collections.singletonList(cached));
                try {
                    List<String> labels = labelTexts(panel);

                    assertFalse(labels.contains("cache cache"));
                    assertFalse(labels.contains("cache"));
                } finally {
                    panel.dispose();
                }
            }
        });
    }

    @Test
    public void imageGridPreferredHeightAllowsVerticalScrollingForManyRows()
            throws Exception {
        final List<RepresentativeSeries> rows = new ArrayList<RepresentativeSeries>();
        for (int i = 0; i < 12; i++) {
            rows.add(series(String.valueOf(i), "Control", "Control-" + i));
        }

        runOnEdt(new Runnable() {
            @Override
            public void run() {
                RepresentativeSelectionPanel panel = new RepresentativeSelectionPanel(rows);
                try {
                    JScrollPane scroll = findFirst(panel, JScrollPane.class);

                    assertNotNull(scroll);
                    assertTrue(scroll.getViewport().getView().getPreferredSize().height
                            > scroll.getPreferredSize().height);
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

    private static List<String> labelTexts(Component root) {
        List<String> out = new ArrayList<String>();
        collectLabelTexts(root, out);
        return out;
    }

    private static void collectLabelTexts(Component component, List<String> out) {
        if (component instanceof JLabel) {
            out.add(((JLabel) component).getText());
        }
        if (component instanceof Container) {
            Component[] children = ((Container) component).getComponents();
            for (int i = 0; i < children.length; i++) {
                collectLabelTexts(children[i], out);
            }
        }
    }

    private static <T extends Component> T findFirst(Component root, Class<T> type) {
        if (type.isInstance(root)) {
            return type.cast(root);
        }
        if (root instanceof Container) {
            Component[] children = ((Container) root).getComponents();
            for (int i = 0; i < children.length; i++) {
                T found = findFirst(children[i], type);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static RepresentativeSeries series(String id,
                                               String condition,
                                               String name) {
        return series(id, condition, name,
                RepresentativeSeries.PreviewSource.GENERATED, false);
    }

    private static RepresentativeSeries series(String id,
                                               String condition,
                                               String name,
                                               RepresentativeSeries.PreviewSource previewSource,
                                               boolean cacheHit) {
        BufferedImage image = new BufferedImage(20, 12, BufferedImage.TYPE_INT_RGB);
        return new RepresentativeSeries(id, Integer.parseInt(id),
                Integer.parseInt(id) + 1, name, name, condition,
                "LH", "SCN", new File(name + ".lif"),
                Collections.singletonList(new RepresentativeSeries.ChannelThumbnail(
                        0, "DAPI", image, null)),
                image, null, previewSource, cacheHit);
    }
}
