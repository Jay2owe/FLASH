package flash.pipeline.analyses;

import flash.pipeline.bin.BinConfig;
import flash.pipeline.segmentation.catalog.ModelCatalog;
import flash.pipeline.segmentation.catalog.ModelEntry;
import flash.pipeline.ui.config.SegmentationMethodStage;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import javax.swing.JComboBox;
import java.io.File;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;

import static org.junit.Assert.assertEquals;

public class BinConfigTrainedRfInitialSetupTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void initialSetupConfirmWithoutChangesPreservesTrainedRfToken() throws Exception {
        String token = "trained_rf:test_model:base=classical";
        CreateBinFileAnalysis.BinUserConfig draft = oneChannelConfig(token);
        Object bindings = newBinSetupBindings(1);

        CreateBinFileAnalysis.BinUserConfig result =
                invokeBuildBinUserConfigFromDialog(new CreateBinFileAnalysis(), draft, bindings);

        assertEquals(token, result.segmentationMethods.get(0));
    }

    @Test
    public void initialSetupConfirmWithoutChangesPreservesTrainedRfTokenWithNestedBase() throws Exception {
        String token = "trained_rf:test_model:"
                + "base=stardist%3A0.5%3A0.3%3Aarea%3D20-2000%3Amodel%3Dstardist_custom";
        CreateBinFileAnalysis.BinUserConfig draft = oneChannelConfig(token);
        Object bindings = newBinSetupBindings(1);

        CreateBinFileAnalysis.BinUserConfig result =
                invokeBuildBinUserConfigFromDialog(new CreateBinFileAnalysis(), draft, bindings);

        assertEquals(token, result.segmentationMethods.get(0));
    }

    @Test
    public void initialSetupClassicalSelectionDeliberatelyOverwritesTrainedRfToken() throws Exception {
        String token = "trained_rf:test_model:base=classical";
        CreateBinFileAnalysis.BinUserConfig draft = oneChannelConfig(token);
        Object bindings = newBinSetupBindings(1);
        @SuppressWarnings("unchecked")
        JComboBox<String>[] segmentationCombos = new JComboBox[]{
                new JComboBox<String>(new String[]{
                        "Trained RF: Test Microglia RF",
                        SegmentationMethodStage.CLASSICAL
                })
        };
        segmentationCombos[0].setSelectedItem(SegmentationMethodStage.CLASSICAL);
        copyBindingArray(bindings, "segmentationCombos", segmentationCombos);

        CreateBinFileAnalysis.BinUserConfig result =
                invokeBuildBinUserConfigFromDialog(new CreateBinFileAnalysis(), draft, bindings);

        assertEquals("classical", result.segmentationMethods.get(0));
    }

    @Test
    public void initialSetupUsesCatalogNameForTrainedRfChoiceLabel() throws Exception {
        File projectRoot = temp.newFolder("project");
        ModelCatalog catalog = new ModelCatalog(projectRoot.toPath(),
                Collections.singletonList(new ModelEntry(
                        "test_model",
                        "Test Microglia RF",
                        "Test RF model",
                        ModelEntry.Engine.SMILE_RF,
                        ModelEntry.Source.USER_TRAINED,
                        "files/test_model/model.model",
                        null,
                        null,
                        null,
                        null,
                        Collections.<String, Object>emptyMap(),
                        Collections.<String, Object>emptyMap(),
                        false)));

        assertEquals("Trained RF: Test Microglia RF",
                CreateBinFileAnalysis.segmentationChoiceForDialogDefault(
                        "trained_rf:test_model:base=classical",
                        true,
                        true,
                        catalog));
    }

    private static CreateBinFileAnalysis.BinUserConfig oneChannelConfig(String segmentationMethod) {
        CreateBinFileAnalysis.BinUserConfig cfg = new CreateBinFileAnalysis.BinUserConfig(
                new ArrayList<String>(Collections.singletonList("IBA1")),
                new ArrayList<String>(Collections.singletonList("Green")),
                new ArrayList<String>(Collections.singletonList("default")),
                new ArrayList<String>(Collections.singletonList("100-Infinity")),
                new ArrayList<String>(Collections.singletonList("None")),
                new ArrayList<String>(Collections.singletonList("Default")),
                new ArrayList<String>(Collections.singletonList("default")));
        cfg.segmentationMethods.set(0, segmentationMethod);
        return cfg;
    }

    private static Object newBinSetupBindings(int channelCount) throws Exception {
        Class<?> type = Class.forName("flash.pipeline.analyses.CreateBinFileAnalysis$BinSetupBindings");
        java.lang.reflect.Constructor<?> constructor = type.getDeclaredConstructor(int.class);
        constructor.setAccessible(true);
        return constructor.newInstance(Integer.valueOf(channelCount));
    }

    private static void copyBindingArray(Object bindings, String fieldName, Object[] values) throws Exception {
        java.lang.reflect.Field field = bindings.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        Object[] target = (Object[]) field.get(bindings);
        System.arraycopy(values, 0, target, 0, Math.min(values.length, target.length));
    }

    private static CreateBinFileAnalysis.BinUserConfig invokeBuildBinUserConfigFromDialog(
            CreateBinFileAnalysis analysis,
            CreateBinFileAnalysis.BinUserConfig draft,
            Object bindings) throws Exception {
        Method method = CreateBinFileAnalysis.class.getDeclaredMethod(
                "buildBinUserConfigFromDialog",
                int.class,
                BinConfig.class,
                CreateBinFileAnalysis.BinUserConfig.class,
                bindings.getClass());
        method.setAccessible(true);
        return (CreateBinFileAnalysis.BinUserConfig) method.invoke(
                analysis, Integer.valueOf(1), null, draft, bindings);
    }
}
