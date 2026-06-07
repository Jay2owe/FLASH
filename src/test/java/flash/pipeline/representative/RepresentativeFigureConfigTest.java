package flash.pipeline.representative;

import flash.pipeline.presentation.PresentationTileConfig;
import org.junit.Test;

import java.awt.Color;
import java.io.File;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
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
