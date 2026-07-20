package flash.pipeline.analyses;

import flash.pipeline.TestConfigFiles;
import flash.pipeline.bin.BinConfig;
import flash.pipeline.recipes.RecipeReplayModelResolver;
import flash.pipeline.runtime.DependencyId;
import flash.pipeline.runtime.DependencyService;
import flash.pipeline.runtime.DependencyStatus;
import flash.pipeline.runtime.FeatureDependencyGate;
import flash.pipeline.segmentation.catalog.ModelCatalog;
import flash.pipeline.segmentation.catalog.ModelCatalogIO;
import flash.pipeline.segmentation.catalog.ModelEntry;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class TrainedRfMissingModelFailsAnalysisTest {
    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @After
    public void resetDependencyGate() {
        FeatureDependencyGate.configure(new DependencyService(), null);
        FeatureDependencyGate.setUiMode(false);
    }

    @Test
    public void missingTrainedRfModelFailsAnalysisBeforeImageProcessing() throws Exception {
        installAllDependenciesPresentForGate();
        File dir = temp.newFolder("missing-rf-analysis");
        BinConfig cfg = TestConfigFiles.basicBinConfig("IBA1");
        cfg.channelColors.clear();
        cfg.channelColors.add("Green");
        cfg.channelThresholds.clear();
        cfg.channelThresholds.add("100");
        cfg.channelSizes.clear();
        cfg.channelSizes.add("10-Infinity");
        cfg.segmentationMethods.clear();
        cfg.addSegmentationMethodToken("trained_rf:missing_model:base=classical");
        TestConfigFiles.writeChannelConfig(dir, cfg);

        ThreeDObjectAnalysis analysis = new ThreeDObjectAnalysis();
        analysis.setHeadless(true);
        analysis.setSuppressDialogs(true);

        try {
            analysis.execute(dir.getAbsolutePath());
            fail("Expected missing trained RF model to fail analysis.");
        } catch (RuntimeException expected) {
            String message = expected.getMessage();
            assertTrue(message, message.contains("Missing segmentation models"));
            assertTrue(message, message.contains("trained_rf:missing_model"));
            assertTrue(message, message.contains("model.smile"));
        }
        assertFalse(new File(dir, "Data Analysis").exists());
    }

    @Test
    public void preflightAcceptsExistingTrainedRfModelFile() throws Exception {
        Path root = temp.newFolder("present-rf").toPath();
        Path modelFile = ModelCatalogIO.catalogDirectory(root)
                .resolve("files")
                .resolve("rf_ok")
                .resolve("model.smile");
        Files.createDirectories(modelFile.getParent());
        Files.write(modelFile, new byte[]{1, 2, 3});
        ModelEntry entry = new ModelEntry(
                "rf_ok",
                "RF OK",
                "Smile RF post-filter.",
                ModelEntry.Engine.SMILE_RF,
                ModelEntry.Source.USER_TRAINED,
                "files/rf_ok/model.smile",
                null,
                null,
                null,
                "classical",
                new LinkedHashMap<String, Object>(),
                new LinkedHashMap<String, Object>(),
                false);
        ModelCatalogIO.writeProject(root, new ModelCatalog(root, Collections.singletonList(entry)));

        String token = "trained_rf:rf_ok:base=classical";
        RecipeReplayModelResolver.validate(root, Collections.singletonList(token));
        List<RecipeReplayModelResolver.ResolvedModelUse> resolved =
                RecipeReplayModelResolver.resolve(root, Collections.singletonList(token));

        assertEquals(1, resolved.size());
        assertEquals(RecipeReplayModelResolver.Engine.TRAINED_RF, resolved.get(0).engine);
        assertEquals("rf_ok", resolved.get(0).modelKey);
        assertEquals(modelFile.toAbsolutePath().normalize().toString(), resolved.get(0).runtimeArgument);
    }

    private static void installAllDependenciesPresentForGate() throws Exception {
        final EnumMap<DependencyId, DependencyStatus> statuses =
                new EnumMap<DependencyId, DependencyStatus>(DependencyId.class);
        for (DependencyId id : DependencyId.values()) {
            statuses.put(id, DependencyStatus.present(id.name() + " present"));
        }

        Class<?> providerType = Class.forName(
                "flash.pipeline.runtime.DependencyService$StatusSnapshotProvider");
        Object provider = Proxy.newProxyInstance(
                providerType.getClassLoader(),
                new Class<?>[]{providerType},
                (proxyObject, method, args) -> {
                    if ("snapshot".equals(method.getName())) {
                        return new EnumMap<DependencyId, DependencyStatus>(statuses);
                    }
                    if ("toString".equals(method.getName())) {
                        return "all-present dependency status provider";
                    }
                    if ("hashCode".equals(method.getName())) {
                        return Integer.valueOf(System.identityHashCode(proxyObject));
                    }
                    if ("equals".equals(method.getName())) {
                        return Boolean.valueOf(proxyObject == args[0]);
                    }
                    return null;
                });
        Constructor<DependencyService> ctor = DependencyService.class.getDeclaredConstructor(providerType);
        ctor.setAccessible(true);
        FeatureDependencyGate.configure(ctor.newInstance(provider), null);
        FeatureDependencyGate.setUiMode(false);
    }
}
