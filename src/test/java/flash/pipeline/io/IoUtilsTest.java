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
import static org.junit.Assert.fail;

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

    @Test
    public void commitReplacingSmallFile_movesAndRemovesTempOnHappyPath() throws Exception {
        File source = temp.newFile("config.json.tmp");
        Files.write(source.toPath(), "fresh".getBytes(StandardCharsets.UTF_8));
        File target = new File(temp.getRoot(), "config.json");

        IoUtils.commitReplacingSmallFile(source.toPath(), target.toPath());

        assertEquals("fresh", read(target));
        assertFalse(source.exists());
    }

    @Test
    public void commitReplacingSmallFile_rewritesInPlaceWhenMoveStaysBlocked() throws Exception {
        // Regression guard for the Windows + cloud-sync (Dropbox/OneDrive) lock:
        // a rename blocked for the whole sync must not lose the write.
        File source = temp.newFile("config.json.tmp");
        Files.write(source.toPath(), "recovered".getBytes(StandardCharsets.UTF_8));
        File target = temp.newFile("config.json");
        Files.write(target.toPath(), "old".getBytes(StandardCharsets.UTF_8));

        final int[] moveCalls = new int[] {0};
        IoUtils.commitReplacingSmallFile(source.toPath(), target.toPath(),
                new IoUtils.FileMover() {
                    @Override
                    public void move(Path s, Path t, CopyOption... options) throws IOException {
                        moveCalls[0]++;
                        throw new java.nio.file.AccessDeniedException(s + " -> " + t);
                    }
                },
                new IoUtils.Sleeper() {
                    @Override
                    public void sleep(long millis) {
                    }
                });

        assertEquals("recovered", read(target));
        assertEquals("old", read(new File(temp.getRoot(), "config.json.bak")));
        assertFalse("temp should be cleaned up", source.exists());
        assertTrue("move should have been retried before the in-place fallback", moveCalls[0] >= 2);
    }

    @Test
    public void commitReplacingSmallFile_surfacesFailureWhenInPlaceAlsoFails() throws Exception {
        File source = temp.newFile("config.json.tmp");
        Files.write(source.toPath(), "x".getBytes(StandardCharsets.UTF_8));
        File target = temp.newFolder("config.json"); // directory: in-place write fails too

        try {
            IoUtils.commitReplacingSmallFile(source.toPath(), target.toPath(),
                    new IoUtils.FileMover() {
                        @Override
                        public void move(Path s, Path t, CopyOption... options) throws IOException {
                            throw new IOException("move blocked");
                        }
                    },
                    new IoUtils.Sleeper() {
                        @Override
                        public void sleep(long millis) {
                        }
                    });
            fail("expected IOException when neither move nor in-place write can succeed");
        } catch (IOException expected) {
            assertTrue(expected.getSuppressed().length >= 1);
        }
        assertFalse("temp should be cleaned up", source.exists());
    }

    private static String read(File f) throws IOException {
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
    }
}
