package flash.pipeline;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Pure-logic coverage for the analysis-picker condition preflight decision,
 * without constructing the Swing picker dialog.
 */
public class FLASHPipelineConditionPreflightTest {

    @Test
    public void aggregationNeedsPreflight() {
        assertTrue(needs(sel(FLASH_Pipeline.IDX_AGGREGATION), false));
    }

    @Test
    public void statisticsNeedsPreflight() {
        assertTrue(needs(sel(FLASH_Pipeline.IDX_STATISTICS), false));
    }

    @Test
    public void excelExportNeedsPreflight() {
        assertTrue(needs(sel(FLASH_Pipeline.IDX_EXCEL_EXPORT), false));
    }

    @Test
    public void representativeFigureNeedsPreflight() {
        assertTrue(needs(sel(FLASH_Pipeline.IDX_REPRESENTATIVE_FIGURE), false));
    }

    @Test
    public void objectAnalysisWithAutoAggregateNeedsPreflight() {
        assertTrue(needs(sel(FLASH_Pipeline.IDX_3D_OBJECT), true));
    }

    @Test
    public void spatialAnalysisWithAutoAggregateNeedsPreflight() {
        assertTrue(needs(sel(FLASH_Pipeline.IDX_SPATIAL), true));
    }

    @Test
    public void intensityAnalysisWithAutoAggregateNeedsPreflight() {
        assertTrue(needs(sel(FLASH_Pipeline.IDX_INTENSITY), true));
    }

    @Test
    public void objectAnalysisWithoutAutoAggregateDoesNotNeedPreflight() {
        assertFalse(needs(sel(FLASH_Pipeline.IDX_3D_OBJECT), false));
        assertFalse(needs(sel(FLASH_Pipeline.IDX_SPATIAL), false));
        assertFalse(needs(sel(FLASH_Pipeline.IDX_INTENSITY), false));
    }

    @Test
    public void nonConditionWorkflowsDoNotNeedPreflight() {
        boolean[] selections = sel(
                FLASH_Pipeline.IDX_CREATE_BIN,
                FLASH_Pipeline.IDX_DRAW_ROIS,
                FLASH_Pipeline.IDX_DECONVOLUTION,
                FLASH_Pipeline.IDX_SPLIT_MERGE,
                FLASH_Pipeline.IDX_LINE_DISTANCE,
                FLASH_Pipeline.IDX_SPECTRAL_DECONTAMINATION);
        // Even with auto-aggregation enabled, none of these produce grouped tables.
        assertFalse(needs(selections, true));
    }

    @Test
    public void nullAndEmptySelectionsDoNotNeedPreflight() {
        assertFalse(needs(null, true));
        assertFalse(needs(new boolean[13], true));
    }

    private static boolean needs(boolean[] selections, boolean autoAggregate) {
        return FLASH_Pipeline.selectionNeedsConditionPreflight(selections, autoAggregate);
    }

    private static boolean[] sel(int... indices) {
        boolean[] selections = new boolean[13];
        for (int index : indices) {
            selections[index] = true;
        }
        return selections;
    }
}
