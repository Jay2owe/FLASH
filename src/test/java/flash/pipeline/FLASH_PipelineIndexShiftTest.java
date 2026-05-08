package flash.pipeline;

import flash.pipeline.analyses.Analysis;
import flash.pipeline.analyses.CreateBinFileAnalysis;
import flash.pipeline.analyses.DeconvolutionAnalysis;
import flash.pipeline.analyses.DrawAndSaveROIsAnalysis;
import flash.pipeline.analyses.ImageOrientationSetupAnalysis;
import flash.pipeline.analyses.IntensityAnalysisV2;
import flash.pipeline.analyses.LineDistanceAnalysis;
import flash.pipeline.analyses.MasterAggregationAnalysis;
import flash.pipeline.analyses.SpatialAnalysis;
import flash.pipeline.analyses.SplitAndMergeImageChannelsAnalysis;
import flash.pipeline.analyses.StatisticalAnalysis;
import flash.pipeline.analyses.ThreeDObjectAnalysis;
import flash.pipeline.decontamination.SpectralDecontaminationAnalysis;
import org.junit.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class FLASH_PipelineIndexShiftTest {

    @Test
    public void initAnalysesUsesExpectedIndexOrder() throws Exception {
        FLASH_Pipeline pipeline = new FLASH_Pipeline();
        invokeInitAnalyses(pipeline);
        Map<Integer, Analysis> analysisMap = analysisMap(pipeline);

        assertEquals(13, analysisMap.size());
        assertTrue(analysisMap.get(FLASH_Pipeline.IDX_CREATE_BIN) instanceof CreateBinFileAnalysis);
        assertTrue(analysisMap.get(FLASH_Pipeline.IDX_DRAW_ROIS) instanceof DrawAndSaveROIsAnalysis);
        assertTrue(analysisMap.get(FLASH_Pipeline.IDX_DECONVOLUTION) instanceof DeconvolutionAnalysis);
        assertTrue(analysisMap.get(FLASH_Pipeline.IDX_SPLIT_MERGE) instanceof SplitAndMergeImageChannelsAnalysis);
        assertTrue(analysisMap.get(FLASH_Pipeline.IDX_3D_OBJECT) instanceof ThreeDObjectAnalysis);
        assertTrue(analysisMap.get(FLASH_Pipeline.IDX_SPATIAL) instanceof SpatialAnalysis);
        assertTrue(analysisMap.get(FLASH_Pipeline.IDX_LINE_DISTANCE) instanceof LineDistanceAnalysis);
        assertTrue(analysisMap.get(FLASH_Pipeline.IDX_INTENSITY) instanceof IntensityAnalysisV2);
        assertTrue(analysisMap.get(FLASH_Pipeline.IDX_AGGREGATION) instanceof MasterAggregationAnalysis);
        assertTrue(analysisMap.get(FLASH_Pipeline.IDX_STATISTICS) instanceof StatisticalAnalysis);
        assertNotNull(analysisMap.get(FLASH_Pipeline.IDX_EXCEL_EXPORT));
        assertTrue(analysisMap.get(FLASH_Pipeline.IDX_SPECTRAL_DECONTAMINATION) instanceof SpectralDecontaminationAnalysis);
        assertTrue(analysisMap.get(FLASH_Pipeline.IDX_ORIENTATION_SETUP) instanceof ImageOrientationSetupAnalysis);
    }

    @Test
    public void selectionLabelsPlaceDeconvolutionAtIndexTwo() throws Exception {
        Field analysesField = FLASH_Pipeline.class.getDeclaredField("analyses");
        analysesField.setAccessible(true);
        String[] analyses = (String[]) analysesField.get(new FLASH_Pipeline());

        assertEquals(13, analyses.length);
        assertEquals("Set Up Configuration", analyses[FLASH_Pipeline.IDX_CREATE_BIN]);
        assertEquals("Draw and Save ROIs", analyses[FLASH_Pipeline.IDX_DRAW_ROIS]);
        assertEquals("3D Deconvolution", analyses[FLASH_Pipeline.IDX_DECONVOLUTION]);
        assertEquals("Split and Merge Image Channels", analyses[FLASH_Pipeline.IDX_SPLIT_MERGE]);
        assertEquals("Spectral Decontamination (Experimental)", analyses[FLASH_Pipeline.IDX_SPECTRAL_DECONTAMINATION]);
        assertEquals("Image Orientation Setup", analyses[FLASH_Pipeline.IDX_ORIENTATION_SETUP]);
    }

    @Test
    public void visibleAnalysisOrderGroupsMainDialogAndHidesStandaloneOrientation() throws Exception {
        Field orderField = FLASH_Pipeline.class.getDeclaredField("VISIBLE_ANALYSIS_ORDER");
        orderField.setAccessible(true);
        int[] visibleOrder = (int[]) orderField.get(null);

        assertEquals(11, visibleOrder.length);
        assertFalse(contains(visibleOrder, FLASH_Pipeline.IDX_LINE_DISTANCE));
        assertFalse(contains(visibleOrder, FLASH_Pipeline.IDX_ORIENTATION_SETUP));
        assertTrue(indexOf(visibleOrder, FLASH_Pipeline.IDX_CREATE_BIN)
                < indexOf(visibleOrder, FLASH_Pipeline.IDX_DRAW_ROIS));
        assertTrue(indexOf(visibleOrder, FLASH_Pipeline.IDX_DECONVOLUTION)
                < indexOf(visibleOrder, FLASH_Pipeline.IDX_SPECTRAL_DECONTAMINATION));
        assertTrue(indexOf(visibleOrder, FLASH_Pipeline.IDX_INTENSITY)
                < indexOf(visibleOrder, FLASH_Pipeline.IDX_3D_OBJECT));
        assertTrue(indexOf(visibleOrder, FLASH_Pipeline.IDX_AGGREGATION)
                < indexOf(visibleOrder, FLASH_Pipeline.IDX_STATISTICS));
    }

    @Test
    public void orchestratorNoLongerHasMissingBinPreflightGate() {
        assertFalse(hasDeclaredMethod("ensureValidBinForAnalysis"));
        assertFalse(hasDeclaredMethod("isBinDependentAnalysis"));
        assertFalse(hasDeclaredMethod("isSelfManagedBinSetup"));
        assertFalse(hasDeclaredMethod("isDeprecatedAnalysis"));
    }

    private static boolean contains(int[] values, int target) {
        return indexOf(values, target) >= 0;
    }

    private static int indexOf(int[] values, int target) {
        for (int i = 0; i < values.length; i++) {
            if (values[i] == target) return i;
        }
        return -1;
    }

    private static void invokeInitAnalyses(FLASH_Pipeline pipeline) throws Exception {
        Method initAnalyses = FLASH_Pipeline.class.getDeclaredMethod("initAnalyses");
        initAnalyses.setAccessible(true);
        initAnalyses.invoke(pipeline);
    }

    private static boolean hasDeclaredMethod(String name) {
        Method[] methods = FLASH_Pipeline.class.getDeclaredMethods();
        for (Method method : methods) {
            if (name.equals(method.getName())) return true;
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    private static Map<Integer, Analysis> analysisMap(FLASH_Pipeline pipeline) throws Exception {
        Field analysisMap = FLASH_Pipeline.class.getDeclaredField("analysisMap");
        analysisMap.setAccessible(true);
        return (Map<Integer, Analysis>) analysisMap.get(pipeline);
    }
}
