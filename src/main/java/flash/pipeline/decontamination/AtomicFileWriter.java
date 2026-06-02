package flash.pipeline.decontamination;

import java.io.File;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Same-directory temp write followed by rename for crash-safe text outputs.
 */
final class AtomicFileWriter {

    interface WriterAction {
        void write(Writer writer) throws IOException;
    }

    private AtomicFileWriter() {
    }

    static void writeUtf8(File file, WriterAction action) throws IOException {
        if (file == null) {
            throw new IllegalArgumentException("file must not be null");
        }
        if (action == null) {
            throw new IllegalArgumentException("write action must not be null");
        }
        File parent = file.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IOException("Could not create " + parent.getAbsolutePath());
        }

        Path parentPath = parent == null ? new File(".").toPath() : parent.toPath();
        Path temp = Files.createTempFile(parentPath, "." + file.getName() + ".", ".tmp");
        boolean moved = false;
        try {
            try (Writer writer = Files.newBufferedWriter(temp, StandardCharsets.UTF_8)) {
                action.write(writer);
            }
            moveIntoPlace(temp, file.toPath());
            moved = true;
        } finally {
            if (!moved) {
                Files.deleteIfExists(temp);
            }
        }
    }

    private static void moveIntoPlace(Path source, Path target) throws IOException {
        // Retry/backoff move, then in-place rewrite if the destination stays
        // locked against rename (Windows + Dropbox/OneDrive). Safe: text output.
        flash.pipeline.io.IoUtils.commitReplacingSmallFile(source, target);
    }
}
