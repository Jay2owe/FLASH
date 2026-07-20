package flash.pipeline.decontamination;

import ij.ImagePlus;
import ij.ImageStack;
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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class SpectralPreviewSelectorTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void selectPreviewImages_includesControlsAndExperimentalsWhenAvailable() {
        List<SpectralPreviewSelector.PreviewSelection> selections = selectDefault(scoredImages());

        assertTrue(hasConditionRole(selections, "control"));
        assertTrue(hasConditionRole(selections, "experimental"));
    }

    @Test
    public void selectPreviewImages_includesTargetBrightControl() {
        List<SpectralPreviewSelector.PreviewSelection> selections = selectDefault(scoredImages());

        SpectralPreviewSelector.PreviewSelection selected = bySeries(selections, 1);
        assertTrue(selected.selectionRole.contains("target_bright_control"));
    }

    @Test
    public void selectPreviewImages_includesAutofluorescenceBrightExperimental() {
        List<SpectralPreviewSelector.PreviewSelection> selections = selectDefault(scoredImages());

        SpectralPreviewSelector.PreviewSelection selected = bySeries(selections, 4);
        assertTrue(selected.selectionRole.contains("autofluorescence_bright_experimental"));
    }

    @Test
    public void selectPreviewImages_isDeterministicForSameInputs() {
        List<SpectralPreviewSelector.ScoredImage> scored = scoredImages();

        List<SpectralPreviewSelector.PreviewSelection> first = selectDefault(scored);
        List<SpectralPreviewSelector.PreviewSelection> second = selectDefault(scored);

        assertEquals(signature(first), signature(second));
    }

    @Test
    public void writePreviewSelection_writesCsvWithQuotedNames() throws Exception {
        File out = new File(temp.newFolder("project"), "preview_selection.csv");
        List<SpectralPreviewSelector.PreviewSelection> selections =
                new ArrayList<SpectralPreviewSelector.PreviewSelection>();
        selections.add(new SpectralPreviewSelector.PreviewSelection(
                new SpectralPreviewSelector.PreviewCandidate(
                        2, "Series, \"Quoted\"", "Mouse A", "Control"),
                new SpectralPreviewSelector.ImageScores(10, 20, 30, 0.125, -1),
                "control",
                "typical"));

        SpectralPreviewSelector.writePreviewSelection(out, runMetadata(), selections);

        String csv = new String(Files.readAllBytes(out.toPath()), StandardCharsets.UTF_8);
        assertTrue(csv.startsWith("SeriesIndex,SeriesNumber,SeriesName"));
        assertTrue(csv.contains("\"Series, \"\"Quoted\"\"\""));
        assertTrue(csv.contains("ConfigId"));
        assertTrue(csv.contains(runMetadata().configId));
        assertTrue(csv.contains(",0.125000,"));
    }

    @Test
    public void previewSelectionFile_usesResultsTablesSpectralFolder() throws Exception {
        File project = temp.newFolder("preview-path-project");

        assertEquals(new File(project,
                        "FLASH/Results/Tables/Spectral Decontamination/preview_selection.csv")
                        .getAbsolutePath(),
                SpectralPreviewSelector.previewSelectionFile(project.getAbsolutePath()).getAbsolutePath());
    }

    @Test
    public void scoreImage_computesPercentilesAndSaturationFraction() {
        ImageStack stack = new ImageStack(2, 2);
        stack.addSlice(new ShortProcessor(2, 2,
                new short[]{1, 2, 3, (short) 0xffff}, null));
        stack.addSlice(new ShortProcessor(2, 2,
                new short[]{5, 6, 7, 8}, null));
        ImagePlus image = new ImagePlus("two-channel", stack);
        image.setDimensions(2, 1, 1);

        SpectralDecontaminationConfig config = new SpectralDecontaminationConfig();
        config.setTargetChannelIndex(0);
        config.setAutofluorescenceChannelIndexes(Arrays.asList(Integer.valueOf(1)));

        SpectralPreviewSelector.ImageScores scores =
                SpectralPreviewSelector.scoreImage(image, config);

        assertEquals(65535.0, scores.targetP99, 0.0001);
        assertEquals(8.0, scores.autofluorescenceP99, 0.0001);
        assertEquals(0.125, scores.saturatedFraction, 0.0001);
    }

    private static List<SpectralPreviewSelector.PreviewSelection> selectDefault(
            List<SpectralPreviewSelector.ScoredImage> scored) {
        return SpectralPreviewSelector.selectPreviewImages(
                scored,
                Arrays.asList("Control"),
                Arrays.asList("Treatment"),
                true);
    }

    private static List<SpectralPreviewSelector.ScoredImage> scoredImages() {
        List<SpectralPreviewSelector.ScoredImage> images =
                new ArrayList<SpectralPreviewSelector.ScoredImage>();
        images.add(scored(0, "Control", 10, 4, 1));
        images.add(scored(1, "Control", 100, 5, 2));
        images.add(scored(2, "Control", 20, 6, 9));
        images.add(scored(3, "Treatment", 30, 15, 2));
        images.add(scored(4, "Treatment", 40, 200, 3));
        images.add(scored(5, "Treatment", 35, 20, 30));
        return images;
    }

    private static SpectralPreviewSelector.ScoredImage scored(int seriesIndex,
                                                             String condition,
                                                             double target,
                                                             double autofluorescence,
                                                             double bleedThrough) {
        return new SpectralPreviewSelector.ScoredImage(
                new SpectralPreviewSelector.PreviewCandidate(
                        seriesIndex,
                        "Series " + (seriesIndex + 1),
                        "Animal " + (seriesIndex + 1),
                        condition),
                new SpectralPreviewSelector.ImageScores(
                        target, autofluorescence, bleedThrough, 0.0, -1));
    }

    private static boolean hasConditionRole(List<SpectralPreviewSelector.PreviewSelection> selections,
                                            String role) {
        for (SpectralPreviewSelector.PreviewSelection selection : selections) {
            if (role.equals(selection.conditionRole)) return true;
        }
        return false;
    }

    private static SpectralPreviewSelector.PreviewSelection bySeries(
            List<SpectralPreviewSelector.PreviewSelection> selections,
            int seriesIndex) {
        for (SpectralPreviewSelector.PreviewSelection selection : selections) {
            if (selection.candidate.seriesIndex == seriesIndex) return selection;
        }
        fail("Series " + seriesIndex + " was not selected");
        return null;
    }

    private static String signature(List<SpectralPreviewSelector.PreviewSelection> selections) {
        StringBuilder sb = new StringBuilder();
        for (SpectralPreviewSelector.PreviewSelection selection : selections) {
            if (sb.length() > 0) sb.append("|");
            sb.append(selection.candidate.seriesIndex)
                    .append(":")
                    .append(selection.conditionRole)
                    .append(":")
                    .append(selection.selectionRole);
        }
        return sb.toString();
    }

    private static SpectralOutputWriter.RunMetadata runMetadata() {
        SpectralDecontaminationConfig config = new SpectralDecontaminationConfig();
        config.setTargetChannelIndex(0);
        CorrectionPipeline pipeline = new CorrectionPipeline();
        pipeline.setPresetId(CorrectionFeatureRegistry.PRESET_ROC_THRESHOLD);
        pipeline.setFeatureIds(Arrays.asList("linear_unmixing", "roc_threshold_search", "size_filter"));
        config.setCorrectionPipeline(pipeline);
        return SpectralOutputWriter.RunMetadata.fromConfig(config, CorrectionFeatureRegistry.getDefault());
    }
}
