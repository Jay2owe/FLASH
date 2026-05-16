package flash.pipeline.audit;

import flash.pipeline.bin.BinConfig;
import flash.pipeline.bin.BinConfigIO;
import flash.pipeline.bin.BinField;
import flash.pipeline.segmentation.catalog.ModelCatalog;
import flash.pipeline.segmentation.catalog.ModelCatalogIO;
import flash.pipeline.segmentation.catalog.ModelEntry;
import flash.pipeline.ui.wizard.JsonIO;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class RunSettingsSnapshotSegmentationModelsTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void snapshotIncludesDerivedSegmentationModelsForEveryChannel() throws Exception {
        File dir = temp.newFolder("snapshot-segmentation-models");
        ModelEntry rf = new ModelEntry(
                "trained_rf_microglia_v1",
                "Microglia RF",
                "Smile random forest post-filter",
                ModelEntry.Engine.SMILE_RF,
                ModelEntry.Source.USER_TRAINED,
                "files/trained_rf_microglia_v1/model.smile",
                null,
                null,
                null,
                "classical",
                null,
                null,
                false);
        ModelCatalogIO.writeProject(dir.toPath(),
                new ModelCatalog(dir.toPath(), Collections.singletonList(rf)));
        BinConfigIO.writeFromConfig(dir.getAbsolutePath(), config(
                "stardist:0.5:0.3:model=stardist_dsb2018_paper",
                "cellpose:30.0:0.4:0.0:gpu=false:model=cellpose_nuclei",
                "trained_rf:trained_rf_microglia_v1:base=classical",
                "classical",
                "enhanced_classical:thresh=75:minSize=25:maxSize=250"));

        RunSettingsSnapshot snapshot = RunSettingsSnapshot.create(
                dir.getAbsolutePath(),
                "3D Object Analysis",
                4,
                EnumSet.of(BinField.SEGMENTATION_METHODS),
                null,
                null);

        List<Object> rows = JsonIO.asList(JsonIO.parseObject(snapshot.toJson()).get("segmentation_models"));
        assertEquals(5, rows.size());

        Map<String, Object> starDist = row(rows, 0);
        assertEquals(0, JsonIO.intValue(starDist.get("channel_index"), -1));
        assertEquals("stardist", starDist.get("engine"));
        assertEquals("stardist_dsb2018_paper", starDist.get("model_key"));
        assertEquals("StarDist - DSB 2018 paper", starDist.get("display_name"));
        assertEquals("stock", starDist.get("source_type"));

        Map<String, Object> cellpose = row(rows, 1);
        assertEquals(1, JsonIO.intValue(cellpose.get("channel_index"), -1));
        assertEquals("cellpose", cellpose.get("engine"));
        assertEquals("cellpose_nuclei", cellpose.get("model_key"));
        assertEquals("Cellpose - nuclei", cellpose.get("display_name"));
        assertEquals("fiji_builtin", cellpose.get("source_type"));

        Map<String, Object> trainedRf = row(rows, 2);
        assertEquals(2, JsonIO.intValue(trainedRf.get("channel_index"), -1));
        assertEquals("trained_rf", trainedRf.get("engine"));
        assertEquals("trained_rf_microglia_v1", trainedRf.get("model_key"));
        assertEquals("Microglia RF", trainedRf.get("display_name"));
        assertEquals("user", trainedRf.get("source_type"));

        Map<String, Object> classical = row(rows, 3);
        assertEquals("classical", classical.get("engine"));
        assertEquals("classical", classical.get("method"));
        assertEquals("unknown", classical.get("source_type"));
        assertFalse(classical.containsKey("model_key"));
        assertFalse(classical.containsKey("display_name"));

        Map<String, Object> enhanced = row(rows, 4);
        assertEquals("classical_enhanced", enhanced.get("engine"));
        assertEquals("unknown", enhanced.get("source_type"));
        assertFalse(enhanced.containsKey("model_key"));
        assertFalse(enhanced.containsKey("display_name"));
    }

    @Test
    public void unresolvedModelKeyIsRecordedWithoutThrowing() throws Exception {
        File dir = temp.newFolder("snapshot-missing-model");
        BinConfigIO.writeFromConfig(dir.getAbsolutePath(), config(
                "stardist:0.5:0.3:model=missing_stardist"));

        RunSettingsSnapshot snapshot = RunSettingsSnapshot.create(
                dir.getAbsolutePath(),
                "3D Object Analysis",
                4,
                EnumSet.of(BinField.SEGMENTATION_METHODS),
                null,
                null);

        List<Object> rows = JsonIO.asList(JsonIO.parseObject(snapshot.toJson()).get("segmentation_models"));
        assertEquals(1, rows.size());
        Map<String, Object> missing = row(rows, 0);
        assertEquals("stardist", missing.get("engine"));
        assertEquals("missing_stardist", missing.get("model_key"));
        assertEquals("unknown", missing.get("source_type"));
        assertFalse(missing.containsKey("display_name"));
    }

    private static BinConfig config(String... methods) {
        BinConfig cfg = new BinConfig();
        List<String> names = Arrays.asList("C1", "C2", "C3", "C4", "C5");
        for (int i = 0; i < methods.length; i++) {
            cfg.channelNames.add(names.get(i));
            cfg.channelColors.add("Green");
            cfg.channelThresholds.add("default");
            cfg.channelSizes.add("100-Infinity");
            cfg.channelMinMax.add("None");
            cfg.channelIntensityThresholds.add("default");
            cfg.segmentationMethods.add(methods[i]);
            cfg.channelFilterPresets.add("Default");
        }
        return cfg;
    }

    private static Map<String, Object> row(List<Object> rows, int index) {
        assertTrue(index >= 0 && index < rows.size());
        return JsonIO.asObject(rows.get(index));
    }
}
