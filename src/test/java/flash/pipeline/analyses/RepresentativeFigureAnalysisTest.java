package flash.pipeline.analyses;

import flash.pipeline.bin.BinConfig;
import flash.pipeline.cli.CLIArgumentParser;
import flash.pipeline.cli.CLIConfig;
import flash.pipeline.execution.AnalysisRunCoordinator;
import flash.pipeline.execution.RunResult;
import flash.pipeline.io.ConditionManifestIO;
import flash.pipeline.io.FlashProjectLayout;
import flash.pipeline.io.SeriesMeta;
import flash.pipeline.presentation.PresentationTileConfig;
import flash.pipeline.project.ProjectFile;
import flash.pipeline.project.ProjectFileIO;
import flash.pipeline.qc.QcSelectionCandidate;
import flash.pipeline.report.QualityReport;
import flash.pipeline.representative.RepresentativeFigureConfig;
import flash.pipeline.representative.RepresentativeLayout;
import flash.pipeline.representative.RepresentativePreviewRenderer;
import flash.pipeline.representative.RepresentativeSelection;
import flash.pipeline.representative.RepresentativeSeries;
import flash.pipeline.representative.RepresentativeStatistic;
import flash.pipeline.runrecord.AnalysisRunContext;
import flash.pipeline.runrecord.LoadedRunParameters;
import flash.pipeline.runrecord.RunRecord;
import flash.pipeline.runrecord.RunRecordIO;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import javax.swing.JComboBox;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class RepresentativeFigureAnalysisTest {
    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void skeletonDeclaresHeadedInteractiveAnalysisWithoutBinRequirements() {
        RepresentativeFigureAnalysis analysis = new RepresentativeFigureAnalysis();

        assertTrue(analysis.requiresHeadedMode());
        assertFalse(analysis.benefitsFromRois());
        assertTrue(analysis.requiredBinFields().isEmpty());
        assertNotNull(analysis.configForTests());
    }

    @Test
    public void reshapeLayoutRowsBalancesAndPreservesAllConditions() {
        RepresentativeLayout layout = RepresentativeLayout.allInOneRow(
                Arrays.asList("A", "B", "C", "D", "E"));

        RepresentativeLayout two = RepresentativeFigureAnalysis.reshapeLayoutRows(layout, 2);
        assertNotNull(two);
        assertEquals(2, two.rowCount());
        assertEquals(3, two.rows().get(0).size());
        assertEquals(2, two.rows().get(1).size());
        assertEquals(Arrays.asList("A", "B", "C", "D", "E"), two.flattenedConditions());

        RepresentativeLayout four = RepresentativeFigureAnalysis.reshapeLayoutRows(layout, 4);
        assertNotNull(four);
        assertEquals(4, four.rowCount());
        assertEquals(Arrays.asList("A", "B", "C", "D", "E"), four.flattenedConditions());

        // rows beyond the condition count clamp to one row per condition.
        RepresentativeLayout clamped =
                RepresentativeFigureAnalysis.reshapeLayoutRows(layout, 99);
        assertNotNull(clamped);
        assertEquals(5, clamped.rowCount());

        assertNull(RepresentativeFigureAnalysis.reshapeLayoutRows(layout, 0));
        assertNull(RepresentativeFigureAnalysis.reshapeLayoutRows(null, 2));
    }

    @Test
    public void commonSettersAndStubExecuteAreHeadlessSafe() {
        RepresentativeFigureAnalysis analysis = new RepresentativeFigureAnalysis();

        analysis.setHeadless(true);
        analysis.setVerboseLogging(true);
        analysis.setSkipExisting(true);
        analysis.setParallelThreads(0);
        analysis.setImageCache(null);
        analysis.setLoaderThreads(0);
        analysis.setLoaderPercent(150);
        analysis.setUseTifCache(true);
        analysis.setQualityReport(new QualityReport());
        analysis.setSuppressDialogs(true);
        analysis.setCliConfig(null);
        analysis.execute("C:/tmp/flash");

        assertEquals(RepresentativeStatistic.NONE, analysis.configForTests().statistic);
        assertTrue(analysis.configForTests().statTable.isEmpty());
    }

    @Test
    public void loadedParametersHydrateConfigAndReportUnknownKeys() {
        RepresentativeFigureAnalysis analysis = new RepresentativeFigureAnalysis();
        RepresentativeFigureConfig saved = representativeConfig(new File("source.lif"));
        Map<String, Object> parameters = new LinkedHashMap<String, Object>();
        parameters.put(RepresentativeFigureConfig.PROJECT_EXTRA_KEY, saved.toMap());
        parameters.put("future_key", "value");

        LoadedRunParameters.Result result = analysis.applyLoadedParameters(parameters);

        assertTrue(result.getAppliedKeys().contains(RepresentativeFigureConfig.PROJECT_EXTRA_KEY));
        assertTrue(result.getIgnoredKeys().contains("future_key"));
        assertEquals(RepresentativeStatistic.QUICK, analysis.configForTests().statistic);
        assertEquals("10-200", analysis.configForTests().customDisplayRangeForChannel(0));
        assertEquals(Arrays.asList("Control"),
                analysis.configForTests().layout.flattenedConditions());
        LoadedRunParameters.Result empty = analysis.applyLoadedParameters(Collections.<String, Object>emptyMap());
        assertFalse(empty.hasIgnoredKeys());
    }

    @Test
    public void loadedParametersCanSelectNamedRepresentativeFigure() {
        RepresentativeFigureConfig mean = representativeConfig(new File("mean.lif"));
        mean.saveName = "Mean intensity";
        RepresentativeFigureConfig count = representativeConfig(new File("count.lif"));
        count.saveName = "Cell count";

        Map<String, Object> parameters = new LinkedHashMap<String, Object>();
        parameters.put(RepresentativeFigureConfig.PROJECT_COLLECTION_KEY,
                Arrays.asList(mean.toMap(), count.toMap()));

        CLIConfig cli = CLIArgumentParser.parse(
                "dir=[/tmp] repfig.save_name=[Cell count]");
        RepresentativeFigureAnalysis analysis = new RepresentativeFigureAnalysis();
        analysis.setCliConfig(cli);

        LoadedRunParameters.Result result = analysis.applyLoadedParameters(parameters);

        assertTrue(result.getAppliedKeys().contains(
                RepresentativeFigureConfig.PROJECT_COLLECTION_KEY));
        assertEquals("Cell count", analysis.configForTests().saveName());
        assertEquals("count.lif",
                analysis.configForTests().selection.seriesForCondition("Control")
                        .sourcePath().getName());
    }

    @Test
    public void newRepresentativeFigureSaveNameStartsFresh() {
        assertTrue(RepresentativeFigureAnalysis.startsFreshNamedFigure(
                "Mean intensity", "Cell count", false));
        assertFalse(RepresentativeFigureAnalysis.startsFreshNamedFigure(
                "Mean intensity", "Mean intensity", false));
        assertFalse(RepresentativeFigureAnalysis.startsFreshNamedFigure(
                "Mean intensity", "Cell count", true));
        assertFalse(RepresentativeFigureAnalysis.startsFreshNamedFigure(
                "Mean intensity", "", false));
    }

    @Test
    public void savedRepresentativeRebindsByPortableIdentityAfterProjectCopyAndReorder()
            throws Exception {
        File original = temp.newFolder("repfig-original");
        File copied = temp.newFolder("repfig-copy");
        File originalInput = new File(original, "input");
        File copiedInput = new File(copied, "input");
        assertTrue(originalInput.mkdirs());
        assertTrue(copiedInput.mkdirs());
        File originalA = new File(originalInput, "container-a.lif");
        File copiedA = new File(copiedInput, "container-a.lif");
        File copiedB = new File(copiedInput, "container-b.lif");
        Files.write(originalA.toPath(), new byte[]{1});
        Files.write(copiedA.toPath(), new byte[]{2});
        Files.write(copiedB.toPath(), new byte[]{3});

        RepresentativeFigureConfig config = selectionConfig(seriesForIdentity(
                0, "Exp-MouseA_LH_SCN", "MouseA", "Control", originalA));
        config.captureSelectionIdentities(original.getAbsolutePath());
        RepresentativeSeries currentB = seriesForIdentity(
                0, "Exp-MouseB_RH_SCN", "MouseB", "Treatment", copiedB);
        RepresentativeSeries currentA = seriesForIdentity(
                1, "Exp-MouseA_LH_SCN", "MouseA", "Control", copiedA);

        RepresentativeSelection rebound = RepresentativePreviewRenderer.rebindSelection(
                copied.getAbsolutePath(), config, Arrays.asList(currentB, currentA));

        RepresentativeSeries selected = rebound.seriesForCondition("Control");
        assertEquals(1, selected.seriesIndex());
        assertEquals(copiedA.getCanonicalFile(), selected.sourcePath().getCanonicalFile());
        Map<String, Object> savedMap = config.toMap();
        Map<?, ?> savedRow = (Map<?, ?>) ((java.util.List<?>)
                savedMap.get("lockedSeries")).get(0);
        assertEquals(Integer.valueOf(2), savedRow.get("identityVersion"));
        assertEquals("project:input/container-a.lif", savedRow.get("sourceKey"));
        assertEquals("Exp-MouseA_LH_SCN", savedRow.get("imageKey"));
    }

    @Test
    public void analysisGuardsPreviewUntilMetadataOnlyRebindUsesCurrentCopiedSource()
            throws Exception {
        File original = temp.newFolder("repfig-order-original");
        File copied = temp.newFolder("repfig-order-copy");
        File originalInput = new File(original, "input");
        File copiedInput = new File(copied, "input");
        assertTrue(originalInput.mkdirs());
        assertTrue(copiedInput.mkdirs());
        File originalSource = new File(originalInput, "mouse-a.tif");
        final File copiedSource = new File(copiedInput, "mouse-a.tif");
        Files.write(originalSource.toPath(), new byte[0]);
        Files.write(copiedSource.toPath(), new byte[0]);
        // Keep the series label's animal token aligned with the candidate builder:
        // the "Experiment-Animal" form is intentionally parsed differently by
        // ConditionManifestIO and would make this an unrelated condition test.
        final String imageName = "MouseA_LH_SCN";
        RepresentativeFigureConfig remembered = selectionConfig(seriesForIdentity(
                0, imageName, "MouseA", "MouseA", originalSource));
        remembered.captureSelectionIdentities(original.getAbsolutePath());
        final RepresentativeFigureAnalysis analysis = new RepresentativeFigureAnalysis();
        analysis.configForTests().copyFrom(remembered);
        final boolean[] previewCalled = new boolean[1];
        analysis.setPreviewSeriesRendererForTests(
                new RepresentativeFigureAnalysis.PreviewSeriesRenderer() {
                    @Override
                    public List<RepresentativeSeries> render(
                            String directory,
                            RepresentativeFigureConfig figureConfig,
                            flash.pipeline.io.ImageCache cache,
                            int threads,
                            boolean tifCache,
                            List<SeriesMeta> metas,
                            List<QcSelectionCandidate> candidates) throws Exception {
                        previewCalled[0] = true;
                        assertEquals(copiedSource.getCanonicalFile(),
                                figureConfig.selection.seriesForCondition("MouseA")
                                        .sourcePath().getCanonicalFile());
                        return Collections.emptyList();
                    }
                });
        List<SeriesMeta> metas = Collections.singletonList(
                new SeriesMeta(0, imageName, 1, 1, 1, 1, 1, 1, 1, "um"));
        List<QcSelectionCandidate> candidates = Collections.singletonList(
                new QcSelectionCandidate(0, imageName, "MouseA", "MouseA"));

        try {
            analysis.renderPreviewSeries(copied.getAbsolutePath(), metas, candidates);
            org.junit.Assert.fail("Preview must be guarded until rebinding finishes");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("rebound before preview"));
        }
        assertFalse(previewCalled[0]);

        analysis.rebindRememberedSelectionBeforePreview(copied.getAbsolutePath(), metas);
        analysis.renderPreviewSeries(copied.getAbsolutePath(), metas, candidates);
        assertTrue(previewCalled[0]);
    }

    @Test
    public void staleOrBiologicallyMismatchedSavedIdentityFailsTransactionally()
            throws Exception {
        File project = temp.newFolder("repfig-stale-identity");
        File source = new File(project, "source-a.lif");
        Files.write(source.toPath(), new byte[]{1});
        RepresentativeSeries remembered = seriesForIdentity(
                0, "Exp-MouseA_LH_SCN", "MouseA", "Control", source);
        RepresentativeFigureConfig config = selectionConfig(remembered);
        config.captureSelectionIdentities(project.getAbsolutePath());
        RepresentativeSeries wrongBiology = seriesForIdentity(
                4, "Exp-MouseA_LH_SCN", "MouseZ", "Control", source);

        try {
            RepresentativePreviewRenderer.rebindSelection(
                    project.getAbsolutePath(), config,
                    Collections.singletonList(wrongBiology));
            org.junit.Assert.fail("Mismatched biological identity must fail");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("no longer matches"));
        }

        assertEquals(0, config.selection.seriesForCondition("Control").seriesIndex());
        assertEquals("MouseA", config.selection.seriesForCondition("Control").animal());
    }

    @Test
    public void laterConditionFailureDoesNotPartiallyRebindEarlierSelectionOrIdentities()
            throws Exception {
        File original = temp.newFolder("repfig-transaction-original");
        File copied = temp.newFolder("repfig-transaction-copy");
        File originalA = new File(original, "a.lif");
        File originalB = new File(original, "b.lif");
        File copiedA = new File(copied, "a.lif");
        File copiedWrong = new File(copied, "wrong.lif");
        Files.write(originalA.toPath(), new byte[]{1});
        Files.write(originalB.toPath(), new byte[]{2});
        Files.write(copiedA.toPath(), new byte[]{3});
        Files.write(copiedWrong.toPath(), new byte[]{4});
        RepresentativeSeries savedA = seriesForIdentity(
                0, "Exp-MouseA_LH_SCN", "MouseA", "Control", originalA);
        RepresentativeSeries savedB = seriesForIdentity(
                1, "Exp-MouseB_RH_SCN", "MouseB", "Treatment", originalB);
        RepresentativeFigureConfig config = new RepresentativeFigureConfig();
        Map<String, RepresentativeSeries> saved =
                new LinkedHashMap<String, RepresentativeSeries>();
        saved.put("Control", savedA);
        saved.put("Treatment", savedB);
        config.selection = new RepresentativeSelection(
                Arrays.asList("Control", "Treatment"), saved);
        config.captureSelectionIdentities(original.getAbsolutePath());
        Map<String, Object> before = config.toMap();
        RepresentativeSeries reboundA = seriesForIdentity(
                1, "Exp-MouseA_LH_SCN", "MouseA", "Control", copiedA);
        RepresentativeSeries wrongB = seriesForIdentity(
                0, "Exp-MouseZ_RH_SCN", "MouseZ", "Treatment", copiedWrong);

        try {
            RepresentativePreviewRenderer.rebindSelection(
                    copied.getAbsolutePath(), config, Arrays.asList(reboundA, wrongB));
            org.junit.Assert.fail("Later stale condition must fail the whole reconciliation");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("Treatment"));
        }

        assertEquals(before, config.toMap());
        assertEquals(0, config.selection.seriesForCondition("Control").seriesIndex());
        assertEquals(originalA.getCanonicalFile(), config.selection
                .seriesForCondition("Control").sourcePath().getCanonicalFile());
        assertEquals(1, config.selection.seriesForCondition("Treatment").seriesIndex());
        assertEquals(originalB.getCanonicalFile(), config.selection
                .seriesForCondition("Treatment").sourcePath().getCanonicalFile());
    }

    @Test
    public void legacySelectionRejectsIndexHintThatNowPointsAtDifferentBiology()
            throws Exception {
        File project = temp.newFolder("repfig-legacy-index-conflict");
        File sourceA = new File(project, "a.lif");
        File sourceB = new File(project, "b.lif");
        Files.write(sourceA.toPath(), new byte[]{1});
        Files.write(sourceB.toPath(), new byte[]{2});
        RepresentativeFigureConfig legacy = selectionConfig(seriesForIdentity(
                0, "Exp-MouseA_LH_SCN", "MouseA", "Control", sourceA));
        RepresentativeSeries currentB = seriesForIdentity(
                0, "Exp-MouseB_RH_SCN", "MouseB", "Treatment", sourceB);
        RepresentativeSeries currentA = seriesForIdentity(
                1, "Exp-MouseA_LH_SCN", "MouseA", "Control", sourceA);

        try {
            RepresentativePreviewRenderer.rebindSelection(
                    project.getAbsolutePath(), legacy,
                    Arrays.asList(currentB, currentA));
            org.junit.Assert.fail("Legacy numeric hint conflict must fail");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("series index hint 0"));
        }
        assertEquals(0, legacy.selection.seriesForCondition("Control").seriesIndex());
    }

    @Test
    public void lockedSelectionSerializesAsV2OnlyAfterCompleteIdentityCapture()
            throws Exception {
        File project = temp.newFolder("repfig-schema-identity");
        File source = new File(project, "source.lif");
        Files.write(source.toPath(), new byte[]{1});
        RepresentativeFigureConfig config = selectionConfig(seriesForIdentity(
                0, "Exp-MouseA_LH_SCN", "MouseA", "Control", source));

        Map<String, Object> legacyMap = config.toMap();
        Map<?, ?> legacyRow = (Map<?, ?>) ((java.util.List<?>)
                legacyMap.get("lockedSeries")).get(0);
        assertEquals(Integer.valueOf(1), legacyMap.get("schemaVersion"));
        assertFalse(legacyRow.containsKey("sourceKey"));
        assertFalse(legacyRow.containsKey("imageKey"));

        config.captureSelectionIdentities(project.getAbsolutePath());
        Map<String, Object> durableMap = config.toMap();
        Map<?, ?> durableRow = (Map<?, ?>) ((java.util.List<?>)
                durableMap.get("lockedSeries")).get(0);
        assertEquals(Integer.valueOf(2), durableMap.get("schemaVersion"));
        assertTrue(durableRow.containsKey("sourceKey"));
        assertTrue(durableRow.containsKey("imageKey"));
    }

    @Test
    public void futureRepresentativeSchemaIsRejectedBeforeItCanRender() {
        Map<String, Object> future = new LinkedHashMap<String, Object>();
        future.put("schemaVersion", Integer.valueOf(999));

        assertSavedConfigRejected(future, "Unsupported representative figure schemaVersion 999");
    }

    @Test
    public void incompleteV2IdentityIsRejectedBeforeItCanRender() throws Exception {
        File project = temp.newFolder("repfig-incomplete-v2");
        File source = new File(project, "source.lif");
        Files.write(source.toPath(), new byte[]{1});
        RepresentativeFigureConfig config = selectionConfig(seriesForIdentity(
                0, "Exp-MouseA_LH_SCN", "MouseA", "Control", source));
        config.captureSelectionIdentities(project.getAbsolutePath());
        Map<String, Object> saved = config.toMap();
        Map<String, Object> row = stringObjectMapForTest(
                ((java.util.List<?>) saved.get("lockedSeries")).get(0));
        row.remove("imageKey");
        ((java.util.List<Object>) saved.get("lockedSeries")).set(0, row);

        assertSavedConfigRejected(saved, "complete sourceKey and imageKey");
    }

    @Test
    public void mixedLegacyRootAndV2RowIsRejectedBeforeItCanRender() throws Exception {
        File project = temp.newFolder("repfig-mixed-schema");
        File source = new File(project, "source.lif");
        Files.write(source.toPath(), new byte[]{1});
        RepresentativeFigureConfig config = selectionConfig(seriesForIdentity(
                0, "Exp-MouseA_LH_SCN", "MouseA", "Control", source));
        config.captureSelectionIdentities(project.getAbsolutePath());
        Map<String, Object> mixed = config.toMap();
        mixed.put("schemaVersion", Integer.valueOf(1));

        assertSavedConfigRejected(mixed, "schema 1 cannot contain version-2 identity fields");
    }

    @Test
    public void v2RowMissingCoreSeriesIndexIsRejectedBeforeItCanRender() throws Exception {
        File project = temp.newFolder("repfig-v2-missing-index");
        File source = new File(project, "source.lif");
        Files.write(source.toPath(), new byte[]{1});
        RepresentativeFigureConfig config = selectionConfig(seriesForIdentity(
                0, "Exp-MouseA_LH_SCN", "MouseA", "Control", source));
        config.captureSelectionIdentities(project.getAbsolutePath());
        Map<String, Object> saved = config.toMap();
        firstLockedRowForTest(saved).remove("seriesIndex");

        assertSavedConfigRejected(saved, "locked row seriesIndex must be an exact integer");
    }

    @Test
    public void v2RowWhoseSeriesNameDisagreesWithImageKeyIsRejected() throws Exception {
        File project = temp.newFolder("repfig-v2-name-key-mismatch");
        File source = new File(project, "source.lif");
        Files.write(source.toPath(), new byte[]{1});
        RepresentativeFigureConfig config = selectionConfig(seriesForIdentity(
                0, "Exp-MouseA_LH_SCN", "MouseA", "Control", source));
        config.captureSelectionIdentities(project.getAbsolutePath());
        Map<String, Object> saved = config.toMap();
        firstLockedRowForTest(saved).put("seriesName", "Exp-MouseB_RH_SCN");

        assertSavedConfigRejected(saved, "seriesName must match its imageKey");
    }

    @Test
    public void duplicateSeriesNamesAcrossContainersResolveByPortableSourceKey()
            throws Exception {
        File project = temp.newFolder("repfig-duplicate-names");
        File first = new File(project, "first.lif");
        File second = new File(project, "second.lif");
        Files.write(first.toPath(), new byte[]{1});
        Files.write(second.toPath(), new byte[]{2});
        RepresentativeFigureConfig config = selectionConfig(seriesForIdentity(
                0, "Exp-MouseA_LH_SCN", "MouseA", "Control", second));
        config.captureSelectionIdentities(project.getAbsolutePath());
        RepresentativeSeries sameNameWrongContainer = seriesForIdentity(
                0, "Exp-MouseA_LH_SCN", "MouseA", "Control", first);
        RepresentativeSeries sameNameRightContainer = seriesForIdentity(
                1, "Exp-MouseA_LH_SCN", "MouseA", "Control", second);

        RepresentativeSelection rebound = RepresentativePreviewRenderer.rebindSelection(
                project.getAbsolutePath(), config,
                Arrays.asList(sameNameWrongContainer, sameNameRightContainer));

        assertEquals(1, rebound.seriesForCondition("Control").seriesIndex());
        assertEquals(second.getCanonicalFile(), rebound.seriesForCondition("Control")
                .sourcePath().getCanonicalFile());
    }

    @Test
    public void displayRangeCardsReflectAvailableSources() {
        assertArrayEquals(new String[]{
                        "Use remembered representative ranges",
                        "Use display ranges already set up",
                        "Adjust now"},
                RepresentativeFigureAnalysis.displayRangeChoiceValues(true, true));
        assertArrayEquals(new String[]{
                        "Use remembered representative ranges",
                        "Adjust now"},
                RepresentativeFigureAnalysis.displayRangeChoiceValues(true, false));
        assertArrayEquals(new String[]{
                        "Use display ranges already set up",
                        "Adjust now"},
                RepresentativeFigureAnalysis.displayRangeChoiceValues(false, true));
        assertArrayEquals(new String[]{"Adjust now"},
                RepresentativeFigureAnalysis.displayRangeChoiceValues(false, false));

        assertEquals("Use remembered representative ranges",
                RepresentativeFigureAnalysis.defaultDisplayRangeChoiceValue(true, true));
        assertEquals("Use display ranges already set up",
                RepresentativeFigureAnalysis.defaultDisplayRangeChoiceValue(false, true));
        assertEquals("Adjust now",
                RepresentativeFigureAnalysis.defaultDisplayRangeChoiceValue(false, false));
    }

    @Test
    public void displayRangeDetailsExplainNextStep() {
        assertEquals("Open the display-range editor for the selected representative images.",
                RepresentativeFigureAnalysis.displayRangeNextStepText("Adjust now"));
        assertEquals("Use the saved representative display ranges and go straight to Layout.",
                RepresentativeFigureAnalysis.displayRangeNextStepText(
                        "Use remembered representative ranges"));
        assertEquals("Use the display ranges from Set Up Configuration and go straight to Layout.",
                RepresentativeFigureAnalysis.displayRangeNextStepText(
                        "Use display ranges already set up"));
    }

    @Test
    public void statisticSelectionChangedIgnoresUnchangedChooserDefaults() {
        assertFalse(RepresentativeFigureAnalysis.statisticSelectionChanged(
                RepresentativeStatistic.QUICK,
                "Intensity.csv :: Mean",
                RepresentativeStatistic.QUICK,
                "Intensity.csv :: Mean"));

        assertTrue(RepresentativeFigureAnalysis.statisticSelectionChanged(
                RepresentativeStatistic.QUICK,
                "Intensity.csv :: Mean",
                RepresentativeStatistic.EXISTING_RESULT,
                "Intensity.csv :: Mean"));

        assertTrue(RepresentativeFigureAnalysis.statisticSelectionChanged(
                RepresentativeStatistic.EXISTING_RESULT,
                "Intensity.csv :: Mean",
                RepresentativeStatistic.EXISTING_RESULT,
                "Objects.csv :: Count"));
    }

    @Test
    public void existingResultChoiceOnlyEnablesForExistingResultStatistic() {
        JComboBox<String> statisticChoice =
                new JComboBox<String>(RepresentativeStatistic.labels());
        JComboBox<String> existingChoice =
                new JComboBox<String>(new String[]{"Result.csv - Mean"});

        statisticChoice.setSelectedItem(RepresentativeStatistic.QUICK.label());
        RepresentativeFigureAnalysis.updateExistingResultChoiceEnabled(
                statisticChoice, existingChoice, true);
        assertFalse(existingChoice.isEnabled());

        statisticChoice.setSelectedItem(RepresentativeStatistic.NONE.label());
        RepresentativeFigureAnalysis.updateExistingResultChoiceEnabled(
                statisticChoice, existingChoice, true);
        assertFalse(existingChoice.isEnabled());

        statisticChoice.setSelectedItem(RepresentativeStatistic.EXISTING_RESULT.label());
        RepresentativeFigureAnalysis.updateExistingResultChoiceEnabled(
                statisticChoice, existingChoice, true);
        assertTrue(existingChoice.isEnabled());

        RepresentativeFigureAnalysis.updateExistingResultChoiceEnabled(
                statisticChoice, existingChoice, false);
        assertFalse(existingChoice.isEnabled());
    }

    @Test
    public void conditionReviewAnimalsUseSourceMetadataOrderAndSkipPreviews() {
        LinkedHashSet<String> animals = RepresentativeFigureAnalysis.conditionReviewAnimals(
                Arrays.asList(
                        meta(0, "study.lif - Syn1WeekTwo_LH_SCN"),
                        meta(1, "study.lif - thumbnail"),
                        meta(2, "study.lif - Syn1WeekTwo_RH_SCN"),
                        meta(3, "")));

        assertEquals(Arrays.asList("Syn1WeekTwo", "Series4"),
                new ArrayList<String>(animals));
    }

    @Test
    public void conditionReviewUsesSharedDialogPrefillAndCancelStopsFlow()
            throws Exception {
        File projectRoot = temp.newFolder("repfig-conditions");
        LinkedHashMap<String, String> saved = new LinkedHashMap<String, String>();
        saved.put("Syn1WeekTwo", "SynWeekTwo");
        ConditionManifestIO.saveAssignments(projectRoot.getAbsolutePath(), saved);

        RepresentativeFigureAnalysis analysis = new RepresentativeFigureAnalysis();
        final LinkedHashSet<String>[] seenAnimals = new LinkedHashSet[1];
        final LinkedHashMap<String, String>[] seenPrefill = new LinkedHashMap[1];
        final String[] seenTitle = new String[1];
        analysis.setConditionReviewDialogForTests(
                new RepresentativeFigureAnalysis.ConditionReviewDialog() {
                    @Override
                    public LinkedHashMap<String, String> show(
                            String directory,
                            Set<String> animals,
                            Map<String, String> prefill,
                            String title) {
                        seenAnimals[0] = new LinkedHashSet<String>(animals);
                        seenPrefill[0] = new LinkedHashMap<String, String>(prefill);
                        seenTitle[0] = title;
                        return new LinkedHashMap<String, String>(prefill);
                    }
                });

        assertTrue(analysis.reviewConditionAssignments(projectRoot.getAbsolutePath(),
                Arrays.asList(
                        meta(0, "study.lif - Syn1WeekTwo_LH_SCN"),
                        meta(1, "study.lif - hAPP2WeekEight_LH_SCN"))));

        assertEquals(Arrays.asList("Syn1WeekTwo", "hAPP2WeekEight"),
                new ArrayList<String>(seenAnimals[0]));
        assertEquals("SynWeekTwo", seenPrefill[0].get("Syn1WeekTwo"));
        assertEquals("hAPPWeekEight", seenPrefill[0].get("hAPP2WeekEight"));
        assertEquals("Representative Figure - Condition Assignment", seenTitle[0]);

        analysis.setConditionReviewDialogForTests(
                new RepresentativeFigureAnalysis.ConditionReviewDialog() {
                    @Override
                    public LinkedHashMap<String, String> show(
                            String directory,
                            Set<String> animals,
                            Map<String, String> prefill,
                            String title) {
                        return null;
                    }
                });
        assertFalse(analysis.reviewConditionAssignments(projectRoot.getAbsolutePath(),
                Collections.singletonList(
                        meta(0, "study.lif - Syn1WeekTwo_LH_SCN"))));
    }

    @Test
    public void persistCompletedRunWritesProjectExtrasDetailsAndRunRecord() throws Exception {
        File projectRoot = temp.newFolder("repfig-project");
        File source = new File(projectRoot, "source.lif");
        Files.write(source.toPath(), "source".getBytes(StandardCharsets.UTF_8));
        File output = new File(projectRoot, "figure.png");
        Files.write(output.toPath(), "png".getBytes(StandardCharsets.UTF_8));

        FlashProjectLayout layout = FlashProjectLayout.forDirectory(projectRoot.getAbsolutePath());
        ProjectFile project = new ProjectFile();
        project.name = "Study";
        ProjectFileIO.write(layout.configurationWriteDir(), project);

        RepresentativeFigureAnalysis analysis = new RepresentativeFigureAnalysis();
        analysis.configForTests().copyFrom(representativeConfig(source));

        BinConfig setup = new BinConfig();
        setup.channelNames.add("DAPI");
        setup.channelMinMax.add("None");

        AnalysisRunContext context = AnalysisRunContext.open(
                "RepresentativeFigureAnalysis",
                3,
                "Make Representative Image Figure",
                projectRoot.getAbsolutePath(),
                null,
                new LinkedHashMap<String, Object>(),
                "");
        analysis.setRunRecordContext(context);
        analysis.persistCompletedRun(projectRoot.getAbsolutePath(), setup, output);
        analysis.setRunRecordContext(null);
        context.close();

        ProjectFile savedProject = ProjectFileIO.read(layout.configurationWriteDir());
        assertNotNull(savedProject.extras.get(RepresentativeFigureConfig.PROJECT_EXTRA_KEY));
        File details = new File(layout.analysisDetailsWriteDir(), "representative_figure.txt");
        assertTrue(details.isFile());

        RunRecord record = RunRecordIO.readLatest(context.recordFile());
        assertEquals(1, record.inputs.size());
        assertEquals(source.getAbsolutePath(), record.inputs.get(0).path);
        assertEquals("Control", record.inputs.get(0).condition);
        assertTrue(record.parameters.containsKey(RepresentativeFigureConfig.PROJECT_EXTRA_KEY));
        assertTrue(hasOutputKind(record, "png"));
        assertTrue(hasMessageContaining(record, "Representative figure written"));
    }

    @Test
    public void persistCompletedRunUpsertsMultipleNamedFiguresInProjectJson() throws Exception {
        File projectRoot = temp.newFolder("repfig-named-project");
        File source = new File(projectRoot, "source.lif");
        Files.write(source.toPath(), "source".getBytes(StandardCharsets.UTF_8));
        File firstOutput = new File(projectRoot, "mean.png");
        File secondOutput = new File(projectRoot, "count.png");
        Files.write(firstOutput.toPath(), "png1".getBytes(StandardCharsets.UTF_8));
        Files.write(secondOutput.toPath(), "png2".getBytes(StandardCharsets.UTF_8));

        FlashProjectLayout layout = FlashProjectLayout.forDirectory(projectRoot.getAbsolutePath());
        ProjectFile project = new ProjectFile();
        project.name = "Study";
        ProjectFileIO.write(layout.configurationWriteDir(), project);

        BinConfig setup = new BinConfig();
        setup.channelNames.add("DAPI");

        RepresentativeFigureAnalysis analysis = new RepresentativeFigureAnalysis();
        RepresentativeFigureConfig first = representativeConfig(source);
        first.saveName = "Mean intensity";
        analysis.configForTests().copyFrom(first);
        analysis.persistCompletedRun(projectRoot.getAbsolutePath(), setup, firstOutput);

        RepresentativeFigureConfig second = representativeConfig(source);
        second.saveName = "Cell count";
        analysis.configForTests().copyFrom(second);
        analysis.persistCompletedRun(projectRoot.getAbsolutePath(), setup, secondOutput);

        ProjectFile savedProject = ProjectFileIO.read(layout.configurationWriteDir());
        Map<String, Object> current = stringObjectMapForTest(
                savedProject.extras.get(RepresentativeFigureConfig.PROJECT_EXTRA_KEY));
        assertEquals("Cell count", current.get("saveName"));
        assertEquals(secondOutput.getAbsolutePath(), current.get("lastOutputPng"));

        Object figuresValue = savedProject.extras.get(
                RepresentativeFigureConfig.PROJECT_COLLECTION_KEY);
        assertTrue(figuresValue instanceof java.util.List<?>);
        java.util.List<?> figures = (java.util.List<?>) figuresValue;
        assertEquals(2, figures.size());
        assertTrue(hasSavedFigure(figures, "Mean intensity", firstOutput));
        assertTrue(hasSavedFigure(figures, "Cell count", secondOutput));
    }

    @Test
    public void persistCompletedRunReplacesExistingNamedFigure() throws Exception {
        File projectRoot = temp.newFolder("repfig-replace-named-project");
        File source = new File(projectRoot, "source.lif");
        Files.write(source.toPath(), "source".getBytes(StandardCharsets.UTF_8));
        File firstOutput = new File(projectRoot, "first.png");
        File secondOutput = new File(projectRoot, "second.png");
        Files.write(firstOutput.toPath(), "png1".getBytes(StandardCharsets.UTF_8));
        Files.write(secondOutput.toPath(), "png2".getBytes(StandardCharsets.UTF_8));

        FlashProjectLayout layout = FlashProjectLayout.forDirectory(projectRoot.getAbsolutePath());
        ProjectFile project = new ProjectFile();
        project.name = "Study";
        ProjectFileIO.write(layout.configurationWriteDir(), project);

        RepresentativeFigureAnalysis analysis = new RepresentativeFigureAnalysis();
        RepresentativeFigureConfig config = representativeConfig(source);
        config.saveName = "Mean intensity";
        analysis.configForTests().copyFrom(config);
        analysis.persistCompletedRun(projectRoot.getAbsolutePath(), new BinConfig(), firstOutput);

        analysis.configForTests().copyFrom(config);
        analysis.persistCompletedRun(projectRoot.getAbsolutePath(), new BinConfig(), secondOutput);

        ProjectFile savedProject = ProjectFileIO.read(layout.configurationWriteDir());
        java.util.List<?> figures = (java.util.List<?>) savedProject.extras.get(
                RepresentativeFigureConfig.PROJECT_COLLECTION_KEY);
        assertEquals(1, figures.size());
        assertTrue(hasSavedFigure(figures, "Mean intensity", secondOutput));
    }

    @Test
    public void requiredProjectSettingsFailureEmitsFailedTerminalResultWithTargetAndCause()
            throws Exception {
        File projectRoot = temp.newFolder("repfig-required-publication-failure");
        File source = new File(projectRoot, "source.lif");
        Files.write(source.toPath(), "source".getBytes(StandardCharsets.UTF_8));
        final File output = new File(projectRoot, "figure.png");
        Files.write(output.toPath(), "png".getBytes(StandardCharsets.UTF_8));

        FlashProjectLayout layout = FlashProjectLayout.forDirectory(projectRoot.getAbsolutePath());
        ProjectFile project = new ProjectFile();
        project.name = "Study";
        ProjectFileIO.write(layout.configurationWriteDir(), project);

        final IOException injected = new IOException("injected project settings fault");
        final RepresentativeFigureAnalysis analysis = new RepresentativeFigureAnalysis();
        analysis.configForTests().copyFrom(representativeConfig(source));
        analysis.setCompletionArtifactPublisherForTests(
                new RepresentativeFigureAnalysis.CompletionArtifactPublisher() {
                    @Override
                    public File writeProjectSettings(
                            String directory, Map<String, Object> representative)
                            throws Exception {
                        throw injected;
                    }

                    @Override
                    public File writeAnalysisDetails(
                            File projectDirectory, RepresentativeFigureConfig figureConfig,
                            BinConfig setupConfig, File outputPng) {
                        org.junit.Assert.fail("Optional details must not run after required failure");
                        return null;
                    }
                });

        AnalysisRunCoordinator coordinator = new AnalysisRunCoordinator();
        final RunResult[] emitted = new RunResult[1];
        installTerminalCollector(coordinator, emitted);
        try {
            coordinator.run(analysis, -1, "Representative Figure",
                    projectRoot.getAbsolutePath(), null, null, "",
                    new Callable<Void>() {
                        @Override
                        public Void call() throws Exception {
                            analysis.persistCompletedRun(
                                    projectRoot.getAbsolutePath(), new BinConfig(), output);
                            return null;
                        }
                    });
            org.junit.Assert.fail("Required settings failure must be terminal");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains(ProjectFileIO.FILE_NAME));
            assertEquals(injected, expected.getCause());
        }

        assertNotNull(emitted[0]);
        assertEquals(RunResult.TerminalState.FAILED, emitted[0].terminalState);
        assertTrue(emitted[0].cause.getMessage().contains(ProjectFileIO.FILE_NAME));
    }

    @Test
    public void optionalAnalysisDetailsFailureProducesWarnedTerminalOutcome()
            throws Exception {
        File projectRoot = temp.newFolder("repfig-optional-publication-failure");
        File source = new File(projectRoot, "source.lif");
        Files.write(source.toPath(), "source".getBytes(StandardCharsets.UTF_8));
        final File output = new File(projectRoot, "figure.png");
        Files.write(output.toPath(), "png".getBytes(StandardCharsets.UTF_8));

        final FlashProjectLayout layout =
                FlashProjectLayout.forDirectory(projectRoot.getAbsolutePath());
        ProjectFile project = new ProjectFile();
        project.name = "Study";
        ProjectFileIO.write(layout.configurationWriteDir(), project);

        final RepresentativeFigureAnalysis analysis = new RepresentativeFigureAnalysis();
        analysis.configForTests().copyFrom(representativeConfig(source));
        analysis.setCompletionArtifactPublisherForTests(
                new RepresentativeFigureAnalysis.CompletionArtifactPublisher() {
                    @Override
                    public File writeProjectSettings(
                            String directory, Map<String, Object> representative) {
                        return new File(layout.configurationWriteDir(), ProjectFileIO.FILE_NAME);
                    }

                    @Override
                    public File writeAnalysisDetails(
                            File projectDirectory, RepresentativeFigureConfig figureConfig,
                            BinConfig setupConfig, File outputPng) throws Exception {
                        throw new IOException("injected optional details fault");
                    }
                });

        RunResult result = new AnalysisRunCoordinator().run(
                analysis, -1, "Representative Figure", projectRoot.getAbsolutePath(),
                null, null, "", new Callable<Void>() {
                    @Override
                    public Void call() throws Exception {
                        analysis.persistCompletedRun(
                                projectRoot.getAbsolutePath(), new BinConfig(), output);
                        return null;
                    }
                });

        assertEquals(RunResult.TerminalState.COMPLETED_WITH_WARNINGS,
                result.terminalState);
        RunRecord record = RunRecordIO.readLatest(result.recordFile);
        assertTrue(hasMessageContaining(record, "representative_figure.txt"));
        assertTrue(hasMessageContaining(record, "injected optional details fault"));
    }

    private static void installTerminalCollector(
            AnalysisRunCoordinator coordinator, final RunResult[] emitted) throws Exception {
        Class<?> emitterType = Class.forName(
                "flash.pipeline.execution.AnalysisRunCoordinator$TerminalResultEmitter");
        Object emitter = Proxy.newProxyInstance(
                emitterType.getClassLoader(), new Class<?>[]{emitterType},
                (proxy, method, args) -> {
                    if (args != null && args.length == 1 && args[0] instanceof RunResult) {
                        emitted[0] = (RunResult) args[0];
                    }
                    return null;
                });
        Method setter = AnalysisRunCoordinator.class.getDeclaredMethod(
                "setTerminalResultEmitterForTests", emitterType);
        setter.setAccessible(true);
        setter.invoke(coordinator, emitter);
    }

    private static RepresentativeFigureConfig representativeConfig(File source) {
        RepresentativeFigureConfig config = new RepresentativeFigureConfig();
        config.statistic = RepresentativeStatistic.QUICK;
        RepresentativeSeries series = new RepresentativeSeries(
                "series-0001",
                0,
                1,
                "Exp-Mouse1_LH_SCN",
                "Mouse1",
                "Control",
                "LH",
                "SCN",
                source,
                Collections.singletonList(
                        new RepresentativeSeries.ChannelThumbnail(0, "DAPI", null, null)),
                null,
                null,
                RepresentativeSeries.PreviewSource.GENERATED,
                false);
        Map<String, RepresentativeSeries> selected =
                new LinkedHashMap<String, RepresentativeSeries>();
        selected.put("Control", series);
        config.selection = new RepresentativeSelection(
                Collections.singletonList("Control"), selected);
        config.setCustomDisplayRangeForChannel(0, "10-200");
        config.layout = RepresentativeLayout.allInOneRow(Collections.singletonList("Control"));
        config.tileConfig = PresentationTileConfig.builder()
                .createOverviewTile(true)
                .annotateOverviewTile(false)
                .scaleBarEnabled(false)
                .channelOrder(Collections.singletonList("DAPI"))
                .build();
        return config;
    }

    private static RepresentativeFigureConfig selectionConfig(RepresentativeSeries series) {
        RepresentativeFigureConfig config = new RepresentativeFigureConfig();
        Map<String, RepresentativeSeries> selected =
                new LinkedHashMap<String, RepresentativeSeries>();
        selected.put(series.condition(), series);
        config.selection = new RepresentativeSelection(
                Collections.singletonList(series.condition()), selected);
        return config;
    }

    private static RepresentativeSeries seriesForIdentity(int index,
                                                          String name,
                                                          String animal,
                                                          String condition,
                                                          File source) {
        flash.pipeline.naming.NameParts parts =
                flash.pipeline.naming.ImageNameParser.parse(name);
        return new RepresentativeSeries(
                String.valueOf(index), index, index + 1, name, animal, condition,
                parts.hemisphere, parts.csvRegion(), source,
                Collections.<RepresentativeSeries.ChannelThumbnail>emptyList(),
                null, null, RepresentativeSeries.PreviewSource.GENERATED, false);
    }

    private static SeriesMeta meta(int index, String name) {
        return new SeriesMeta(index, name, 1, 1.0, 1.0, 1.0, "um");
    }

    private static boolean hasOutputKind(RunRecord record, String kind) {
        for (RunRecord.OutputItem output : record.outputs) {
            if (kind.equals(output.kind)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasMessageContaining(RunRecord record, String text) {
        for (RunRecord.Message message : record.messages) {
            if (message.text != null && message.text.contains(text)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasSavedFigure(java.util.List<?> figures,
                                          String saveName,
                                          File output) {
        for (Object value : figures) {
            Map<String, Object> row = stringObjectMapForTest(value);
            if (saveName.equals(row.get("saveName"))
                    && output.getAbsolutePath().equals(row.get("lastOutputPng"))) {
                return true;
            }
        }
        return false;
    }

    private static Map<String, Object> stringObjectMapForTest(Object value) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        if (!(value instanceof Map<?, ?>)) {
            return out;
        }
        for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
            if (entry.getKey() != null) {
                out.put(String.valueOf(entry.getKey()), entry.getValue());
            }
        }
        return out;
    }

    private static void assertSavedConfigRejected(Map<String, Object> saved,
                                                  String expectedMessage) {
        try {
            RepresentativeFigureConfig.fromMap(saved);
            org.junit.Assert.fail("Unsafe representative schema must not load for rendering");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains(expectedMessage));
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> firstLockedRowForTest(Map<String, Object> saved) {
        return (Map<String, Object>) ((java.util.List<?>) saved.get("lockedSeries")).get(0);
    }
}
