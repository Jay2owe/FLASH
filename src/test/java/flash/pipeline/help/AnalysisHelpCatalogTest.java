package flash.pipeline.help;

import flash.pipeline.FLASH_Pipeline;
import org.junit.Test;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class AnalysisHelpCatalogTest {

    @Test
    public void everyVisibleAnalysisHasTopic() throws Exception {
        int[] visibleOrder = visibleAnalysisOrder();
        for (int i = 0; i < visibleOrder.length; i++) {
            int analysisIndex = visibleOrder[i];
            assertTrue("missing help topic for visible analysis index " + analysisIndex,
                    AnalysisHelpCatalog.hasTopic(analysisIndex));

            AnalysisHelpTopic topic = AnalysisHelpCatalog.forAnalysis(analysisIndex);
            assertNotNull(topic);
            assertEquals(analysisIndex, topic.analysisIndex);
            assertNonBlank(topic.key);
            assertNonBlank(topic.title);
            assertNonBlank(topic.summary);
            assertFalse(topic.whenToUse.isEmpty());
            assertFalse(topic.inputs.isEmpty());
            assertFalse(topic.setup.isEmpty());
            assertFalse(topic.workflow.isEmpty());
            assertFalse(topic.outputs.isEmpty());
            assertFalse(topic.pitfalls.isEmpty());
        }
    }

    @Test
    public void catalogContainsOnlyVisibleAnalysisTopics() throws Exception {
        Set<Integer> visible = new HashSet<Integer>();
        int[] visibleOrder = visibleAnalysisOrder();
        for (int i = 0; i < visibleOrder.length; i++) {
            visible.add(Integer.valueOf(visibleOrder[i]));
        }

        Map<Integer, AnalysisHelpTopic> all = AnalysisHelpCatalog.all();
        assertEquals(visible.size(), all.size());
        for (Integer index : all.keySet()) {
            assertTrue("catalog topic is not in VISIBLE_ANALYSIS_ORDER: " + index,
                    visible.contains(index));
        }
    }

    @Test
    public void hiddenDeprecatedAnalysesAreNotRequiredTopics() throws Exception {
        int[] visibleOrder = visibleAnalysisOrder();

        assertFalse(contains(visibleOrder, FLASH_Pipeline.IDX_NUCLEAR));
        assertFalse(AnalysisHelpCatalog.hasTopic(FLASH_Pipeline.IDX_NUCLEAR));

        assertFalse(contains(visibleOrder, FLASH_Pipeline.IDX_LINE_DISTANCE));
        assertFalse(AnalysisHelpCatalog.hasTopic(FLASH_Pipeline.IDX_LINE_DISTANCE));

        assertFalse(contains(visibleOrder, FLASH_Pipeline.IDX_ORIENTATION_SETUP));
        assertFalse(AnalysisHelpCatalog.hasTopic(FLASH_Pipeline.IDX_ORIENTATION_SETUP));
    }

    @Test
    public void visibleOrderHelperReturnsDefensiveCopy() {
        int[] first = FLASH_Pipeline.visibleAnalysisOrderForTests();
        int originalFirstIndex = first[0];
        first[0] = FLASH_Pipeline.IDX_NUCLEAR;

        int[] second = FLASH_Pipeline.visibleAnalysisOrderForTests();

        assertEquals(originalFirstIndex, second[0]);
        assertFalse(contains(second, FLASH_Pipeline.IDX_NUCLEAR));
    }

    @Test
    public void visibleRowsResolveToMatchingHelpTopics() {
        FLASH_Pipeline pipeline = new FLASH_Pipeline();
        int[] visibleOrder = FLASH_Pipeline.visibleAnalysisOrderForTests();

        for (int i = 0; i < visibleOrder.length; i++) {
            int analysisIndex = visibleOrder[i];
            AnalysisHelpTopic topic = FLASH_Pipeline.analysisHelpTopicForTests(analysisIndex);

            assertNotNull("missing row help topic for index " + analysisIndex, topic);
            assertEquals("row help opens wrong topic for index " + analysisIndex,
                    analysisIndex, topic.analysisIndex);
            assertEquals("topic title should match the visible row label",
                    pipeline.analysisLabelForTests(analysisIndex), topic.title);
        }

        assertFalse(contains(visibleOrder, FLASH_Pipeline.IDX_NUCLEAR));
        assertFalse(contains(visibleOrder, FLASH_Pipeline.IDX_LINE_DISTANCE));
        assertFalse(contains(visibleOrder, FLASH_Pipeline.IDX_ORIENTATION_SETUP));
        assertTrue(FLASH_Pipeline.analysisHelpTopicForTests(FLASH_Pipeline.IDX_NUCLEAR) == null);
        assertTrue(FLASH_Pipeline.analysisHelpTopicForTests(FLASH_Pipeline.IDX_LINE_DISTANCE) == null);
        assertTrue(FLASH_Pipeline.analysisHelpTopicForTests(FLASH_Pipeline.IDX_ORIENTATION_SETUP) == null);
    }

    @Test(expected = UnsupportedOperationException.class)
    public void allReturnsImmutableMap() {
        AnalysisHelpCatalog.all().clear();
    }

    @Test(expected = UnsupportedOperationException.class)
    public void topicListsAreImmutable() {
        AnalysisHelpCatalog.forAnalysis(FLASH_Pipeline.IDX_CREATE_BIN)
                .workflow.add("mutated");
    }

    @Test
    public void stableTopicKeysMatchVisibleAnalyses() {
        assertEquals("set-up-configuration",
                AnalysisHelpCatalog.forAnalysis(FLASH_Pipeline.IDX_CREATE_BIN).key);
        assertEquals("draw-save-rois",
                AnalysisHelpCatalog.forAnalysis(FLASH_Pipeline.IDX_DRAW_ROIS).key);
        assertEquals("deconvolution",
                AnalysisHelpCatalog.forAnalysis(FLASH_Pipeline.IDX_DECONVOLUTION).key);
        assertEquals("spectral-decontamination",
                AnalysisHelpCatalog.forAnalysis(FLASH_Pipeline.IDX_SPECTRAL_DECONTAMINATION).key);
        assertEquals("split-merge",
                AnalysisHelpCatalog.forAnalysis(FLASH_Pipeline.IDX_SPLIT_MERGE).key);
        assertEquals("intensity",
                AnalysisHelpCatalog.forAnalysis(FLASH_Pipeline.IDX_INTENSITY).key);
        assertEquals("three-d-object",
                AnalysisHelpCatalog.forAnalysis(FLASH_Pipeline.IDX_3D_OBJECT).key);
        assertEquals("spatial",
                AnalysisHelpCatalog.forAnalysis(FLASH_Pipeline.IDX_SPATIAL).key);
        assertEquals("aggregation",
                AnalysisHelpCatalog.forAnalysis(FLASH_Pipeline.IDX_AGGREGATION).key);
        assertEquals("statistics",
                AnalysisHelpCatalog.forAnalysis(FLASH_Pipeline.IDX_STATISTICS).key);
        assertEquals("excel-export",
                AnalysisHelpCatalog.forAnalysis(FLASH_Pipeline.IDX_EXCEL_EXPORT).key);
    }

    @Test
    public void setupAndPrepTopicTitlesMatchMainDialogLabels() throws Exception {
        String[] labels = analysisLabels();
        int[] indices = setupAndPrepTopicIndices();

        for (int i = 0; i < indices.length; i++) {
            int analysisIndex = indices[i];
            assertEquals(labels[analysisIndex], AnalysisHelpCatalog.forAnalysis(analysisIndex).title);
        }
    }

    @Test
    public void setupAndPrepTopicsHaveCompletedContentAndImageReferences() {
        int[] indices = setupAndPrepTopicIndices();

        for (int i = 0; i < indices.length; i++) {
            AnalysisHelpTopic topic = AnalysisHelpCatalog.forAnalysis(indices[i]);
            assertNotNull(topic);
            assertFalse("missing image references for " + topic.key, topic.images.isEmpty());
            assertNoPlaceholderText(topic.whenToUse, topic.key);
            assertNoPlaceholderText(topic.inputs, topic.key);
            assertNoPlaceholderText(topic.setup, topic.key);
            assertNoPlaceholderText(topic.workflow, topic.key);
            assertNoPlaceholderText(topic.outputs, topic.key);
            assertNoPlaceholderText(topic.pitfalls, topic.key);
        }
    }

    @Test
    public void coreAnalysisTopicsHaveCompletedContentAndImageReferences() {
        int[] indices = coreAnalysisTopicIndices();

        for (int i = 0; i < indices.length; i++) {
            AnalysisHelpTopic topic = AnalysisHelpCatalog.forAnalysis(indices[i]);
            assertNotNull(topic);
            assertFalse("missing image references for " + topic.key, topic.images.isEmpty());
            assertNoPlaceholderText(topic.whenToUse, topic.key);
            assertNoPlaceholderText(topic.inputs, topic.key);
            assertNoPlaceholderText(topic.setup, topic.key);
            assertNoPlaceholderText(topic.workflow, topic.key);
            assertNoPlaceholderText(topic.outputs, topic.key);
            assertNoPlaceholderText(topic.pitfalls, topic.key);
        }
    }

    @Test
    public void spatialTopicHasCompletedContentAndImageReferences() {
        AnalysisHelpTopic topic = AnalysisHelpCatalog.forAnalysis(FLASH_Pipeline.IDX_SPATIAL);
        assertNotNull(topic);
        assertContains(topic.summary, "3D Object Analysis");
        assertContains(topic.whenToUse, "without resegmenting images");
        assertContains(topic.inputs, "FLASH/05 - 3D Object Analysis/Objects/");
        assertContains(topic.workflow, "update the same object CSV tables");
        assertContains(topic.outputs, "FLASH/06 - Spatial Analysis/Spatial/");
        assertContains(topic.outputs, "FLASH/06 - Spatial Analysis/Morphometry/");
        assertContains(topic.pitfalls, "Bad segmentation creates bad spatial findings");
        assertFalse("missing image references for " + topic.key, topic.images.isEmpty());

        assertNoPlaceholderText(topic.whenToUse, topic.key);
        assertNoPlaceholderText(topic.inputs, topic.key);
        assertNoPlaceholderText(topic.setup, topic.key);
        assertNoPlaceholderText(topic.workflow, topic.key);
        assertNoPlaceholderText(topic.outputs, topic.key);
        assertNoPlaceholderText(topic.pitfalls, topic.key);
    }

    @Test
    public void coreAnalysisTopicsNameRequiredDependencies() {
        assertContains(AnalysisHelpCatalog.forAnalysis(FLASH_Pipeline.IDX_SPLIT_MERGE).inputs,
                "Set Up Configuration");
        assertContains(AnalysisHelpCatalog.forAnalysis(FLASH_Pipeline.IDX_INTENSITY).inputs,
                "Set Up Configuration");
        assertContains(AnalysisHelpCatalog.forAnalysis(FLASH_Pipeline.IDX_INTENSITY).inputs,
                "Draw and Save ROIs");
        assertContains(AnalysisHelpCatalog.forAnalysis(FLASH_Pipeline.IDX_3D_OBJECT).inputs,
                "Set Up Configuration");
        assertContains(AnalysisHelpCatalog.forAnalysis(FLASH_Pipeline.IDX_3D_OBJECT).inputs,
                "Draw and Save ROIs");
    }

    @Test
    public void drawRoisTopicOwnsOrientationGuidance() {
        AnalysisHelpTopic topic = AnalysisHelpCatalog.forAnalysis(FLASH_Pipeline.IDX_DRAW_ROIS);

        assertContains(topic.summary, "rotate/flip controls");
        assertContains(topic.setup, "always-available orientation panel");
        assertContains(topic.setup, "if you do nothing, FLASH keeps the current orientation");
        assertContains(topic.workflow, "saved transforms");
        assertContains(topic.outputs, "Project_Image_Orientation.csv");
        assertContains(topic.pitfalls, "Changing orientation after drawing an unsaved ROI");
    }

    @Test
    public void splitMergeTopicDistinguishesDisplayFromQuantification() {
        AnalysisHelpTopic topic = AnalysisHelpCatalog.forAnalysis(FLASH_Pipeline.IDX_SPLIT_MERGE);

        assertContains(topic.summary, "display-ready");
        assertContains(topic.whenToUse, "only need quantitative CSV measurements");
        assertContains(topic.pitfalls, "display choices, not quantitative fluorescence measurements");
        assertContains(topic.pitfalls, "Do not use display-enhanced PNGs");
    }

    @Test
    public void setupAndPrepOutputPathsMatchCurrentLayout() {
        assertContains(AnalysisHelpCatalog.forAnalysis(FLASH_Pipeline.IDX_CREATE_BIN).outputs,
                "FLASH/00 - Configuration/Channel_Data.txt");
        assertContains(AnalysisHelpCatalog.forAnalysis(FLASH_Pipeline.IDX_DRAW_ROIS).outputs,
                "FLASH/01 - Regions of Interest/ROI Sets/");
        assertContains(AnalysisHelpCatalog.forAnalysis(FLASH_Pipeline.IDX_DRAW_ROIS).outputs,
                "ImageJ Exports/Project_Image_Orientation.csv");
        assertContains(AnalysisHelpCatalog.forAnalysis(FLASH_Pipeline.IDX_DECONVOLUTION).outputs,
                "FLASH/02 - 3D Deconvolution/");
        assertContains(AnalysisHelpCatalog.forAnalysis(FLASH_Pipeline.IDX_SPECTRAL_DECONTAMINATION).outputs,
                "FLASH/08 - Spectral Decontamination/");
    }

    @Test
    public void coreAnalysisOutputPathsMatchCurrentLayout() {
        assertContains(AnalysisHelpCatalog.forAnalysis(FLASH_Pipeline.IDX_SPLIT_MERGE).outputs,
                "FLASH/03 - Split and Merge/Images/");
        assertContains(AnalysisHelpCatalog.forAnalysis(FLASH_Pipeline.IDX_SPLIT_MERGE).outputs,
                "FLASH/03 - Split and Merge/OME-TIFF/");
        assertContains(AnalysisHelpCatalog.forAnalysis(FLASH_Pipeline.IDX_INTENSITY).outputs,
                "FLASH/04 - Fluorescence Intensity/<channel>.csv");
        assertContains(AnalysisHelpCatalog.forAnalysis(FLASH_Pipeline.IDX_3D_OBJECT).outputs,
                "FLASH/05 - 3D Object Analysis/Objects/<channel>.csv");
        assertContains(AnalysisHelpCatalog.forAnalysis(FLASH_Pipeline.IDX_3D_OBJECT).outputs,
                "FLASH/05 - 3D Object Analysis/Image Outputs/");
    }

    @Test
    public void resultsExportTopicsHaveCompletedContentAndImageReferences() {
        int[] indices = resultsExportTopicIndices();

        for (int i = 0; i < indices.length; i++) {
            AnalysisHelpTopic topic = AnalysisHelpCatalog.forAnalysis(indices[i]);
            assertNotNull(topic);
            assertFalse("missing image references for " + topic.key, topic.images.isEmpty());
            assertNoPlaceholderText(topic.whenToUse, topic.key);
            assertNoPlaceholderText(topic.inputs, topic.key);
            assertNoPlaceholderText(topic.setup, topic.key);
            assertNoPlaceholderText(topic.workflow, topic.key);
            assertNoPlaceholderText(topic.outputs, topic.key);
            assertNoPlaceholderText(topic.pitfalls, topic.key);
        }
    }

    @Test
    public void resultsExportTopicsNameRequiredDependencies() {
        AnalysisHelpTopic aggregation = AnalysisHelpCatalog.forAnalysis(FLASH_Pipeline.IDX_AGGREGATION);
        assertContains(aggregation.inputs, "FLASH/04 - Fluorescence Intensity/");
        assertContains(aggregation.inputs, "FLASH/05 - 3D Object Analysis/");
        assertContains(aggregation.inputs, "FLASH/06 - Spatial Analysis/");
        assertContains(aggregation.inputs, "FLASH/09 - Result Aggregation/Project_Conditions.csv");

        AnalysisHelpTopic statistics = AnalysisHelpCatalog.forAnalysis(FLASH_Pipeline.IDX_STATISTICS);
        assertContains(statistics.whenToUse, "Combine results per condition / animal");
        assertContains(statistics.inputs, "FLASH/09 - Result Aggregation/Project_Master_Objects.csv");
        assertContains(statistics.inputs, "Project_Master_Intensities.csv");
        assertContains(statistics.inputs, "FLASH/09 - Result Aggregation/Project_Conditions.csv");

        AnalysisHelpTopic excel = AnalysisHelpCatalog.forAnalysis(FLASH_Pipeline.IDX_EXCEL_EXPORT);
        assertContains(excel.whenToUse, "after aggregation");
        assertContains(excel.inputs, "FLASH/09 - Result Aggregation/Project_Master_Objects.csv");
        assertContains(excel.inputs, "FLASH/09 - Result Aggregation/Project_Conditions.csv");
        assertContains(excel.inputs, "FLASH/10 - Statistical Analysis/Project_Statistics.csv");
    }

    @Test
    public void resultsExportOutputPathsMatchCurrentLayout() {
        assertContains(AnalysisHelpCatalog.forAnalysis(FLASH_Pipeline.IDX_AGGREGATION).outputs,
                "FLASH/09 - Result Aggregation/Project_Master_Objects.csv");
        assertContains(AnalysisHelpCatalog.forAnalysis(FLASH_Pipeline.IDX_AGGREGATION).outputs,
                "FLASH/09 - Result Aggregation/Project_Master_Intensities.csv");
        assertContains(AnalysisHelpCatalog.forAnalysis(FLASH_Pipeline.IDX_STATISTICS).outputs,
                "FLASH/10 - Statistical Analysis/Project_Statistics.csv");
        assertContains(AnalysisHelpCatalog.forAnalysis(FLASH_Pipeline.IDX_EXCEL_EXPORT).outputs,
                "FLASH/11 - Excel Summary Export/Project_Summary.xlsx");
    }

    @Test
    public void resultsExportTopicsDoNotRestoreNuclearCounterContent() {
        int[] indices = resultsExportTopicIndices();
        for (int i = 0; i < indices.length; i++) {
            AnalysisHelpTopic topic = AnalysisHelpCatalog.forAnalysis(indices[i]);
            assertFalse(topic.title.contains("Nuclear Counter"));
            assertFalse(topic.summary.contains("Nuclear Counter"));
            assertNoNuclearCounterText(topic.whenToUse, topic.key);
            assertNoNuclearCounterText(topic.inputs, topic.key);
            assertNoNuclearCounterText(topic.setup, topic.key);
            assertNoNuclearCounterText(topic.workflow, topic.key);
            assertNoNuclearCounterText(topic.outputs, topic.key);
            assertNoNuclearCounterText(topic.pitfalls, topic.key);
        }
    }

    private static int[] visibleAnalysisOrder() throws Exception {
        return FLASH_Pipeline.visibleAnalysisOrderForTests();
    }

    private static String[] analysisLabels() throws Exception {
        FLASH_Pipeline pipeline = new FLASH_Pipeline();
        int[] visibleOrder = FLASH_Pipeline.visibleAnalysisOrderForTests();
        String[] labels = new String[FLASH_Pipeline.IDX_ORIENTATION_SETUP + 1];
        for (int i = 0; i < visibleOrder.length; i++) {
            labels[visibleOrder[i]] = pipeline.analysisLabelForTests(visibleOrder[i]);
        }
        return labels;
    }

    private static int[] setupAndPrepTopicIndices() {
        return new int[]{
                FLASH_Pipeline.IDX_CREATE_BIN,
                FLASH_Pipeline.IDX_DRAW_ROIS,
                FLASH_Pipeline.IDX_DECONVOLUTION,
                FLASH_Pipeline.IDX_SPECTRAL_DECONTAMINATION
        };
    }

    private static int[] coreAnalysisTopicIndices() {
        return new int[]{
                FLASH_Pipeline.IDX_SPLIT_MERGE,
                FLASH_Pipeline.IDX_INTENSITY,
                FLASH_Pipeline.IDX_3D_OBJECT
        };
    }

    private static int[] resultsExportTopicIndices() {
        return new int[]{
                FLASH_Pipeline.IDX_AGGREGATION,
                FLASH_Pipeline.IDX_STATISTICS,
                FLASH_Pipeline.IDX_EXCEL_EXPORT
        };
    }

    private static boolean contains(int[] values, int target) {
        for (int i = 0; i < values.length; i++) {
            if (values[i] == target) return true;
        }
        return false;
    }

    private static void assertNonBlank(String value) {
        assertNotNull(value);
        assertFalse(value.trim().isEmpty());
    }

    private static void assertNoPlaceholderText(Iterable<String> values, String topicKey) {
        for (String value : values) {
            assertFalse("placeholder text remains in " + topicKey + ": " + value,
                    value.contains("selected project needs this analysis step")
                            || value.contains("standard FLASH outputs for this module")
                            || value.contains("Review inputs, run the module"));
        }
    }

    private static void assertNoNuclearCounterText(Iterable<String> values, String topicKey) {
        for (String value : values) {
            assertFalse("Nuclear Counter content appears in " + topicKey + ": " + value,
                    value.contains("Nuclear Counter"));
        }
    }

    private static void assertContains(Iterable<String> values, String expected) {
        for (String value : values) {
            if (value.contains(expected)) {
                return;
            }
        }
        assertTrue("expected output text to contain: " + expected, false);
    }

    private static void assertContains(String value, String expected) {
        assertNotNull(value);
        assertTrue("expected text to contain: " + expected, value.contains(expected));
    }
}
