package flash.pipeline.recipes;

import flash.pipeline.intelligence.MiniJson;
import flash.pipeline.ui.wizard.JsonIO;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class PipelineRecipeTest {

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void fromSelections_ignoresOutOfRangeSelections() {
        boolean[] selections = new boolean[32];
        selections[31] = true;

        PipelineRecipe recipe = PipelineRecipe.fromSelections(
                "Out of range", "Test recipe", selections);

        assertTrue(recipe.getAnalyses().isEmpty());
    }

    @Test
    public void unknownRecipeKeysRemainUnknown() {
        PipelineRecipe recipe = new PipelineRecipe(
                "Unknown", "Test recipe", PipelineRecipe.CURRENT_FLASH_VERSION,
                Collections.singletonList("UnknownAnalysis"), Collections.<String, String>emptyMap());

        assertEquals(Collections.singletonList("UnknownAnalysis"), recipe.unknownAnalysisKeys());
    }

    @Test
    public void toSelectionsMapsKnownRecipeKeysBackToAnalysisIndexes() {
        java.util.List<String> analyses = new java.util.ArrayList<String>();
        analyses.add("CreateBin");
        analyses.add("Intensity");
        analyses.add("RepresentativeFigure");
        analyses.add("UnknownAnalysis");
        PipelineRecipe recipe = new PipelineRecipe(
                "Restore", "Test recipe", PipelineRecipe.CURRENT_FLASH_VERSION,
                analyses, Collections.<String, String>emptyMap());

        boolean[] selections = recipe.toSelections(
                flash.pipeline.FLASH_Pipeline.IDX_REPRESENTATIVE_FIGURE + 1);

        assertTrue(selections[flash.pipeline.FLASH_Pipeline.IDX_CREATE_BIN]);
        assertTrue(selections[flash.pipeline.FLASH_Pipeline.IDX_INTENSITY]);
        assertTrue(selections[flash.pipeline.FLASH_Pipeline.IDX_REPRESENTATIVE_FIGURE]);
        assertFalse(selections[flash.pipeline.FLASH_Pipeline.IDX_DRAW_ROIS]);
    }

    @Test
    public void toSelectionsIgnoresKeysOutsideRequestedLength() {
        PipelineRecipe recipe = new PipelineRecipe(
                "Short", "Test recipe", PipelineRecipe.CURRENT_FLASH_VERSION,
                Collections.singletonList("Excel"), Collections.<String, String>emptyMap());

        boolean[] selections = recipe.toSelections(2);

        assertEquals(2, selections.length);
        assertFalse(selections[0]);
        assertFalse(selections[1]);
    }

    @Test
    public void distinctUnicodeRecipeNamesCannotOverwriteEachOther() throws Exception {
        Path dir = temporaryFolder.newFolder("unicode-recipes").toPath();
        PipelineRecipe chinese = recipe("处理方案");
        PipelineRecipe greek = recipe("Θεραπεία");
        PipelineRecipe accented = recipe("Thérapie");

        File chineseFile = PipelineRecipeIO.saveToDirectory(chinese, dir);
        File greekFile = PipelineRecipeIO.saveToDirectory(greek, dir);
        File accentedFile = PipelineRecipeIO.saveToDirectory(accented, dir);

        assertNotEquals(chineseFile.getName().toLowerCase(Locale.ROOT),
                greekFile.getName().toLowerCase(Locale.ROOT));
        assertNotEquals(chineseFile.getName().toLowerCase(Locale.ROOT),
                accentedFile.getName().toLowerCase(Locale.ROOT));
        assertEquals("处理方案", PipelineRecipeIO.loadFromFile(chineseFile).getName());
        assertEquals("Θεραπεία", PipelineRecipeIO.loadFromFile(greekFile).getName());
        assertEquals("Thérapie", PipelineRecipeIO.loadFromFile(accentedFile).getName());
        assertEquals(3L, directoryEntries(dir));
    }

    @Test
    public void collidingReadableStemsAndCanonicalFormsAreDurablyDisambiguated()
            throws Exception {
        Path dir = temporaryFolder.newFolder("collisions").toPath();
        File slash = PipelineRecipeIO.saveToDirectory(recipe("处理/方案"), dir);
        File colon = PipelineRecipeIO.saveToDirectory(recipe("处理:方案"), dir);
        File composed = PipelineRecipeIO.saveToDirectory(recipe("Café"), dir);
        File decomposed = PipelineRecipeIO.saveToDirectory(recipe("Cafe\u0301"), dir);

        assertNotEquals(slash.getName().toLowerCase(Locale.ROOT),
                colon.getName().toLowerCase(Locale.ROOT));
        assertNotEquals(composed.getName().toLowerCase(Locale.ROOT),
                decomposed.getName().toLowerCase(Locale.ROOT));
        assertEquals("Café", PipelineRecipeIO.loadFromFile(composed).getName());
        assertEquals("Cafe\u0301", PipelineRecipeIO.loadFromFile(decomposed).getName());
        assertTrue(decomposed.getName().matches(".*-[2-9][0-9]*\\.json"));
    }

    @Test
    public void currentWriteStoresDisplayNameAndVersionedCanonicalIdentity() throws Exception {
        File file = temporaryFolder.newFile("unicode.json");
        PipelineRecipeIO.saveToFile(recipe("处理方案"), file);

        String json = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        Map<String, Object> root = JsonIO.parseObject(json);
        assertEquals("处理方案", root.get("name"));
        assertEquals(1, ((Number) root.get("identityVersion")).intValue());
        assertTrue(String.valueOf(root.get("canonicalIdentity")).matches("r1\\$[0-9a-f]{64}"));
        assertEquals("处理方案", PipelineRecipeIO.loadFromFile(file).getName());
    }

    @Test
    public void legacyRecipeWithoutIdentityMetadataStillLoads() throws Exception {
        File file = temporaryFolder.newFile("legacy.json");
        PipelineRecipe legacy = recipe("旧配方");
        Files.write(file.toPath(), legacy.toJson().getBytes(StandardCharsets.UTF_8));
        assertEquals("旧配方", PipelineRecipeIO.loadFromFile(file).getName());
    }

    @Test
    public void boundedLoadPreservesNearLimitUnicodeLosslessly() throws Exception {
        File file = temporaryFolder.newFile("bounded-unicode.json");
        PipelineRecipe expected = recipe(repeat("\u8111", 128));
        PipelineRecipeIO.saveToFile(expected, file);
        byte[] json = Files.readAllBytes(file.toPath());

        PipelineRecipe loaded = PipelineRecipeIO.loadFromFile(file,
                limits(json.length, json.length, 32, 1000, 128, 100, 32));

        assertEquals(expected.getName(), loaded.getName());
    }

    @Test
    public void boundedLoadRejectsEveryResourceDimensionWithoutMutatingStateOrFile()
            throws Exception {
        File file = temporaryFolder.newFile("bounded-rejections.json");
        PipelineRecipe prior = recipe("Prior recipe");

        assertLimit(file,
                "{\"name\":\"A\",\"analyses\":[]}",
                limits(8, 1000, 32, 100, 100, 100, 32),
                MiniJson.LimitDimension.INPUT_UTF8_BYTES);
        assertLimit(file,
                "{\"name\":\"A\",\"analyses\":[],\"x\":[[[[]]]]}",
                limits(1000, 1000, 3, 100, 100, 100, 32),
                MiniJson.LimitDimension.NESTING_DEPTH);
        assertLimit(file,
                "{\"name\":\"A\",\"analyses\":[],\"x\":0}",
                limits(1000, 1000, 32, 5, 100, 100, 32),
                MiniJson.LimitDimension.TOTAL_NODES);
        assertLimit(file,
                "{\"name\":\"A\",\"analyses\":[],\"x\":\"abcdefghijklmnopq\"}",
                limits(1000, 1000, 32, 100, 16, 100, 32),
                MiniJson.LimitDimension.STRING_CHARACTERS);
        assertLimit(file,
                "{\"name\":\"A\",\"analyses\":[],\"x\":12345678901234567}",
                limits(1000, 1000, 32, 100, 100, 100, 16),
                MiniJson.LimitDimension.NUMBER_CHARACTERS);
        assertMalformedUtf8(file);

        assertEquals("Prior recipe", prior.getName());
        assertEquals(Collections.singletonList("Intensity"), prior.getAnalyses());
    }

    @Test
    public void malformedOrNewerIdentityMetadataIsRejected() throws Exception {
        assertRejected("{\"name\":\"A\",\"identityVersion\":2,"
                + "\"canonicalIdentity\":\"r2$abc\",\"analyses\":[]}");
        assertRejected("{\"name\":\"A\",\"identityVersion\":\"1\","
                + "\"canonicalIdentity\":\"r1$abc\",\"analyses\":[]}");
        assertRejected("{\"name\":\"A\",\"identityVersion\":1,"
                + "\"canonicalIdentity\":\"r1$wrong\",\"analyses\":[]}");
        assertRejected("{\"name\":\"A\",\"canonicalIdentity\":\"r1$orphan\","
                + "\"analyses\":[]}");
    }

    @Test
    public void windowsCaseEquivalentExistingFilenameIsReused() throws Exception {
        Path dir = temporaryFolder.newFolder("case-insensitive").toPath();
        PipelineRecipe recipe = recipe("Thérapie");
        File original = PipelineRecipeIO.saveToDirectory(recipe, dir);
        Path upper = dir.resolve(original.getName().toUpperCase(Locale.ROOT));
        if (!Files.exists(upper)) {
            Files.move(original.toPath(), upper);
        }

        File resaved = PipelineRecipeIO.saveToDirectory(recipe, dir);
        assertTrue(Files.isSameFile(upper, resaved.toPath()));
        assertEquals(1L, directoryEntries(dir));
    }

    private void assertRejected(String json) throws Exception {
        File file = temporaryFolder.newFile("invalid-" + System.nanoTime() + ".json");
        Files.write(file.toPath(), json.getBytes(StandardCharsets.UTF_8));
        try {
            PipelineRecipeIO.loadFromFile(file);
        } catch (IOException expected) {
            return;
        }
        throw new AssertionError("Expected malformed recipe identity metadata to be rejected.");
    }

    private static PipelineRecipe recipe(String name) {
        return new PipelineRecipe(name, "Unicode identity test", PipelineRecipe.CURRENT_FLASH_VERSION,
                Collections.singletonList("Intensity"), Collections.<String, String>emptyMap());
    }

    private static long directoryEntries(Path dir) throws IOException {
        try (Stream<Path> entries = Files.list(dir)) {
            return entries.count();
        }
    }

    private static void assertLimit(File file,
                                    String json,
                                    MiniJson.Limits limits,
                                    MiniJson.LimitDimension dimension) throws Exception {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        Files.write(file.toPath(), bytes);
        try {
            PipelineRecipeIO.loadFromFile(file, limits);
            fail("Expected " + dimension + " rejection.");
        } catch (MiniJson.LimitExceededException expected) {
            assertEquals(dimension, expected.getDimension());
            assertTrue(expected.getSource().contains(file.getName()));
        }
        assertArrayEquals(bytes, Files.readAllBytes(file.toPath()));
    }

    private static void assertMalformedUtf8(File file) throws Exception {
        byte[] bytes = new byte[]{'{', '"', 'x', '"', ':', '"', (byte) 0xc3, '"', '}'};
        Files.write(file.toPath(), bytes);
        try {
            PipelineRecipeIO.loadFromFile(file, MiniJson.DEFAULT_LIMITS);
            fail("Expected malformed UTF-8 rejection.");
        } catch (MiniJson.LimitExceededException wrongFailure) {
            fail("Malformed UTF-8 must not be reported as a resource limit.");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("Malformed UTF-8"));
        }
        assertArrayEquals(bytes, Files.readAllBytes(file.toPath()));
    }

    private static MiniJson.Limits limits(long bytes,
                                          int characters,
                                          int depth,
                                          long nodes,
                                          int stringCharacters,
                                          int collectionSize,
                                          int numberCharacters) {
        return new MiniJson.Limits(bytes, characters, depth, nodes,
                stringCharacters, collectionSize, numberCharacters);
    }

    private static String repeat(String value, int count) {
        StringBuilder out = new StringBuilder(value.length() * count);
        for (int i = 0; i < count; i++) out.append(value);
        return out.toString();
    }
}
