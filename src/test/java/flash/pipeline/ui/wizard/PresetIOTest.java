package flash.pipeline.ui.wizard;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class PresetIOTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void roundTripSaveLoadAndDelete() throws Exception {
        TestPresetIO io = new TestPresetIO(temp.newFolder("roundtrip"));
        TestPreset preset = new TestPreset("My Preset", "desc", "payload", "1");

        io.save(preset);

        assertEquals(preset, io.load("my_preset"));
        assertEquals(1, io.listAll().size());
        assertTrue(new File(temp.getRoot(), "roundtrip/FLASH/Presets/Test Presets/my_preset.json").isFile());
        io.delete("My Preset");
        assertFalse(new File(io.presetDirectory(), "my_preset.json").exists());
    }

    @Test
    public void loadFindsLegacyProjectRootPresetFolder() throws Exception {
        File root = temp.newFolder("legacy");
        File legacyDir = new File(root, "Test Presets");
        assertTrue(legacyDir.mkdirs());
        Files.write(new File(legacyDir, "legacy.json").toPath(),
                "{\"name\":\"Legacy\",\"payload\":\"old\",\"libraryVersion\":\"1\"}"
                        .getBytes(StandardCharsets.UTF_8));
        TestPresetIO io = new TestPresetIO(root);

        assertEquals("old", io.load("Legacy").getPayload());
        assertTrue(containsPresetNamed(io.listAll(), "Legacy"));
    }

    @Test
    public void atomicWriteLeavesOldFileWhenMoveFails() throws Exception {
        TestPresetIO io = new TestPresetIO(temp.newFolder("atomic"));
        TestPreset original = new TestPreset("Crash", null, "old", "1");
        io.save(original);
        io.failMove = true;

        try {
            io.save(new TestPreset("Crash", null, "new", "1"));
        } catch (IOException expected) {
            // expected
        }

        assertEquals(original, io.load("Crash"));
        File[] tmp = io.presetDirectory().listFiles((dir, name) -> name.endsWith(".tmp"));
        assertTrue(tmp == null || tmp.length == 0);
    }

    @Test
    public void stockPresetsBootstrapOnlyWhenEmpty() throws Exception {
        TestPresetIO io = new TestPresetIO(temp.newFolder("stock"));
        List<TestPreset> bootstrapped = io.listAll();
        assertEquals(1, bootstrapped.size());
        assertEquals("Stock", bootstrapped.get(0).getName());

        io.save(new TestPreset("User", null, "custom", "1"));
        io.resourcePayload = "{\"name\":\"Changed\",\"payload\":\"changed\",\"libraryVersion\":\"2\"}";
        assertEquals(2, io.listAll().size());
        assertTrue(new File(io.presetDirectory(), "stock.json").isFile());
    }

    private static final class TestPresetIO extends PresetIO<TestPreset> {
        boolean failMove;
        String resourcePayload = "{\"name\":\"Stock\",\"payload\":\"stock\",\"libraryVersion\":\"1\"}";

        TestPresetIO(File projectRoot) {
            super(projectRoot);
        }

        @Override protected String presetDirectoryName() { return "Test Presets"; }
        @Override protected List<String> stockResourceFiles() { return Arrays.asList("stock.json"); }
        @Override protected String stockResourceDirectory() { return "test_presets"; }

        @Override
        protected InputStream openStockResource(String resourceName) {
            return new ByteArrayInputStream(resourcePayload.getBytes(StandardCharsets.UTF_8));
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

    private static boolean containsPresetNamed(List<TestPreset> presets, String name) {
        for (TestPreset preset : presets) {
            if (name.equals(preset.getName())) {
                return true;
            }
        }
        return false;
    }
}
