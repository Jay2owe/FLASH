package flash.pipeline.project;

import flash.pipeline.bin.BinConfigIO;
import flash.pipeline.intelligence.MiniJson;
import ij.IJ;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.Arrays;

/**
 * Atomic read/write for {@code project.json}. Mirrors
 * {@link flash.pipeline.bin.ChannelConfigIO}: settings-dir based,
 * atomic temp-and-move via {@link BinConfigIO#writeAtomic}, soft-fail on
 * corrupt files so the caller can fall back to legacy folder scans.
 */
public final class ProjectFileIO {
    public static final String FILE_NAME = "project.json";

    private ProjectFileIO() {
    }

    public static void write(File settingsDir, ProjectFile project) throws IOException {
        if (settingsDir == null) {
            throw new IOException("Cannot write project.json without a settings directory.");
        }
        BinConfigIO.writeAtomic(new File(settingsDir, FILE_NAME).toPath(),
                Arrays.asList(ProjectFileCodec.encode(project)));
    }

    public static ProjectFile read(File settingsDir) {
        File file = file(settingsDir);
        if (file == null || !file.isFile()) {
            return null;
        }
        try {
            return read(settingsDir, MiniJson.DEFAULT_LIMITS);
        } catch (IOException e) {
            IJ.log("[FLASH] Could not read " + file.getAbsolutePath() + ": " + e.getMessage());
            return null;
        }
    }

    /** Package-visible bounded-read seam used by persistence contract tests. */
    static ProjectFile read(File settingsDir, MiniJson.Limits limits) throws IOException {
        File file = file(settingsDir);
        if (file == null || !file.isFile()) {
            return null;
        }
        Object parsed;
        try (InputStream input = Files.newInputStream(file.toPath())) {
            parsed = MiniJson.parseUtf8(input, limits, file.getAbsolutePath());
        }
        // ProjectFileCodec owns schema migration. Re-serializing the already
        // admitted tree keeps that single decoder without reopening untrusted bytes.
        return ProjectFileCodec.decode(MiniJson.write(parsed));
    }

    public static boolean exists(File settingsDir) {
        File file = file(settingsDir);
        return file != null && file.isFile();
    }

    public static void delete(File settingsDir) {
        File file = file(settingsDir);
        if (file == null || !file.isFile()) {
            return;
        }
        try {
            Files.deleteIfExists(file.toPath());
        } catch (IOException e) {
            IJ.log("[FLASH] Could not delete " + file.getAbsolutePath() + ": " + e.getMessage());
        }
    }

    private static File file(File settingsDir) {
        return settingsDir == null ? null : new File(settingsDir, FILE_NAME);
    }
}
