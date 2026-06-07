package flash.pipeline.analyses;

import flash.pipeline.bin.BinConfig;
import flash.pipeline.io.ConditionManifestIO;
import flash.pipeline.io.FlashProjectLayout;
import flash.pipeline.io.SeriesMeta;
import flash.pipeline.presentation.PresentationTileConfig;
import flash.pipeline.project.ProjectFile;
import flash.pipeline.project.ProjectFileIO;
import flash.pipeline.report.QualityReport;
import flash.pipeline.representative.RepresentativeFigureConfig;
import flash.pipeline.representative.RepresentativeLayout;
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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
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
}
