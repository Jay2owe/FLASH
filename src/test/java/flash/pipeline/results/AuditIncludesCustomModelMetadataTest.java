package flash.pipeline.results;

import flash.pipeline.segmentation.catalog.ModelCatalog;
import flash.pipeline.segmentation.catalog.ModelEntry;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class AuditIncludesCustomModelMetadataTest {
    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void writesRfMetadataWhenChannelUsesTrainedRf() throws Exception {
        Path root = temp.newFolder("rf-project").toPath();
        File analysisDetailsDir = temp.newFolder("rf-details");

        Map<String, Object> metadata = new LinkedHashMap<String, Object>();
        metadata.put("trainedAt", Long.valueOf(1747850000000L));
        metadata.put("crossValAccuracy", Double.valueOf(0.83));
        metadata.put("quality", "OK");
        metadata.put("engineVersion", "smile-2.6.0");
        Map<String, Object> importance = new LinkedHashMap<String, Object>();
        importance.put("volume", Double.valueOf(0.70));
        importance.put("sphericity", Double.valueOf(0.90));
        importance.put("mean_intensity", Double.valueOf(0.60));
        importance.put("surface_area", Double.valueOf(0.10));
        metadata.put("featureImportance", importance);

        ModelEntry rf = new ModelEntry(
                "trained_rf_microglia_v1",
                "Microglia RF (28pos/41neg)",
                "Smile RF post-filter",
                ModelEntry.Engine.SMILE_RF,
                ModelEntry.Source.USER_TRAINED,
                "files/trained_rf_microglia_v1/model.smile",
                null,
                null,
                null,
                "classical",
                null,
                metadata,
                false);

        ObjectAnalysisDetailsWriter.writeSegmentationModelsReport(
                analysisDetailsDir,
                new ModelCatalog(root, Collections.singletonList(rf)),
                new String[] {"Iba1"},
                new String[] {"trained_rf:trained_rf_microglia_v1:base=classical"});

        String text = reportText(analysisDetailsDir);
        assertTrue(text.contains("<segmentation_models>"));
        assertTrue(text.contains("channel=Iba1"));
        assertTrue(text.contains("model_name=Microglia RF (28pos/41neg)"));
        assertTrue(text.contains("model_key=trained_rf_microglia_v1"));
        assertTrue(text.contains("engine=smile_rf"));
        assertTrue(text.contains("source=user_trained"));
        assertTrue(text.contains("trained_at=1747850000000"));
        assertTrue(text.contains("cross_val_accuracy=0.83"));
        assertTrue(text.contains("quality_flag=OK"));
        assertTrue(text.contains("feature_importance_top3=sphericity,volume,mean_intensity"));
        assertTrue(text.contains("engine_version=smile-2.6.0"));
    }

    @Test
    public void writesDeepModelMetadataWhenChannelUsesCustomStarDist() throws Exception {
        Path root = temp.newFolder("sd-project").toPath();
        File analysisDetailsDir = temp.newFolder("sd-details");

        Map<String, Object> metadata = new LinkedHashMap<String, Object>();
        metadata.put("sourceNotebook", "https://colab.research.google.com/example");
        metadata.put("engineVersion", "stardist-0.9.1");

        ModelEntry starDist = new ModelEntry(
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
                metadata,
                false);

        ObjectAnalysisDetailsWriter.writeSegmentationModelsReport(
                analysisDetailsDir,
                new ModelCatalog(root, Collections.singletonList(starDist)),
                new String[] {"DAPI"},
                new String[] {"stardist:0.5:0.3:model=user_dapi_stardist_v1"});

        String text = reportText(analysisDetailsDir);
        assertTrue(text.contains("channel=DAPI"));
        assertTrue(text.contains("model_name=DAPI custom StarDist v1"));
        assertTrue(text.contains("model_key=user_dapi_stardist_v1"));
        assertTrue(text.contains("engine=stardist"));
        assertTrue(text.contains("source=user_imported"));
        assertTrue(text.contains("source_notebook=https://colab.research.google.com/example"));
        assertTrue(text.contains("engine_version=stardist-0.9.1"));
    }

    @Test
    public void omitsModelSectionWhenAllChannelsUseStockClassicalThreshold() throws Exception {
        Path root = temp.newFolder("classical-project").toPath();
        File analysisDetailsDir = temp.newFolder("classical-details");

        ObjectAnalysisDetailsWriter.writeSegmentationModelsReport(
                analysisDetailsDir,
                new ModelCatalog(root, Collections.<ModelEntry>emptyList()),
                new String[] {"Iba1", "DAPI"},
                new String[] {"classical", "classical"});

        assertFalse(ObjectAnalysisDetailsWriter.segmentationModelsReportFile(analysisDetailsDir).exists());
    }

    @Test
    public void handlesMissingCatalogEntryGracefully() throws Exception {
        Path root = temp.newFolder("missing-project").toPath();
        File analysisDetailsDir = temp.newFolder("missing-details");

        ObjectAnalysisDetailsWriter.writeSegmentationModelsReport(
                analysisDetailsDir,
                new ModelCatalog(root, Collections.<ModelEntry>emptyList()),
                new String[] {"DAPI"},
                new String[] {"stardist:0.5:0.3:model=missing_model"});

        String text = reportText(analysisDetailsDir);
        assertTrue(text.contains("channel=DAPI"));
        assertTrue(text.contains("model_key=missing_model"));
        assertTrue(text.contains("error=Model not found in catalog at audit time"));
    }

    private static String reportText(File analysisDetailsDir) throws Exception {
        File report = ObjectAnalysisDetailsWriter.segmentationModelsReportFile(analysisDetailsDir);
        assertTrue(report.isFile());
        return new String(Files.readAllBytes(report.toPath()), StandardCharsets.UTF_8);
    }
}
