package flash.pipeline.results;

import flash.pipeline.segmentation.catalog.ModelEntry;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ObjectAnalysisDetailsModelMetadataTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void starDistDetailsUseResolvedStockAndCustomModelMetadata() throws Exception {
        ModelEntry stock = new ModelEntry(
                "stardist_dsb2018_paper",
                "StarDist - DSB 2018 paper",
                "Stock StarDist model",
                ModelEntry.Engine.STARDIST,
                ModelEntry.Source.STOCK_RESOURCE,
                null,
                "models/2D/dsb2018_paper.zip",
                null,
                "DSB 2018 (from StarDist 2D paper)",
                null,
                null,
                null,
                false);
        String stockText = writeStarDist("DAPI", stock);

        assertTrue(stockText.contains("// Model: StarDist - DSB 2018 paper (key=stardist_dsb2018_paper)"));
        assertTrue(stockText.contains("// Model Source: Fiji stock resource"));
        assertTrue(stockText.contains("Fiji model choice=DSB 2018 (from StarDist 2D paper)"));
        assertTrue(stockText.contains("modelChoice='DSB 2018 (from StarDist 2D paper)'"));
        assertFalse(stockText.contains("modelChoice='Versatile (fluorescent nuclei)'"));

        ModelEntry custom = new ModelEntry(
                "user_dapi_stardist_v1",
                "DAPI custom StarDist v1",
                "Imported StarDist model",
                ModelEntry.Engine.STARDIST,
                ModelEntry.Source.USER_IMPORTED,
                "files/user_dapi_stardist_v1/TF_SavedModel.zip",
                null,
                null,
                null,
                null,
                null,
                null,
                false);
        String customText = writeStarDist("GFAP", custom);

        assertTrue(customText.contains("// Model: DAPI custom StarDist v1 (key=user_dapi_stardist_v1)"));
        assertTrue(customText.contains("// Model Source: Custom import; path=files/user_dapi_stardist_v1/TF_SavedModel.zip"));
        assertTrue(customText.contains("// run(\"StarDist 3D\", \"input=GFAP_stardist_input modelFile='files/user_dapi_stardist_v1/TF_SavedModel.zip'"));
        assertFalse(customText.contains("Versatile (fluorescent nuclei)"));
    }

    @Test
    public void cellposeDetailsUseResolvedStockAndCustomModelMetadata() throws Exception {
        ModelEntry stock = new ModelEntry(
                "cellpose_nuclei",
                "Cellpose - nuclei",
                "Stock Cellpose model",
                ModelEntry.Engine.CELLPOSE,
                ModelEntry.Source.STOCK_BUILTIN,
                null,
                null,
                "nuclei",
                null,
                null,
                null,
                null,
                false);
        String stockText = writeCellpose("DAPI", stock, "cellpose_nuclei");

        assertTrue(stockText.contains("// Model: Cellpose - nuclei (key=cellpose_nuclei)"));
        assertTrue(stockText.contains("// Model Source: Fiji built-in; pretrained name=nuclei"));
        assertTrue(stockText.contains("--pretrained_model nuclei"));
        assertFalse(stockText.contains("// Model: cellpose_nuclei"));

        ModelEntry custom = new ModelEntry(
                "user_astro_cellpose_v1",
                "Astrocyte Cellpose v1",
                "Imported Cellpose model",
                ModelEntry.Engine.CELLPOSE,
                ModelEntry.Source.USER_IMPORTED,
                "files/user_astro_cellpose_v1/model.pt",
                null,
                null,
                null,
                null,
                null,
                null,
                true);
        String customText = writeCellpose("GFAP", custom, "user_astro_cellpose_v1");

        assertTrue(customText.contains("// Model: Astrocyte Cellpose v1 (key=user_astro_cellpose_v1)"));
        assertTrue(customText.contains("// Model Source: Custom import; path=files/user_astro_cellpose_v1/model.pt"));
        assertTrue(customText.contains("--pretrained_model files/user_astro_cellpose_v1/model.pt"));
        assertFalse(customText.contains("// Model: user_astro_cellpose_v1"));
    }

    private String writeStarDist(String channelName, ModelEntry entry) throws Exception {
        File root = temp.newFolder(channelName + "-stardist");
        File detailsDir = new File(root, "Analysis Details");
        ObjectAnalysisDetailsWriter.writeStarDistPerChannel(
                detailsDir,
                new File(root, ".bin"),
                channelName,
                1,
                entry,
                0.5,
                0.3,
                5.0,
                5.0,
                1,
                0.0,
                Double.POSITIVE_INFINITY,
                0.0,
                0.0,
                new String[] {channelName},
                false,
                null);
        return readDetails(detailsDir, channelName);
    }

    private String writeCellpose(String channelName, ModelEntry entry, String modelToken) throws Exception {
        File root = temp.newFolder(channelName + "-cellpose");
        File detailsDir = new File(root, "Analysis Details");
        ObjectAnalysisDetailsWriter.writeCellposePerChannel(
                detailsDir,
                new File(root, ".bin"),
                channelName,
                1,
                entry,
                modelToken,
                30.0,
                0.4,
                0.0,
                true,
                null,
                new String[] {channelName},
                false,
                null);
        return readDetails(detailsDir, channelName);
    }

    private static String readDetails(File detailsDir, String channelName) throws Exception {
        File out = new File(detailsDir, ObjectAnalysisDetailsWriter.detailsFileName(channelName));
        assertTrue(out.isFile());
        return new String(Files.readAllBytes(out.toPath()), StandardCharsets.UTF_8);
    }
}
