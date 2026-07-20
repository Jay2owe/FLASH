package flash.pipeline.click.training;

import flash.pipeline.segmentation.catalog.ModelEntry;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import smile.classification.RandomForest;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.io.ObjectOutputStream;
import java.nio.file.Files;

import static org.junit.Assert.*;

public class ObjectClassifierTrainerTest {
    private static final String[] SYNTHETIC_NAMES = new String[] {"volume", "sphericity"};

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void refusesTrainingBelowMinimumExamples() {
        try {
            new ObjectClassifierTrainer().train(rows(19, true), rows(20, false), 7);
            fail("Expected training to refuse too few positives.");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("20 positive"));
            assertTrue(expected.getMessage().contains("20 negative"));
        }
    }

    @Test
    public void trainsAndScoresASyntheticSeparableDataset() {
        ObjectClassifierTrainer.TrainingResult result =
                new ObjectClassifierTrainer().train(rows(60, true), rows(60, false), 13);

        assertNotNull(result.model);
        assertTrue(result.crossValAccuracy >= 0.95);
        assertEquals(ObjectClassifierTrainer.QualityFlag.OK, result.quality);

        ObjectClassifierScorer scorer = new ObjectClassifierScorer();
        List<ObjectClassifierScorer.ScoreResult> scores = scorer.score(
                result.model,
                result.featureNames,
                exampleRows(),
                0.5);

        assertTrue(scores.get(0).kept);
        assertFalse(scores.get(1).kept);
    }

    @Test
    public void flagsLowQualityWhenAccuracyUnder0_7() {
        List<ObjectFeatureExtractor.FeatureRow> positives = ambiguousRows(30, true);
        List<ObjectFeatureExtractor.FeatureRow> negatives = ambiguousRows(30, false);

        ObjectClassifierTrainer.TrainingResult result =
                new ObjectClassifierTrainer().train(positives, negatives, 5);

        assertTrue(result.crossValAccuracy < 0.7);
        assertEquals(ObjectClassifierTrainer.QualityFlag.LOW, result.quality);
    }

    @Test
    public void handlesMissingFeaturesViaImputation() {
        String[] names = new String[] {"volume", "quality"};
        List<ObjectFeatureExtractor.FeatureRow> positives = new ArrayList<ObjectFeatureExtractor.FeatureRow>();
        List<ObjectFeatureExtractor.FeatureRow> negatives = new ArrayList<ObjectFeatureExtractor.FeatureRow>();
        for (int i = 0; i < 30; i++) {
            positives.add(new ObjectFeatureExtractor.FeatureRow(i + 1,
                    new double[] {300 + i, Double.NaN}, names));
            negatives.add(new ObjectFeatureExtractor.FeatureRow(i + 101,
                    new double[] {50 + i, Double.NaN}, names));
        }

        ObjectClassifierTrainer.TrainingResult result =
                new ObjectClassifierTrainer().train(positives, negatives, 11);

        assertNotNull(result.model);
        assertTrue(result.crossValAccuracy >= 0.9);
        assertEquals(1, result.featureNames.length);
        assertEquals("volume", result.featureNames[0]);
    }

    @Test
    public void dropsPartiallyNonFiniteFeaturesBeforeTraining() {
        String[] names = new String[] {"volume", "quality"};
        List<ObjectFeatureExtractor.FeatureRow> positives = new ArrayList<ObjectFeatureExtractor.FeatureRow>();
        List<ObjectFeatureExtractor.FeatureRow> negatives = new ArrayList<ObjectFeatureExtractor.FeatureRow>();
        for (int i = 0; i < 30; i++) {
            positives.add(new ObjectFeatureExtractor.FeatureRow(i + 1,
                    new double[] {300 + i, i == 0 ? Double.NaN : 0.9}, names));
            negatives.add(new ObjectFeatureExtractor.FeatureRow(i + 101,
                    new double[] {50 + i, 0.2}, names));
        }

        ObjectClassifierTrainer.TrainingResult result =
                new ObjectClassifierTrainer().train(positives, negatives, 11);

        assertNotNull(result.model);
        assertArrayEquals(new String[] {"volume"}, result.featureNames);
    }

    @Test
    public void featureImportanceIsRelative() {
        ObjectClassifierTrainer.TrainingResult result =
                new ObjectClassifierTrainer().train(rows(60, true), rows(60, false), 13);

        double sum = 0.0;
        for (double value : result.featureImportance) {
            assertTrue(value >= 0.0);
            assertTrue(value <= 1.0);
            sum += value;
        }
        assertEquals(1.0, sum, 1.0e-9);
    }

    @Test
    public void seededExplicitFoldsRepeatAssignmentsAndMetrics() {
        List<ObjectFeatureExtractor.FeatureRow> positives = rows(40, true);
        List<ObjectFeatureExtractor.FeatureRow> negatives = rows(40, false);
        ObjectClassifierTrainer trainer = new ObjectClassifierTrainer();

        ObjectClassifierTrainer.TrainingResult first =
                trainer.train(positives, negatives, 20260716);
        for (int run = 0; run < 3; run++) {
            ObjectClassifierTrainer.TrainingResult repeated =
                    trainer.train(positives, negatives, 20260716);
            assertArrayEquals("Fold assignments changed on stress repetition " + run,
                    first.foldAssignments, repeated.foldAssignments);
            assertEquals("Cross-validation accuracy changed on stress repetition " + run,
                    Double.doubleToLongBits(first.crossValAccuracy),
                    Double.doubleToLongBits(repeated.crossValAccuracy));
            assertArrayEquals("Feature importance changed on stress repetition " + run,
                    first.featureImportance, repeated.featureImportance, 0.0);
        }
        assertEquals(20260716, first.seed);
        assertEquals("smile-2.6.0", first.backendVersion);
        assertEquals("cpu", first.device);
        assertEquals("global-smile-rng-lock-explicit-seeded-folds-serial-tree-streams",
                first.deterministicMode);

        int[] changed = ObjectClassifierTrainer.deterministicFoldAssignments(
                positives.size(), negatives.size(), 5, 20260717);
        assertFalse("Changing the seed should change at least one whole row assignment",
                java.util.Arrays.equals(first.foldAssignments, changed));
        for (int fold = 0; fold < 5; fold++) {
            int positivesInFold = 0;
            int negativesInFold = 0;
            for (int row = 0; row < first.foldAssignments.length; row++) {
                int assignment = first.foldAssignments[row];
                assertTrue(assignment >= 0 && assignment < 5);
                if (assignment == fold) {
                    if (row < positives.size()) positivesInFold++;
                    else negativesInFold++;
                }
            }
            assertTrue("Each explicit fold needs positive validation examples",
                    positivesInFold > 0);
            assertTrue("Each explicit fold needs negative validation examples",
                    negativesInFold > 0);
        }
    }

    @Test
    public void catalogEntryIncludesAuditMetadata() {
        int[] foldAssignments = new int[] {0, 1, 2, 3, 4};
        ObjectClassifierTrainer.TrainingResult result =
                new ObjectClassifierTrainer.TrainingResult(
                        null,
                        new String[] {"volume", "sphericity", "mean_intensity"},
                        0.83,
                        new double[] {0.20, 0.50, 0.30},
                        28,
                        41,
                        ObjectClassifierTrainer.QualityFlag.OK,
                        8675309,
                        "smile-2.6.0",
                        "cpu",
                        "global-smile-rng-lock-explicit-seeded-folds-serial-tree-streams",
                        foldAssignments);

        ModelEntry entry = ObjectClassifierPersistence.catalogEntry(
                "trained_rf_microglia_v1",
                "Microglia RF",
                "description",
                "classical",
                result);

        assertEquals("smile-2.6.0", entry.metadata.get("engineVersion"));
        assertTrue(entry.metadata.get("trainedAt") instanceof Long);
        assertEquals(Double.valueOf(0.83), entry.metadata.get("crossValAccuracy"));
        assertEquals("OK", entry.metadata.get("qualityFlag"));
        assertEquals("OK", entry.metadata.get("quality"));
        assertEquals(Integer.valueOf(8675309), entry.metadata.get("seed"));
        assertEquals("smile-2.6.0", entry.metadata.get("backendVersion"));
        assertEquals("cpu", entry.metadata.get("device"));
        assertEquals("global-smile-rng-lock-explicit-seeded-folds-serial-tree-streams",
                entry.metadata.get("deterministicModeStatus"));
        assertEquals(java.util.Arrays.asList(0, 1, 2, 3, 4),
                entry.metadata.get("crossValidationFoldAssignments"));

        result.foldAssignments[0] = 4;
        foldAssignments[1] = 4;
        assertEquals("Catalog fold evidence must be a defensive persisted snapshot",
                java.util.Arrays.asList(0, 1, 2, 3, 4),
                entry.metadata.get("crossValidationFoldAssignments"));

        @SuppressWarnings("unchecked")
        Map<String, Object> importance = (Map<String, Object>) entry.metadata.get("featureImportance");
        assertEquals(Double.valueOf(0.20), importance.get("volume"));
        assertEquals(Double.valueOf(0.50), importance.get("sphericity"));
        assertEquals(Double.valueOf(0.30), importance.get("mean_intensity"));
    }

    @Test
    public void serializeRoundTrip() throws Exception {
        ObjectClassifierTrainer.TrainingResult result =
                new ObjectClassifierTrainer().train(rows(40, true), rows(40, false), 3);
        Path path = temp.newFile("model.smile").toPath();

        ObjectClassifierPersistence.saveModel(path, result.model);
        RandomForest loaded = ObjectClassifierPersistence.loadModel(path);

        List<ObjectClassifierScorer.ScoreResult> scores = new ObjectClassifierScorer().score(
                loaded,
                result.featureNames,
                exampleRows(),
                0.5);
        assertTrue(scores.get(0).kept);
        assertFalse(scores.get(1).kept);
    }

    @Test
    public void loadModelRejectsUnexpectedSerializedClasses() throws Exception {
        Path path = temp.newFile("not-model.smile").toPath();
        ObjectOutputStream out = new ObjectOutputStream(Files.newOutputStream(path));
        try {
            out.writeObject(new java.io.File("payload"));
        } finally {
            out.close();
        }

        try {
            ObjectClassifierPersistence.loadModel(path);
            fail("Expected non-model serialized payload to be rejected.");
        } catch (java.io.InvalidClassException expected) {
            assertTrue(expected.getMessage().contains("java.io.File"));
        }
    }

    private static List<ObjectFeatureExtractor.FeatureRow> rows(int count, boolean positive) {
        List<ObjectFeatureExtractor.FeatureRow> rows = new ArrayList<ObjectFeatureExtractor.FeatureRow>();
        for (int i = 0; i < count; i++) {
            double volume = positive ? 240 + i : 40 + i;
            double sphericity = positive ? 0.75 + (i % 10) * 0.01 : 0.25 + (i % 10) * 0.01;
            rows.add(new ObjectFeatureExtractor.FeatureRow(i + 1,
                    new double[] {volume, sphericity}, SYNTHETIC_NAMES));
        }
        return rows;
    }

    private static List<ObjectFeatureExtractor.FeatureRow> ambiguousRows(int count, boolean positive) {
        List<ObjectFeatureExtractor.FeatureRow> rows = new ArrayList<ObjectFeatureExtractor.FeatureRow>();
        for (int i = 0; i < count; i++) {
            rows.add(new ObjectFeatureExtractor.FeatureRow(i + 1,
                    new double[] {100 + (i % 5), 0.5 + (i % 3) * 0.01}, SYNTHETIC_NAMES));
        }
        return rows;
    }

    private static List<ObjectFeatureExtractor.FeatureRow> exampleRows() {
        List<ObjectFeatureExtractor.FeatureRow> rows = new ArrayList<ObjectFeatureExtractor.FeatureRow>();
        rows.add(new ObjectFeatureExtractor.FeatureRow(1, new double[] {260, 0.8}, SYNTHETIC_NAMES));
        rows.add(new ObjectFeatureExtractor.FeatureRow(2, new double[] {60, 0.3}, SYNTHETIC_NAMES));
        return rows;
    }
}
