package flash.pipeline.results;

import flash.pipeline.analyses.wizard.IntensitySpatialConfig;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertTrue;

public class IntensityDetailsWriterTest {

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    @Test
    public void writePerChannelRecordsFilterSourceAndActualMacro() throws Exception {
        File root = tempFolder.newFolder("intensity-details");
        File analysisDetailsDir = IntensityDetailsWriter.analysisDetailsWriteDir(root);
        File binDir = new File(root, ".bin");
        assertTrue(binDir.mkdirs());

        String macro = "run(\"Median...\", \"radius=4 stack\");\n";

        IntensityDetailsWriter.writePerChannel(
                analysisDetailsDir,
                binDir,
                "GFAP",
                2,
                true,
                "Astrocyte cleanup (C2_Filters.ijm)",
                macro,
                true,
                "125",
                "DAPI"
        );

        File out = new File(analysisDetailsDir, "GFAP.txt");
        assertTrue(out.getAbsolutePath().contains(
                "FLASH" + File.separator + "Results"
                        + File.separator + "Run Records"
                        + File.separator + "analysis_details"));
        String text = new String(Files.readAllBytes(out.toPath()), StandardCharsets.UTF_8);

        assertTrue(text.contains("// Filter source: Astrocyte cleanup (C2_Filters.ijm)"));
        assertTrue(text.contains("<Filter Macro>\n" + macro + "</Filter Macro>"));
        assertTrue(text.contains("setThreshold(125, 65535);"));
        assertTrue(text.contains("<In ROI>\nDAPI\n</In ROI>"));
        File[] leftovers = analysisDetailsDir.listFiles((dir, name) -> name.endsWith(".tmp"));
        assertTrue(leftovers == null || leftovers.length == 0);
    }

    @Test
    public void writePerChannelRecordsSpatialDetails() throws Exception {
        File root = tempFolder.newFolder("intensity-spatial-details");
        File analysisDetailsDir = IntensityDetailsWriter.analysisDetailsWriteDir(root);
        File binDir = new File(root, ".bin");
        assertTrue(binDir.mkdirs());

        IntensitySpatialConfig spatialConfig = IntensitySpatialConfig.builder()
                .enabled(true)
                .addAnalysis(IntensitySpatialConfig.AnalysisKey.PATCHINESS)
                .addAnalysis(IntensitySpatialConfig.AnalysisKey.CROSSMARK)
                .mipEnabled(true)
                .native3dEnabled(true)
                .overlaysEnabled(true)
                .build();

        IntensityDetailsWriter.writePerChannel(
                analysisDetailsDir,
                binDir,
                "DAPI",
                1,
                true,
                "Basic background and noise removal",
                "run(\"Median...\", \"radius=2 stack\");\n",
                true,
                "100",
                "GFAP",
                spatialConfig,
                "Full stack",
                "FLASH/Results/Analysis Images/Intensity Overlays",
                "Coloc 2 checked at run time",
                "No partial failures"
        );

        File out = new File(analysisDetailsDir, "DAPI.txt");
        String text = new String(Files.readAllBytes(out.toPath()), StandardCharsets.UTF_8);

        assertTrue(text.contains("<Intensity Spatial Analysis>"));
        assertTrue(text.contains("Selected analyses: patchiness,crossmark"));
        assertTrue(text.contains("MIP output: true"));
        assertTrue(text.contains("Native 3D output: true"));
        assertTrue(text.contains("Overlays: true"));
        assertTrue(text.contains("Dependency gates: Coloc 2 checked at run time"));
        assertTrue(text.contains("Partial failures: No partial failures"));
        assertTrue(text.contains("Partner mask usage: GFAP"));
    }
}
