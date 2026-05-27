package flash.pipeline.io;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.CopyOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class IoUtilsTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void moveReplacing_fallsBackAndRetriesWhenAtomicMoveFails() throws Exception {
        File source = temp.newFile("START_HERE.html.tmp");
        File target = temp.newFile("START_HERE.html");
        Files.write(source.toPath(), "new".getBytes(StandardCharsets.UTF_8));
        Files.write(target.toPath(), "old".getBytes(StandardCharsets.UTF_8));

        final List<List<CopyOption>> moveOptions = new ArrayList<List<CopyOption>>();
        final List<Long> sleeps = new ArrayList<Long>();
        final int[] calls = new int[] {0};

        IoUtils.moveReplacing(source.toPath(), target.toPath(), new IoUtils.FileMover() {
            @Override
            public void move(Path source, Path target, CopyOption... options) throws IOException {
                calls[0]++;
                moveOptions.add(Arrays.asList(options));
                if (calls[0] == 1) {
                    throw new IOException("sync rejected atomic move");
                }
                if (calls[0] == 2) {
                    throw new IOException("target busy");
                }
                Files.move(source, target, options);
            }
        }, new IoUtils.Sleeper() {
            @Override
            public void sleep(long millis) {
                sleeps.add(Long.valueOf(millis));
            }
        });

        String updated = new String(Files.readAllBytes(target.toPath()), StandardCharsets.UTF_8);
        assertEquals("new", updated);
        assertFalse(source.exists());
        assertTrue(moveOptions.get(0).contains(StandardCopyOption.ATOMIC_MOVE));
        assertFalse(moveOptions.get(1).contains(StandardCopyOption.ATOMIC_MOVE));
        assertEquals(Arrays.asList(Long.valueOf(100L)), sleeps);
    }
}
