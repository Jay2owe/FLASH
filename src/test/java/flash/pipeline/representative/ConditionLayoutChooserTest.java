package flash.pipeline.representative;

import flash.pipeline.presentation.PresentationTileConfig;
import org.junit.Test;

import java.awt.Color;
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
                .build();

        ConditionLayoutChooser.TileOptionsPanel panel =
                new ConditionLayoutChooser.TileOptionsPanel(
                        Arrays.asList("GFAP", "Merge"), initial);

        PresentationTileConfig config = panel.buildConfig();

        assertFalse(config.createOverviewTile());
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

    private static RepresentativeSeries.ChannelThumbnail thumbnail(int index, String name) {
        return new RepresentativeSeries.ChannelThumbnail(
                index, name, new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB), null);
    }
}
