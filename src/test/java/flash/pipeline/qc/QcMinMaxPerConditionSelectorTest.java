package flash.pipeline.qc;

import flash.pipeline.io.ConditionManifestIO;
import flash.pipeline.io.DeferredImageSupplier;
import flash.pipeline.io.OrientationManifestIO;
import flash.pipeline.io.SeriesMeta;
import flash.pipeline.naming.OrientationManifestRow;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class QcMinMaxPerConditionSelectorTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void buildCandidates_usesConditionManifestAssignments() throws Exception {
        File dir = temp.newFolder("project");
        LinkedHashMap<String, String> assignments = new LinkedHashMap<String, String>();
        assignments.put("Syn1WeekTwo", "SynWeekTwo");
        assignments.put("hAPP2WeekEight", "hAPPWeekEight");
        ConditionManifestIO.saveAssignments(dir.getAbsolutePath(), assignments);

        List<SeriesMeta> metas = Arrays.asList(
                new SeriesMeta(0, "project.lif - Syn1WeekTwo_LH_SCN", 12, 1.0, 1.0, 1.0, "pixel"),
                new SeriesMeta(1, "project.lif - hAPP2WeekEight_RH_SCN", 12, 1.0, 1.0, 1.0, "pixel"));

        List<QcSelectionCandidate> candidates =
                QcMinMaxPerConditionSelector.buildCandidates(dir.getAbsolutePath(), metas);

        assertEquals(2, candidates.size());
        assertEquals("Syn1WeekTwo", candidates.get(0).animalName);
        assertEquals("SynWeekTwo", candidates.get(0).conditionName);
        assertEquals("hAPP2WeekEight", candidates.get(1).animalName);
        assertEquals("hAPPWeekEight", candidates.get(1).conditionName);
    }

    @Test
    public void buildCandidates_prefersReviewedMetadataAssignments() throws Exception {
        File dir = temp.newFolder("reviewed-project");
        List<SeriesMeta> metas = Arrays.asList(
                new SeriesMeta(0, "project.lif - BadGuess_LH_SCN", 12, 1.0, 1.0, 1.0, "pixel"),
                new SeriesMeta(1, "project.lif - OtherGuess_RH_SCN", 12, 1.0, 1.0, 1.0, "pixel"));

        Map<Integer, QcMinMaxPerConditionSelector.MetadataAssignment> reviewed =
                new LinkedHashMap<Integer, QcMinMaxPerConditionSelector.MetadataAssignment>();
        reviewed.put(Integer.valueOf(0),
                new QcMinMaxPerConditionSelector.MetadataAssignment("MouseA", "Control"));
        reviewed.put(Integer.valueOf(1),
                new QcMinMaxPerConditionSelector.MetadataAssignment("MouseB", "Treatment"));

        List<QcSelectionCandidate> candidates =
                QcMinMaxPerConditionSelector.buildCandidates(
                        dir.getAbsolutePath(), metas, reviewed);

        assertEquals(2, candidates.size());
        assertEquals("MouseA", candidates.get(0).animalName);
        assertEquals("Control", candidates.get(0).conditionName);
        assertEquals("MouseB", candidates.get(1).animalName);
        assertEquals("Treatment", candidates.get(1).conditionName);
    }

    @Test
    public void cacheInvalidatesWhenOrientationManifestChanges() throws Exception {
        File dir = temp.newFolder("cache-project");
        File lif = temp.newFile("experiment.lif");
        File cache = temp.newFile("cache.properties");
        File scores = temp.newFile("scores.csv");

        OrientationManifestIO.saveRows(dir.getAbsolutePath(), Arrays.asList(
                orientationRow("A", "MouseA")));

        List<QcSelectionChannel> qcChannels = Arrays.asList(
                new QcSelectionChannel(0, "DAPI", true, false, false));
        Method write = QcMinMaxPerConditionSelector.class.getDeclaredMethod(
                "writeCacheProperties", String.class, File.class, List.class,
                flash.pipeline.zslice.ZSliceConfig.class, File.class);
        write.setAccessible(true);
        write.invoke(null, dir.getAbsolutePath(), lif, qcChannels, null, cache);

        Method valid = QcMinMaxPerConditionSelector.class.getDeclaredMethod(
                "isCacheValid", String.class, File.class, List.class,
                flash.pipeline.zslice.ZSliceConfig.class, File.class, File.class);
        valid.setAccessible(true);
        assertEquals(Boolean.TRUE,
                valid.invoke(null, dir.getAbsolutePath(), lif, qcChannels, null, cache, scores));

        OrientationManifestIO.saveRows(dir.getAbsolutePath(), Arrays.asList(
                orientationRow("A", "MouseA"),
                orientationRow("B", "MouseB")));

        assertEquals(Boolean.FALSE,
                valid.invoke(null, dir.getAbsolutePath(), lif, qcChannels, null, cache, scores));
    }

    @Test
    public void sourceSeriesSignatureIncludesProjectLocalSeriesMapping() throws Exception {
        File lif = new File("Cas3.All.Time.Points.lif");
        DeferredImageSupplier supplier = testSupplier(
                Arrays.asList(lif),
                new int[]{8},
                Arrays.<List<Integer>>asList(
                        Arrays.asList(Integer.valueOf(2), Integer.valueOf(5))));
        List<SeriesMeta> metas = Arrays.asList(
                new SeriesMeta(0, "hAPP1Week2_LH_SCN", 12, 1.0, 1.0, 1.0, "pixel"),
                new SeriesMeta(1, "hAPP2Week2_RH_SCN", 12, 1.0, 1.0, 1.0, "pixel"));

        Method signatureMethod = QcMinMaxPerConditionSelector.class.getDeclaredMethod(
                "sourceSeriesSignature", File.class, DeferredImageSupplier.class, List.class);
        signatureMethod.setAccessible(true);
        String signature = (String) signatureMethod.invoke(null, lif, supplier, metas);

        assertTrue(signature.contains("|0:2:Cas3.All.Time.Points.lif:hAPP1Week2_LH_SCN"));
        assertTrue(signature.contains("|1:5:Cas3.All.Time.Points.lif:hAPP2Week2_RH_SCN"));
    }

    @Test
    public void cacheInvalidatesWhenProjectSourceSeriesSignatureChanges() throws Exception {
        File dir = temp.newFolder("cache-source-project");
        File lif = temp.newFile("source.lif");
        File cache = temp.newFile("source-cache.properties");
        File scores = temp.newFile("source-scores.csv");
        List<QcSelectionChannel> qcChannels = Arrays.asList(
                new QcSelectionChannel(0, "DAPI", true, false, false));

        Method write = QcMinMaxPerConditionSelector.class.getDeclaredMethod(
                "writeCacheProperties", String.class, File.class, List.class,
                flash.pipeline.zslice.ZSliceConfig.class, String.class, File.class);
        write.setAccessible(true);
        write.invoke(null, dir.getAbsolutePath(), lif, qcChannels, null,
                "source-series:v1|0:2:source.lif:hAPP1Week2_LH_SCN:0x0z12c0",
                cache);

        Method valid = QcMinMaxPerConditionSelector.class.getDeclaredMethod(
                "isCacheValid", String.class, File.class, List.class,
                flash.pipeline.zslice.ZSliceConfig.class, String.class, File.class, File.class);
        valid.setAccessible(true);

        assertEquals(Boolean.TRUE,
                valid.invoke(null, dir.getAbsolutePath(), lif, qcChannels, null,
                        "source-series:v1|0:2:source.lif:hAPP1Week2_LH_SCN:0x0z12c0",
                        cache, scores));
        assertEquals(Boolean.FALSE,
                valid.invoke(null, dir.getAbsolutePath(), lif, qcChannels, null,
                        "source-series:v1|0:5:source.lif:hAPP2Week2_RH_SCN:0x0z12c0",
                        cache, scores));
    }

    @Test
    public void cacheInvalidatesWhenSelectionScopeChanges() throws Exception {
        File dir = temp.newFolder("cache-scope-project");
        File lif = temp.newFile("scope-source.lif");
        File cache = temp.newFile("scope-cache.properties");
        File scores = temp.newFile("scope-scores.csv");
        List<QcSelectionChannel> qcChannels = Arrays.asList(
                new QcSelectionChannel(0, "DAPI", true, false, false));

        Method write = QcMinMaxPerConditionSelector.class.getDeclaredMethod(
                "writeCacheProperties", String.class, File.class, List.class,
                flash.pipeline.zslice.ZSliceConfig.class, String.class, String.class, File.class);
        write.setAccessible(true);
        write.invoke(null, dir.getAbsolutePath(), lif, qcChannels, null,
                "source-series:v1|0:0:scope-source.lif:MouseA_LH_SCN:0x0z12c0",
                "overall",
                cache);

        Method valid = QcMinMaxPerConditionSelector.class.getDeclaredMethod(
                "isCacheValid", String.class, File.class, List.class,
                flash.pipeline.zslice.ZSliceConfig.class, String.class, String.class, File.class, File.class);
        valid.setAccessible(true);

        assertEquals(Boolean.TRUE,
                valid.invoke(null, dir.getAbsolutePath(), lif, qcChannels, null,
                        "source-series:v1|0:0:scope-source.lif:MouseA_LH_SCN:0x0z12c0",
                        "overall",
                        cache, scores));
        assertEquals(Boolean.FALSE,
                valid.invoke(null, dir.getAbsolutePath(), lif, qcChannels, null,
                        "source-series:v1|0:0:scope-source.lif:MouseA_LH_SCN:0x0z12c0",
                        "per_condition",
                        cache, scores));
    }

    @Test
    public void selectSampledZIndices_spreadsEvenlyAcrossStack() {
        assertEquals(Arrays.asList(0, 2, 3, 5, 6, 8, 9, 11, 12, 14),
                QcMinMaxPerConditionSelector.selectSampledZIndices(15, 10));
        assertEquals(Arrays.asList(0, 5, 11, 16, 22, 27, 33, 38, 44, 49),
                QcMinMaxPerConditionSelector.selectSampledZIndices(50, 10));
    }

    @Test
    public void assignSelectionRoles_combinesChannelRanksPerCondition() {
        List<QcSelectionChannel> qcChannels = Arrays.asList(
                new QcSelectionChannel(0, "DAPI", true, false, false),
                new QcSelectionChannel(1, "Marker", false, true, false));

        List<QcMinMaxPerConditionSelector.ScoreRecord> records =
                new ArrayList<QcMinMaxPerConditionSelector.ScoreRecord>();
        records.add(record("CondA", 0, 10, 30));
        records.add(record("CondA", 1, 20, 20));
        records.add(record("CondA", 2, 30, 10));

        QcMinMaxPerConditionSelector.assignSelectionRoles(records, qcChannels);

        assertEquals("MIN", records.get(0).selectedRole);
        assertEquals("MAX", records.get(2).selectedRole);
        assertEquals(2.0, records.get(1).compositeRank, 0.0001);
    }

    @Test
    public void selectedSeries_ordersConditionsByHighestMaxThenOpensMaxBeforeMin() {
        List<QcMinMaxPerConditionSelector.ScoreRecord> records =
                new ArrayList<QcMinMaxPerConditionSelector.ScoreRecord>();
        QcMinMaxPerConditionSelector.ScoreRecord lowMin = record("Low", 7, 10, 20);
        lowMin.selectedRole = "MIN";
        records.add(lowMin);
        QcMinMaxPerConditionSelector.ScoreRecord lowMax = record("Low", 2, 30, 40);
        lowMax.selectedRole = "MAX";
        records.add(lowMax);
        QcMinMaxPerConditionSelector.ScoreRecord highMin = record("High", 9, 5, 15);
        highMin.selectedRole = "MIN";
        records.add(highMin);
        QcMinMaxPerConditionSelector.ScoreRecord highMax = record("High", 4, 80, 90);
        highMax.selectedRole = "MAX";
        records.add(highMax);
        QcMinMaxPerConditionSelector.ScoreRecord middleMinMax = record("Middle", 5, 50, 60);
        middleMinMax.selectedRole = "MIN_MAX";
        records.add(middleMinMax);

        List<QcMinMaxPerConditionSelector.SelectedSeries> selected =
                QcMinMaxPerConditionSelector.selectedSeries(records);

        assertEquals(5, selected.size());
        assertEquals(4, selected.get(0).seriesIndex);
        assertEquals("High", selected.get(0).conditionName);
        assertEquals("MAX", selected.get(0).selectedRole);
        assertEquals(9, selected.get(1).seriesIndex);
        assertEquals("MIN", selected.get(1).selectedRole);
        assertEquals(5, selected.get(2).seriesIndex);
        assertEquals("Middle", selected.get(2).conditionName);
        assertEquals("MIN_MAX", selected.get(2).selectedRole);
        assertEquals(2, selected.get(3).seriesIndex);
        assertEquals("Low", selected.get(3).conditionName);
        assertEquals("MAX", selected.get(3).selectedRole);
        assertEquals(7, selected.get(4).seriesIndex);
        assertEquals("MIN", selected.get(4).selectedRole);
    }

    @Test
    public void selectedSeriesByChannel_ordersConditionsByThatChannelMax() {
        List<QcSelectionChannel> qcChannels = Arrays.asList(
                new QcSelectionChannel(0, "DAPI", true, false, false),
                new QcSelectionChannel(1, "Marker", true, false, false));
        List<QcMinMaxPerConditionSelector.ScoreRecord> records =
                new ArrayList<QcMinMaxPerConditionSelector.ScoreRecord>();

        QcMinMaxPerConditionSelector.ScoreRecord alphaMin = record("Alpha", 0, 1, 60);
        alphaMin.selectedRole = "MIN";
        records.add(alphaMin);
        QcMinMaxPerConditionSelector.ScoreRecord alphaMax = record("Alpha", 1, 100, 10);
        alphaMax.selectedRole = "MAX";
        records.add(alphaMax);

        QcMinMaxPerConditionSelector.ScoreRecord betaMin = record("Beta", 2, 2, 2);
        betaMin.selectedRole = "MIN";
        records.add(betaMin);
        QcMinMaxPerConditionSelector.ScoreRecord betaMax = record("Beta", 3, 80, 200);
        betaMax.selectedRole = "MAX";
        records.add(betaMax);

        QcMinMaxPerConditionSelector.ScoreRecord gammaMinMax = record("Gamma", 4, 50, 50);
        gammaMinMax.selectedRole = "MIN_MAX";
        records.add(gammaMinMax);

        Map<Integer, List<QcMinMaxPerConditionSelector.SelectedSeries>> byChannel =
                QcMinMaxPerConditionSelector.selectedSeriesByChannelNumber(records, qcChannels);
        List<QcMinMaxPerConditionSelector.SelectedSeries> c1 =
                byChannel.get(Integer.valueOf(1));
        List<QcMinMaxPerConditionSelector.SelectedSeries> c2 =
                byChannel.get(Integer.valueOf(2));

        assertEquals(5, c1.size());
        assertEquals("Alpha", c1.get(0).conditionName);
        assertEquals("MAX", c1.get(0).selectedRole);
        assertEquals("Beta", c1.get(2).conditionName);
        assertEquals("Gamma", c1.get(4).conditionName);

        assertEquals(5, c2.size());
        assertEquals("Beta", c2.get(0).conditionName);
        assertEquals("MAX", c2.get(0).selectedRole);
        assertEquals("Alpha", c2.get(2).conditionName);
        assertEquals("Gamma", c2.get(4).conditionName);
    }

    @Test
    public void selectedSeriesOverall_returnsOnlyGlobalMaxThenMin() {
        List<QcSelectionChannel> qcChannels = Arrays.asList(
                new QcSelectionChannel(0, "DAPI", true, false, false),
                new QcSelectionChannel(1, "Marker", false, true, false));
        List<QcMinMaxPerConditionSelector.ScoreRecord> records =
                new ArrayList<QcMinMaxPerConditionSelector.ScoreRecord>();
        records.add(record("Low", 1, 10, 20));
        records.add(record("Middle", 2, 50, 60));
        records.add(record("High", 3, 90, 95));
        records.add(record("SecondHigh", 4, 80, 85));

        List<QcMinMaxPerConditionSelector.SelectedSeries> selected =
                QcMinMaxPerConditionSelector.selectedSeriesOverall(records, qcChannels);

        assertEquals(2, selected.size());
        assertEquals(3, selected.get(0).seriesIndex);
        assertEquals("High", selected.get(0).conditionName);
        assertEquals("OVERALL_MAX", selected.get(0).selectedRole);
        assertEquals(1, selected.get(1).seriesIndex);
        assertEquals("Low", selected.get(1).conditionName);
        assertEquals("OVERALL_MIN", selected.get(1).selectedRole);
    }

    @Test
    public void selectedSeriesOverall_collapsesSingleSeriesToMinMax() {
        List<QcSelectionChannel> qcChannels = Arrays.asList(
                new QcSelectionChannel(0, "DAPI", true, false, false),
                new QcSelectionChannel(1, "Marker", false, true, false));
        List<QcMinMaxPerConditionSelector.ScoreRecord> records =
                new ArrayList<QcMinMaxPerConditionSelector.ScoreRecord>();
        records.add(record("Only", 6, 42, 43));

        List<QcMinMaxPerConditionSelector.SelectedSeries> selected =
                QcMinMaxPerConditionSelector.selectedSeriesOverall(records, qcChannels);

        assertEquals(1, selected.size());
        assertEquals(6, selected.get(0).seriesIndex);
        assertEquals("Only", selected.get(0).conditionName);
        assertEquals("OVERALL_MIN_MAX", selected.get(0).selectedRole);
    }

    @Test
    public void meanOfBrightestFraction_returnsTopPercentMean() {
        float[] values = new float[]{1, 2, 3, 4, 5, 100};
        assertEquals(100.0,
                QcMinMaxPerConditionSelector.meanOfBrightestFraction(values, 0.01),
                0.0001);
    }

    @Test
    public void scoresCsv_roundTripsSelectionRoles() throws Exception {
        File csv = temp.newFile("scores.csv");
        List<QcSelectionChannel> qcChannels = Arrays.asList(
                new QcSelectionChannel(0, "DAPI", true, false, false),
                new QcSelectionChannel(2, "NeuN", false, true, false));
        List<QcMinMaxPerConditionSelector.ScoreRecord> records =
                new ArrayList<QcMinMaxPerConditionSelector.ScoreRecord>();
        QcMinMaxPerConditionSelector.ScoreRecord first = record("CondA", 3, 12.5, 5.5);
        first.channelScores.put(3, 5.5);
        first.channelScores.remove(2);
        first.compositeRank = 1.0;
        first.selectedRole = "MIN";
        records.add(first);
        QcMinMaxPerConditionSelector.ScoreRecord second = record("CondA", 5, 42.0, 10.0);
        second.channelScores.put(3, 10.0);
        second.channelScores.remove(2);
        second.compositeRank = 2.0;
        second.selectedRole = "MAX";
        records.add(second);

        Method write = QcMinMaxPerConditionSelector.class.getDeclaredMethod(
                "writeScoresCsv", File.class, List.class, List.class);
        write.setAccessible(true);
        write.invoke(null, csv, records, qcChannels);

        List<QcMinMaxPerConditionSelector.ScoreRecord> read =
                QcMinMaxPerConditionSelector.readScoresCsv(csv);

        assertEquals(2, read.size());
        assertEquals("MIN", read.get(0).selectedRole);
        assertEquals(3, read.get(0).candidate.seriesIndex);
        assertEquals(12.5, read.get(0).channelScores.get(1), 0.0001);
        assertEquals(10.0, read.get(1).channelScores.get(3), 0.0001);
    }

    @Test
    public void scoresCsv_roundTripsQuotedSeriesAndAnimalNames() throws Exception {
        File csv = temp.newFile("quoted-scores.csv");
        List<QcSelectionChannel> qcChannels = Arrays.asList(
                new QcSelectionChannel(1, "DAPI", true, false, false));
        List<QcMinMaxPerConditionSelector.ScoreRecord> records =
                new ArrayList<QcMinMaxPerConditionSelector.ScoreRecord>();

        QcSelectionCandidate candidate = new QcSelectionCandidate(
                7,
                "Series, \"Quoted\"\nLine2",
                "Animal, \"Alpha\"",
                "Cond, \"Beta\"");
        QcMinMaxPerConditionSelector.ScoreRecord record =
                new QcMinMaxPerConditionSelector.ScoreRecord(candidate);
        record.channelScores.put(2, 12.5);
        record.compositeRank = 1.0;
        record.selectedRole = "MIN";
        records.add(record);

        Method write = QcMinMaxPerConditionSelector.class.getDeclaredMethod(
                "writeScoresCsv", File.class, List.class, List.class);
        write.setAccessible(true);
        write.invoke(null, csv, records, qcChannels);

        List<QcMinMaxPerConditionSelector.ScoreRecord> read =
                QcMinMaxPerConditionSelector.readScoresCsv(csv);

        assertEquals(1, read.size());
        assertEquals("Series, \"Quoted\"\nLine2", read.get(0).candidate.seriesName);
        assertEquals("Animal, \"Alpha\"", read.get(0).candidate.animalName);
        assertEquals("Cond, \"Beta\"", read.get(0).candidate.conditionName);
        assertEquals(12.5, read.get(0).channelScores.get(2), 0.0001);
    }

    @Test
    public void buildSelectionLogLines_reportsMinAndMaxPerCondition() {
        List<QcSelectionChannel> qcChannels = Arrays.asList(
                new QcSelectionChannel(0, "DAPI", true, false, false),
                new QcSelectionChannel(1, "Marker", false, true, false));
        List<QcMinMaxPerConditionSelector.ScoreRecord> records =
                new ArrayList<QcMinMaxPerConditionSelector.ScoreRecord>();

        QcMinMaxPerConditionSelector.ScoreRecord min = record("CondA", 0, 10, 20);
        min.selectedRole = "MIN";
        min.compositeRank = 1.0;
        records.add(min);

        QcMinMaxPerConditionSelector.ScoreRecord max = record("CondA", 1, 40, 50);
        max.selectedRole = "MAX";
        max.compositeRank = 2.0;
        records.add(max);

        List<String> lines = QcMinMaxPerConditionSelector.buildSelectionLogLines(records, qcChannels);

        assertEquals(1, lines.size());
        assertTrue(lines.get(0).contains("CondA -> MAX:"));
        assertTrue(lines.get(0).contains("MIN:"));
        assertTrue(lines.get(0).contains("MAX:"));
        assertTrue(lines.get(0).contains("Series 1"));
        assertTrue(lines.get(0).contains("Series 2"));
    }

    @Test
    public void buildOverallSelectionLogLines_reportsGlobalMinAndMax() {
        List<QcSelectionChannel> qcChannels = Arrays.asList(
                new QcSelectionChannel(0, "DAPI", true, false, false),
                new QcSelectionChannel(1, "Marker", false, true, false));
        List<QcMinMaxPerConditionSelector.ScoreRecord> records =
                new ArrayList<QcMinMaxPerConditionSelector.ScoreRecord>();

        QcMinMaxPerConditionSelector.ScoreRecord min = record("Low", 0, 10, 20);
        min.selectedRole = "MIN";
        min.compositeRank = 1.0;
        records.add(min);

        QcMinMaxPerConditionSelector.ScoreRecord max = record("High", 1, 40, 50);
        max.selectedRole = "MAX";
        max.compositeRank = 2.0;
        records.add(max);

        List<String> lines = QcMinMaxPerConditionSelector.buildOverallSelectionLogLines(records, qcChannels);

        assertEquals(1, lines.size());
        assertTrue(lines.get(0).contains("Overall -> MAX:"));
        assertTrue(lines.get(0).contains("condition=High"));
        assertTrue(lines.get(0).contains("MIN:"));
        assertTrue(lines.get(0).contains("condition=Low"));
    }

    private static QcMinMaxPerConditionSelector.ScoreRecord record(String condition, int seriesIndex,
                                                                   double c1, double c2) {
        QcSelectionCandidate candidate = new QcSelectionCandidate(
                seriesIndex, "Series" + seriesIndex, "Animal" + seriesIndex, condition);
        QcMinMaxPerConditionSelector.ScoreRecord record =
                new QcMinMaxPerConditionSelector.ScoreRecord(candidate);
        record.channelScores.put(1, c1);
        record.channelScores.put(2, c2);
        return record;
    }

    private static OrientationManifestRow orientationRow(String keySuffix, String animalName) {
        return new OrientationManifestRow(
                OrientationManifestRow.buildImageKey(
                        "CONTAINER", "experiment.lif", 1, "Series " + keySuffix),
                "experiment.lif",
                1,
                "Series " + keySuffix,
                "Series " + keySuffix,
                animalName,
                OrientationManifestRow.Hemisphere.LH,
                "SCN",
                OrientationManifestRow.RotationDegrees.DEG_0,
                false,
                false,
                OrientationManifestRow.ViewPolicy.MANUAL_ONLY,
                OrientationManifestRow.DecisionSource.MANUAL,
                OrientationManifestRow.ConfirmationState.YES,
                "");
    }

    private static DeferredImageSupplier testSupplier(
            List<File> containers,
            int[] seriesCounts,
            List<List<Integer>> includedSeries) throws Exception {
        Method factory = DeferredImageSupplier.class.getDeclaredMethod(
                "multiContainerForTests", List.class, int[].class, List.class);
        factory.setAccessible(true);
        return (DeferredImageSupplier) factory.invoke(null, containers, seriesCounts, includedSeries);
    }
}
