package flash.pipeline.io;

import java.io.File;
import java.io.IOException;

/** Filesystem helpers shared across the pipeline. */
public final class IoUtils {

    private IoUtils() {}

    /**
     * Create {@code f} as a directory (and any missing parents). Throws a clear
     * {@link IOException} if the path already exists as a file, or if creation
     * fails for any reason (permissions, cloud-sync conflict, long path,
     * reserved device name, etc.). Silently succeeds if the directory already
     * exists.
     */
    public static void mustMkdirs(File f) throws IOException {
        if (f == null) throw new IOException("null directory");
        if (f.exists()) {
            if (!f.isDirectory()) {
                throw new IOException("path exists but is not a directory: " + f.getAbsolutePath());
            }
            return;
        }
        if (!f.mkdirs() && !f.isDirectory()) {
            throw new IOException("could not create directory: " + f.getAbsolutePath());
        }
    }
}
