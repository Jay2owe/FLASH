package flash.pipeline.intelligence;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertTrue;

public class JunkFileFilterTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void listCleanFiles_preservesDirectoryOrderWhileRemovingJunk() throws Exception {
        File dir = temp.newFolder("clean-scan");
        assertTrue(new File(dir, "alpha.tif").createNewFile());
        assertTrue(new File(dir, ".DS_Store").createNewFile());
        assertTrue(new File(dir, "beta.tif").createNewFile());
        assertTrue(new File(dir, "~$beta.tif").createNewFile());
        assertTrue(new File(dir, "gamma conflicted copy.tif").createNewFile());

        File[] clean = JunkFileFilter.listCleanFiles(dir);

        String[] names = new String[clean.length];
        for (int i = 0; i < clean.length; i++) {
            names[i] = clean[i].getName();
        }
        assertArrayEquals(new String[]{"alpha.tif", "beta.tif"}, names);
    }
}
