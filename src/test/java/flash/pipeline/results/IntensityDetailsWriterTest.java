package flash.pipeline.results;

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
                "FLASH" + File.separator + "Image Analysis"
                        + File.separator + "Image Intensities"
                        + File.separator + "Analysis Details"));
        String text = new String(Files.readAllBytes(out.toPath()), StandardCharsets.UTF_8);

        assertTrue(text.contains("// Filter source: Astrocyte cleanup (C2_Filters.ijm)"));
        assertTrue(text.contains("<Filter Macro>\n" + macro + "</Filter Macro>"));
        assertTrue(text.contains("setThreshold(125, 65535);"));
        assertTrue(text.contains("<In ROI>\nDAPI\n</In ROI>"));
    }
}
