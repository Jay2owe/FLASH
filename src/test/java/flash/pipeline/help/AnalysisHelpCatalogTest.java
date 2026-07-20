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
            assertNonBlank(topic.whatItDoes);
            assertFalse(topic.needsFirst.isEmpty());
            assertFalse(topic.produces.isEmpty());
            assertFalse(topic.workflow.isEmpty());
            assertFalse(topic.watchOut.isEmpty());
        }
    }

    @Test
    public void deconvolutionWorkflowMentionsPreview() {
        AnalysisHelpTopic topic = AnalysisHelpCatalog.forAnalysis(FLASH_Pipeline.IDX_DECONVOLUTION);
        assertContains(topic.workflow, "Preview");
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
    public void hiddenAnalysesAreNotRequiredTopics() throws Exception {
        int[] visibleOrder = visibleAnalysisOrder();

        assertFalse(contains(visibleOrder, FLASH_Pipeline.IDX_LINE_DISTANCE));
        assertFalse(AnalysisHelpCatalog.hasTopic(FLASH_Pipeline.IDX_LINE_DISTANCE));

    }

    @Test
    public void visibleOrderHelperReturnsDefensiveCopy() {
        int[] first = FLASH_Pipeline.visibleAnalysisOrderForTests();
        int originalFirstIndex = first[0];
        first[0] = FLASH_Pipeline.IDX_LINE_DISTANCE;

        int[] second = FLASH_Pipeline.visibleAnalysisOrderForTests();

        assertEquals(originalFirstIndex, second[0]);
        assertFalse(contains(second, FLASH_Pipeline.IDX_LINE_DISTANCE));
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

        assertFalse(contains(visibleOrder, FLASH_Pipeline.IDX_LINE_DISTANCE));
        assertTrue(FLASH_Pipeline.analysisHelpTopicForTests(FLASH_Pipeline.IDX_LINE_DISTANCE) == null);
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
        assertEquals("representative-figure",
                AnalysisHelpCatalog.forAnalysis(FLASH_Pipeline.IDX_REPRESENTATIVE_FIGURE).key);
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
            assertNoPlaceholderText(topic);
        }
    }

    @Test
    public void coreAnalysisTopicsHaveCompletedContentAndImageReferences() {
        int[] indices = coreAnalysisTopicIndices();

        for (int i = 0; i < indices.length; i++) {
            AnalysisHelpTopic topic = AnalysisHelpCatalog.forAnalysis(indices[i]);
            assertNotNull(topic);
            assertFalse("missing image references for " + topic.key, topic.images.isEmpty());
            assertNoPlaceholderText(topic);
        }
    }

    @Test
    public void spatialTopicHasCompletedContentAndImageReferences() {
        AnalysisHelpTopic topic = AnalysisHelpCatalog.forAnalysis(FLASH_Pipeline.IDX_SPATIAL);
        assertNotNull(topic);
        assertContains(topic.whatItDoes, "3D Object Analysis");
        assertContains(topic.needsFirst, "FLASH/Results/Tables/Objects/");
        assertContains(topic.produces, "FLASH/Results/Tables/Spatial/");
        assertContains(topic.produces, "FLASH/Results/Tables/Morphometry/");
        assertContains(topic.watchOut, "Bad segmentation creates bad spatial findings");
        assertFalse("missing image references for " + topic.key, topic.images.isEmpty());

        assertNoPlaceholderText(topic);
    }

    @Test
    public void spatialTopicWatchOutMentionsForceRerun() {
        AnalysisHelpTopic topic = AnalysisHelpCatalog.forAnalysis(FLASH_Pipeline.IDX_SPATIAL);
        assertContains(topic.watchOut, "Force re-run");
    }

    @Test
    public void coreAnalysisTopicsNameRequiredDependencies() {
        assertContains(AnalysisHelpCatalog.forAnalysis(FLASH_Pipeline.IDX_SPLIT_MERGE).needsFirst,
                "Channel config");
        assertContains(AnalysisHelpCatalog.forAnalysis(FLASH_Pipeline.IDX_INTENSITY).needsFirst,
                "Channel config");
        assertContains(AnalysisHelpCatalog.forAnalysis(FLASH_Pipeline.IDX_INTENSITY).needsFirst,
                "ROI");
        assertContains(AnalysisHelpCatalog.forAnalysis(FLASH_Pipeline.IDX_3D_OBJECT).needsFirst,
                "Channel config");
        assertContains(AnalysisHelpCatalog.forAnalysis(FLASH_Pipeline.IDX_3D_OBJECT).needsFirst,
                "ROI");
    }

    @Test
    public void drawRoisTopicOwnsOrientationGuidance() {
        AnalysisHelpTopic topic = AnalysisHelpCatalog.forAnalysis(FLASH_Pipeline.IDX_DRAW_ROIS);

        assertContains(topic.whatItDoes, "rotate/flip");
        assertContains(topic.produces, "Image Orientation.csv");
        assertContains(topic.watchOut, "Changing orientation after drawing an unsaved ROI");
    }

    @Test
    public void splitMergeTopicDistinguishesDisplayFromQuantification() {
        AnalysisHelpTopic topic = AnalysisHelpCatalog.forAnalysis(FLASH_Pipeline.IDX_SPLIT_MERGE);

        assertContains(topic.whatItDoes, "display");
        assertContains(topic.watchOut, "display choices, not quantitative");
        assertContains(topic.watchOut, "display-enhanced PNGs");
    }

    @Test
    public void setupAndPrepOutputPathsMatchCurrentLayout() {
        assertContains(AnalysisHelpCatalog.forAnalysis(FLASH_Pipeline.IDX_CREATE_BIN).produces,
                "FLASH/Config/.settings/channel_config.json");
        assertContains(AnalysisHelpCatalog.forAnalysis(FLASH_Pipeline.IDX_DRAW_ROIS).produces,
                "FLASH/Results/Analysis Images/ROIs/");
        assertContains(AnalysisHelpCatalog.forAnalysis(FLASH_Pipeline.IDX_DRAW_ROIS).produces,
                "FLASH/Results/Tables/Project Summary/Image Orientation.csv");
        assertContains(AnalysisHelpCatalog.forAnalysis(FLASH_Pipeline.IDX_DECONVOLUTION).produces,
                "FLASH/Results/Analysis Images/Deconvolution/");
        assertContains(AnalysisHelpCatalog.forAnalysis(FLASH_Pipeline.IDX_SPECTRAL_DECONTAMINATION).produces,
                "FLASH/Results/Tables/Spectral Decontamination/");
    }

    @Test
    public void coreAnalysisOutputPathsMatchCurrentLayout() {
        assertContains(AnalysisHelpCatalog.forAnalysis(FLASH_Pipeline.IDX_SPLIT_MERGE).produces,
                "FLASH/Results/Presentation Images/Images/");
        assertContains(AnalysisHelpCatalog.forAnalysis(FLASH_Pipeline.IDX_SPLIT_MERGE).produces,
                "FLASH/Results/Presentation Images/OME-TIFF/");
        assertContains(AnalysisHelpCatalog.forAnalysis(FLASH_Pipeline.IDX_INTENSITY).produces,
                "FLASH/Results/Tables/Intensity/<channel>.csv");
        assertContains(AnalysisHelpCatalog.forAnalysis(FLASH_Pipeline.IDX_3D_OBJECT).produces,
                "FLASH/Results/Tables/Objects/<channel>.csv");
        assertContains(AnalysisHelpCatalog.forAnalysis(FLASH_Pipeline.IDX_3D_OBJECT).produces,
                "FLASH/Results/Analysis Images/Segmentation/");
    }

    @Test
    public void resultsExportTopicsHaveCompletedContentAndImageReferences() {
        int[] indices = resultsExportTopicIndices();

        for (int i = 0; i < indices.length; i++) {
            AnalysisHelpTopic topic = AnalysisHelpCatalog.forAnalysis(indices[i]);
            assertNotNull(topic);
            assertFalse("missing image references for " + topic.key, topic.images.isEmpty());
            assertNoPlaceholderText(topic);
        }
    }

    @Test
    public void resultsExportTopicsNameRequiredDependencies() {
        AnalysisHelpTopic aggregation = AnalysisHelpCatalog.forAnalysis(FLASH_Pipeline.IDX_AGGREGATION);
        assertContains(aggregation.needsFirst, "Per-image CSVs in FLASH/Results/Tables/");
        assertContains(aggregation.needsFirst, "FLASH/Results/Tables/Project Summary/Conditions.csv");

        AnalysisHelpTopic statistics = AnalysisHelpCatalog.forAnalysis(FLASH_Pipeline.IDX_STATISTICS);
        assertContains(statistics.needsFirst, "FLASH/Results/Tables/Project Summary/3D Objects.csv");
        assertContains(statistics.needsFirst, "FLASH/Results/Tables/Project Summary/Conditions.csv");

        AnalysisHelpTopic excel = AnalysisHelpCatalog.forAnalysis(FLASH_Pipeline.IDX_EXCEL_EXPORT);
        assertContains(excel.needsFirst, "FLASH/Results/Tables/Project Summary/3D Objects.csv");
        assertContains(excel.needsFirst, "FLASH/Results/Tables/Project Summary/Conditions.csv");
        assertContains(excel.needsFirst, "FLASH/Results/Tables/Project Summary/Statistics.csv");
    }

    @Test
    public void resultsExportOutputPathsMatchCurrentLayout() {
        assertContains(AnalysisHelpCatalog.forAnalysis(FLASH_Pipeline.IDX_AGGREGATION).produces,
                "FLASH/Results/Tables/Project Summary/3D Objects.csv");
        assertContains(AnalysisHelpCatalog.forAnalysis(FLASH_Pipeline.IDX_AGGREGATION).produces,
                "FLASH/Results/Tables/Project Summary/Image Intensities.csv");
        assertContains(AnalysisHelpCatalog.forAnalysis(FLASH_Pipeline.IDX_STATISTICS).produces,
                "FLASH/Results/Tables/Project Summary/Statistics.csv");
        assertContains(AnalysisHelpCatalog.forAnalysis(FLASH_Pipeline.IDX_EXCEL_EXPORT).produces,
                "FLASH/Results/Summary.xlsx");
    }

    private static int[] visibleAnalysisOrder() throws Exception {
        return FLASH_Pipeline.visibleAnalysisOrderForTests();
    }

    private static String[] analysisLabels() throws Exception {
        FLASH_Pipeline pipeline = new FLASH_Pipeline();
        int[] visibleOrder = FLASH_Pipeline.visibleAnalysisOrderForTests();
        String[] labels = new String[FLASH_Pipeline.IDX_REPRESENTATIVE_FIGURE + 1];
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

    private static void assertNoPlaceholderText(AnalysisHelpTopic topic) {
        assertNoPlaceholderText(java.util.Collections.singletonList(topic.whatItDoes), topic.key);
        assertNoPlaceholderText(topic.needsFirst, topic.key);
        assertNoPlaceholderText(topic.produces, topic.key);
        assertNoPlaceholderText(topic.workflow, topic.key);
        assertNoPlaceholderText(topic.watchOut, topic.key);
    }

    private static void assertNoPlaceholderText(Iterable<String> values, String topicKey) {
        for (String value : values) {
            assertFalse("placeholder text remains in " + topicKey + ": " + value,
                    value.contains("selected project needs this analysis step")
                            || value.contains("standard FLASH outputs for this module")
                            || value.contains("Review inputs, run the module")
                            || value.contains("content stage")
                            || value.contains("implemented"));
        }
    }

    private static void assertContains(Iterable<String> values, String expected) {
        for (String value : values) {
            if (value.contains(expected)) {
                return;
            }
        }
        assertTrue("expected list text to contain: " + expected, false);
    }

    private static void assertContains(String value, String expected) {
        assertNotNull(value);
        assertTrue("expected text to contain: " + expected, value.contains(expected));
    }
}
