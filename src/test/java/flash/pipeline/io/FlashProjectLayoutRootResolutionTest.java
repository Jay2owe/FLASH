package flash.pipeline.io;

import flash.pipeline.click.training.ObjectClassifierTrainer;
import flash.pipeline.click.training.ObjectFeatureExtractor;
import flash.pipeline.segmentation.catalog.ModelCatalog;
import flash.pipeline.segmentation.catalog.ModelCatalogIO;
import flash.pipeline.segmentation.catalog.ModelEntry;
import flash.pipeline.ui.wizard.TrainCustomEngineWorkflow;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class FlashProjectLayoutRootResolutionTest {
    private static final String[] FEATURE_NAMES = new String[] {"volume"};

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void legacyBinConfigurationDirResolvesToProjectRoot() throws Exception {
        File project = temp.newFolder("project");
        File legacyBin = new File(project, ".bin");

        assertPath(project, FlashProjectLayout.projectRootForConfigurationDir(legacyBin));
    }

    @Test
    public void flashChildConfigurationDirResolvesToProjectRoot() throws Exception {
        File project = temp.newFolder("project");
        File configDir = new File(project, "FLASH/00 - Configuration");

        assertPath(project, FlashProjectLayout.projectRootForConfigurationDir(configDir));
    }

    @Test
    public void setupSettingsConfigurationDirResolvesToProjectRoot() throws Exception {
        File project = temp.newFolder("project");
        File configDir = new File(project, "FLASH/Set Up Configuration/.settings");

        assertPath(project, FlashProjectLayout.projectRootForConfigurationDir(configDir));
    }

    @Test
    public void wizardSavedRfCatalogEntryFromConfigurationWriteDirIsVisibleToAnalysisRoot() throws Exception {
        File project = temp.newFolder("project");
        FlashProjectLayout layout = FlashProjectLayout.forDirectory(project.getAbsolutePath());
        File setupRoot = FlashProjectLayout.projectRootForConfigurationDir(layout.configurationWriteDir());

        ObjectClassifierTrainer.TrainingResult trained = new ObjectClassifierTrainer()
                .train(rows(20, true), rows(20, false), 17);
        new TrainCustomEngineWorkflow.DefaultModelCatalogService().saveRf(
                setupRoot.toPath(),
                "rf_setup_qc",
                "Setup QC RF",
                "Saved from setup QC",
                "classical",
                trained);

        ModelCatalog analysisCatalog = ModelCatalogIO.read(project.toPath());
        Optional<ModelEntry> entry = analysisCatalog.get("rf_setup_qc");

        assertTrue("analysis root should see the setup-saved RF catalog entry", entry.isPresent());
        assertTrue("analysis root should resolve the setup-saved RF model file",
                Files.isRegularFile(analysisCatalog.resolve(entry.get())));
    }

    private static List<ObjectFeatureExtractor.FeatureRow> rows(int count, boolean positive) {
        List<ObjectFeatureExtractor.FeatureRow> rows = new ArrayList<ObjectFeatureExtractor.FeatureRow>();
        for (int i = 0; i < count; i++) {
            double volume = positive ? 10.0 + i : 1.0 + (i % 3);
            rows.add(new ObjectFeatureExtractor.FeatureRow(i + 1,
                    new double[] {volume},
                    FEATURE_NAMES));
        }
        return rows;
    }

    private static void assertPath(File expected, File actual) {
        assertEquals(expected.getAbsolutePath(), actual.getAbsolutePath());
    }
}
