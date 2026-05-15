package flash.pipeline.stardist;

import flash.pipeline.segmentation.SegmentationMethod;
import flash.pipeline.segmentation.SegmentationTokenParser;
import flash.pipeline.segmentation.catalog.ModelCatalog;
import flash.pipeline.segmentation.catalog.ModelCatalogIO;
import flash.pipeline.segmentation.catalog.ModelEntry;
import fiji.plugin.trackmate.Settings;
import fiji.plugin.trackmate.detection.DetectorKeys;
import fiji.plugin.trackmate.stardist.StarDistCustomDetectorFactory;
import ij.ImagePlus;
import ij.process.ByteProcessor;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class StarDist3DRunnerCustomFactoryTest {
    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @After
    public void resetWarningSink() {
        StarDist3DRunner.setWarningSinkForTest(null);
    }

    @Test
    public void usesCustomFactoryWithStockModel() throws Exception {
        File root = temp.newFolder("stock-root");
        SegmentationMethod method = SegmentationTokenParser.parse(
                "stardist:0.73:0.21:model=stardist_versatile_fluo");

        File modelFile = StarDist3DRunner.resolveStarDistModelFile(
                method, root, image(), "DAPI");
        Settings settings = new Settings(image());

        StarDist3DRunner.configureStarDistDetector(settings, 0.73, 0.21, modelFile);

        assertTrue(settings.detectorFactory instanceof StarDistCustomDetectorFactory);
        assertEquals(Integer.valueOf(1),
                settings.detectorSettings.get(DetectorKeys.KEY_TARGET_CHANNEL));
        assertEquals(modelFile.getAbsolutePath(),
                settings.detectorSettings.get(StarDistCustomDetectorFactory.KEY_MODEL_FILEPATH));
        assertEquals(0.73, ((Double) settings.detectorSettings.get(
                StarDistCustomDetectorFactory.KEY_SCORE_THRESHOLD)).doubleValue(), 0.0);
        assertEquals(0.21, ((Double) settings.detectorSettings.get(
                StarDistCustomDetectorFactory.KEY_OVERLAP_THRESHOLD)).doubleValue(), 0.0);
    }

    @Test
    public void usesCustomFactoryWithUserImportedModel() throws Exception {
        Path root = temp.newFolder("user-root").toPath();
        Path source = temp.newFile("TF_SavedModel.zip").toPath();
        Files.write(source, "user-model".getBytes(StandardCharsets.UTF_8));
        ModelCatalog catalog = ModelCatalogIO.read(root);
        ModelEntry saved = catalog.add(userStarDist("user_microglia_iba1_v3",
                0.61, 0.22), source);
        ModelCatalogIO.writeProject(root, catalog);
        SegmentationMethod method = SegmentationTokenParser.parse(
                "stardist:0.61:0.22:model=user_microglia_iba1_v3");

        File modelFile = StarDist3DRunner.resolveStarDistModelFile(
                method, root.toFile(), image(), "IBA1");
        Settings settings = new Settings(image());
        StarDist3DRunner.configureStarDistDetector(settings, 0.61, 0.22, modelFile);

        Path expected = root.resolve("Configuration").resolve("Segmentation Models")
                .resolve(saved.filePath.get()).toAbsolutePath().normalize();
        assertEquals(expected.toFile().getAbsolutePath(),
                settings.detectorSettings.get(StarDistCustomDetectorFactory.KEY_MODEL_FILEPATH));
        assertEquals(0.61, ((Double) settings.detectorSettings.get(
                StarDistCustomDetectorFactory.KEY_SCORE_THRESHOLD)).doubleValue(), 0.0);
        assertEquals(0.22, ((Double) settings.detectorSettings.get(
                StarDistCustomDetectorFactory.KEY_OVERLAP_THRESHOLD)).doubleValue(), 0.0);
    }

    @Test
    public void unknownModelKeyLogsWarningAndFallsBackToVersatile() throws Exception {
        final List<String> warnings = new ArrayList<String>();
        StarDist3DRunner.setWarningSinkForTest(new StarDist3DRunner.WarningSink() {
            @Override public void warn(String message) {
                warnings.add(message);
            }
        });
        File root = temp.newFolder("fallback-root");

        File modelFile = StarDist3DRunner.resolveStarDistModelFile(
                "missing_model", root, image(), "DAPI");

        assertTrue(modelFile.isFile());
        assertTrue(modelFile.getName().contains("dsb2018_heavy_augment"));
        assertEquals(1, warnings.size());
        assertTrue(warnings.get(0).contains("missing_model"));
        assertTrue(warnings.get(0).contains("stardist_versatile_fluo"));
    }

    @Test
    public void cachesExtractedStockResource() throws Exception {
        Path root = temp.newFolder("cache-root").toPath();
        Path jar = temp.newFile("stock-resource.jar").toPath();
        String resourcePath = "test_stardist_cache/model.zip";
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            out.putNextEntry(new JarEntry(resourcePath));
            out.write("stock-model".getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }

        ClassLoader oldLoader = Thread.currentThread().getContextClassLoader();
        URLClassLoader loader = new URLClassLoader(new URL[]{jar.toUri().toURL()}, oldLoader);
        Thread.currentThread().setContextClassLoader(loader);
        try {
            ModelEntry stock = new ModelEntry("stardist_cache_test", "Cache Test", null,
                    ModelEntry.Engine.STARDIST, ModelEntry.Source.STOCK_RESOURCE,
                    null, resourcePath, null, null, null,
                    defaults(0.5, 0.3), null, false);
            ModelCatalog catalog = new ModelCatalog(root, Arrays.asList(stock));

            Path first = catalog.resolve(stock);
            FileTime sentinel = FileTime.fromMillis(123456789000L);
            Files.setLastModifiedTime(first, sentinel);
            Path second = catalog.resolve(stock);

            assertEquals(first, second);
            assertEquals(sentinel.toMillis(), Files.getLastModifiedTime(second).toMillis());
            assertEquals("stock-model",
                    new String(Files.readAllBytes(second), StandardCharsets.UTF_8));
        } finally {
            Thread.currentThread().setContextClassLoader(oldLoader);
            loader.close();
        }
    }

    private static ModelEntry userStarDist(String key, double prob, double nms) {
        return new ModelEntry(key, "User StarDist", null,
                ModelEntry.Engine.STARDIST, ModelEntry.Source.USER_IMPORTED,
                null, null, null, null, null,
                defaults(prob, nms), null, false);
    }

    private static Map<String, Object> defaults(double prob, double nms) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("probThresh", Double.valueOf(prob));
        out.put("nmsThresh", Double.valueOf(nms));
        return out;
    }

    private static ImagePlus image() {
        ByteProcessor processor = new ByteProcessor(2, 2);
        processor.set(0, 0, 1);
        return new ImagePlus("input", processor);
    }
}
