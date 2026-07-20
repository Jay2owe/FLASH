package flash.pipeline.analyses;

import flash.pipeline.ui.config.CellposeParameterStage;
import flash.pipeline.ui.config.ClassicalSegmentationStage;
import flash.pipeline.ui.config.ConfigQcContext;
import flash.pipeline.ui.config.ConfigQcStage;
import flash.pipeline.ui.config.EnhancedClassicalSegmentationStage;
import flash.pipeline.ui.config.SegmentationMethodStage;
import flash.pipeline.ui.config.StarDistParameterStage;
import flash.pipeline.ui.config.TrainedRfSummaryStage;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class TrainedRfStageRoutingTest {
    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void trainedRfRoutesToSummaryStageInsteadOfClassicalControls() throws Exception {
        List<String> keys = applicableStageKeys("trained_rf:test_model:base=classical");

        assertTrue(keys.contains(SegmentationMethodStage.class.getName()));
        assertTrue(keys.contains(TrainedRfSummaryStage.class.getName()));
        assertFalse(keys.contains(ClassicalSegmentationStage.class.getName()));
    }

    @Test
    public void existingSegmentationMethodsKeepTheirStageRouting() throws Exception {
        assertRoutesTo("classical", ClassicalSegmentationStage.class);
        assertRoutesTo("enhanced_classical:thresh=40:minSize=10:maxSize=100",
                EnhancedClassicalSegmentationStage.class);
        assertRoutesTo("stardist:0.5:0.4:model=stardist_versatile_fluo",
                StarDistParameterStage.class);
        assertRoutesTo("cellpose:30.0:0.4:0.0:model=cellpose_cyto3",
                CellposeParameterStage.class);
    }

    private void assertRoutesTo(String token, Class<?> expectedStage) throws Exception {
        List<String> keys = applicableStageKeys(token);

        assertTrue("Expected " + expectedStage.getSimpleName() + " for " + token,
                keys.contains(expectedStage.getName()));
        assertFalse("Only trained_rf should use the Trained RF summary stage.",
                keys.contains(TrainedRfSummaryStage.class.getName()));
    }

    private List<String> applicableStageKeys(String segmentationToken) throws Exception {
        File project = temp.newFolder("project-" + Math.abs(segmentationToken.hashCode()));
        File binFolder = new File(project, ".bin");
        assertTrue(binFolder.isDirectory() || binFolder.mkdirs());

        CreateBinFileAnalysis.BinUserConfig cfg = new CreateBinFileAnalysis.BinUserConfig(
                new ArrayList<String>(Collections.singletonList("IBA1")),
                new ArrayList<String>(Collections.singletonList("Green")),
                new ArrayList<String>(Collections.singletonList("100")),
                new ArrayList<String>(Collections.singletonList("10-Infinity")),
                new ArrayList<String>(Collections.singletonList("None")),
                new ArrayList<String>(Collections.singletonList("None")),
                new ArrayList<String>(Collections.singletonList("default")));
        cfg.segmentationMethods.set(0, segmentationToken);

        CreateBinFileAnalysis analysis = new CreateBinFileAnalysis();
        List<ConfigQcStage> stages = analysis.createSegmentationObjectQcStagesForTest(
                cfg, binFolder, 0, false);
        ConfigQcContext context = new ConfigQcContext(
                project,
                binFolder,
                cfg,
                Collections.<ConfigQcContext.ConfigQcImage>emptyList(),
                cfg.names,
                0);

        List<String> keys = new ArrayList<String>();
        for (ConfigQcStage stage : stages) {
            if (stage != null && stage.isApplicable(context)) {
                keys.add(stage.key());
            }
        }
        return keys;
    }
}
