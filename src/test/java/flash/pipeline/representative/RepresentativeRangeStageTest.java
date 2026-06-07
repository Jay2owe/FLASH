package flash.pipeline.representative;

import flash.pipeline.bin.BinConfig;
import org.junit.Test;

import java.awt.image.BufferedImage;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class RepresentativeRangeStageTest {

    @Test
    public void setupRangesMustCoverEverySelectedChannelBeforePromptCanAppear() {
        BinConfig cfg = twoChannelConfig("0-100", "None");
        RepresentativeSelection selection = selection();

        assertFalse(RepresentativeRangeStage.hasCompleteSetupRanges(cfg, selection));

        cfg.channelMinMax.set(1, "0-200");

        assertTrue(RepresentativeRangeStage.hasCompleteSetupRanges(cfg, selection));
    }

    @Test
    public void selectedChannelsUseSetupNamesAndColorsWhenAvailable() {
        BinConfig cfg = twoChannelConfig("0-100", "0-200");
        RepresentativeSelection selection = selection();

        List<RepresentativeRangeStage.ChannelRef> channels =
                RepresentativeRangeStage.channelsForSelection(selection, cfg);

        assertEquals(2, channels.size());
        assertEquals(0, channels.get(0).channelIndex);
        assertEquals("DAPI", channels.get(0).channelName);
        assertEquals("Red", channels.get(0).colorName);
        assertEquals(1, channels.get(1).channelIndex);
        assertEquals("GFAP", channels.get(1).channelName);
        assertEquals("Green", channels.get(1).colorName);
    }

    @Test
    public void seedRangeUsesCustomThenSetupThenQuickStatistic() {
        BinConfig cfg = twoChannelConfig("0-100", "None");
        RepresentativeSelection selection = selection();
        RepresentativeFigureConfig config = new RepresentativeFigureConfig();
        config.selection = selection;
        config.statistic = RepresentativeStatistic.QUICK;
        config.statTable = new RepresentativeStatTable();
        config.statTable.putValue("0", 0, 1, "Exp-Mouse1_LH_SCN", "Mouse1",
                "Control", "LH", "SCN", "DAPI", 999.0);

        RepresentativeRangeStage.ChannelRef channel =
                new RepresentativeRangeStage.ChannelRef(0, "DAPI", "Red");

        config.setCustomDisplayRangeForChannel(0, "3-33");
        assertEquals("3-33",
                RepresentativeRangeStage.seedRangeForChannel(config, cfg, selection, channel));

        config.clearCustomDisplayRanges();
        assertEquals("0-100",
                RepresentativeRangeStage.seedRangeForChannel(config, cfg, selection, channel));

        cfg.channelMinMax.set(0, "None");
        assertEquals("0-999",
                RepresentativeRangeStage.seedRangeForChannel(config, cfg, selection, channel));
    }

    private static BinConfig twoChannelConfig(String c1Range, String c2Range) {
        BinConfig cfg = new BinConfig();
        cfg.channelNames.add("DAPI");
        cfg.channelNames.add("GFAP");
        cfg.channelColors.add("Red");
        cfg.channelColors.add("Green");
        cfg.channelMinMax.add(c1Range);
        cfg.channelMinMax.add(c2Range);
        return cfg;
    }

    private static RepresentativeSelection selection() {
        Map<String, RepresentativeSeries> selected =
                new LinkedHashMap<String, RepresentativeSeries>();
        RepresentativeSeries series = new RepresentativeSeries(
                "0",
                0,
                1,
                "Exp-Mouse1_LH_SCN",
                "Mouse1",
                "Control",
                "LH",
                "SCN",
                null,
                Arrays.asList(
                        thumbnail(0, "DAPI"),
                        thumbnail(1, "GFAP")),
                image(),
                null,
                RepresentativeSeries.PreviewSource.GENERATED,
                false);
        selected.put("Control", series);
        return new RepresentativeSelection(Arrays.asList("Control"), selected);
    }

    private static RepresentativeSeries.ChannelThumbnail thumbnail(int index, String name) {
        return new RepresentativeSeries.ChannelThumbnail(index, name, image(), null);
    }

    private static BufferedImage image() {
        return new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB);
    }
}
