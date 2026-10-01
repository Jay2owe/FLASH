package flash.pipeline.runrecord;

import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.attribute.FileTime;

import static org.junit.Assert.*;

public class EnvironmentArtifactFingerprintTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @After public void resetCache() {
        EnvironmentSnapshot.clearCacheForTests();
    }

    @Test
    public void jarBytesMatchStandardSha256KnownAnswer() throws Exception {
        File jar = temporary.newFile("plugin.jar");
        Files.write(jar.toPath(), "abc".getBytes(StandardCharsets.UTF_8));
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                EnvironmentSnapshot.computeArtifactFingerprint(jar));
    }

    @Test
    public void directoryFingerprintUsesSortedRelativeNamesAndExactContents() throws Exception {
        File first = temporary.newFolder("first");
        write(first, "nested/z.class", "xyz");
        write(first, "a.class", "ABC");
        File second = temporary.newFolder("second");
        write(second, "a.class", "ABC");
        write(second, "nested/z.class", "xyz");
        String fingerprint = EnvironmentSnapshot.computeArtifactFingerprint(first);
        assertEquals(EnvironmentSnapshot.computeArtifactFingerprint(second), fingerprint);
        // Independently generated with Python hashlib + struct.pack('>Q', length).
        assertEquals("7f8f85aba447ddb7a24a970acd292b22285fa4356b3d20ff54ad335fae22029c", fingerprint);
    }

    @Test
    public void changedBytesOrRelativeNameDistinguishSameVersionBuilds() throws Exception {
        File root = temporary.newFolder("classes");
        write(root, "a.class", "ABC");
        String original = EnvironmentSnapshot.computeArtifactFingerprint(root);
        write(root, "a.class", "ABD");
        assertNotEquals(original, EnvironmentSnapshot.computeArtifactFingerprint(root));
        write(root, "a.class", "ABC");
        Files.move(new File(root, "a.class").toPath(), new File(root, "b.class").toPath());
        assertNotEquals(original, EnvironmentSnapshot.computeArtifactFingerprint(root));
    }

    @Test
    public void timestampsAndAbsoluteDirectoryNamesAreExcluded() throws Exception {
        File root = temporary.newFolder("classes");
        write(root, "a.class", "ABC");
        String original = EnvironmentSnapshot.computeArtifactFingerprint(root);
        Files.setLastModifiedTime(new File(root, "a.class").toPath(), FileTime.fromMillis(1000));
        Files.setLastModifiedTime(root.toPath(), FileTime.fromMillis(2000));
        assertEquals(original, EnvironmentSnapshot.computeArtifactFingerprint(root));
        File copied = temporary.newFolder("different-personal-path");
        write(copied, "a.class", "ABC");
        assertEquals(original, EnvironmentSnapshot.computeArtifactFingerprint(copied));
    }

    @Test
    public void missingArtifactsReturnEmptyInsteadOfInventingIdentity() {
        assertEquals("", EnvironmentSnapshot.computeArtifactFingerprint(null));
        assertEquals("", EnvironmentSnapshot.computeArtifactFingerprint(
                new File(temporary.getRoot(), "missing.jar")));
    }

    @Test
    public void loadedArtifactFingerprintIsCachedHexWithoutPaths() {
        String fingerprint = EnvironmentSnapshot.flashArtifactFingerprint();
        assertTrue(fingerprint.matches("[0-9a-f]{64}"));
        assertSame(fingerprint, EnvironmentSnapshot.flashArtifactFingerprint());
    }

    private static void write(File directory, String name, String value) throws Exception {
        File file = new File(directory, name);
        Files.createDirectories(file.toPath().getParent());
        Files.write(file.toPath(), value.getBytes(StandardCharsets.UTF_8));
    }
}
