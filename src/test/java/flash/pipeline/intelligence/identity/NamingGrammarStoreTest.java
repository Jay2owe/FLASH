package flash.pipeline.intelligence.identity;

import flash.pipeline.intelligence.MiniJson;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** Stage 07b: grammar file store under FLASH/Config/.settings/naming_grammars/. */
public class NamingGrammarStoreTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    private static NamingGrammar sampleGrammar() {
        List<FieldRule> rules = new ArrayList<FieldRule>();
        rules.add(FieldRule.capture(FieldRule.Type.ANIMAL, "", "(?<=_)M\\d+"));
        rules.add(FieldRule.alias(FieldRule.Type.CONDITION, "Genotype",
                Arrays.asList(new ValuePattern("hAPP", Collections.singletonList("hAPP")))));
        return new NamingGrammar("Brancaccio", rules);
    }

    @Test
    public void saveListLoad_roundTrips() throws Exception {
        String dir = temp.newFolder("project").getAbsolutePath();
        NamingGrammarStore.save(dir, sampleGrammar());

        assertTrue(NamingGrammarStore.hasAny(dir));
        assertTrue(NamingGrammarStore.listNames(dir).contains("Brancaccio"));

        NamingGrammar back = NamingGrammarStore.load(dir, "Brancaccio");
        assertEquals("Brancaccio", back.name);
        assertEquals(2, back.rules.size());

        // behaviour survives the round-trip
        PartialIdentity p = new GrammarInterpreter().apply(back, "x_M14_hAPP");
        assertEquals("M14", p.animal().value);
        assertEquals("hAPP", p.conditions().get("genotype").value);
    }

    @Test
    public void writesUnderConfigSettingsNamingGrammars() throws Exception {
        File project = temp.newFolder("project2");
        NamingGrammarStore.save(project.getAbsolutePath(), sampleGrammar());

        File grammarDir = NamingGrammarStore.dir(project.getAbsolutePath());
        assertEquals("naming_grammars", grammarDir.getName());
        assertEquals(".settings", grammarDir.getParentFile().getName());
        assertEquals("Config", grammarDir.getParentFile().getParentFile().getName());
        assertTrue(new File(grammarDir, "Brancaccio.json").isFile());
    }

    @Test
    public void loadIfExists_missingReturnsNull() {
        String dir = temp.getRoot().getAbsolutePath();
        assertNull(NamingGrammarStore.loadIfExists(dir, "nope"));
    }

    @Test
    public void typedLoadDistinguishesAbsentCorruptUnsupportedAndUnsafe() throws Exception {
        String projectDir = temp.newFolder("typed-states").getAbsolutePath();
        assertEquals(NamingGrammarStore.LoadState.ABSENT,
                NamingGrammarStore.loadResult(projectDir, "absent").state);

        File directory = NamingGrammarStore.dir(projectDir);
        assertTrue(directory.mkdirs());
        Files.write(new File(directory, "corrupt.json").toPath(),
                "{".getBytes(StandardCharsets.UTF_8));
        NamingGrammarStore.LoadResult corrupt = NamingGrammarStore.loadResult(projectDir, "corrupt");
        assertEquals(NamingGrammarStore.LoadState.CORRUPT, corrupt.state);
        assertTrue(corrupt.diagnostic.contains("corrupt"));

        Files.write(new File(directory, "future.json").toPath(),
                "{\"schemaVersion\":2,\"name\":\"future\",\"fields\":[]}"
                        .getBytes(StandardCharsets.UTF_8));
        NamingGrammarStore.LoadResult future = NamingGrammarStore.loadResult(projectDir, "future");
        assertEquals(NamingGrammarStore.LoadState.UNSUPPORTED, future.state);
        assertTrue(future.diagnostic.contains("newer"));

        Files.write(new File(directory, "unsafe.json").toPath(),
                ("{\"schemaVersion\":1,\"name\":\"unsafe\",\"fields\":["
                        + "{\"type\":\"ANIMAL\",\"mode\":\"capture\","
                        + "\"pattern\":\"^(a+)+$\"}]}").getBytes(StandardCharsets.UTF_8));
        NamingGrammarStore.LoadResult unsafe = NamingGrammarStore.loadResult(projectDir, "unsafe");
        assertEquals(NamingGrammarStore.LoadState.UNSAFE, unsafe.state);
        assertTrue(unsafe.diagnostic.contains("Unsafe naming pattern"));
    }

    @Test
    public void overwritePublishesVerifiedGenerationAndRetainsExactLastGoodBytes() throws Exception {
        String projectDir = temp.newFolder("rolling-backup").getAbsolutePath();
        NamingGrammarStore.save(projectDir, sampleGrammar());
        File target = new File(NamingGrammarStore.dir(projectDir), "Brancaccio.json");
        byte[] first = Files.readAllBytes(target.toPath());

        NamingGrammar replacement = new NamingGrammar("Brancaccio", Collections.singletonList(
                FieldRule.capture(FieldRule.Type.ANIMAL, "", "(?<=_)N\\d+")));
        NamingGrammarStore.save(projectDir, replacement);

        byte[] second = Files.readAllBytes(target.toPath());
        assertArrayEquals(NamingGrammarCodec.toJson(replacement).getBytes(StandardCharsets.UTF_8), second);
        assertArrayEquals(first, Files.readAllBytes(
                new File(NamingGrammarStore.dir(projectDir), "Brancaccio.json.bak").toPath()));
        assertEquals(second.length, Files.size(target.toPath()));
    }

    @Test
    public void failedReplacementRestoresExactPreviousGeneration() throws Exception {
        String projectDir = temp.newFolder("failed-replace").getAbsolutePath();
        NamingGrammarStore.save(projectDir, sampleGrammar());
        final File target = new File(NamingGrammarStore.dir(projectDir), "Brancaccio.json");
        final byte[] previous = Files.readAllBytes(target.toPath());
        NamingGrammar replacement = new NamingGrammar("Brancaccio", Collections.singletonList(
                FieldRule.capture(FieldRule.Type.ANIMAL, "", "(?<=_)N\\d+")));

        try {
            NamingGrammarStore.save(projectDir, replacement,
                    new NamingGrammarStore.ReplaceOperation() {
                        @Override
                        public void replace(java.nio.file.Path source, java.nio.file.Path destination)
                                throws IOException {
                            Files.write(destination, "partial".getBytes(StandardCharsets.UTF_8));
                            throw new IOException("injected publication failure");
                        }
                    });
            fail("Expected injected publication failure.");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("injected publication failure"));
        }

        assertArrayEquals(previous, Files.readAllBytes(target.toPath()));
        NamingGrammar loaded = NamingGrammarStore.load(projectDir, "Brancaccio");
        assertNotNull(loaded);
        assertEquals("M14", new GrammarInterpreter().apply(loaded, "x_M14").animal().value);
    }

    @Test
    public void unicodeNamesRoundTripThroughPortableFilenameCodec() throws Exception {
        String projectDir = temp.newFolder("unicode-name").getAbsolutePath();
        NamingGrammar unicode = new NamingGrammar("脃/amyloid 🧠",
                Collections.singletonList(FieldRule.capture(
                        FieldRule.Type.ANIMAL, "", "(?<=_)M\\d+")));
        NamingGrammarStore.save(projectDir, unicode);

        assertTrue(NamingGrammarStore.listNames(projectDir).contains("脃/amyloid 🧠"));
        assertEquals("脃/amyloid 🧠",
                NamingGrammarStore.load(projectDir, "脃/amyloid 🧠").name);
    }

    @Test
    public void windowsReservedNameIsEncodedAndRoundTripsLosslessly() throws Exception {
        String projectDir = temp.newFolder("reserved-name").getAbsolutePath();
        NamingGrammar reserved = new NamingGrammar("CON", sampleGrammar().rules);
        NamingGrammarStore.save(projectDir, reserved);

        assertTrue(new File(NamingGrammarStore.dir(projectDir), "%5FCON.json").isFile());
        assertTrue(NamingGrammarStore.listNames(projectDir).contains("CON"));
        assertEquals("CON", NamingGrammarStore.load(projectDir, "CON").name);
    }

    @Test
    public void caseInsensitiveNameCollisionIsRejectedWithoutChangingOriginal() throws Exception {
        String projectDir = temp.newFolder("name-collision").getAbsolutePath();
        NamingGrammar upper = new NamingGrammar("CaseName", sampleGrammar().rules);
        NamingGrammarStore.save(projectDir, upper);
        File original = new File(NamingGrammarStore.dir(projectDir), "CaseName.json");
        byte[] previous = Files.readAllBytes(original.toPath());

        try {
            NamingGrammarStore.save(projectDir,
                    new NamingGrammar("casename", sampleGrammar().rules));
            fail("Expected case-insensitive collision rejection.");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("collides case-insensitively"));
        }
        assertArrayEquals(previous, Files.readAllBytes(original.toPath()));
    }

    @Test
    public void boundedLoadPreservesNearLimitUnicodeLosslessly() throws Exception {
        String projectDir = temp.newFolder("bounded-unicode").getAbsolutePath();
        String pattern = repeat("\u8111", 128);
        NamingGrammar expected = new NamingGrammar("unicode", Collections.singletonList(
                FieldRule.capture(FieldRule.Type.ANIMAL, "", pattern)));
        NamingGrammarStore.save(projectDir, expected);
        File file = new File(NamingGrammarStore.dir(projectDir), "unicode.json");
        byte[] json = Files.readAllBytes(file.toPath());

        NamingGrammar loaded = NamingGrammarStore.load(projectDir, "unicode",
                limits(json.length, json.length, 32, 1000, 128, 100, 32));

        assertEquals(pattern, loaded.rules.get(0).capture.pattern());
    }

    @Test
    public void boundedLoadRejectsEveryResourceDimensionWithoutMutatingStateOrFile()
            throws Exception {
        String projectDir = temp.newFolder("bounded-rejections").getAbsolutePath();
        NamingGrammar prior = sampleGrammar();
        File directory = NamingGrammarStore.dir(projectDir);
        assertTrue(directory.mkdirs());
        File file = new File(directory, "hostile.json");

        assertLimit(projectDir, file,
                "{\"name\":\"A\",\"fields\":[]}",
                limits(8, 1000, 32, 100, 100, 100, 32),
                MiniJson.LimitDimension.INPUT_UTF8_BYTES);
        assertLimit(projectDir, file,
                "{\"name\":\"A\",\"fields\":[],\"x\":[[[[]]]]}",
                limits(1000, 1000, 3, 100, 100, 100, 32),
                MiniJson.LimitDimension.NESTING_DEPTH);
        assertLimit(projectDir, file,
                "{\"name\":\"A\",\"fields\":[],\"x\":0}",
                limits(1000, 1000, 32, 5, 100, 100, 32),
                MiniJson.LimitDimension.TOTAL_NODES);
        assertLimit(projectDir, file,
                "{\"name\":\"A\",\"fields\":[],\"x\":\"abcdefghijklmnopq\"}",
                limits(1000, 1000, 32, 100, 16, 100, 32),
                MiniJson.LimitDimension.STRING_CHARACTERS);
        assertLimit(projectDir, file,
                "{\"name\":\"A\",\"fields\":[],\"x\":12345678901234567}",
                limits(1000, 1000, 32, 100, 100, 100, 16),
                MiniJson.LimitDimension.NUMBER_CHARACTERS);
        assertMalformedUtf8(projectDir, file);

        assertEquals("Brancaccio", prior.name);
        assertEquals(2, prior.rules.size());
    }

    private static void assertLimit(String projectDir,
                                    File file,
                                    String json,
                                    MiniJson.Limits limits,
                                    MiniJson.LimitDimension dimension) throws Exception {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        Files.write(file.toPath(), bytes);
        try {
            NamingGrammarStore.load(projectDir, "hostile", limits);
            fail("Expected " + dimension + " rejection.");
        } catch (MiniJson.LimitExceededException expected) {
            assertEquals(dimension, expected.getDimension());
            assertTrue(expected.getSource().contains(file.getName()));
        }
        assertArrayEquals(bytes, Files.readAllBytes(file.toPath()));
    }

    private static void assertMalformedUtf8(String projectDir, File file) throws Exception {
        byte[] bytes = new byte[]{'{', '"', 'x', '"', ':', '"', (byte) 0xc3, '"', '}'};
        Files.write(file.toPath(), bytes);
        try {
            NamingGrammarStore.load(projectDir, "hostile", MiniJson.DEFAULT_LIMITS);
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
