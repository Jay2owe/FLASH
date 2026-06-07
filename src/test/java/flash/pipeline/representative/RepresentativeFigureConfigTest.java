package flash.pipeline.representative;

import flash.pipeline.presentation.PresentationTileConfig;
import org.junit.Test;

import java.awt.Color;
import java.io.File;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;

public class RepresentativeFigureConfigTest {

    @Test
    public void mapRoundTripPreservesReplayableFigureSettings() {
        RepresentativeFigureConfig config = new RepresentativeFigureConfig();
        config.statistic = RepresentativeStatistic.EXISTING_RESULT;
        config.existingResult = new RepresentativeStatLoader.ExistingResultOption(
                new File("FLASH/Results/Tables/Intensity/Image Intensities.csv"),
                "Mean",
                "Tables/Intensity/Image Intensities.csv");

        Map<String, RepresentativeSeries> selected =
                new LinkedHashMap<String, RepresentativeSeries>();
        selected.put("Control", representativeSeries(0, "Control"));
        selected.put("Treatment", representativeSeries(1, "Treatment"));
        config.selection = new RepresentativeSelection(
                Arrays.asList("Control", "Treatment"), selected);
        config.setCustomDisplayRangeForChannel(0, "10-200");
        config.setCustomDisplayRangeForChannel(1, "5-90");
        config.layout = new RepresentativeLayout(Arrays.asList(
                Arrays.asList("Control"),
                Arrays.asList("Treatment")));
        config.tileConfig = PresentationTileConfig.builder()
                .createOverviewTile(true)
                .annotateOverviewTile(true)
                .annotateIndividualImages(false)
                .groupRowsBy(PresentationTileConfig.GroupRowsBy.CONDITION)
                .channelOrder(Arrays.asList("Merge", "DAPI", "GFAP"))
                .cellSizePx(180)
                .scaleBarEnabled(true)
                .scaleBarLengthUm(25.0)
                .scaleBarThicknessPx(4)
                .scaleBarPosition(PresentationTileConfig.Position.BOTTOM_LEFT)
                .annotationColor(Color.BLACK)
                .labelMode(PresentationTileConfig.LabelMode.CONDITION_IMAGE)
                .customLabelTemplate("{condition} {image}")
                .labelFontSizePx(14)
                .labelPosition(PresentationTileConfig.Position.TOP_RIGHT)
                .marginPx(20)
                .innerColGapPx(9)
                .conditionGapPx(33)
                .rowGapPx(17)
                .conditionFontSizePx(22)
                .channelFontSizePx(24)
                .exportScale(3)
                .labelFracX(0.10)
                .labelFracY(0.85)
                .scaleBarFracX(0.60)
                .scaleBarFracY(0.92)
                .build();

        RepresentativeFigureConfig back =
                RepresentativeFigureConfig.fromMap(config.toMap());

        assertEquals(RepresentativeStatistic.EXISTING_RESULT, back.statistic);
        assertNotNull(back.existingResult);
        assertEquals("Mean", back.existingResult.columnName);
        assertNotNull(back.selection);
        assertEquals(Arrays.asList("Control", "Treatment"),
                back.selection.conditionNames());
        assertEquals("Mouse1", back.selection.seriesForCondition("Control").animal());
        assertEquals("10-200", back.customDisplayRangeForChannel(0));
        assertEquals(Arrays.asList(Arrays.asList("Control"), Arrays.asList("Treatment")),
                back.layout.rows());
        assertEquals(Arrays.asList("Merge", "DAPI", "GFAP"),
                back.tileConfig.channelOrder());
        assertEquals(180, back.tileConfig.cellSizePx());
        assertEquals(Color.BLACK, back.tileConfig.annotationColor());
        assertEquals(PresentationTileConfig.LabelMode.CONDITION_IMAGE,
                back.tileConfig.labelMode());
        assertEquals(20, back.tileConfig.marginPx());
        assertEquals(9, back.tileConfig.innerColGapPx());
        assertEquals(33, back.tileConfig.conditionGapPx());
        assertEquals(17, back.tileConfig.rowGapPx());
        assertEquals(22, back.tileConfig.conditionFontSizePx());
        assertEquals(24, back.tileConfig.channelFontSizePx());
        assertEquals(3, back.tileConfig.exportScale());
        assertEquals(0.10, back.tileConfig.labelFracX(), 1e-9);
        assertEquals(0.85, back.tileConfig.labelFracY(), 1e-9);
        assertEquals(0.60, back.tileConfig.scaleBarFracX(), 1e-9);
        assertEquals(0.92, back.tileConfig.scaleBarFracY(), 1e-9);
    }

    @Test
    @SuppressWarnings("unchecked")
    public void legacyTileConfigWithoutSpacingKeysFallsBackToDefaults() {
        RepresentativeFigureConfig config = new RepresentativeFigureConfig();
        Map<String, RepresentativeSeries> selected =
                new LinkedHashMap<String, RepresentativeSeries>();
        selected.put("Control", representativeSeries(0, "Control"));
        config.selection = new RepresentativeSelection(
                Arrays.asList("Control"), selected);
        config.layout = new RepresentativeLayout(Arrays.asList(Arrays.asList("Control")));
        config.tileConfig = PresentationTileConfig.builder()
                .channelOrder(Arrays.asList("Merge"))
                .build();

        Map<String, Object> map = config.toMap();
        Map<String, Object> tileConfig = (Map<String, Object>) map.get("tileConfig");
        // Simulate a project saved before the spacing/font fields existed.
        tileConfig.remove("marginPx");
        tileConfig.remove("innerColGapPx");
        tileConfig.remove("conditionGapPx");
        tileConfig.remove("rowGapPx");
        tileConfig.remove("conditionFontSizePx");
        tileConfig.remove("channelFontSizePx");
        tileConfig.remove("exportScale");
        tileConfig.remove("labelFracX");
        tileConfig.remove("labelFracY");
        tileConfig.remove("scaleBarFracX");
        tileConfig.remove("scaleBarFracY");

        RepresentativeFigureConfig back = RepresentativeFigureConfig.fromMap(map);
        assertEquals(6, back.tileConfig.marginPx());
        assertEquals(4, back.tileConfig.innerColGapPx());
        assertEquals(12, back.tileConfig.conditionGapPx());
        assertEquals(8, back.tileConfig.rowGapPx());
        assertEquals(15, back.tileConfig.conditionFontSizePx());
        assertEquals(16, back.tileConfig.channelFontSizePx());
        assertEquals(1, back.tileConfig.exportScale());
        assertEquals(-1.0, back.tileConfig.labelFracX(), 1e-9);
        assertEquals(-1.0, back.tileConfig.scaleBarFracY(), 1e-9);
        assertFalse(back.tileConfig.hasLabelFraction());
        assertFalse(back.tileConfig.hasScaleBarFraction());
    }

    private static RepresentativeSeries representativeSeries(int index, String condition) {
        return new RepresentativeSeries(
                "series-000" + (index + 1),
                index,
                index + 1,
                "Exp-Mouse" + (index + 1) + "_LH_SCN",
                "Mouse" + (index + 1),
                condition,
                "LH",
                "SCN",
                new File("source" + (index + 1) + ".lif"),
                Arrays.asList(
                        new RepresentativeSeries.ChannelThumbnail(0, "DAPI", null, null),
                        new RepresentativeSeries.ChannelThumbnail(1, "GFAP", null, null)),
                null,
                null,
                RepresentativeSeries.PreviewSource.GENERATED,
                false);
    }
}
