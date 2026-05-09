package flash.pipeline.analyses;

import flash.pipeline.ui.config.FilterParameterStage;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Round-trip test for {@code FilterParameterStage.MacroStore.saveAsPreset}.
 * The MacroStore is constructed inline in
 * {@code CreateBinFileAnalysis.createFilterParameterStage}, so we reflect to
 * pull it out of the wired stage and exercise the saveAsPreset →
 * loadPresetMacro round trip end-to-end.
 */
public class MacroStoreSaveAsPresetTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    private static final String CUSTOM_MACRO =
            "run(\"Median...\", \"radius=4 stack\");\n";

    @Test
    public void saveAsPreset_roundTripPersistsByteForByte() throws Exception {
        File project = temp.newFolder("project-saveAsPreset");
        File binFolder = new File(project, "FLASH/Set Up Configuration/.settings");
        assertTrue(binFolder.mkdirs());

        CreateBinFileAnalysis analysis = new CreateBinFileAnalysis();
        analysis.setSuppressDialogs(true);
        analysis.setHeadless(true);

        CreateBinFileAnalysis.BinUserConfig cfg = oneChannelConfig("Default");
        FilterParameterStage stage = invokeCreateFilterParameterStage(
                analysis, new ArrayList<Object>(), cfg, binFolder, 0);

        FilterParameterStage.MacroStore store = extractMacroStore(stage);

        store.saveAsPreset("MyTuned", CUSTOM_MACRO);

        File expected = new File(project,
                "FLASH/.settings/Presets/Custom Filter Presets/MyTuned.ijm");
        assertTrue("expected preset file: " + expected.getAbsolutePath(),
                expected.isFile());
        assertEquals(CUSTOM_MACRO,
                new String(Files.readAllBytes(expected.toPath()), StandardCharsets.UTF_8));

        String reloaded = store.loadPresetMacro("MyTuned");
        assertEquals("loadPresetMacro must round-trip saveAsPreset bytes",
                CUSTOM_MACRO, reloaded);
    }

    @Test
    public void saveAsPreset_emptyNameIsRejected() throws Exception {
        File project = temp.newFolder("project-saveAsPreset-empty");
        File binFolder = new File(project, "FLASH/Set Up Configuration/.settings");
        assertTrue(binFolder.mkdirs());

        CreateBinFileAnalysis analysis = new CreateBinFileAnalysis();
        analysis.setSuppressDialogs(true);
        analysis.setHeadless(true);

        CreateBinFileAnalysis.BinUserConfig cfg = oneChannelConfig("Default");
        FilterParameterStage stage = invokeCreateFilterParameterStage(
                analysis, new ArrayList<Object>(), cfg, binFolder, 0);

        FilterParameterStage.MacroStore store = extractMacroStore(stage);

        try {
            store.saveAsPreset("   ", CUSTOM_MACRO);
            fail("expected exception for blank preset name");
        } catch (IllegalArgumentException expected) {
            // ok
        }
    }

    private static CreateBinFileAnalysis.BinUserConfig oneChannelConfig(String filterPreset) {
        return new CreateBinFileAnalysis.BinUserConfig(
                new ArrayList<String>(Arrays.asList("IBA1")),
                new ArrayList<String>(Arrays.asList("Green")),
                new ArrayList<String>(Arrays.asList("default")),
                new ArrayList<String>(Arrays.asList("100-Infinity")),
                new ArrayList<String>(Arrays.asList("None")),
                new ArrayList<String>(Arrays.asList(filterPreset)),
                new ArrayList<String>(Arrays.asList("default")));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static FilterParameterStage invokeCreateFilterParameterStage(
            CreateBinFileAnalysis analysis, java.util.List<?> images,
            CreateBinFileAnalysis.BinUserConfig cfg, File binFolder, int channelIndex)
            throws Exception {
        Method method = null;
        for (Method m : CreateBinFileAnalysis.class.getDeclaredMethods()) {
            if (m.getName().equals("createFilterParameterStage")) {
                method = m;
                break;
            }
        }
        if (method == null) {
            throw new IllegalStateException("createFilterParameterStage method not found");
        }
        method.setAccessible(true);
        return (FilterParameterStage) method.invoke(analysis, images, cfg, binFolder, Integer.valueOf(channelIndex));
    }

    private static FilterParameterStage.MacroStore extractMacroStore(FilterParameterStage stage)
            throws Exception {
        Field field = FilterParameterStage.class.getDeclaredField("macroStore");
        field.setAccessible(true);
        return (FilterParameterStage.MacroStore) field.get(stage);
    }
}
