package flash.pipeline.ui.variations.state;

import flash.pipeline.ui.variations.CropSpec;
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
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class VariationStateStoreTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void saveLoadRoundTripPreservesSweepAndCompletedCells() throws Exception {
        File bin = temp.newFolder(".bin");
        VariationStateStore store = new VariationStateStore(bin.toPath());
        ParameterSweep sweep = cellposeSweep("image-a");
        ParameterCombo combo = sweep.combos().get(0);
        String cacheKey = VariationCache.keyFor(sweep, combo);
        VariationState.CompletedCell completed = new VariationState.CompletedCell(
                VariationState.comboIdFor(sweep, combo), cacheKey, 42, 4500L);
        VariationState state = new VariationState(sweep,
                Collections.singletonList(completed),
                "2026-05-13T11:14:00Z",
                "2026-05-13T11:18:33Z");

        store.save(state);
        Optional<VariationState> loaded = store.load();

        assertTrue(loaded.isPresent());
        assertEquals(ParameterSweep.Method.CELLPOSE, loaded.get().method());
        assertEquals("DAPI", loaded.get().channel());
        assertEquals("image-a", loaded.get().imageHash());
        assertEquals(CropSpec.centre256(), loaded.get().sweep().cropSpec());
        assertEquals(sweep.valueLists(), loaded.get().sweep().valueLists());
        assertEquals(1, loaded.get().completed().size());
        assertEquals(cacheKey, loaded.get().completed().get(0).labelCacheKey());
        assertEquals(42, loaded.get().completed().get(0).nObjects());
        assertEquals(4500L, loaded.get().completed().get(0).durationMs());
        assertEquals("2026-05-13T11:14:00Z", loaded.get().startedAt());
        assertEquals("2026-05-13T11:18:33Z", loaded.get().updatedAt());
    }

    @Test
    public void missingFileReturnsEmptyOptional() throws Exception {
        VariationStateStore store = new VariationStateStore(temp.newFolder(".bin").toPath());

        assertFalse(store.load().isPresent());
    }

    @Test
    public void corruptFileReturnsEmptyOptionalWithoutThrowing() throws Exception {
        File bin = temp.newFolder(".bin");
        VariationStateStore store = new VariationStateStore(bin.toPath());
        Files.write(store.stateFileForTest(),
                "{not-json".getBytes(StandardCharsets.UTF_8));

        assertFalse(store.load().isPresent());
    }

    @Test
    public void unsupportedVersionIsTreatedAsCorrupt() throws Exception {
        File bin = temp.newFolder(".bin");
        VariationStateStore store = new VariationStateStore(bin.toPath());
        Files.write(store.stateFileForTest(),
                "{\"version\":2}".getBytes(StandardCharsets.UTF_8));

        assertFalse(store.load().isPresent());
    }

    @Test
    public void mismatchedImageHashIsNotResumeCompatible() throws Exception {
        VariationState state = VariationState.started(cellposeSweep("old-image"));

        assertFalse(state.isCompatible(ParameterSweep.Method.CELLPOSE, "DAPI", "new-image"));
        assertTrue(state.isCompatible(ParameterSweep.Method.CELLPOSE, "DAPI", "old-image"));
    }

    private static ParameterSweep cellposeSweep(String imageHash) {
        Map<ParameterId, ParameterValueList> values =
                new LinkedHashMap<ParameterId, ParameterValueList>();
        values.put(ParameterId.DIAMETER, ParameterValueList.ofDoubles(25.0d, 30.0d, 35.0d));
        values.put(ParameterId.FLOW_THRESHOLD, ParameterValueList.ofDoubles(0.3d, 0.5d));
        values.put(ParameterId.CELLPROB_THRESHOLD, ParameterValueList.ofDoubles(0.0d));
        values.put(ParameterId.MODEL, ParameterValueList.ofStrings("cyto3"));
        return new ParameterSweep(ParameterSweep.Method.CELLPOSE,
                values, CropSpec.centre256(), "DAPI", imageHash);
    }
}
