package flash.pipeline.io;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class LooseTiffRelocatorTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void shouldPrompt_returnsFalseWhenMarkerExists() throws Exception {
        File dir = temp.newFolder("marker");

        assertTrue(LooseTiffRelocator.shouldPrompt(dir.getAbsolutePath()));

        File marker = new File(dir, LooseTiffRelocator.NO_PROMPT_MARKER);
        assertTrue(marker.createNewFile());

        assertFalse(LooseTiffRelocator.shouldPrompt(dir.getAbsolutePath()));
    }

    @Test
    public void shouldPrompt_returnsFalseWhenFlashStatusMarkerExists() throws Exception {
        File dir = temp.newFolder("marker-new");
        File marker = FlashProjectLayout.forDirectory(dir.getAbsolutePath())
                .statusWriteFile(LooseTiffRelocator.NO_PROMPT_MARKER);
        assertTrue(marker.getParentFile().mkdirs());
        assertTrue(marker.createNewFile());

        assertFalse(LooseTiffRelocator.shouldPrompt(dir.getAbsolutePath()));
    }

    @Test
    public void moveAll_movesFilesIntoInputAndIsIdempotent() throws Exception {
        File dir = temp.newFolder("move-all");
        File alpha = writeBytes(new File(dir, "alpha.tif"), new byte[] {1, 2, 3});
        File beta = writeBytes(new File(dir, "beta.tiff"), new byte[] {4, 5, 6});

        int moved = LooseTiffRelocator.moveAll(
                dir.getAbsolutePath(), Arrays.asList(alpha, beta));

        File input = new File(dir, "input");
        assertEquals(2, moved);
        assertTrue(input.isDirectory());
        assertFalse(alpha.exists());
        assertFalse(beta.exists());
        assertTrue(new File(input, "alpha.tif").isFile());
        assertTrue(new File(input, "beta.tiff").isFile());

        int movedAgain = LooseTiffRelocator.moveAll(
                dir.getAbsolutePath(), Arrays.asList(alpha, beta));

        assertEquals(0, movedAgain);
        assertTrue(new File(input, "alpha.tif").isFile());
        assertTrue(new File(input, "beta.tiff").isFile());
    }

    @Test
    public void moveAll_skipsNameCollisionTargets() throws Exception {
        File dir = temp.newFolder("collision");
        File input = new File(dir, "input");
        assertTrue(input.mkdirs());
        File source = writeBytes(new File(dir, "foo.tif"), new byte[] {1, 2, 3});
        File target = writeBytes(new File(input, "foo.tif"), new byte[] {9, 8, 7});

        int moved = LooseTiffRelocator.moveAll(
                dir.getAbsolutePath(), Collections.singletonList(source));

        assertEquals(0, moved);
        assertTrue(source.isFile());
        assertTrue(target.isFile());
        assertEquals(3, target.length());
        assertEquals(9, firstByte(target));
    }

    @Test
    public void moveAll_continuesAfterOneSourceCannotMove() throws Exception {
        File dir = temp.newFolder("partial-failure");
        File good = writeBytes(new File(dir, "good.tif"), new byte[] {1, 2, 3});
        File missing = new File(dir, "locked-or-missing.tif");
        File alsoGood = writeBytes(new File(dir, "also-good.tif"), new byte[] {4, 5, 6});

        int moved = LooseTiffRelocator.moveAll(
                dir.getAbsolutePath(), Arrays.asList(good, missing, alsoGood));

        File input = new File(dir, "input");
        assertEquals(2, moved);
        assertTrue(new File(input, "good.tif").isFile());
        assertTrue(new File(input, "also-good.tif").isFile());
        assertFalse(new File(input, "locked-or-missing.tif").exists());
    }

    private File writeBytes(File file, byte[] bytes) throws Exception {
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(bytes);
        }
        return file;
    }

    private int firstByte(File file) throws Exception {
        return java.nio.file.Files.readAllBytes(file.toPath())[0] & 0xff;
    }
}
