package flash.pipeline.runrecord;

import flash.pipeline.io.FlashProjectLayout;
import flash.pipeline.roi.RoiIO;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Content identities of project settings and region selections used by a run. */
public final class ConfigurationSnapshot {
    public static final String EXTRA_KEY = "configurationFingerprints";

    private ConfigurationSnapshot() { }

    /** An empty map explicitly records that no selected configuration files existed. */
    public static Map<String, Object> capture(File projectRoot) throws IOException {
        final File root = projectRoot.getCanonicalFile();
        FlashProjectLayout layout = FlashProjectLayout.forDirectory(root.getAbsolutePath());
        List<File> files = new ArrayList<File>();
        collect(layout.configurationWriteDir(), files);
        collect(layout.presetsRoot(), files);
        files.addAll(RoiIO.listRoiZipFiles(root));
        files.addAll(RoiIO.listRoiPropertiesCsvFiles(root));
        addExisting(layout.projectSummaryWriteFile(FlashProjectLayout.CONDITIONS_FILENAME), files);
        addExisting(layout.projectSummaryWriteFile(FlashProjectLayout.ORIENTATION_MANIFEST_FILENAME), files);
        Collections.sort(files, (left, right) -> left.getPath().compareTo(right.getPath()));
        Map<String, Object> fingerprints = new LinkedHashMap<String, Object>();
        for (File file : files) {
            File canonical = file.getCanonicalFile();
            if (!canonical.toPath().startsWith(root.toPath())) {
                throw new IOException("Configuration file is outside the opened project: " + file);
            }
            String relative = root.toPath().relativize(canonical.toPath()).toString()
                    .replace(File.separatorChar, '/');
            InputFingerprinter.FingerprintResult result = InputFingerprinter.fullFingerprint(canonical);
            if (!result.hasValue()) {
                throw new IOException(result.warning);
            }
            if (canonical.length() != result.sizeBytes
                    || canonical.lastModified() != result.lastModifiedMillis) {
                throw new IOException("Configuration changed while being fingerprinted: " + file);
            }
            fingerprints.put(relative, result.value);
        }
        return fingerprints;
    }

    /** Resolve a recorded relative configuration path, rejecting escapes and external links. */
    public static File resolve(File projectRoot, String relative) throws IOException {
        File root = projectRoot.getCanonicalFile();
        File file = new File(root, relative).getCanonicalFile();
        if (!file.toPath().startsWith(root.toPath()) || file.equals(root)) {
            throw new IOException("Recorded configuration path escapes the project: " + relative);
        }
        return file;
    }

    private static void addExisting(File file, List<File> files) {
        if (file.exists()) files.add(file);
    }

    private static void collect(File directory, final List<File> files) throws IOException {
        if (!directory.exists()) return;
        Files.walkFileTree(directory.toPath(), new SimpleFileVisitor<Path>() {
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attributes)
                    throws IOException {
                String name = file.getFileName().toString();
                if (attributes.isRegularFile() && !name.endsWith(".tmp")
                        && !name.endsWith(".lock")) {
                    files.add(file.toFile());
                }
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
