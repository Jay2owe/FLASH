package flash.pipeline.representative;

import org.junit.Test;

import javax.swing.JPanel;
import java.awt.Component;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class RepresentativeStatisticChoicePanelTest {

    @Test
    public void existingResultDetailsOnlyEnableWhenExistingResultIsSelected() {
        RepresentativeStatisticChoicePanel panel = new RepresentativeStatisticChoicePanel(
                RepresentativeStatistic.QUICK,
                new String[]{"AR488.csv :: Region ID"},
                "AR488.csv :: Region ID",
                true);

        assertEquals(RepresentativeStatistic.QUICK, panel.getSelectedStatistic());
        assertFalse(panel.isExistingResultDetailsEnabledForTests());

        panel.selectStatisticForTests(RepresentativeStatistic.EXISTING_RESULT);

        assertEquals(RepresentativeStatistic.EXISTING_RESULT, panel.getSelectedStatistic());
        assertTrue(panel.isExistingResultDetailsEnabledForTests());
        assertEquals("AR488.csv :: Region ID", panel.getSelectedExistingLabel());

        panel.selectStatisticForTests(RepresentativeStatistic.NONE);

        assertEquals(RepresentativeStatistic.NONE, panel.getSelectedStatistic());
        assertFalse(panel.isExistingResultDetailsEnabledForTests());
    }

    @Test
    public void existingResultTileIsDisabledWhenNoExistingResultsAreAvailable() {
        RepresentativeStatisticChoicePanel panel = new RepresentativeStatisticChoicePanel(
                RepresentativeStatistic.EXISTING_RESULT,
                new String[]{RepresentativeStatLoader.NO_EXISTING_RESULT_OPTION},
                RepresentativeStatLoader.NO_EXISTING_RESULT_OPTION,
                false);

        assertEquals(RepresentativeStatistic.QUICK, panel.getSelectedStatistic());
        assertFalse(panel.isExistingResultTileEnabledForTests());
        assertFalse(panel.isExistingResultDetailsEnabledForTests());

        panel.selectStatisticForTests(RepresentativeStatistic.EXISTING_RESULT);

        assertEquals(RepresentativeStatistic.QUICK, panel.getSelectedStatistic());
        assertFalse(panel.isExistingResultDetailsEnabledForTests());
    }

    @Test
    public void statisticCardsGrowToFitWrappedCaptionsAndChip() {
        RepresentativeStatisticChoicePanel panel = new RepresentativeStatisticChoicePanel(
                RepresentativeStatistic.QUICK,
                new String[]{"AR488.csv :: Region ID"},
                "AR488.csv :: Region ID",
                true);

        JPanel tileRow = (JPanel) panel.getComponent(0);
        assertTrue("Statistic cards should grow beyond the legacy fixed tile height",
                tileRow.getPreferredSize().height > 104);
        for (Component child : tileRow.getComponents()) {
            assertTrue("Statistic card content should fit inside the row height",
                    child.getPreferredSize().height <= tileRow.getPreferredSize().height);
        }
    }
}
