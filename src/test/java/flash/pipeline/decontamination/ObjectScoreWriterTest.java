package flash.pipeline.decontamination;

import ij.ImagePlus;
import ij.ImageStack;
import ij.process.ShortProcessor;
import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class ObjectScoreWriterTest {

    @Test
    public void locatesObjectMapsFrom3DObjectCsvAndWritesPerObjectScores() throws Exception {
        File directory = Files.createTempDirectory("spectral-object-writer").toFile();
        flash.pipeline.io.FlashProjectLayout layout =
                flash.pipeline.io.FlashProjectLayout.forDirectory(directory.getAbsolutePath());
        File objectsDir = layout.tablesObjectsWriteDir();
        assertTrue(objectsDir.mkdirs());
        File imageDir = new File(layout.analysisImagesObjectsMasksDir(), "AnimalA");
        assertTrue(imageDir.mkdirs());

        File objectCsv = new File(objectsDir, "Target.csv");
        Files.write(objectCsv.toPath(), Arrays.asList(
                "Region,Hemisphere,ROI,Animal Name,SCN,Label",
                "SCN,LH,SCN1,AnimalA,1,7"), StandardCharsets.UTF_8);

        File objectMap = new File(imageDir, "Target_objects_LH_SCN1.tif");
        assertTrue(objectMap.createNewFile());

        List<ObjectScoreWriter.ObjectMapDescriptor> maps =
                ObjectScoreWriter.locateObjectLabelMaps(
                        directory.getAbsolutePath(),
                        0,
                        "Experiment - AnimalA_LH_SCN",
                        "Target");
        assertEquals(1, maps.size());
        assertEquals(objectMap.getAbsolutePath(), maps.get(0).getFile().getAbsolutePath());

        ImagePlus labels = labelImage(2, 1, new int[]{7, 7});
        ImagePlus source = multiChannelImage(2, 1,
                new int[]{100, 120},
                new int[]{10, 10});
        SpectralDecontaminationConfig config = new SpectralDecontaminationConfig();
        config.setTargetChannelIndex(0);
        config.setBleedThroughChannelIndexes(Arrays.asList(Integer.valueOf(1)));

        ObjectDecontaminationScorer.ScoreResult scoreResult =
                ObjectDecontaminationScorer.score(
                        labels,
                        source,
                        config,
                        null,
                        new ObjectDecontaminationScorer.Settings());

        SpectralOutputWriter.ExpectedOutputs expectedOutputs =
                SpectralOutputWriter.expectedOutputs(
                        directory.getAbsolutePath(),
                        0,
                        "Experiment - AnimalA_LH_SCN",
                        "Target");
        File cleanedMapFile = ObjectScoreWriter.cleanedObjectMapFile(
                expectedOutputs,
                "Target",
                maps.get(0));
        ObjectScoreWriter.saveCleanedObjectMap(scoreResult.getCleanedObjectMap(), cleanedMapFile);

        List<Map<String, String>> rows = ObjectScoreWriter.buildRows(
                directory.getAbsolutePath(),
                0,
                "Experiment - AnimalA_LH_SCN",
                "Control",
                "control",
                runMetadata(),
                "Target",
                maps.get(0),
                scoreResult,
                cleanedMapFile,
                "processed");
        ObjectScoreWriter.writePerObjectScores(directory.getAbsolutePath(), rows);

        String csv = new String(Files.readAllBytes(
                ObjectScoreWriter.perObjectScoresFile(directory.getAbsolutePath()).toPath()),
                StandardCharsets.UTF_8);
        assertTrue(ObjectScoreWriter.perObjectScoresFile(directory.getAbsolutePath())
                .getAbsolutePath()
                .contains(new File("FLASH/Spectral Decontamination").getPath()));
        assertTrue(cleanedMapFile.getAbsolutePath()
                .contains(new File("FLASH/Spectral Decontamination/Image Outputs").getPath()));
        assertTrue(csv.contains("ObjectID"));
        assertTrue(csv.contains("SCN1"));
        assertTrue(csv.contains("7"));
        assertTrue(csv.contains("TargetMean"));
        assertTrue(csv.contains("ConfigId"));
        assertTrue(csv.contains(runMetadata().configId));
        assertTrue(csv.contains("CleanedObjectMapPath"));
        assertTrue(ObjectScoreWriter.objectRowsReusable(directory.getAbsolutePath(), rows));
    }

    @Test
    public void locatesObjectMapsFromFlashObjectLayout() throws Exception {
        File directory = Files.createTempDirectory("spectral-object-flash-reader").toFile();
        flash.pipeline.io.FlashProjectLayout layout =
                flash.pipeline.io.FlashProjectLayout.forDirectory(directory.getAbsolutePath());
        File objectsDir = layout.tablesObjectsWriteDir();
        assertTrue(objectsDir.mkdirs());
        File imageDir = new File(layout.analysisImagesObjectsMasksDir(), "AnimalA");
        assertTrue(imageDir.mkdirs());

        File objectCsv = new File(objectsDir, "Target.csv");
        Files.write(objectCsv.toPath(), Arrays.asList(
                "Region,Hemisphere,ROI,Animal Name,SCN,Label",
                "SCN,LH,SCN1,AnimalA,1,7"), StandardCharsets.UTF_8);

        File objectMap = new File(imageDir, "Target_objects_LH_SCN1.tif");
        assertTrue(objectMap.createNewFile());

        List<ObjectScoreWriter.ObjectMapDescriptor> maps =
                ObjectScoreWriter.locateObjectLabelMaps(
                        directory.getAbsolutePath(),
                        0,
                        "Experiment - AnimalA_LH_SCN",
                        "Target");

        assertEquals(1, maps.size());
        assertEquals(objectMap.getAbsolutePath(), maps.get(0).getFile().getAbsolutePath());
        assertEquals(objectCsv.getAbsolutePath(), maps.get(0).getSourceObjectCsvFile().getAbsolutePath());
    }

    private static SpectralOutputWriter.RunMetadata runMetadata() {
        SpectralDecontaminationConfig config = new SpectralDecontaminationConfig();
        config.setTargetChannelIndex(0);
        CorrectionPipeline pipeline = new CorrectionPipeline();
        pipeline.setPresetId(CorrectionFeatureRegistry.PRESET_BASIC);
        pipeline.setFeatureIds(Arrays.asList("linear_unmixing", "size_filter"));
        config.setCorrectionPipeline(pipeline);
        return SpectralOutputWriter.RunMetadata.fromConfig(config, CorrectionFeatureRegistry.getDefault());
    }

    private static ImagePlus multiChannelImage(int width, int height, int[]... channels) {
        ImageStack stack = new ImageStack(width, height);
        for (int[] channel : channels) {
            stack.addSlice(new ShortProcessor(width, height, toShorts(channel), null));
        }
        ImagePlus image = new ImagePlus("source", stack);
        image.setDimensions(channels.length, 1, 1);
        return image;
    }

    private static ImagePlus labelImage(int width, int height, int[] pixels) {
        ImageStack stack = new ImageStack(width, height);
        stack.addSlice(new ShortProcessor(width, height, toShorts(pixels), null));
        ImagePlus image = new ImagePlus("labels", stack);
        image.setDimensions(1, 1, 1);
        return image;
    }

    private static short[] toShorts(int[] values) {
        short[] out = new short[values.length];
        for (int i = 0; i < values.length; i++) {
            out[i] = (short) values[i];
        }
        return out;
    }
}
