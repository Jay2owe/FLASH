package flash.pipeline.io;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;

import org.junit.Test;

/**
 * Regression: saving over an online-only cloud-sync file failed with
 * "replacement target is not a regular file" because Java reports Windows
 * cloud placeholders (non-symlink reparse points) as "other".
 */
public class IoUtilsCloudPlaceholderTest {

    @Test
    public void regularFileIsReplaceable() {
        assertTrue(IoUtils.isReplaceableFile(attrs(true, false, false, false)));
    }

    @Test
    public void cloudPlaceholderFileIsReplaceable() {
        assertTrue(IoUtils.isReplaceableFile(attrs(false, false, false, true)));
    }

    @Test
    public void directoryJunctionIsNotReplaceable() {
        assertFalse(IoUtils.isReplaceableFile(attrs(false, true, false, true)));
    }

    @Test
    public void directoryIsNotReplaceable() {
        assertFalse(IoUtils.isReplaceableFile(attrs(false, true, false, false)));
    }

    @Test
    public void symbolicLinkIsNotReplaceable() {
        assertFalse(IoUtils.isReplaceableFile(attrs(false, false, true, false)));
    }

    private static BasicFileAttributes attrs(final boolean regular, final boolean directory,
                                             final boolean symlink, final boolean other) {
        return new BasicFileAttributes() {
            public FileTime lastModifiedTime() { return FileTime.fromMillis(0L); }
            public FileTime lastAccessTime() { return FileTime.fromMillis(0L); }
            public FileTime creationTime() { return FileTime.fromMillis(0L); }
            public boolean isRegularFile() { return regular; }
            public boolean isDirectory() { return directory; }
            public boolean isSymbolicLink() { return symlink; }
            public boolean isOther() { return other; }
            public long size() { return 0L; }
            public Object fileKey() { return null; }
        };
    }
}
