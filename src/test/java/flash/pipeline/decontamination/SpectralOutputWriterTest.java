package flash.pipeline.decontamination;

import ij.ImagePlus;
import ij.ImageStack;
import ij.process.ByteProcessor;
import ij.process.FloatProcessor;
import ij.process.ShortProcessor;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SpectralOutputWriterTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void writesExpectedBatchFilesAndRows() throws Exception {
        File directory = temp.newFolder("spectral-output-writer");
        SpectralOutputWriter.RunMetadata runMetadata = runMetadata();
        SpectralOutputWriter.ExpectedOutputs outputs =
                SpectralOutputWriter.expectedOutputs(directory.getAbsolutePath(), 0, "Mouse1_LH_SCN", "Target/A");

        ImagePlus correctedImage = shortImage(new int[]{10, 20, 30, 40});
        ImagePlus maskImage = maskImage(new int[]{0, 255, 255, 0});

        SpectralOutputWriter.saveCorrectedImage(correctedImage, outputs.correctedImageFile);
        SpectralOutputWriter.saveMaskImage(maskImage, outputs.maskImageFile);

        List<CorrectionPipeline.FeatureSummary> featureSummaries =
                new ArrayList<CorrectionPipeline.FeatureSummary>();
        featureSummaries.add(new CorrectionPipeline.FeatureSummary("linear_unmixing", "Linear unmixing")
                .putDouble("weight_channel_2", 0.5)
                .putInt("fit_pixel_count", 4));

        List<Map<String, String>> summaryRows = new ArrayList<Map<String, String>>();
        summaryRows.add(SpectralOutputWriter.buildPerImageSummaryRow(
                0,
                "Mouse1_LH_SCN",
                outputs.imageOutputRelativePath,
                "Control",
                "control",
                "Create cleaned mask",
                runMetadata,
                "Basic",
                "Linear unmixing -> Threshold corrected target -> Size filter",
                "processed",
                outputs.correctedImageFile,
                outputs.maskImageFile,
                "2-4",
                featureSummaries,
                "",
                directory.getAbsolutePath()));

        List<Map<String, String>> coefficientRows =
                SpectralOutputWriter.buildCoefficientRows(
                        0,
                        "Mouse1_LH_SCN",
                        "Control",
                        "control",
                        runMetadata,
                        "processed",
                        featureSummaries);

        SpectralOutputWriter.writePerImageSummary(directory.getAbsolutePath(), summaryRows);
        SpectralOutputWriter.writeCorrectionCoefficients(directory.getAbsolutePath(), coefficientRows);

        SpectralOutputWriter.AnalysisDetails details = new SpectralOutputWriter.AnalysisDetails();
        details.goalLabel = "Create cleaned mask";
        details.configVersion = runMetadata.configVersion;
        details.configId = runMetadata.configId;
        details.pipelinePresetId = runMetadata.pipelinePresetId;
        details.pipelineStackId = runMetadata.pipelineStackId;
        details.targetChannelName = "1 - Target/A";
        details.bleedThroughChannels = "2 - Bleed";
        details.autofluorescenceChannels = "none";
        details.excludedChannels = "none";
        details.conditionSourceLabel = "Infer from image names";
        details.controlConditions = "Control";
        details.experimentalConditions = "Treatment";
        details.pipelinePresetLabel = "Basic";
        details.pipelineDescription = "Linear unmixing -> Threshold corrected target -> Size filter";
        details.zSliceSummary = "Per image subset";
        details.skipExisting = false;
        details.parallelThreads = 2;
        details.totalImages = 1;
        details.processedImages = 1;
        details.skippedImages = 0;
        details.failedImages = 0;
        details.correctedImagesWritten = 1;
        details.maskImagesWritten = 1;
        details.perImageSummaryPath = "FLASH/Results/Tables/Spectral Decontamination/per_image_summary.csv";
        details.correctionCoefficientsPath =
                "FLASH/Results/Tables/Spectral Decontamination/correction_coefficients.csv";
        details.previewSelectionPath =
                "FLASH/Results/Tables/Spectral Decontamination/preview_selection.csv";
        details.runtimeMs = 1500L;

        SpectralOutputWriter.writeAnalysisDetails(directory.getAbsolutePath(), details);

        assertTrue(outputs.correctedImageFile.isFile());
        assertTrue(outputs.maskImageFile.isFile());
        assertEquals(new File(directory,
                        "FLASH/Results/Analysis Images/Spectral Decontamination/Series 001 - Mouse1_LH_SCN")
                        .getAbsolutePath(),
                outputs.imageOutputDirectory.getAbsolutePath());
        assertTrue(SpectralOutputWriter.perImageSummaryFile(directory.getAbsolutePath()).isFile());
        assertTrue(SpectralOutputWriter.correctionCoefficientsFile(directory.getAbsolutePath()).isFile());
        assertTrue(SpectralOutputWriter.analysisDetailsFile(directory.getAbsolutePath()).isFile());

        String perImageSummary = readFile(SpectralOutputWriter.perImageSummaryFile(directory.getAbsolutePath()));
        assertTrue(perImageSummary.contains("ConfigId"));
        assertTrue(perImageSummary.contains(runMetadata.configId));
        assertTrue(perImageSummary.contains("PipelineStackId"));
        assertTrue(perImageSummary.contains("RunAction"));
        assertTrue(perImageSummary.contains("processed"));
        assertTrue(perImageSummary.contains("corrected_Target%2FA.tif"));
        assertTrue(perImageSummary.contains("final_mask_Target%2FA.tif"));

        String correctionCoefficients =
                readFile(SpectralOutputWriter.correctionCoefficientsFile(directory.getAbsolutePath()));
        assertTrue(correctionCoefficients.contains(runMetadata.pipelineStackId));
        assertTrue(correctionCoefficients.contains("linear_unmixing"));
        assertTrue(correctionCoefficients.contains("weight_channel_2"));
        assertTrue(correctionCoefficients.contains("0.500000"));

        String analysisDetails = readFile(SpectralOutputWriter.analysisDetailsFile(directory.getAbsolutePath()));
        assertTrue(analysisDetails.contains("Spectral Decontamination - Analysis Details"));
        assertTrue(analysisDetails.contains(runMetadata.configId));
        assertTrue(analysisDetails.contains("Create cleaned mask"));
        assertTrue(analysisDetails.contains("Linear unmixing -> Threshold corrected target -> Size filter"));
    }

    @Test
    public void reloadsAndRewritesRowsForSkipExistingRuns() throws Exception {
        File directory = temp.newFolder("spectral-skip-existing");
        SpectralOutputWriter.RunMetadata runMetadata = runMetadata();

        List<Map<String, String>> summaryRows = new ArrayList<Map<String, String>>();
        summaryRows.add(SpectralOutputWriter.buildPerImageSummaryRow(
                0,
                "Mouse1_LH_SCN",
                "FLASH/Results/Analysis Images/Spectral Decontamination/Series 001 - Mouse1_LH_SCN",
                "Control",
                "control",
                "Create cleaned image",
                runMetadata,
                "Basic",
                "Linear unmixing",
                "processed",
                new File(directory, "FLASH/Results/Analysis Images/Spectral Decontamination/Series 001 - Mouse1_LH_SCN/corrected_Target.tif"),
                null,
                "Full stack",
                new ArrayList<CorrectionPipeline.FeatureSummary>(),
                "",
                directory.getAbsolutePath()));

        List<CorrectionPipeline.FeatureSummary> featureSummaries =
                new ArrayList<CorrectionPipeline.FeatureSummary>();
        featureSummaries.add(new CorrectionPipeline.FeatureSummary("linear_unmixing", "Linear unmixing")
                .putDouble("weight_channel_2", 0.25));
        List<Map<String, String>> coefficientRows =
                SpectralOutputWriter.buildCoefficientRows(
                        0,
                        "Mouse1_LH_SCN",
                        "Control",
                        "control",
                        runMetadata,
                        "processed",
                        featureSummaries);

        SpectralOutputWriter.writePerImageSummary(directory.getAbsolutePath(), summaryRows);
        SpectralOutputWriter.writeCorrectionCoefficients(directory.getAbsolutePath(), coefficientRows);

        Map<Integer, Map<String, String>> existingSummaryRows =
                SpectralOutputWriter.readPerImageSummaryRows(directory.getAbsolutePath());
        Map<Integer, List<Map<String, String>>> existingCoefficientRows =
                SpectralOutputWriter.readCoefficientRows(directory.getAbsolutePath());

        assertTrue(existingSummaryRows.containsKey(Integer.valueOf(0)));
        assertTrue(existingCoefficientRows.containsKey(Integer.valueOf(0)));
        assertEquals(1, existingCoefficientRows.get(Integer.valueOf(0)).size());

        List<Map<String, String>> skippedSummaryRows = new ArrayList<Map<String, String>>();
        skippedSummaryRows.add(SpectralOutputWriter.copySummaryRow(
                existingSummaryRows.get(Integer.valueOf(0)),
                "skipped_existing",
                "Output files already existed."));
        List<Map<String, String>> skippedCoefficientRows =
                SpectralOutputWriter.copyCoefficientRows(
                        existingCoefficientRows.get(Integer.valueOf(0)),
                        "skipped_existing");

        SpectralOutputWriter.writePerImageSummary(directory.getAbsolutePath(), skippedSummaryRows);
        SpectralOutputWriter.writeCorrectionCoefficients(directory.getAbsolutePath(), skippedCoefficientRows);

        String perImageSummary = readFile(SpectralOutputWriter.perImageSummaryFile(directory.getAbsolutePath()));
        String correctionCoefficients =
                readFile(SpectralOutputWriter.correctionCoefficientsFile(directory.getAbsolutePath()));

        assertTrue(perImageSummary.contains("skipped_existing"));
        assertTrue(perImageSummary.contains(runMetadata.configId));
        assertTrue(perImageSummary.contains("Output files already existed."));
        assertTrue(correctionCoefficients.contains("skipped_existing"));
        assertTrue(correctionCoefficients.contains(runMetadata.pipelineStackId));
        assertTrue(correctionCoefficients.contains("weight_channel_2"));
    }

    @Test
    public void writesOptionalParameterMapOutputs() throws Exception {
        File directory = temp.newFolder("spectral-parameter-map");
        SpectralOutputWriter.ExpectedOutputs outputs =
                SpectralOutputWriter.expectedOutputs(directory.getAbsolutePath(), 0, "Mouse1_LH_SCN", "Target");

        File parameterMapFile = SpectralOutputWriter.parameterMapFile(outputs, "local_k_coefficient_channel_2");
        SpectralOutputWriter.saveParameterMap(floatImage(new float[]{0.25f, 0.50f, 0.75f, 1.00f}), parameterMapFile);

        assertTrue(parameterMapFile.isFile());
        assertTrue(parameterMapFile.getName().contains("local_k_coefficient_channel_2"));
        assertTrue(parameterMapFile.getAbsolutePath().contains(
                new File("FLASH/Results/Analysis Images/Spectral Decontamination").getPath()));
    }

    @Test
    public void runMetadataIsStableForEquivalentConfig() {
        SpectralDecontaminationConfig config = config();
        SpectralOutputWriter.RunMetadata first =
                SpectralOutputWriter.RunMetadata.fromConfig(config, CorrectionFeatureRegistry.getDefault());
        SpectralOutputWriter.RunMetadata second =
                SpectralOutputWriter.RunMetadata.fromConfig(config.copy(), CorrectionFeatureRegistry.getDefault());

        assertEquals(first.configId, second.configId);
        assertEquals("linear_unmixing>threshold_corrected_target>size_filter", first.pipelineStackId);
        assertFalse(first.configId.isEmpty());
    }

    private static SpectralOutputWriter.RunMetadata runMetadata() {
        return SpectralOutputWriter.RunMetadata.fromConfig(config(), CorrectionFeatureRegistry.getDefault());
    }

    private static SpectralDecontaminationConfig config() {
        SpectralDecontaminationConfig config = new SpectralDecontaminationConfig();
        config.setVersion(4);
        config.setTargetChannelIndex(0);
        config.setBleedThroughChannelIndexes(Arrays.asList(Integer.valueOf(1)));
        CorrectionPipeline pipeline = new CorrectionPipeline();
        pipeline.setPresetId(CorrectionFeatureRegistry.PRESET_BASIC);
        pipeline.setFeatureIds(Arrays.asList(
                "linear_unmixing",
                "threshold_corrected_target",
                "size_filter"));
        config.setCorrectionPipeline(pipeline);
        return config;
    }

    private static ImagePlus shortImage(int[] pixels) {
        ImageStack stack = new ImageStack(2, 2);
        stack.addSlice(new ShortProcessor(2, 2, toShorts(pixels), null));
        ImagePlus image = new ImagePlus("corrected", stack);
        image.setDimensions(1, 1, 1);
        return image;
    }

    private static ImagePlus maskImage(int[] pixels) {
        ImageStack stack = new ImageStack(2, 2);
        stack.addSlice(new ByteProcessor(2, 2, toBytes(pixels), null));
        ImagePlus image = new ImagePlus("mask", stack);
        image.setDimensions(1, 1, 1);
        return image;
    }

    private static ImagePlus floatImage(float[] pixels) {
        ImageStack stack = new ImageStack(2, 2);
        stack.addSlice(new FloatProcessor(2, 2, pixels, null));
        ImagePlus image = new ImagePlus("parameter_map", stack);
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

    private static byte[] toBytes(int[] values) {
        byte[] out = new byte[values.length];
        for (int i = 0; i < values.length; i++) {
            out[i] = (byte) values[i];
        }
        return out;
    }

    private static String readFile(File file) throws Exception {
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }
}
