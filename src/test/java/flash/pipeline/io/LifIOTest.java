package flash.pipeline.io;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class LifIOTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void listLifFiles_returnsEmptyForEmptyDirectory() throws Exception {
        File dir = temp.newFolder("empty");
        List<File> result = LifIO.listLifFiles(dir.getAbsolutePath());
        assertTrue(result.isEmpty());
    }

    @Test
    public void listLifFiles_returnsSingleFile() throws Exception {
        File dir = temp.newFolder("single");
        new File(dir, "experiment.lif").createNewFile();
        List<File> result = LifIO.listLifFiles(dir.getAbsolutePath());
        assertEquals(1, result.size());
        assertEquals("experiment.lif", result.get(0).getName());
    }

    @Test
    public void listLifFiles_sortsDeterministically() throws Exception {
        File dir = temp.newFolder("multi");
        new File(dir, "Zebra.lif").createNewFile();
        new File(dir, "alpha.lif").createNewFile();
        new File(dir, "Beta.lif").createNewFile();

        List<File> result = LifIO.listLifFiles(dir.getAbsolutePath());
        assertEquals(3, result.size());
        assertEquals("alpha.lif", result.get(0).getName());
        assertEquals("Beta.lif", result.get(1).getName());
        assertEquals("Zebra.lif", result.get(2).getName());
    }

    @Test
    public void listLifFiles_ignoresNonLifFiles() throws Exception {
        File dir = temp.newFolder("mixed");
        new File(dir, "experiment.lif").createNewFile();
        new File(dir, "readme.txt").createNewFile();
        new File(dir, "data.csv").createNewFile();

        List<File> result = LifIO.listLifFiles(dir.getAbsolutePath());
        assertEquals(1, result.size());
        assertEquals("experiment.lif", result.get(0).getName());
    }

    @Test
    public void listLifFiles_ignoresJunkNames() throws Exception {
        File dir = temp.newFolder("junk");
        new File(dir, "alpha.lif").createNewFile();
        new File(dir, "~$alpha.lif").createNewFile();
        new File(dir, "beta conflicted copy.lif").createNewFile();
        new File(dir, ".DS_Store").createNewFile();

        List<File> result = LifIO.listLifFiles(dir.getAbsolutePath());

        assertEquals(1, result.size());
        assertEquals("alpha.lif", result.get(0).getName());
    }

    @Test
    public void listLifFiles_matchesCaseInsensitiveExtensions() throws Exception {
        File dir = temp.newFolder("caseExt");
        new File(dir, "lower.lif").createNewFile();
        new File(dir, "upper.LIF").createNewFile();
        new File(dir, "mixed.Lif").createNewFile();

        List<File> result = LifIO.listLifFiles(dir.getAbsolutePath());
        assertEquals(3, result.size());
    }

    @Test
    public void listLifFiles_ignoresSymlinkedLifFiles() throws Exception {
        File dir = temp.newFolder("symlink-lif");
        File outside = temp.newFile("outside.lif");
        Path link = new File(dir, "linked.lif").toPath();
        try {
            Files.createSymbolicLink(link, outside.toPath());
        } catch (UnsupportedOperationException | SecurityException | java.io.IOException e) {
            return;
        }

        List<File> result = LifIO.listLifFiles(dir.getAbsolutePath());

        assertTrue(result.isEmpty());
    }

    @Test
    public void requireReadableLifFile_rejectsSymlinkedLifFiles() throws Exception {
        File dir = temp.newFolder("symlink-readable-lif");
        File outside = temp.newFile("outside-readable.lif");
        Path link = new File(dir, "linked.lif").toPath();
        try {
            Files.createSymbolicLink(link, outside.toPath());
        } catch (UnsupportedOperationException | SecurityException | java.io.IOException e) {
            return;
        }

        try {
            LifIO.requireReadableLifFile(link.toFile());
            fail("Expected symbolic-link .lif file to be rejected.");
        } catch (java.io.IOException e) {
            assertTrue(e.getMessage().contains("symbolic-link"));
        }
    }

    @Test
    public void requireSingleLifFile_returnsOnlyMatch() throws Exception {
        File dir = temp.newFolder("one");
        File lif = new File(dir, "experiment.lif");
        lif.createNewFile();

        File result = LifIO.requireSingleLifFile(dir.getAbsolutePath());
        assertEquals(lif.getAbsolutePath(), result.getAbsolutePath());
    }

    @Test
    public void requireSingleLifFile_throwsWhenMissing() throws Exception {
        File dir = temp.newFolder("none");
        try {
            LifIO.requireSingleLifFile(dir.getAbsolutePath());
            fail("Expected IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            assertTrue("Message should mention 'No .lif'",
                    e.getMessage().contains("No .lif file found"));
            assertTrue("Message should include the directory",
                    e.getMessage().contains(dir.getAbsolutePath()));
        }
    }

    @Test
    public void requireSingleLifFile_throwsWhenMultipleExist() throws Exception {
        File dir = temp.newFolder("ambiguous");
        new File(dir, "alpha.lif").createNewFile();
        new File(dir, "beta.lif").createNewFile();

        try {
            LifIO.requireSingleLifFile(dir.getAbsolutePath());
            fail("Expected IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            assertTrue("Message should mention 'Multiple'",
                    e.getMessage().contains("Multiple .lif files found"));
            assertTrue("Message should list alpha.lif",
                    e.getMessage().contains("alpha.lif"));
            assertTrue("Message should list beta.lif",
                    e.getMessage().contains("beta.lif"));
            // filenames should appear in sorted order
            int alphaPos = e.getMessage().indexOf("alpha.lif");
            int betaPos = e.getMessage().indexOf("beta.lif");
            assertTrue("alpha.lif should appear before beta.lif",
                    alphaPos < betaPos);
        }
    }
}
