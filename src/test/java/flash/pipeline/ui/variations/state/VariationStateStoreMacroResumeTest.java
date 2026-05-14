package flash.pipeline.ui.variations.state;

import flash.pipeline.ui.variations.CropSpec;
import flash.pipeline.ui.variations.MacroVariation;
import flash.pipeline.ui.variations.MacroVariationSet;
import flash.pipeline.ui.variations.ParameterCombo;
import flash.pipeline.ui.variations.ParameterId;
import flash.pipeline.ui.variations.ParameterSweep;
import flash.pipeline.ui.variations.ParameterValueList;
import flash.pipeline.ui.variations.VariationCache;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class VariationStateStoreMacroResumeTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void macroIdentityMetadataRoundTripsWithoutScriptText() throws Exception {
        File bin = temp.newFolder(".bin");
        VariationStateStore store = new VariationStateStore(bin.toPath());
        MacroVariation blur = MacroVariation.pasted("Blur",
                "run(\"Gaussian Blur...\", \"sigma=2 stack\");");
        MacroVariation median = MacroVariation.pasted("Median",
                "run(\"Median...\", \"radius=2 stack\");");
        ParameterSweep sweep = sweepFor(blur, median);
        VariationState state = new VariationState(sweep, completedFor(sweep),
                "2026-05-14T10:00:00Z",
                "2026-05-14T10:01:00Z");

        store.save(state);
        String json = new String(Files.readAllBytes(store.stateFileForTest()),
                StandardCharsets.UTF_8);
        Optional<VariationState> loaded = store.load();

        assertTrue(loaded.isPresent());
        assertFalse(json.contains("Gaussian Blur"));
        assertFalse(json.contains("Median..."));
        assertEquals(2, loaded.get().completed().size());
        assertEquals(blur.normalizedScriptHash(),
                loaded.get().sweep().macroVariations()
                        .resolve(blur.token()).normalizedScriptHash());
        assertEquals("", loaded.get().sweep().macroVariations()
                .resolve(blur.token()).scriptText());
    }

    @Test
    public void changedMacroHashInvalidatesOnlyAffectedCompletedCells()
            throws Exception {
        File bin = temp.newFolder(".bin");
        VariationStateStore store = new VariationStateStore(bin.toPath());
        MacroVariation blur = MacroVariation.pasted("Blur",
                "run(\"Gaussian Blur...\", \"sigma=2 stack\");");
        MacroVariation median = MacroVariation.pasted("Median",
                "run(\"Median...\", \"radius=2 stack\");");
        ParameterSweep savedSweep = sweepFor(blur, median);
        store.save(new VariationState(savedSweep, completedFor(savedSweep),
                "2026-05-14T10:00:00Z",
                "2026-05-14T10:01:00Z"));

        MacroVariation editedBlur = MacroVariation.pasted("Blur",
                "run(\"Gaussian Blur...\", \"sigma=3 stack\");");
        ParameterSweep activeSweep = sweepFor(editedBlur, median);
        Optional<VariationState> loaded = store.load(activeSweep);

        assertTrue(loaded.isPresent());
        assertEquals(1, loaded.get().completed().size());
        assertEquals("0_1", loaded.get().completed().get(0).comboId());
    }

    private static ParameterSweep sweepFor(MacroVariation first,
                                           MacroVariation second) {
        Map<ParameterId, ParameterValueList> values =
                new LinkedHashMap<ParameterId, ParameterValueList>();
        values.put(ParameterId.THRESHOLD, ParameterValueList.ofInts(128));
        values.put(ParameterId.MACRO,
                ParameterValueList.ofStrings(first.token(), second.token()));
        return new ParameterSweep(ParameterSweep.Method.CLASSICAL,
                values, CropSpec.full(), "DAPI", "image-a", "",
                MacroVariationSet.of(first, second));
    }

    private static List<VariationState.CompletedCell> completedFor(
            ParameterSweep sweep) {
        List<VariationState.CompletedCell> completed =
                new ArrayList<VariationState.CompletedCell>();
        List<ParameterCombo> combos = sweep.combos();
        for (int i = 0; i < combos.size(); i++) {
            ParameterCombo combo = combos.get(i);
            completed.add(new VariationState.CompletedCell(
                    VariationState.comboIdFor(sweep, combo),
                    VariationCache.keyFor(sweep, combo),
                    10 + i,
                    1000L + i));
        }
        return completed;
    }
}
