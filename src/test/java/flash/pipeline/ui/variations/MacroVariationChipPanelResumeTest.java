package flash.pipeline.ui.variations;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

public class MacroVariationChipPanelResumeTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void savedCurrentChannelMacroIsRehydratedFromLiveCatalog()
            throws Exception {
        File bin = temp.newFolder("bin");
        File macro = new File(bin, "C1_Filters.ijm");
        Files.write(macro.toPath(),
                "run(\"Gaussian Blur...\", \"sigma=3 stack\");"
                        .getBytes(StandardCharsets.UTF_8));

        MacroVariation saved = MacroVariation.currentChannel(
                "Current C1 filter",
                "C1_Filters.ijm",
                "run(\"Gaussian Blur...\", \"sigma=2 stack\");");
        MacroVariationCatalog catalog = new MacroVariationCatalog(
                bin, 0, "DAPI", null);
        MacroVariationChipPanel panel = new MacroVariationChipPanel(
                ParameterValueList.ofStrings(MacroToken.NONE_VALUE), catalog);

        panel.setMacroVariationSet(MacroVariationSet.of(saved));
        panel.setValues(Collections.singletonList(saved.token()));

        String liveToken = String.valueOf(panel.currentValueList().get(0));
        MacroVariation live = panel.selectedVariationSet().resolve(liveToken);
        assertNotEquals(saved.token(), liveToken);
        assertNotEquals(saved.normalizedScriptHash(), live.normalizedScriptHash());
        assertEquals(MacroVariation.SOURCE_CURRENT_CHANNEL, live.sourceKind());
        assertTrue(live.scriptText().contains("sigma=3"));
    }
}
