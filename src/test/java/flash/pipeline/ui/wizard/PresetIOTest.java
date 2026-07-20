package flash.pipeline.ui.wizard;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class PresetIOTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void roundTripSaveLoadAndDelete() throws Exception {
        TestPresetIO io = new TestPresetIO(temp.newFolder("roundtrip"));
        io.resourceFiles = Collections.emptyList();
        TestPreset preset = new TestPreset("My Preset", "desc", "payload", "1");

        io.save(preset);

        assertEquals(preset, io.load("My Preset"));
        assertEquals(1, io.listAll().size());
        assertTrue(new File(io.presetDirectory(), "my_preset.json").isFile());
        io.delete("My Preset");
        assertTrue(jsonFiles(io.presetDirectory()).isEmpty());
    }

    @Test
    public void atomicWriteLeavesOldFileWhenMoveFails() throws Exception {
        TestPresetIO io = new TestPresetIO(temp.newFolder("atomic"));
        io.resourceFiles = Collections.emptyList();
        TestPreset original = new TestPreset("失败保留", null, "old", "1");
        io.save(original);
        File target = jsonFiles(io.presetDirectory()).get(0);
        byte[] before = Files.readAllBytes(target.toPath());
        io.failMove = true;

        try {
            io.save(new TestPreset("失败保留", null, "new", "1"));
        } catch (IOException expected) {
            // expected
        }

        assertArrayEquals(before, Files.readAllBytes(target.toPath()));
        assertEquals(original, io.load("失败保留"));
        File[] tmp = io.presetDirectory().listFiles((dir, name) -> name.endsWith(".tmp"));
        assertTrue(tmp == null || tmp.length == 0);
    }

    @Test
    public void unicodeCasePunctuationReservedAndLongNamesStayDistinct()
            throws Exception {
        TestPresetIO io = new TestPresetIO(temp.newFolder("unicode-distinct"));
        io.resourceFiles = Collections.emptyList();
        String veryLong = repeat("非常长的预设名称", 40);
        List<String> names = Arrays.asList(
                "小胶质细胞",
                "Μικρογλοία",
                "🧠✨",
                "CON",
                "con",
                "A+B",
                "A B",
                "Cafe\u0301",
                "Cafe\u0300",
                veryLong);
        for (int i = 0; i < names.size(); i++) {
            io.save(new TestPreset(names.get(i), null, "payload-" + i, "1"));
        }

        List<TestPreset> listed = io.listAll();
        assertEquals(names.size(), listed.size());
        Set<String> listedNames = new LinkedHashSet<String>();
        for (TestPreset preset : listed) {
            listedNames.add(preset.getName());
        }
        assertEquals(new LinkedHashSet<String>(names), listedNames);

        List<File> files = jsonFiles(io.presetDirectory());
        assertEquals(names.size(), files.size());
        Set<String> windowsNames = new LinkedHashSet<String>();
        for (File file : files) {
            assertTrue("bounded filename expected: " + file.getName(),
                    file.getName().length() < 180);
            assertFalse("Windows reserved device filename escaped",
                    "CON.json".equalsIgnoreCase(file.getName()));
            assertTrue("case-insensitive filenames must remain unique",
                    windowsNames.add(file.getName().toLowerCase(java.util.Locale.ROOT)));
        }
        for (int i = 0; i < names.size(); i++) {
            assertEquals("payload-" + i, io.load(names.get(i)).getPayload());
        }
    }

    @Test
    public void nfcEquivalentSpellingsShareOneDeliberateIdentity()
            throws Exception {
        TestPresetIO io = new TestPresetIO(temp.newFolder("nfc-equivalent"));
        io.resourceFiles = Collections.emptyList();
        String composed = "Café";
        String decomposed = "Cafe\u0301";

        io.save(new TestPreset(composed, null, "first", "1"));
        io.save(new TestPreset(decomposed, null, "second", "1"));

        assertEquals(1, jsonFiles(io.presetDirectory()).size());
        assertEquals(1, io.listAll().size());
        assertEquals("second", io.load(composed).getPayload());
        assertEquals("second", io.load(decomposed).getPayload());
    }

    @Test
    public void legacyFilenameCollisionNeverOverwritesOrDeletesDifferentIdentity()
            throws Exception {
        TestPresetIO io = new TestPresetIO(temp.newFolder("legacy-collision"));
        io.resourceFiles = Collections.emptyList();
        File directory = io.presetDirectory();
        assertTrue(directory.mkdirs());
        File legacy = new File(directory, "preset.json");
        byte[] legacyBytes = presetJson("小胶质细胞", "legacy", "1")
                .getBytes(StandardCharsets.UTF_8);
        Files.write(legacy.toPath(), legacyBytes);

        io.save(new TestPreset("Μικρογλοία", null, "current", "1"));

        assertArrayEquals(legacyBytes, Files.readAllBytes(legacy.toPath()));
        assertEquals(2, io.listAll().size());
        assertEquals("legacy", io.load("小胶质细胞").getPayload());
        assertEquals("current", io.load("Μικρογλοία").getPayload());
        try {
            io.load("preset");
            fail("Lossy legacy token must not select one colliding identity");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("Ambiguous legacy preset token"));
        }

        io.delete("Μικρογλοία");
        assertArrayEquals(legacyBytes, Files.readAllBytes(legacy.toPath()));
        assertEquals("legacy", io.load("小胶质细胞").getPayload());
    }

    @Test
    public void legacySameIdentityRemainsReadableButCurrentSaveWins()
            throws Exception {
        TestPresetIO io = new TestPresetIO(temp.newFolder("legacy-migration"));
        io.resourceFiles = Collections.emptyList();
        File directory = io.presetDirectory();
        assertTrue(directory.mkdirs());
        File legacy = new File(directory, "migration_preset.json");
        byte[] legacyBytes = presetJson("迁移预设", "legacy", "1")
                .getBytes(StandardCharsets.UTF_8);
        Files.write(legacy.toPath(), legacyBytes);
        assertEquals("legacy", io.load("迁移预设").getPayload());

        io.save(new TestPreset("迁移预设", null, "current", "2"));

        assertArrayEquals(legacyBytes, Files.readAllBytes(legacy.toPath()));
        assertEquals(2, jsonFiles(directory).size());
        assertEquals(1, io.listAll().size());
        assertEquals("current", io.load("迁移预设").getPayload());

        io.delete("迁移预设");
        assertTrue(jsonFiles(directory).isEmpty());
        try {
            io.load("迁移预设");
            fail("Deleting an identity must not reveal its legacy duplicate");
        } catch (FileNotFoundException expected) {
            // expected
        }
    }

    @Test
    public void occupiedCanonicalFilenameUsesStableDisambiguatorWithoutOverwrite()
            throws Exception {
        TestPresetIO io = new TestPresetIO(temp.newFolder("digest-collision"));
        io.resourceFiles = Collections.emptyList();
        File directory = io.presetDirectory();
        assertTrue(directory.mkdirs());
        File occupied = new File(directory,
                PresetIO.sanitizeFileToken("Α") + ".json");
        byte[] occupiedBytes = presetJson("Different", "occupied", "1")
                .getBytes(StandardCharsets.UTF_8);
        Files.write(occupied.toPath(), occupiedBytes);

        io.save(new TestPreset("Α", null, "alpha", "1"));

        assertArrayEquals(occupiedBytes, Files.readAllBytes(occupied.toPath()));
        assertTrue(new File(directory,
                PresetIO.sanitizeFileToken("Α") + "-2.json").isFile());
        assertEquals("occupied", io.load("Different").getPayload());
        assertEquals("alpha", io.load("Α").getPayload());
        assertEquals(2, io.listAll().size());
    }

    @Test
    public void missingStockIsRestoredAlongsideCustomPreset() throws Exception {
        TestPresetIO io = new TestPresetIO(temp.newFolder("missing-stock"));
        io.save(new TestPreset("User", null, "custom", "1"));

        List<TestPreset> presets = io.listAll();

        assertEquals(2, presets.size());
        assertEquals("stock", io.load("Stock").getPayload());
        assertEquals("custom", io.load("User").getPayload());
        assertTrue(new File(io.presetDirectory(), "stock.json").isFile());
    }

    @Test
    public void unchangedManagedStockUpgradesToNewerBundledVersion() throws Exception {
        TestPresetIO io = new TestPresetIO(temp.newFolder("upgrade"));
        assertEquals("stock", io.load("Stock").getPayload());

        io.setResource("stock.json", presetJson("Stock", "upgraded", "2"));

        TestPreset upgraded = io.load("Stock");
        assertEquals("upgraded", upgraded.getPayload());
        assertEquals("2", upgraded.getLibraryVersion());
    }

    @Test
    public void modifiedManagedStockRemainsUserOwnedDuringBundledUpgrade() throws Exception {
        TestPresetIO io = new TestPresetIO(temp.newFolder("override"));
        io.load("Stock");
        TestPreset override = new TestPreset("Stock", "mine", "user override", "99");
        io.save(override);
        io.setResource("stock.json", presetJson("Stock", "bundled v2", "2"));

        assertEquals(override, io.load("Stock"));
        assertEquals(1, io.listAll().size());
    }

    @Test
    public void ambiguousLegacyStockFilenameDefaultsToUserOwned() throws Exception {
        TestPresetIO io = new TestPresetIO(temp.newFolder("legacy"));
        File directory = io.presetDirectory();
        assertTrue(directory.mkdirs());
        byte[] legacy = presetJson("Stock", "legacy edit", "77")
                .getBytes(StandardCharsets.UTF_8);
        Files.write(new File(directory, "stock.json").toPath(), legacy);
        io.setResource("stock.json", presetJson("Stock", "bundled v2", "2"));

        assertEquals("legacy edit", io.load("Stock").getPayload());
        assertArrayEquals(legacy, Files.readAllBytes(
                new File(directory, "stock.json").toPath()));
    }

    @Test
    public void customLogicalIdentityIsNotShadowedByMissingStockFile() throws Exception {
        TestPresetIO io = new TestPresetIO(temp.newFolder("logical-override"));
        File directory = io.presetDirectory();
        assertTrue(directory.mkdirs());
        File custom = new File(directory, "my_custom_filename.json");
        byte[] customBytes = presetJson("Stock", "custom identity", "42")
                .getBytes(StandardCharsets.UTF_8);
        Files.write(custom.toPath(), customBytes);

        List<TestPreset> presets = io.listAll();

        assertEquals(1, presets.size());
        assertEquals("custom identity", presets.get(0).getPayload());
        assertFalse(new File(directory, "stock.json").exists());
        assertArrayEquals(customBytes, Files.readAllBytes(custom.toPath()));
    }

    @Test
    public void deletedStockHasDurableTombstoneAcrossVersionAndDisplayNameChange()
            throws Exception {
        TestPresetIO io = new TestPresetIO(temp.newFolder("tombstone"));
        io.load("Stock");

        io.delete("Stock");
        io.setResource("stock.json", presetJson("Renamed stock", "bundled v2", "2"));

        assertTrue(io.listAll().isEmpty());
        assertFalse(new File(io.presetDirectory(), "stock.json").exists());
    }

    @Test
    public void reconciliationFailureRollsBackEveryPresetAndManifestByte() throws Exception {
        TestPresetIO io = new TestPresetIO(temp.newFolder("transaction"));
        io.resourceFiles = Arrays.asList("alpha.json", "beta.json");
        io.setResource("alpha.json", presetJson("Alpha", "alpha-v1", "1"));
        io.setResource("beta.json", presetJson("Beta", "beta-v1", "1"));
        assertEquals(2, io.listAll().size());
        File directory = io.presetDirectory();
        File alpha = new File(directory, "alpha.json");
        File beta = new File(directory, "beta.json");
        File manifest = new File(directory, ".flash-managed-stock");
        byte[] alphaBefore = Files.readAllBytes(alpha.toPath());
        byte[] betaBefore = Files.readAllBytes(beta.toPath());
        byte[] manifestBefore = Files.readAllBytes(manifest.toPath());

        io.setResource("alpha.json", presetJson("Alpha", "alpha-v2", "2"));
        io.setResource("beta.json", presetJson("Beta", "beta-v2", "2"));
        io.replaceCalls = 0;
        io.failOnReplace = 3; // Both stock files moved; fail the manifest publication.
        try {
            io.bootstrapStockPresets();
            fail("Expected reconciliation failure");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("reconciliation failure"));
        }
        io.failOnReplace = -1;

        assertArrayEquals(alphaBefore, Files.readAllBytes(alpha.toPath()));
        assertArrayEquals(betaBefore, Files.readAllBytes(beta.toPath()));
        assertArrayEquals(manifestBefore, Files.readAllBytes(manifest.toPath()));
        File[] staged = directory.listFiles((dir, name) -> name.endsWith(".tmp"));
        assertTrue(staged == null || staged.length == 0);
    }

    @Test
    public void runtimeFailureAfterPartialReconciliationRollsBackEveryByte()
            throws Exception {
        assertPartialReconciliationFailureRollsBack(
                "runtime-reconciliation", new RuntimeException("unchecked hook failure"));
    }

    @Test
    public void fatalFailureAfterPartialReconciliationRollsBackEveryByte()
            throws Exception {
        assertPartialReconciliationFailureRollsBack(
                "fatal-reconciliation", new ThreadDeath());
    }

    @Test
    public void runtimeFailureAfterPresetQuarantineRestoresEveryByte()
            throws Exception {
        assertDeleteFailureRestoresQuarantine(
                "runtime-delete", new RuntimeException("unchecked delete hook failure"));
    }

    @Test
    public void fatalFailureAfterPresetQuarantineRestoresEveryByte()
            throws Exception {
        assertDeleteFailureRestoresQuarantine("fatal-delete", new ThreadDeath());
    }

    private void assertPartialReconciliationFailureRollsBack(
            String folderName, Throwable injectedFailure) throws Exception {
        TestPresetIO io = new TestPresetIO(temp.newFolder(folderName));
        io.resourceFiles = Arrays.asList("alpha.json", "beta.json");
        io.setResource("alpha.json", presetJson("Alpha", "alpha-v1", "1"));
        io.setResource("beta.json", presetJson("Beta", "beta-v1", "1"));
        assertEquals(2, io.listAll().size());
        File directory = io.presetDirectory();
        File alpha = new File(directory, "alpha.json");
        File beta = new File(directory, "beta.json");
        File manifest = new File(directory, ".flash-managed-stock");
        byte[] alphaBefore = Files.readAllBytes(alpha.toPath());
        byte[] betaBefore = Files.readAllBytes(beta.toPath());
        byte[] manifestBefore = Files.readAllBytes(manifest.toPath());

        io.setResource("alpha.json", presetJson("Alpha", "alpha-v2", "2"));
        io.setResource("beta.json", presetJson("Beta", "beta-v2", "2"));
        io.replaceCalls = 0;
        io.failOnReplace = 3; // Both stock files moved; fail before the manifest move.
        io.replaceFailure = injectedFailure;
        Throwable observed = null;
        boolean interruptRestored;
        Thread.currentThread().interrupt();
        try {
            io.bootstrapStockPresets();
        } catch (Throwable failure) {
            observed = failure;
        } finally {
            interruptRestored = Thread.currentThread().isInterrupted();
            Thread.interrupted();
            io.failOnReplace = -1;
            io.replaceFailure = null;
        }

        assertSame(injectedFailure, observed);
        assertTrue("rollback must restore the caller interrupt", interruptRestored);
        assertArrayEquals(alphaBefore, Files.readAllBytes(alpha.toPath()));
        assertArrayEquals(betaBefore, Files.readAllBytes(beta.toPath()));
        assertArrayEquals(manifestBefore, Files.readAllBytes(manifest.toPath()));
        assertNoTransactionTemps(directory);
    }

    private void assertDeleteFailureRestoresQuarantine(
            String folderName, Throwable injectedFailure) throws Exception {
        TestPresetIO io = new TestPresetIO(temp.newFolder(folderName));
        assertEquals("stock", io.load("Stock").getPayload());
        File directory = io.presetDirectory();
        File stock = new File(directory, "stock.json");
        File duplicate = new File(directory, "stock-legacy-copy.json");
        Files.write(duplicate.toPath(), presetJson("Stock", "legacy-copy", "1")
                .getBytes(StandardCharsets.UTF_8));
        File manifest = new File(directory, ".flash-managed-stock");
        byte[] stockBefore = Files.readAllBytes(stock.toPath());
        byte[] duplicateBefore = Files.readAllBytes(duplicate.toPath());
        byte[] manifestBefore = Files.readAllBytes(manifest.toPath());

        io.replaceCalls = 0;
        io.failOnReplace = 1; // All matching JSON files are already quarantined.
        io.replaceFailure = injectedFailure;
        Throwable observed = null;
        boolean interruptRestored;
        Thread.currentThread().interrupt();
        try {
            io.delete("Stock");
        } catch (Throwable failure) {
            observed = failure;
        } finally {
            interruptRestored = Thread.currentThread().isInterrupted();
            Thread.interrupted();
            io.failOnReplace = -1;
            io.replaceFailure = null;
        }

        assertSame(injectedFailure, observed);
        assertTrue("quarantine restore must restore the caller interrupt", interruptRestored);
        assertArrayEquals(stockBefore, Files.readAllBytes(stock.toPath()));
        assertArrayEquals(duplicateBefore, Files.readAllBytes(duplicate.toPath()));
        assertArrayEquals(manifestBefore, Files.readAllBytes(manifest.toPath()));
        assertNoTransactionTemps(directory);
    }

    private static void assertNoTransactionTemps(File directory) {
        File[] staged = directory.listFiles((dir, name) -> name.endsWith(".tmp"));
        assertTrue(staged == null || staged.length == 0);
    }

    private static final class TestPresetIO extends PresetIO<TestPreset> {
        boolean failMove;
        int replaceCalls;
        int failOnReplace = -1;
        Throwable replaceFailure;
        List<String> resourceFiles = new ArrayList<String>(Arrays.asList("stock.json"));
        final Map<String, String> resourcePayloads = new LinkedHashMap<String, String>();

        TestPresetIO(File projectRoot) {
            super(projectRoot);
            setResource("stock.json", presetJson("Stock", "stock", "1"));
        }

        @Override protected String presetDirectoryName() { return "Test Presets"; }
        @Override protected List<String> stockResourceFiles() { return resourceFiles; }
        @Override protected String stockResourceDirectory() { return "test_presets"; }
        @Override protected String stockFamilyKey() { return "test-presets"; }

        void setResource(String name, String payload) {
            resourcePayloads.put(name, payload);
        }

        @Override
        protected InputStream openStockResource(String resourceName) {
            String payload = resourcePayloads.get(resourceName);
            return payload == null ? null : new ByteArrayInputStream(
                    payload.getBytes(StandardCharsets.UTF_8));
        }

        @Override
        protected TestPreset parsePreset(String json) throws IOException {
            Map<String, Object> map = JsonIO.parseObject(json);
            return new TestPreset(JsonIO.stringValue(map.get("name")),
                    JsonIO.stringValue(map.get("description")),
                    JsonIO.stringValue(map.get("payload")),
                    JsonIO.stringValue(map.get("libraryVersion")));
        }

        @Override
        protected void moveAtomically(File source, File target) throws IOException {
            if (failMove) {
                throw new IOException("simulated failure");
            }
            super.moveAtomically(source, target);
        }

        @Override
        protected void beforeAtomicReplace(File source, File target) throws IOException {
            replaceCalls++;
            if (replaceCalls == failOnReplace) {
                Throwable failure = replaceFailure == null
                        ? new IOException("simulated reconciliation failure")
                        : replaceFailure;
                if (failure instanceof IOException) {
                    throw (IOException) failure;
                }
                if (failure instanceof RuntimeException) {
                    throw (RuntimeException) failure;
                }
                if (failure instanceof Error) {
                    throw (Error) failure;
                }
                throw new IOException("unexpected test hook failure", failure);
            }
        }
    }

    private static String presetJson(String name, String payload, String version) {
        return "{\"name\":\"" + name + "\",\"payload\":\"" + payload
                + "\",\"libraryVersion\":\"" + version + "\"}";
    }

    private static List<File> jsonFiles(File directory) {
        File[] files = directory.listFiles((dir, name) ->
                name.toLowerCase(java.util.Locale.ROOT).endsWith(".json"));
        if (files == null) {
            return Collections.emptyList();
        }
        Arrays.sort(files);
        return Arrays.asList(files);
    }

    private static String repeat(String value, int count) {
        StringBuilder out = new StringBuilder(value.length() * count);
        for (int i = 0; i < count; i++) {
            out.append(value);
        }
        return out.toString();
    }

    private static final class TestPreset implements Preset<String> {
        private final String name;
        private final String description;
        private final String payload;
        private final String libraryVersion;

        TestPreset(String name, String description, String payload, String libraryVersion) {
            this.name = name;
            this.description = description;
            this.payload = payload;
            this.libraryVersion = libraryVersion;
        }

        @Override public String getName() { return name; }
        @Override public String getDescription() { return description; }
        @Override public String getPayload() { return payload; }
        @Override public String getLibraryVersion() { return libraryVersion; }

        @Override
        public Map<String, Object> toJsonObject() {
            Map<String, Object> out = new LinkedHashMap<String, Object>();
            out.put("name", name);
            out.put("description", description);
            out.put("payload", payload);
            out.put("libraryVersion", libraryVersion);
            return out;
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof TestPreset)) return false;
            TestPreset that = (TestPreset) other;
            return eq(name, that.name) && eq(description, that.description)
                    && eq(payload, that.payload) && eq(libraryVersion, that.libraryVersion);
        }

        @Override
        public int hashCode() {
            return name == null ? 0 : name.hashCode();
        }

        private boolean eq(Object left, Object right) {
            return left == null ? right == null : left.equals(right);
        }
    }

}
