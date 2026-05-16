package flash.pipeline.ui.wizard;

import flash.pipeline.click.ClickStore;
import flash.pipeline.click.training.ObjectClassifierTrainer;
import flash.pipeline.segmentation.SegmentationMethod;
import flash.pipeline.segmentation.SegmentationTokenParser;
import flash.pipeline.segmentation.catalog.ModelCatalog;
import flash.pipeline.segmentation.catalog.ModelEntry;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class TrainCustomEngineWorkflowStarDistBaseTest {
    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void starDistRfFlowTrainsSmileRfAndWritesNestedBaseToken() throws Exception {
        final String baseToken = "stardist:0.5:0.3:area=20-2000:model=user_stardist_v1";
        RecordingMethodStore store = new RecordingMethodStore("classical");
        RecordingCatalogService catalog = new RecordingCatalogService();
        RecordingRfTrainer trainer = new RecordingRfTrainer();
        TrainCustomEngineWorkflow workflow = new TrainCustomEngineWorkflow(
                temp.newFolder("project").toPath(),
                1,
                "Iba1",
                clickStore(24, 26),
                store,
                services(trainer, catalog));
        workflow.setBaseToken(TrainCustomEngineWorkflow.Base.STARDIST, baseToken);

        workflow.selectBase(TrainCustomEngineWorkflow.Base.STARDIST_RF);
        workflow.runTrainingStep(TrainCustomEngineWorkflow.NO_PROGRESS);
        workflow.saveModel("Microglia StarDist RF", "description");
        workflow.applyRecommended();

        assertEquals(TrainCustomEngineWorkflow.Base.STARDIST_RF, trainer.base);
        assertEquals(ModelEntry.Engine.SMILE_RF, catalog.saved.engine);
        assertEquals(baseToken, catalog.savedBaseToken);
        assertEquals("trained_rf:rf_microglia:base=stardist%3A0.5%3A0.3%3Aarea%3D20-2000%3Amodel%3Duser_stardist_v1",
                store.token);
        SegmentationMethod parsed = SegmentationTokenParser.parse(store.token);
        assertTrue(parsed.isTrainedRf());
        assertEquals(baseToken, parsed.params.get("base"));
    }

    private static TrainCustomEngineWorkflow.Services services(
            TrainCustomEngineWorkflow.RfTrainingService rf,
            RecordingCatalogService catalog) {
        return new TrainCustomEngineWorkflow.Services(
                rf,
                new TrainCustomEngineWorkflow.StarDistPackagingService() {
                    @Override public flash.pipeline.click.training.stardist.StarDistDatasetPackager.PackagingResult packageDataset(
                            TrainCustomEngineWorkflow.ClickSelection selection,
                            String sessionName,
                            TrainCustomEngineWorkflow.ProgressListener progress) {
                        fail("StarDist RF post-filter flow must not package an external StarDist dataset.");
                        return null;
                    }
                },
                null,
                catalog,
                new TrainCustomEngineWorkflow.ModelKeyGenerator() {
                    @Override public String newModelKey(ModelCatalog catalog,
                                                        ModelEntry.Engine engine,
                                                        String displayName) {
                        return "rf_microglia";
                    }
                },
                Clock.fixed(Instant.parse("2026-05-15T00:00:00Z"), ZoneOffset.UTC));
    }

    private static ClickStore clickStore(int positive, int negative) {
        ClickStore store = new ClickStore();
        for (int i = 0; i < positive; i++) {
            store.add(new ClickStore.Click("Image1", 1, i + 1, 1,
                    i, 0, ClickStore.Verdict.POSITIVE, i));
        }
        for (int i = 0; i < negative; i++) {
            store.add(new ClickStore.Click("Image1", 1, i + 101, 1,
                    i, 1, ClickStore.Verdict.NEGATIVE, i));
        }
        return store;
    }

    private static final class RecordingMethodStore
            implements TrainCustomEngineWorkflow.ChannelMethodStore {
        String token;

        RecordingMethodStore(String token) {
            this.token = token;
        }

        @Override public String getMethodToken() {
            return token;
        }

        @Override public void setMethodToken(String token) {
            this.token = token;
        }
    }

    private static final class RecordingRfTrainer
            implements TrainCustomEngineWorkflow.RfTrainingService {
        TrainCustomEngineWorkflow.Base base;

        @Override public ObjectClassifierTrainer.TrainingResult train(
                TrainCustomEngineWorkflow.Base base,
                TrainCustomEngineWorkflow.ClickSelection selection,
                TrainCustomEngineWorkflow.ProgressListener progress) {
            this.base = base;
            return new ObjectClassifierTrainer.TrainingResult(
                    null,
                    new String[] {"volume"},
                    0.9,
                    new double[] {1.0},
                    selection.summary.positive,
                    selection.summary.negative,
                    ObjectClassifierTrainer.QualityFlag.OK);
        }
    }

    private static final class RecordingCatalogService
            implements TrainCustomEngineWorkflow.ModelCatalogService {
        ModelEntry saved;
        String savedBaseToken;

        @Override public ModelEntry saveRf(Path projectRoot,
                                           String modelKey,
                                           String name,
                                           String description,
                                           String baseToken,
                                           ObjectClassifierTrainer.TrainingResult result) {
            savedBaseToken = baseToken;
            saved = entry(modelKey, name, ModelEntry.Engine.SMILE_RF, baseToken);
            return saved;
        }

        @Override public ModelEntry saveStarDist(Path projectRoot,
                                                 String modelKey,
                                                 String name,
                                                 String description,
                                                 Path modelFile,
                                                 String baseToken,
                                                 TrainCustomEngineWorkflow.ClickSummary clickSummary,
                                                 flash.pipeline.click.training.stardist.StarDistDatasetPackager.PackagingResult packageResult) {
            fail("StarDist RF post-filter flow must save a Smile RF model, not a StarDist model.");
            return null;
        }

        @Override public ModelEntry saveCellpose(Path projectRoot,
                                                 String modelKey,
                                                 String name,
                                                 String description,
                                                 Path modelFile,
                                                 String baseToken,
                                                 TrainCustomEngineWorkflow.ClickSummary clickSummary,
                                                 flash.pipeline.click.training.cellpose.CellposeDatasetPackager.PackagingResult packageResult) {
            fail("StarDist RF post-filter flow must save a Smile RF model, not a Cellpose model.");
            return null;
        }

        private static ModelEntry entry(String key,
                                        String name,
                                        ModelEntry.Engine engine,
                                        String baseToken) {
            return new ModelEntry(key, name, "description", engine,
                    ModelEntry.Source.USER_TRAINED,
                    "files/" + key + "/model", null, null, null, baseToken,
                    new LinkedHashMap<String, Object>(),
                    new LinkedHashMap<String, Object>(),
                    false);
        }
    }
}
