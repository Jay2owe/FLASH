package flash.pipeline.representative;

import flash.pipeline.presentation.PresentationTileConfig;
import org.junit.Test;

import javax.swing.JLabel;
import javax.swing.ListSelectionModel;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class ConditionLayoutChooserTest {

    @Test
    public void defaultTileOrderUsesSelectedChannelsThenMerge() {
        RepresentativeSelection selection = selection();

        List<String> order = ConditionLayoutChooser.defaultTileOrder(selection);

        assertEquals(Arrays.asList("DAPI", "GFAP", "Merge"), order);
    }

    @Test
    public void defaultTileConfigIsFullyPopulatedForRepresentativeFigure() {
        PresentationTileConfig config = ConditionLayoutChooser.defaultTileConfig(
                Arrays.asList("DAPI", "GFAP", "Merge"));

        assertTrue(config.createOverviewTile());
        assertTrue(config.annotateOverviewTile());
        assertFalse(config.annotateIndividualImages());
        assertEquals(PresentationTileConfig.GroupRowsBy.CONDITION, config.groupRowsBy());
        assertEquals(Arrays.asList("DAPI", "GFAP", "Merge"), config.channelOrder());
        assertEquals(260, config.cellSizePx());
        assertTrue(config.scaleBarEnabled());
        assertEquals(100.0, config.scaleBarLengthUm(), 0.001);
        assertEquals(6, config.scaleBarThicknessPx());
        assertEquals(PresentationTileConfig.Position.BOTTOM_RIGHT, config.scaleBarPosition());
        assertEquals(Color.WHITE, config.annotationColor());
        assertEquals(PresentationTileConfig.LabelMode.STAIN_NAME, config.labelMode());
        assertEquals("{stain}", config.customLabelTemplate());
        assertEquals(18, config.labelFontSizePx());
        assertEquals(PresentationTileConfig.Position.TOP_LEFT, config.labelPosition());
        assertTrue(config.conditionHeaderVisible());
        assertTrue(config.channelHeaderVisible());
        assertEquals(300, config.outputDpi());
    }

    @Test
    public void rememberedLayoutIsReusedOnlyWhenConditionsMatch() {
        RepresentativeLayout remembered = new RepresentativeLayout(Arrays.asList(
                Collections.singletonList("Control"),
                Collections.singletonList("Treatment")));

        assertSame(remembered, ConditionLayoutChooser.initialLayout(
                Arrays.asList("Treatment", "Control"), remembered));

        RepresentativeLayout fallback = ConditionLayoutChooser.initialLayout(
                Arrays.asList("Control", "Washout"), remembered);

        assertEquals(Collections.singletonList(Arrays.asList("Control", "Washout")),
                fallback.rows());
    }

    @Test
    public void conditionLayoutSupportsShiftRangeSelection() {
        ConditionLayoutChooser.LayoutAssignmentPanel panel = layoutPanel();

        assertEquals(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION,
                panel.selectionModeForTest());
    }

    @Test
    public void selectedConditionRangeMovesAsBlock() {
        ConditionLayoutChooser.LayoutAssignmentPanel panel = layoutPanel();

        panel.selectRangeForTest(1, 2);
        panel.moveSelectedForTest(-1);

        assertEquals(Collections.singletonList(Arrays.asList("B", "C", "A", "D")),
                panel.createLayout().rows());
    }

    @Test
    public void selectedConditionsAdjustRowsTogether() {
        ConditionLayoutChooser.LayoutAssignmentPanel panel = layoutPanel();

        panel.selectRangeForTest(1, 3);
        panel.adjustSelectedRowForTest(1);

        assertEquals(Arrays.asList(
                        Collections.singletonList("A"),
                        Arrays.asList("B", "C", "D")),
                panel.createLayout().rows());
    }

    @Test
    public void separatedSelectedConditionsMoveWithoutLosingOrder() {
        ConditionLayoutChooser.LayoutAssignmentPanel panel = layoutPanel();

        panel.selectIndicesForTest(1, 3);
        panel.moveSelectedForTest(1);

        assertEquals(Collections.singletonList(Arrays.asList("A", "C", "B", "D")),
                panel.createLayout().rows());
    }

    @Test
    public void tileOptionsPanelPreservesExistingConfigAndAddsMissingDefaultChannels() {
        PresentationTileConfig initial = PresentationTileConfig.builder()
                .createOverviewTile(false)
                .annotateOverviewTile(false)
                .annotateIndividualImages(false)
                .groupRowsBy(PresentationTileConfig.GroupRowsBy.ANIMAL)
                .channelOrder(Collections.singletonList("DAPI"))
                .cellSizePx(512)
                .scaleBarEnabled(false)
                .scaleBarLengthUm(50.0)
                .scaleBarThicknessPx(3)
                .scaleBarPosition(PresentationTileConfig.Position.TOP_RIGHT)
                .annotationColor(Color.BLACK)
                .labelMode(PresentationTileConfig.LabelMode.CUSTOM)
                .customLabelTemplate("{condition}")
                .labelFontSizePx(24)
                .labelPosition(PresentationTileConfig.Position.BOTTOM_LEFT)
                .conditionHeaderVisible(false)
                .channelHeaderVisible(false)
                .outputDpi(600)
                .build();

        ConditionLayoutChooser.TileOptionsPanel panel =
                new ConditionLayoutChooser.TileOptionsPanel(
                        Arrays.asList("GFAP", "Merge"), initial);

        PresentationTileConfig config = panel.buildConfig();

        assertTrue(config.createOverviewTile());
        assertFalse(config.annotateOverviewTile());
        assertEquals(PresentationTileConfig.GroupRowsBy.ANIMAL, config.groupRowsBy());
        assertEquals(Arrays.asList("DAPI", "GFAP", "Merge"), config.channelOrder());
        assertEquals(512, config.cellSizePx());
        assertFalse(config.scaleBarEnabled());
        assertEquals(50.0, config.scaleBarLengthUm(), 0.001);
        assertEquals(3, config.scaleBarThicknessPx());
        assertEquals(PresentationTileConfig.Position.TOP_RIGHT, config.scaleBarPosition());
        assertEquals(Color.BLACK, config.annotationColor());
        assertEquals(PresentationTileConfig.LabelMode.CUSTOM, config.labelMode());
        assertEquals("{condition}", config.customLabelTemplate());
        assertEquals(24, config.labelFontSizePx());
        assertEquals(PresentationTileConfig.Position.BOTTOM_LEFT, config.labelPosition());
        assertFalse(config.conditionHeaderVisible());
        assertFalse(config.channelHeaderVisible());
        assertEquals(600, config.outputDpi());
    }

    @Test
    public void tileOptionsPanelAppliesLayoutEditorHeaderVisibility() {
        ConditionLayoutChooser.TileOptionsPanel panel =
                new ConditionLayoutChooser.TileOptionsPanel(
                        Arrays.asList("DAPI", "GFAP", "Merge"),
                        ConditionLayoutChooser.defaultTileConfig(
                                Arrays.asList("DAPI", "GFAP", "Merge")));
        PresentationTileConfig arranged = panel.buildConfig().toBuilder()
                .rowGapPx(0)
                .conditionHeaderVisible(false)
                .channelHeaderVisible(false)
                .build();

        panel.applySpacing(arranged);

        PresentationTileConfig config = panel.buildConfig();
        assertEquals(0, config.rowGapPx());
        assertFalse(config.conditionHeaderVisible());
        assertFalse(config.channelHeaderVisible());
    }

    @Test
    public void tileOptionsPanelDoesNotExposeCreateTileToggle() {
        ConditionLayoutChooser.TileOptionsPanel panel =
                new ConditionLayoutChooser.TileOptionsPanel(
                        Arrays.asList("DAPI", "GFAP", "Merge"),
                        ConditionLayoutChooser.defaultTileConfig(
                                Arrays.asList("DAPI", "GFAP", "Merge")));

        assertFalse(containsLabel(panel.panel, "Create tile"));
        assertFalse(containsLabel(panel.panel, "Export scale"));
        assertTrue(containsLabel(panel.panel, "DPI"));
        assertTrue(panel.buildConfig().createOverviewTile());
    }

    @Test
    public void tileAndAnnotationEditorBackedControlsStartCollapsed() {
        ConditionLayoutChooser.TileOptionsPanel panel =
                new ConditionLayoutChooser.TileOptionsPanel(
                        Arrays.asList("DAPI", "GFAP", "Merge"),
                        ConditionLayoutChooser.defaultTileConfig(
                                Arrays.asList("DAPI", "GFAP", "Merge")));

        assertFalse(panel.advancedTileExpandedForTest());
        assertFalse(panel.advancedAnnotationsExpandedForTest());
        assertTrue(containsVisibleLabel(panel.panel, "Rows by"));
        assertTrue(containsVisibleLabel(panel.panel, "DPI"));
        assertTrue(containsVisibleLabel(panel.panel, "Label"));
        assertTrue(containsVisibleLabel(panel.panel, "Scale bar"));
        assertFalse(containsVisibleLabel(panel.panel, "Row gap"));
        assertFalse(containsVisibleLabel(panel.panel, "Condition headers"));
        assertFalse(containsVisibleLabel(panel.panel, "Bar um"));
        assertFalse(containsVisibleLabel(panel.panel, "Font px"));
        assertTrue(containsLabel(panel.panel, "Advanced tile"));
        assertTrue(containsLabel(panel.panel, "Advanced annotations"));
    }

    private static RepresentativeSelection selection() {
        Map<String, RepresentativeSeries> selected =
                new LinkedHashMap<String, RepresentativeSeries>();
        selected.put("Control", series("0", "Control",
                Arrays.asList(thumbnail(0, "DAPI"), thumbnail(1, "GFAP"))));
        selected.put("Treatment", series("1", "Treatment",
                Collections.singletonList(thumbnail(0, "DAPI"))));
        return new RepresentativeSelection(Arrays.asList("Control", "Treatment"), selected);
    }

    private static RepresentativeSeries series(String id,
                                               String condition,
                                               List<RepresentativeSeries.ChannelThumbnail> thumbnails) {
        BufferedImage image = new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB);
        return new RepresentativeSeries(
                id,
                Integer.parseInt(id),
                Integer.parseInt(id) + 1,
                "Series " + id,
                "Animal " + id,
                condition,
                "LH",
                "SCN",
                new File("series-" + id + ".lif"),
                thumbnails,
                image,
                null,
                RepresentativeSeries.PreviewSource.GENERATED,
                false);
    }

    private static ConditionLayoutChooser.LayoutAssignmentPanel layoutPanel() {
        List<String> conditions = Arrays.asList("A", "B", "C", "D");
        return new ConditionLayoutChooser.LayoutAssignmentPanel(
                conditions,
                RepresentativeLayout.allInOneRow(conditions));
    }

    private static boolean containsLabel(Component component, String text) {
        if (component instanceof JLabel
                && text.equals(((JLabel) component).getText())) {
            return true;
        }
        if (component instanceof Container) {
            Component[] children = ((Container) component).getComponents();
            for (int i = 0; i < children.length; i++) {
                if (containsLabel(children[i], text)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean containsVisibleLabel(Component component, String text) {
        return containsVisibleLabel(component, text, true);
    }

    private static boolean containsVisibleLabel(Component component,
                                                String text,
                                                boolean visibleParent) {
        boolean visible = visibleParent && component.isVisible();
        if (visible && component instanceof JLabel
                && text.equals(((JLabel) component).getText())) {
            return true;
        }
        if (component instanceof Container) {
            Component[] children = ((Container) component).getComponents();
            for (int i = 0; i < children.length; i++) {
                if (containsVisibleLabel(children[i], text, visible)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static RepresentativeSeries.ChannelThumbnail thumbnail(int index, String name) {
        return new RepresentativeSeries.ChannelThumbnail(
                index, name, new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB), null);
    }
}
