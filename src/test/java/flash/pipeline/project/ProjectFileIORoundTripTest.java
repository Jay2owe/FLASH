package flash.pipeline.project;

import flash.pipeline.intelligence.MiniJson;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class ProjectFileIORoundTripTest {
    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void writeReadRoundTripPreservesAllFields() throws Exception {
        File settingsDir = temp.newFolder("settings");

        ProjectFile project = sampleProject();
        project.extras.put("futureRoot", "keep");
        project.items.get(0).extras.put("futureItem", "keep");

        ProjectFileIO.write(settingsDir, project);
        ProjectFile back = ProjectFileIO.read(settingsDir);

        assertEquals("test-writer", back.writerId);
        assertEquals(123L, back.writtenAtMillis);
        assertEquals("Cohort A", back.name);
        assertEquals("D:/out", back.outputRoot);
        assertEquals(1, back.items.size());
        assertEquals("X.lif", back.items.get(0).path);
        assertEquals("WT", back.items.get(0).condition);
        assertEquals("keep", back.extras.get("futureRoot"));
        assertEquals("keep", back.items.get(0).extras.get("futureItem"));
    }

    @Test
    public void writeIsAtomicTmpFileDoesNotRemain() throws Exception {
        File settingsDir = temp.newFolder("atomic");

        ProjectFileIO.write(settingsDir, sampleProject());

        assertTrue(new File(settingsDir, ProjectFileIO.FILE_NAME).isFile());
        assertFalse(new File(settingsDir, ProjectFileIO.FILE_NAME + ".tmp").exists());
    }

    @Test
    public void writeCreatesMissingSettingsDir() throws Exception {
        File settingsDir = new File(temp.newFolder("created"), "nested/.settings");

        ProjectFileIO.write(settingsDir, sampleProject());

        assertTrue(new File(settingsDir, ProjectFileIO.FILE_NAME).isFile());
    }

    @Test
    public void readMissingReturnsNull() throws Exception {
        assertNull(ProjectFileIO.read(temp.newFolder("missing")));
    }

    @Test
    public void readCorruptReturnsNullWithoutThrowing() throws Exception {
        File settingsDir = temp.newFolder("corrupt");
        Files.write(new File(settingsDir, ProjectFileIO.FILE_NAME).toPath(),
                "{not json".getBytes(StandardCharsets.UTF_8));

        assertNull(ProjectFileIO.read(settingsDir));
    }

    @Test
    public void boundedReadPreservesNearLimitUnicodeLosslessly() throws Exception {
        File settingsDir = temp.newFolder("bounded-unicode");
        ProjectFile project = sampleProject();
        project.name = repeat("\u8111", 64);
        byte[] json = ProjectFileCodec.encode(project).getBytes(StandardCharsets.UTF_8);
        Files.write(new File(settingsDir, ProjectFileIO.FILE_NAME).toPath(), json);

        ProjectFile loaded = ProjectFileIO.read(settingsDir,
                limits(json.length, json.length, 32, 1000, project.name.length(), 100, 32));

        assertEquals(project.name, loaded.name);
    }

    @Test
    public void boundedReadRejectsEveryResourceDimensionWithoutMutatingStateOrFile()
            throws Exception {
        File settingsDir = temp.newFolder("bounded-rejections");
        ProjectFileIO.write(settingsDir, sampleProject());
        ProjectFile prior = ProjectFileIO.read(settingsDir);

        assertLimit(settingsDir,
                "{\"schemaVersion\":1,\"items\":[]}",
                limits(8, 1000, 32, 100, 100, 100, 32),
                MiniJson.LimitDimension.INPUT_UTF8_BYTES);
        assertLimit(settingsDir,
                "{\"schemaVersion\":1,\"items\":[],\"x\":[[[[]]]]}",
                limits(1000, 1000, 3, 100, 100, 100, 32),
                MiniJson.LimitDimension.NESTING_DEPTH);
        assertLimit(settingsDir,
                "{\"schemaVersion\":1,\"items\":[],\"x\":0}",
                limits(1000, 1000, 32, 5, 100, 100, 32),
                MiniJson.LimitDimension.TOTAL_NODES);
        assertLimit(settingsDir,
                "{\"schemaVersion\":1,\"items\":[],\"x\":\"abcdefghijklmnopq\"}",
                limits(1000, 1000, 32, 100, 16, 100, 32),
                MiniJson.LimitDimension.STRING_CHARACTERS);
        assertLimit(settingsDir,
                "{\"schemaVersion\":1,\"items\":[],\"x\":12345678901234567}",
                limits(1000, 1000, 32, 100, 100, 100, 16),
                MiniJson.LimitDimension.NUMBER_CHARACTERS);
        assertMalformedUtf8(settingsDir);

        assertEquals("Cohort A", prior.name);
        assertEquals("X.lif", prior.items.get(0).path);
    }

    @Test
    public void existsReportsPresence() throws Exception {
        File settingsDir = temp.newFolder("exists");

        assertFalse(ProjectFileIO.exists(settingsDir));
        ProjectFileIO.write(settingsDir, sampleProject());
        assertTrue(ProjectFileIO.exists(settingsDir));
    }

    @Test
    public void deleteRemovesFile() throws Exception {
        File settingsDir = temp.newFolder("delete");
        ProjectFileIO.write(settingsDir, sampleProject());
        assertTrue(ProjectFileIO.exists(settingsDir));

        ProjectFileIO.delete(settingsDir);

        assertFalse(ProjectFileIO.exists(settingsDir));
    }

    static ProjectFile sampleProject() {
        ProjectFile project = new ProjectFile();
        project.writerId = "test-writer";
        project.writtenAtMillis = 123L;
        project.name = "Cohort A";
        project.outputRoot = "D:/out";

        ProjectFile.Item item = new ProjectFile.Item();
        item.path = "X.lif";
        item.series.addAll(Arrays.asList(Integer.valueOf(0)));
        item.include = true;
        item.animalId = "03";
        item.hemisphere = "LH";
        item.region = "Hb";
        item.condition = "WT";
        item.notes = "";
        project.items.add(item);

        return project;
    }

    private static void assertLimit(File settingsDir,
                                    String json,
                                    MiniJson.Limits limits,
                                    MiniJson.LimitDimension dimension) throws Exception {
        File file = new File(settingsDir, ProjectFileIO.FILE_NAME);
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        Files.write(file.toPath(), bytes);
        try {
            ProjectFileIO.read(settingsDir, limits);
            fail("Expected " + dimension + " rejection.");
        } catch (MiniJson.LimitExceededException expected) {
            assertEquals(dimension, expected.getDimension());
            assertTrue(expected.getSource().contains(ProjectFileIO.FILE_NAME));
        }
        assertArrayEquals(bytes, Files.readAllBytes(file.toPath()));
    }

    private static void assertMalformedUtf8(File settingsDir) throws Exception {
        File file = new File(settingsDir, ProjectFileIO.FILE_NAME);
        byte[] bytes = new byte[]{'{', '"', 'x', '"', ':', '"', (byte) 0xc3, '"', '}'};
        Files.write(file.toPath(), bytes);
        try {
            ProjectFileIO.read(settingsDir, MiniJson.DEFAULT_LIMITS);
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
