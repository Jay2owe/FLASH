package flash.pipeline.representative;

import flash.pipeline.presentation.PresentationTileConfig;
import org.junit.Test;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.image.BufferedImage;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class RepresentativeFigurePreviewTest {

    @Test
    public void thumbnailFitsWithinMaxLongEdge() {
        RepresentativeSelection selection = selectionOf("Control", "Treatment");
        RepresentativeLayout layout =
                RepresentativeLayout.allInOneRow(Arrays.asList("Control", "Treatment"));

        BufferedImage thumb = RepresentativeFigurePreview.renderLayoutThumbnail(
                selection, layout, tile(12), 240);

        assertNotNull(thumb);
        assertTrue("thumbnail long edge should fit the cap",
                Math.max(thumb.getWidth(), thumb.getHeight()) <= 240);
    }

    @Test
    public void finalSizeHonorsConditionGap() {
        RepresentativeSelection selection = selectionOf("Control", "Treatment");
        RepresentativeLayout layout =
                RepresentativeLayout.allInOneRow(Arrays.asList("Control", "Treatment"));

        Dimension tight = RepresentativeFigurePreview.finalFigureSize(selection, layout, tile(12));
        Dimension wide = RepresentativeFigurePreview.finalFigureSize(selection, layout, tile(60));

        // One condition gap between the two condition blocks: +48 px wide, same height.
        assertEquals(48, wide.width - tight.width);
        assertEquals(tight.height, wide.height);
    }

    @Test
    public void layoutThumbnailDrawsScaleBarWhenPreviewCalibrationIsKnown() {
        RepresentativeSelection selection = calibratedSelection("Control");
        RepresentativeLayout layout =
                RepresentativeLayout.allInOneRow(Collections.singletonList("Control"));
        PresentationTileConfig tileConfig = PresentationTileConfig.builder()
                .createOverviewTile(true)
                .annotateOverviewTile(true)
                .scaleBarEnabled(true)
                .scaleBarLengthUm(20.0)
                .scaleBarThicknessPx(4)
                .scaleBarPosition(PresentationTileConfig.Position.BOTTOM_RIGHT)
                .annotationColor(Color.GREEN)
                .labelMode(PresentationTileConfig.LabelMode.NONE)
                .channelOrder(Collections.singletonList("DAPI"))
                .cellSizePx(100)
                .build();

        BufferedImage thumb = RepresentativeFigurePreview.renderLayoutThumbnail(
                selection, layout, tileConfig, 1000);

        assertNotNull(thumb);
        assertTrue("layout preview should include the scale bar annotation",
                containsDominantGreen(thumb));
    }

    private static PresentationTileConfig tile(int conditionGapPx) {
        return PresentationTileConfig.builder()
                .createOverviewTile(true)
                .annotateOverviewTile(false)
                .scaleBarEnabled(false)
                .labelMode(PresentationTileConfig.LabelMode.NONE)
                .channelOrder(Arrays.asList("DAPI", "Merge"))
                .cellSizePx(100)
                .conditionGapPx(conditionGapPx)
                .build();
    }

    private static RepresentativeSelection selectionOf(String... conditions) {
        Map<String, RepresentativeSeries> selected =
                new LinkedHashMap<String, RepresentativeSeries>();
        for (int i = 0; i < conditions.length; i++) {
            selected.put(conditions[i], series(i, conditions[i]));
        }
        return new RepresentativeSelection(Arrays.asList(conditions), selected);
    }

    private static RepresentativeSelection calibratedSelection(String condition) {
        Map<String, RepresentativeSeries> selected =
                new LinkedHashMap<String, RepresentativeSeries>();
        selected.put(condition, calibratedSeries(0, condition));
        return new RepresentativeSelection(Collections.singletonList(condition), selected);
    }

    private static RepresentativeSeries series(int index, String condition) {
        return new RepresentativeSeries(
                RepresentativeStatTable.seriesIdForIndex(index),
                index,
                index + 1,
                "Exp-Mouse" + (index + 1) + "_LH_SCN",
                "Mouse" + (index + 1),
                condition,
                "LH",
                "SCN",
                null,
                Collections.singletonList(new RepresentativeSeries.ChannelThumbnail(
                        0, "DAPI", solid(Color.RED, 60), null)),
                solid(Color.CYAN, 60),
                null,
                RepresentativeSeries.PreviewSource.GENERATED,
                false);
    }

    private static RepresentativeSeries calibratedSeries(int index, String condition) {
        return new RepresentativeSeries(
                RepresentativeStatTable.seriesIdForIndex(index),
                index,
                index + 1,
                "Exp-Mouse" + (index + 1) + "_LH_SCN",
                "Mouse" + (index + 1),
                condition,
                "LH",
                "SCN",
                null,
                Collections.singletonList(new RepresentativeSeries.ChannelThumbnail(
                        0, "DAPI", solid(Color.BLACK, 60), null)),
                solid(Color.BLACK, 60),
                null,
                RepresentativeSeries.PreviewSource.GENERATED,
                false,
                2.0,
                2.0);
    }

    private static BufferedImage solid(Color color, int size) {
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
        int rgb = color.getRGB();
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                image.setRGB(x, y, rgb);
            }
        }
        return image;
    }

    private static boolean containsDominantGreen(BufferedImage image) {
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                Color c = new Color(image.getRGB(x, y), true);
                if (c.getGreen() > 180 && c.getRed() < 80 && c.getBlue() < 80) {
                    return true;
                }
            }
        }
        return false;
    }
}
