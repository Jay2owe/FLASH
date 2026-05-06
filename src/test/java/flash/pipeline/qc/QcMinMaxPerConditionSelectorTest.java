package flash.pipeline.qc;

import flash.pipeline.io.LifIO;
import flash.pipeline.io.SeriesMeta;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class QcMinMaxPerConditionSelectorTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void buildCandidates_usesConditionManifestAssignments() throws Exception {
        File dir = temp.newFolder("project");
        File exportDir = new File(dir, "ImageJ Exports");
        assertTrue(exportDir.mkdirs());

        Files.write(new File(exportDir, "Project_Conditions.csv").toPath(),
                ("AnimalName,Condition\n"
                        + "Syn1WeekTwo,SynWeekTwo\n"
                        + "hAPP2WeekEight,hAPPWeekEight\n").getBytes(StandardCharsets.UTF_8));

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
        assertTrue(lines.get(0).contains("CondA -> MIN:"));
        assertTrue(lines.get(0).contains("MAX:"));
        assertTrue(lines.get(0).contains("Series 1"));
        assertTrue(lines.get(0).contains("Series 2"));
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
}
