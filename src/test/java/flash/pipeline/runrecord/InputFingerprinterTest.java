package flash.pipeline.runrecord;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

public class InputFingerprinterTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    private File writeFile(String name, int size, byte fill) throws Exception {
        File file = new File(temp.getRoot(), name);
        byte[] data = new byte[size];
        java.util.Arrays.fill(data, fill);
        Files.write(file.toPath(), data);
        return file;
    }

    private static void overwriteByte(File file, long offset, byte value) throws Exception {
        RandomAccessFile raf = new RandomAccessFile(file, "rw");
        try {
            raf.seek(offset);
            raf.writeByte(value);
        } finally {
            raf.close();
        }
    }

    @Test
    public void sameFileSameFastFingerprint() throws Exception {
        File file = writeFile("a.bin", 4096, (byte) 1);
        InputFingerprinter.FingerprintResult a = InputFingerprinter.fastFingerprint(file);
        InputFingerprinter.FingerprintResult b = InputFingerprinter.fastFingerprint(file);
        assertEquals("fast", a.mode);
        assertTrue(a.hasValue());
        assertEquals(a.value, b.value);
        assertEquals(4096L, a.sizeBytes);
    }

    @Test
    public void byteChangeInHeadChangesFastFingerprint() throws Exception {
        File file = writeFile("head.bin", 100_000, (byte) 0);
        long mtime = file.lastModified();
        InputFingerprinter.FingerprintResult before = InputFingerprinter.fastFingerprint(file);

        overwriteByte(file, 1000L, (byte) 0x7F);
        assertTrue(file.setLastModified(mtime)); // isolate the head-content effect from mtime
        InputFingerprinter.FingerprintResult after = InputFingerprinter.fastFingerprint(file);

        assertNotEquals(before.value, after.value);
    }

    @Test
    public void byteChangeBeyondHeadDoesNotChangeFastFingerprint() throws Exception {
        File file = writeFile("tail.bin", 2_000_000, (byte) 0);
        long mtime = file.lastModified();
        InputFingerprinter.FingerprintResult before = InputFingerprinter.fastFingerprint(file);

        overwriteByte(file, 1_000_000L, (byte) 0x7F); // well past the 64 KB head
        assertTrue(file.setLastModified(mtime));       // size unchanged, mtime forced equal
        InputFingerprinter.FingerprintResult after = InputFingerprinter.fastFingerprint(file);

        assertEquals("documented fast-mode limitation: tail change is invisible",
                before.value, after.value);
    }

    @Test
    public void fullFingerprintCatchesTailChange() throws Exception {
        File file = writeFile("full.bin", 2_000_000, (byte) 0);
        long mtime = file.lastModified();
        InputFingerprinter.FingerprintResult before = InputFingerprinter.fullFingerprint(file);

        overwriteByte(file, 1_000_000L, (byte) 0x7F);
        assertTrue(file.setLastModified(mtime));
        InputFingerprinter.FingerprintResult after = InputFingerprinter.fullFingerprint(file);

        assertEquals("full", before.mode);
        assertNotEquals(before.value, after.value);
    }

    @Test
    public void missingFileReturnsEmptyValueAndWarning() {
        File missing = new File(temp.getRoot(), "nope.bin");
        InputFingerprinter.FingerprintResult result = InputFingerprinter.fastFingerprint(missing);
        assertFalse(result.hasValue());
        assertEquals("", result.value);
        assertFalse(result.warning.isEmpty());
    }

    @Test
    public void fingerprintDispatchesByMode() throws Exception {
        File file = writeFile("dispatch.bin", 1024, (byte) 5);
        assertEquals("fast",
                InputFingerprinter.fingerprint(file, InputFingerprinter.FingerprintMode.FAST).mode);
        assertEquals("full",
                InputFingerprinter.fingerprint(file, InputFingerprinter.FingerprintMode.FULL).mode);
    }

    @Test
    public void hashUtf8IsStable() {
        assertEquals(InputFingerprinter.hashUtf8("hello"), InputFingerprinter.hashUtf8("hello"));
        assertNotEquals(InputFingerprinter.hashUtf8("hello"), InputFingerprinter.hashUtf8("world"));
        // sanity: non-empty base64url
        assertTrue(InputFingerprinter.hashUtf8("x").length() > 0);
        assertFalse(new String(InputFingerprinter.hashUtf8("x").getBytes(StandardCharsets.UTF_8))
                .contains("="));
    }
}
