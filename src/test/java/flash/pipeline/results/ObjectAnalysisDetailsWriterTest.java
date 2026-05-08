package flash.pipeline.results;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class ObjectAnalysisDetailsWriterTest {

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    @Test
    public void writeStarDistPerChannel_includesFilterMacroAndQcParameters() throws Exception {
        File root = tempFolder.newFolder("object-details");
        File analysisDetailsDir = new File(root, "Analysis Details");
        File binDir = new File(root, ".bin");
        assertTrue(binDir.mkdirs());

        File filterFile = new File(binDir, "C2_Filters.ijm");
        Files.write(filterFile.toPath(),
                ("run(\"Median...\", \"radius=3 stack\");\n"
                        + "run(\"Subtract Background...\", \"rolling=20 stack\");\n")
                        .getBytes(StandardCharsets.UTF_8));

        ObjectAnalysisDetailsWriter.writeStarDistPerChannel(
                analysisDetailsDir,
                binDir,
                "GFAP",
                2,
                0.61,
                0.27,
                4.5,
                6.0,
                2,
                50.0,
                5000.0,
                0.15,
                125.0,
                new String[] {"GFAP", "IBA1"},
                true,
                Collections.singletonMap("GFAP", 35.0)
        );

        File out = new File(analysisDetailsDir, "GFAP.txt");
        String text = new String(Files.readAllBytes(out.toPath()), StandardCharsets.UTF_8);

        assertTrue(text.contains("<Filter Macro>"));
        assertTrue(text.contains("selectImage(GFAP_stardist_input);"));
        assertTrue(text.contains("run(\"Median...\", \"radius=3 stack\");"));
        assertTrue(text.contains("<Segmentation Method>\nStarDist 3D\n</Segmentation Method>"));
        assertTrue(text.contains("// QC/Sanity Parameters: probThresh=0.61, nmsThresh=0.27, linking=4.5, gapClosing=6.0, frameGap=2, area=50.0-5000.0, quality>=0.15, intensity>=125.0"));
        assertTrue(text.contains("input=GFAP_stardist_input"));
        assertTrue(text.contains("run(\"3D MultiColoc\", \"image_a=GFAP_objects image_b=IBA1_objects);"));
    }

    @Test
    public void analysisDetailsWriteDir_isInsideFlashObjectObjectsFolder() throws Exception {
        File root = tempFolder.newFolder("object-details-layout");

        assertEquals(
                new File(root, "FLASH/Image Analysis/3D Objects/Objects/Analysis Details").getAbsolutePath(),
                ObjectAnalysisDetailsWriter.analysisDetailsWriteDir(root).getAbsolutePath());
    }

    @Test
    public void writeStarDistPerChannel_fallsBackToBundledDefaultFilterWhenMissing() throws Exception {
        File root = tempFolder.newFolder("object-details-default");
        File analysisDetailsDir = new File(root, "Analysis Details");
        File binDir = new File(root, ".bin");
        assertTrue(binDir.mkdirs());

        ObjectAnalysisDetailsWriter.writeStarDistPerChannel(
                analysisDetailsDir,
                binDir,
                "DAPI",
                1,
                0.5,
                0.4,
                5.0,
                5.0,
                1,
                0.0,
                Double.POSITIVE_INFINITY,
                0.0,
                0.0,
                new String[] {"DAPI"},
                false,
                null
        );

        File out = new File(analysisDetailsDir, "DAPI.txt");
        String text = new String(Files.readAllBytes(out.toPath()), StandardCharsets.UTF_8);

        assertTrue(text.contains("// === STANDARD CLEANUP ==="));
        assertTrue(text.contains("run(\"Gaussian Blur...\", \"sigma=2 stack\");"));
    }

    @Test
    public void writeCellposePerChannel_includesFilterMacroAndCellposeParameters() throws Exception {
        File root = tempFolder.newFolder("object-details-cellpose");
        File analysisDetailsDir = new File(root, "Analysis Details");
        File binDir = new File(root, ".bin");
        assertTrue(binDir.mkdirs());

        File filterFile = new File(binDir, "C1_Filters.ijm");
        Files.write(filterFile.toPath(),
                ("run(\"Median...\", \"radius=2 stack\");\n"
                        + "run(\"Subtract Background...\", \"rolling=30 stack\");\n")
                        .getBytes(StandardCharsets.UTF_8));

        ObjectAnalysisDetailsWriter.writeCellposePerChannel(
                analysisDetailsDir,
                binDir,
                "IBA1",
                1,
                "cyto3",
                22.5,
                0.3,
                -1.0,
                false,
                "DAPI",
                new String[] {"IBA1", "DAPI"},
                true,
                Collections.singletonMap("IBA1", 40.0)
        );

        File out = new File(analysisDetailsDir, "IBA1.txt");
        String text = new String(Files.readAllBytes(out.toPath()), StandardCharsets.UTF_8);

        assertTrue(text.contains("<Filter Macro>"));
        assertTrue(text.contains("selectImage(IBA1_cellpose_input);"));
        assertTrue(text.contains("run(\"Median...\", \"radius=2 stack\");"));
        assertTrue(text.contains("<Segmentation Method>\nCellpose\n</Segmentation Method>"));
        assertTrue(text.contains("// Model: cyto3"));
        assertTrue(text.contains("diameter=22.5"));
        assertTrue(text.contains("flowThreshold=0.3"));
        assertTrue(text.contains("cellprobThreshold=-1.0"));
        assertTrue(text.contains("useGpu=false"));
        assertTrue(text.contains("companionChannel=DAPI"));
        assertTrue(text.contains("Companion channel: DAPI"));
        assertTrue(text.contains("python -m cellpose --image_path <stack.tif> --savedir <output_dir> --pretrained_model cyto3"));
        assertTrue(text.contains("--chan 1 --chan2 2 --channel_axis <derived>"));
        assertTrue(text.contains("--do_3D --z_axis 0 --save_tif"));
        assertTrue(text.contains("run(\"3D MultiColoc\", \"image_a=IBA1_objects image_b=DAPI_objects);"));
    }
}
