package flash.pipeline.project;

import flash.pipeline.bin.BinConfigIO;
import ij.IJ;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
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
            return ProjectFileCodec.decode(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
        } catch (IOException e) {
            IJ.log("[FLASH] Could not read " + file.getAbsolutePath() + ": " + e.getMessage());
            return null;
        }
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
