package flash.pipeline.representative;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class RepresentativeStatsPanelTest {

    @Test
    public void buildChartDataComputesConditionMeansAndSkipsNonFiniteValues() {
        RepresentativeStatTable table = new RepresentativeStatTable();
        add(table, "0", "Control", "Mouse1", "DAPI", 10.0);
        add(table, "1", "Control", "Mouse2", "DAPI", 14.0);
        add(table, "2", "Treatment", "Mouse3", "DAPI", 20.0);
        add(table, "0", "Control", "Mouse1", "GFAP", 4.0);
        add(table, "1", "Control", "Mouse2", "GFAP", Double.NaN);

        List<RepresentativeStatsPanel.ChannelChartData> charts =
                RepresentativeStatsPanel.buildChartData(table);

        assertEquals(2, charts.size());

        RepresentativeStatsPanel.ChannelChartData dapi = charts.get(0);
        assertEquals("DAPI", dapi.channelName);
        assertEquals(2, dapi.conditions.size());
        assertEquals(12.0, dapi.condition("Control").mean, 0.0001);
        assertEquals(20.0, dapi.condition("Treatment").mean, 0.0001);
        assertEquals(2, dapi.condition("Control").points.size());

        RepresentativeStatsPanel.ChannelChartData gfap = charts.get(1);
        assertEquals("GFAP", gfap.channelName);
        assertEquals(1, gfap.conditions.size());
        assertEquals(4.0, gfap.condition("Control").mean, 0.0001);
        assertNull(gfap.condition("Treatment"));
    }

    @Test
    public void highlightedSeriesIdUpdatesAllChartsModelState() {
        RepresentativeStatTable table = new RepresentativeStatTable();
        add(table, "0", "Control", "Mouse1", "DAPI", 10.0);
        add(table, "0", "Control", "Mouse1", "GFAP", 4.0);

        RepresentativeStatsPanel panel = new RepresentativeStatsPanel(table);
        assertTrue(panel.hasChartsForTest());

        panel.setHighlightedSeriesId("0");

        assertEquals("0", panel.highlightedSeriesIdForTest());
        assertEquals(2, panel.chartCountForTest());
    }

    private static void add(RepresentativeStatTable table,
                            String seriesId,
                            String condition,
                            String animal,
                            String channel,
                            double value) {
        int seriesIndex = Integer.parseInt(seriesId);
        table.putValue(seriesId, seriesIndex, seriesIndex + 1,
                "Series " + seriesId, animal, condition, "LH", "SCN",
                channel, value);
    }
}
