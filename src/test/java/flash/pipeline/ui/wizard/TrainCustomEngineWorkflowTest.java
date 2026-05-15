package flash.pipeline.ui.wizard;

import flash.pipeline.click.ClickStore;
import flash.pipeline.click.training.ObjectClassifierTrainer;
import flash.pipeline.click.training.cellpose.CellposeDatasetPackager;
import flash.pipeline.click.training.stardist.StarDistDatasetPackager;
import flash.pipeline.segmentation.catalog.ModelCatalog;
import flash.pipeline.segmentation.catalog.ModelEntry;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

import static org.junit.Assert.*;

public class TrainCustomEngineWorkflowTest {
    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void cancellationAtEachStepRestoresPreviousMethod() throws Exception {
        RecordingMethodStore store = new RecordingMethodStore("stardist:0.5:0.4:model=old");
        RecordingCatalogService catalog = new RecordingCatalogService();
        TrainCustomEngineWorkflow workflow = workflow(store, clickStore(20, 20),
                services(okRf(), null, null, catalog, "rf_model"));

        workflow.selectBase(TrainCustomEngineWorkflow.Base.CLASSICAL);
        workflow.cancel();
        assertEquals("stardist:0.5:0.4:model=old", store.token);

        workflow = workflow(store, clickStore(20, 20),
                services(okRf(), null, null, catalog, "rf_model"));
        workflow.selectBase(TrainCustomEngineWorkflow.Base.CLASSICAL);
        workflow.runTrainingStep(TrainCustomEngineWorkflow.NO_PROGRESS);
        store.token = "temporary";
        workflow.cancel();
        assertEquals("stardist:0.5:0.4:model=old", store.token);

        workflow = workflow(store, clickStore(20, 20),
                services(okRf(), null, null, catalog, "rf_model"));
        workflow.selectBase(TrainCustomEngineWorkflow.Base.CLASSICAL);
        workflow.runTrainingStep(TrainCustomEngineWorkflow.NO_PROGRESS);
        workflow.saveModel("RF model", "description");
        store.token = "temporary";
        workflow.cancel();
        assertEquals("stardist:0.5:0.4:model=old", store.token);

        workflow.applyRecommended();
        assertEquals("trained_rf:rf_model:base=classical", store.token);
        workflow.cancel();
        assertEquals("stardist:0.5:0.4:model=old", store.token);
    }

    @Test
    public void insufficientClicksBlockStepTwo() throws Exception {
        TrainCustomEngineWorkflow workflow = workflow(
                new RecordingMethodStore("classical"),
                clickStore(19, 20),
                services(okRf(), null, null, new RecordingCatalogService(), "rf_model"));
        workflow.selectBase(TrainCustomEngineWorkflow.Base.CLASSICAL);

        assertFalse(workflow.canProceedFromClicks());
        assertTrue(workflow.clickGateMessage().contains("Need at least 20 positive"));
        try {
            workflow.runTrainingStep(TrainCustomEngineWorkflow.NO_PROGRESS);
            fail("Expected insufficient clicks to block training.");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("19 positive"));
        }
    }

    @Test
    public void successfulSmileRfFlowWritesCatalogEntryAndAppliesTrainedRfToken() throws Exception {
        RecordingMethodStore store = new RecordingMethodStore("classical");
        RecordingCatalogService catalog = new RecordingCatalogService();
        TrainCustomEngineWorkflow workflow = workflow(store, clickStore(28, 41),
                services(okRf(), null, null, catalog, "rf_iba1"));

        workflow.selectBase(TrainCustomEngineWorkflow.Base.CLASSICAL);
        workflow.runTrainingStep(TrainCustomEngineWorkflow.NO_PROGRESS);
        workflow.saveModel("Microglia RF", "28 positive / 41 negative");
        workflow.applyRecommended();

        assertEquals(1, catalog.saved.size());
        assertEquals(ModelEntry.Engine.SMILE_RF, catalog.saved.get(0).engine);
        assertEquals("trained_rf:rf_iba1:base=classical", store.token);
    }

    @Test
    public void successfulStarDistFlowPackagesDatasetAndAppliesCanonicalToken() throws Exception {
        RecordingMethodStore store = new RecordingMethodStore("classical");
        RecordingCatalogService catalog = new RecordingCatalogService();
        RecordingStarDistPackager packager = new RecordingStarDistPackager(temp.newFolder("sd-dataset").toPath());
        TrainCustomEngineWorkflow workflow = workflow(store, clickStore(25, 25),
                services(null, packager, null, catalog, "stardist_custom"));
        workflow.setBaseToken(TrainCustomEngineWorkflow.Base.STARDIST,
                "stardist:0.5:0.3:linking=5.0:gapClosing=5.0:"
                        + "area=20-2000:quality=0.2:intensity=50");

        Path zip = temp.newFile("TF_SavedModel.zip").toPath();
        Files.write(zip, "zip".getBytes(StandardCharsets.UTF_8));

        workflow.selectBase(TrainCustomEngineWorkflow.Base.STARDIST);
        workflow.runTrainingStep(TrainCustomEngineWorkflow.NO_PROGRESS);
        workflow.acceptExternalModelFile(zip);
        workflow.saveModel("StarDist custom", "description");
        workflow.applyRecommended();

        assertTrue(packager.called);
        assertEquals("stardist:0.5:0.3:linking=5.0:gapClosing=5.0:"
                        + "area=20-2000:quality=0.2:intensity=50:model=stardist_custom",
                store.token);
    }

    @Test
    public void successfulCellposeFlowPackagesDatasetAndAppliesCanonicalToken() throws Exception {
        RecordingMethodStore store = new RecordingMethodStore("classical");
        RecordingCatalogService catalog = new RecordingCatalogService();
        RecordingCellposePackager packager = new RecordingCellposePackager(temp.newFolder("cp-dataset").toPath());
        TrainCustomEngineWorkflow workflow = workflow(store, clickStore(22, 23),
                services(null, null, packager, catalog, "cellpose_custom"));
        workflow.setBaseToken(TrainCustomEngineWorkflow.Base.CELLPOSE,
                "cellpose:30.0:0.4:0.0:gpu=true:chan2=0:model=cellpose_cyto3");

        Path model = temp.newFile("cellpose_model").toPath();
        Files.write(model, "model".getBytes(StandardCharsets.UTF_8));

        workflow.selectBase(TrainCustomEngineWorkflow.Base.CELLPOSE);
        workflow.runTrainingStep(TrainCustomEngineWorkflow.NO_PROGRESS);
        workflow.acceptExternalModelFile(model);
        workflow.saveModel("Cellpose custom", "description");
        workflow.applyRecommended();

        assertTrue(packager.called);
        assertEquals("cellpose:30.0:0.4:0.0:gpu=true:chan2=0:model=cellpose_custom",
                store.token);
    }

    @Test
    public void lowQualityFlagSurfacesWarningMessage() throws Exception {
        TrainCustomEngineWorkflow workflow = workflow(
                new RecordingMethodStore("classical"),
                clickStore(25, 25),
                services(lowRf(), null, null, new RecordingCatalogService(), "rf_low"));
        workflow.selectBase(TrainCustomEngineWorkflow.Base.CLASSICAL);

        TrainCustomEngineWorkflow.TrainStepResult result =
                workflow.runTrainingStep(TrainCustomEngineWorkflow.NO_PROGRESS);

        assertEquals(ObjectClassifierTrainer.QualityFlag.LOW, result.rfResult.quality);
        assertTrue(workflow.warningMessage().contains("LOW"));
        assertTrue(workflow.warningMessage().contains("0.62"));
    }

    private TrainCustomEngineWorkflow workflow(RecordingMethodStore store,
                                               ClickStore clicks,
                                               TrainCustomEngineWorkflow.Services services) throws Exception {
        return new TrainCustomEngineWorkflow(
                temp.newFolder().toPath(),
                2,
                "Iba1",
                clicks,
                store,
                services);
    }

    private static TrainCustomEngineWorkflow.Services services(
            TrainCustomEngineWorkflow.RfTrainingService rf,
            TrainCustomEngineWorkflow.StarDistPackagingService sd,
            TrainCustomEngineWorkflow.CellposePackagingService cp,
            RecordingCatalogService catalog,
            final String key) {
        return new TrainCustomEngineWorkflow.Services(
                rf,
                sd,
                cp,
                catalog,
                new TrainCustomEngineWorkflow.ModelKeyGenerator() {
                    @Override public String newModelKey(ModelCatalog catalog,
                                                        ModelEntry.Engine engine,
                                                        String displayName) {
                        return key;
                    }
                },
                Clock.fixed(Instant.parse("2026-05-15T00:00:00Z"), ZoneOffset.UTC));
    }

    private static TrainCustomEngineWorkflow.RfTrainingService okRf() {
        return rf(ObjectClassifierTrainer.QualityFlag.OK, 0.83);
    }

    private static TrainCustomEngineWorkflow.RfTrainingService lowRf() {
        return rf(ObjectClassifierTrainer.QualityFlag.LOW, 0.62);
    }

    private static TrainCustomEngineWorkflow.RfTrainingService rf(
            final ObjectClassifierTrainer.QualityFlag quality,
            final double accuracy) {
        return new TrainCustomEngineWorkflow.RfTrainingService() {
            @Override public ObjectClassifierTrainer.TrainingResult train(
                    TrainCustomEngineWorkflow.Base base,
                    TrainCustomEngineWorkflow.ClickSelection selection,
                    TrainCustomEngineWorkflow.ProgressListener progress) {
                return new ObjectClassifierTrainer.TrainingResult(
                        null,
                        new String[] {"volume"},
                        accuracy,
                        new double[] {1.0},
                        selection.summary.positive,
                        selection.summary.negative,
                        quality);
            }
        };
    }

    private static ClickStore clickStore(int positive, int negative) {
        ClickStore store = new ClickStore();
        for (int i = 0; i < positive; i++) {
            store.add(new ClickStore.Click("Image1", 2, i + 1, 1,
                    i, 0, ClickStore.Verdict.POSITIVE, i));
        }
        for (int i = 0; i < negative; i++) {
            store.add(new ClickStore.Click("Image1", 2, i + 101, 1,
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

    private static final class RecordingCatalogService
            implements TrainCustomEngineWorkflow.ModelCatalogService {
        final List<ModelEntry> saved = new ArrayList<ModelEntry>();

        @Override public ModelEntry saveRf(Path projectRoot, String modelKey,
                                           String name, String description,
                                           String baseToken,
                                           ObjectClassifierTrainer.TrainingResult result) {
            ModelEntry entry = entry(modelKey, name, ModelEntry.Engine.SMILE_RF, baseToken);
            saved.add(entry);
            return entry;
        }

        @Override public ModelEntry saveStarDist(Path projectRoot, String modelKey,
                                                 String name, String description,
                                                 Path modelFile, String baseToken,
                                                 TrainCustomEngineWorkflow.ClickSummary clickSummary,
                                                 StarDistDatasetPackager.PackagingResult packageResult) {
            ModelEntry entry = entry(modelKey, name, ModelEntry.Engine.STARDIST, baseToken);
            saved.add(entry);
            return entry;
        }

        @Override public ModelEntry saveCellpose(Path projectRoot, String modelKey,
                                                 String name, String description,
                                                 Path modelFile, String baseToken,
                                                 TrainCustomEngineWorkflow.ClickSummary clickSummary,
                                                 CellposeDatasetPackager.PackagingResult packageResult) {
            ModelEntry entry = entry(modelKey, name, ModelEntry.Engine.CELLPOSE, baseToken);
            saved.add(entry);
            return entry;
        }

        private static ModelEntry entry(String key, String name,
                                        ModelEntry.Engine engine, String baseToken) {
            return new ModelEntry(key, name, "description", engine,
                    ModelEntry.Source.USER_TRAINED,
                    "files/" + key + "/model", null, null, null, baseToken,
                    new LinkedHashMap<String, Object>(),
                    new LinkedHashMap<String, Object>(),
                    false);
        }
    }

    private static final class RecordingStarDistPackager
            implements TrainCustomEngineWorkflow.StarDistPackagingService {
        final Path dir;
        boolean called;

        RecordingStarDistPackager(Path dir) {
            this.dir = dir;
        }

        @Override public StarDistDatasetPackager.PackagingResult packageDataset(
                TrainCustomEngineWorkflow.ClickSelection selection,
                String sessionName,
                TrainCustomEngineWorkflow.ProgressListener progress) {
            called = true;
            return new StarDistDatasetPackager.PackagingResult(dir, 8, 25, 25);
        }
    }

    private static final class RecordingCellposePackager
            implements TrainCustomEngineWorkflow.CellposePackagingService {
        final Path dir;
        boolean called;

        RecordingCellposePackager(Path dir) {
            this.dir = dir;
        }

        @Override public CellposeDatasetPackager.PackagingResult packageDataset(
                TrainCustomEngineWorkflow.ClickSelection selection,
                String sessionName,
                String baseModel,
                TrainCustomEngineWorkflow.ProgressListener progress) {
            called = true;
            return new CellposeDatasetPackager.PackagingResult(
                    dir, dir.resolve("train_command.txt"), 8, 96, 25, 25);
        }
    }
}
