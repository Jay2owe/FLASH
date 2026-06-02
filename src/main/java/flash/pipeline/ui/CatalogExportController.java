package flash.pipeline.ui;

import flash.pipeline.segmentation.catalog.ModelCatalog;
import flash.pipeline.segmentation.catalog.ModelCatalogIO;
import flash.pipeline.segmentation.catalog.ModelEntry;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Non-Swing import/export controller for project segmentation model catalogs.
 */
public final class CatalogExportController {
    public static final long MAX_CATALOG_ZIP_BYTES = 512L * 1024L * 1024L;
    public static final long MAX_CATALOG_EXPANDED_BYTES = 2L * 1024L * 1024L * 1024L;
    public static final int MAX_CATALOG_ZIP_ENTRIES = 20000;

    public enum ConflictPolicy {
        KEEP_PROJECT,
        REPLACE_WITH_IMPORTED
    }

    private final Path projectRoot;
    private final long maxZipBytes;
    private final long maxExpandedBytes;
    private final int maxEntries;

    public CatalogExportController(Path projectRoot) {
        this(projectRoot, MAX_CATALOG_ZIP_BYTES, MAX_CATALOG_EXPANDED_BYTES,
                MAX_CATALOG_ZIP_ENTRIES);
    }

    CatalogExportController(Path projectRoot, long maxZipBytes,
                            long maxExpandedBytes, int maxEntries) {
        if (projectRoot == null) {
            throw new IllegalArgumentException("Project root must not be null.");
        }
        this.projectRoot = projectRoot.toAbsolutePath().normalize();
        this.maxZipBytes = Math.max(1L, maxZipBytes);
        this.maxExpandedBytes = Math.max(1L, maxExpandedBytes);
        this.maxEntries = Math.max(1, maxEntries);
    }

    public void exportCatalog(Path destinationZip) throws IOException {
        if (destinationZip == null) {
            throw new IOException("Choose a destination zip file.");
        }
        Path zip = destinationZip.toAbsolutePath().normalize();
        ModelCatalog catalog = ModelCatalogIO.read(projectRoot);
        ModelCatalogIO.writeProject(projectRoot, catalog);
        Path catalogDir = catalog.catalogDirectory().toAbsolutePath().normalize();
        Files.createDirectories(catalogDir);

        Path parent = zip.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path tmp = zip.resolveSibling(zip.getFileName().toString() + ".tmp");
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(tmp))) {
            List<Path> files = listFiles(catalogDir);
            for (Path file : files) {
                String entryName = catalogDir.relativize(file.toAbsolutePath().normalize())
                        .toString().replace('\\', '/');
                out.putNextEntry(new ZipEntry(entryName));
                Files.copy(file, out);
                out.closeEntry();
            }
        }
        // Atomic move with retry/backoff for transient locks (cloud-sync, AV).
        // No in-place fallback: export zips can be large, never read into memory.
        flash.pipeline.io.IoUtils.moveReplacing(tmp, zip);
    }

    public ImportSummary importCatalog(Path sourceZip, ConflictPolicy conflictPolicy)
            throws IOException {
        if (sourceZip == null || !Files.isRegularFile(sourceZip)) {
            throw new IOException("Catalog zip does not exist: " + sourceZip);
        }
        long zipBytes = Files.size(sourceZip);
        if (zipBytes > maxZipBytes) {
            throw new IOException("Catalog zip is too large: " + zipBytes
                    + " bytes (max " + maxZipBytes + ").");
        }
        ConflictPolicy policy = conflictPolicy == null
                ? ConflictPolicy.KEEP_PROJECT : conflictPolicy;
        Path tempRoot = Files.createTempDirectory("flash_catalog_import_");
        try {
            unzipSafely(sourceZip.toAbsolutePath().normalize(), tempRoot,
                    maxExpandedBytes, maxEntries);
            Path importedCatalogFile = findCatalogJson(tempRoot);
            Path importedCatalogDir = importedCatalogFile.getParent();
            List<ModelEntry> importedEntries =
                    ModelCatalogIO.readProjectCatalogFile(importedCatalogFile);
            ModelCatalog current = ModelCatalogIO.read(projectRoot);
            List<ModelEntry> merged = new ArrayList<ModelEntry>(current.all());
            Set<String> keys = new LinkedHashSet<String>();
            for (ModelEntry entry : merged) {
                keys.add(entry.modelKey);
            }

            ImportSummary summary = new ImportSummary();
            for (ModelEntry entry : importedEntries) {
                if (entry == null || entry.isStock()
                        && !ModelCatalogIO.isProjectRegisteredBuiltin(entry)) {
                    summary.skippedCount++;
                    continue;
                }
                boolean conflict = keys.contains(entry.modelKey);
                if (conflict && policy == ConflictPolicy.KEEP_PROJECT) {
                    summary.conflictCount++;
                    summary.skippedCount++;
                    continue;
                }
                if (entry.filePath.isPresent()) {
                    Path source = importedCatalogDir.resolve(entry.filePath.get())
                            .toAbsolutePath().normalize();
                    if (!source.startsWith(importedCatalogDir.toAbsolutePath().normalize())
                            || !Files.isRegularFile(source)) {
                        summary.skippedCount++;
                        continue;
                    }
                    copyImportedFile(source, entry.filePath.get());
                }
                if (conflict) {
                    summary.conflictCount++;
                    removeEntry(merged, entry.modelKey);
                } else {
                    keys.add(entry.modelKey);
                }
                merged.add(entry);
                summary.importedCount++;
            }

            ModelCatalogIO.writeProject(projectRoot, new ModelCatalog(projectRoot, merged));
            return summary;
        } finally {
            deleteRecursively(tempRoot);
        }
    }

    private void copyImportedFile(Path source,
                                  String relativePath) throws IOException {
        Path targetCatalogDir = ModelCatalogIO.catalogDirectory(projectRoot)
                .toAbsolutePath().normalize();
        Path target = targetCatalogDir.resolve(relativePath).normalize();
        if (!target.startsWith(targetCatalogDir)) {
            throw new IOException("Imported model path escapes the catalog directory: " + relativePath);
        }
        Path parent = target.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
    }

    private static List<Path> listFiles(Path root) throws IOException {
        List<Path> out = new ArrayList<Path>();
        if (!Files.exists(root)) {
            return out;
        }
        try (Stream<Path> stream = Files.walk(root)) {
            stream.forEach(path -> {
                if (Files.isRegularFile(path)) {
                    out.add(path);
                }
            });
        }
        Collections.sort(out);
        return out;
    }

    private static void unzipSafely(Path zip, Path targetDir,
                                    long maxExpandedBytes,
                                    int maxEntries) throws IOException {
        Path root = targetDir.toAbsolutePath().normalize();
        long expandedBytes = 0L;
        int entriesSeen = 0;
        try (ZipInputStream in = new ZipInputStream(Files.newInputStream(zip))) {
            ZipEntry entry;
            byte[] buffer = new byte[8192];
            while ((entry = in.getNextEntry()) != null) {
                entriesSeen++;
                if (entriesSeen > maxEntries) {
                    throw new IOException("Catalog zip has too many entries: "
                            + entriesSeen + " (max " + maxEntries + ").");
                }
                String name = entry.getName() == null ? "" : entry.getName().replace('\\', '/');
                while (name.startsWith("/")) {
                    name = name.substring(1);
                }
                if (name.isEmpty()) {
                    continue;
                }
                Path out = root.resolve(name).normalize();
                if (!out.startsWith(root)) {
                    throw new IOException("Unsafe zip entry: " + entry.getName());
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(out);
                    continue;
                }
                Path parent = out.getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                try (OutputStream output = Files.newOutputStream(out)) {
                    int read;
                    while ((read = in.read(buffer)) >= 0) {
                        expandedBytes += read;
                        if (expandedBytes > maxExpandedBytes) {
                            throw new IOException("Catalog zip expands too large: "
                                    + expandedBytes + " bytes (max "
                                    + maxExpandedBytes + ").");
                        }
                        output.write(buffer, 0, read);
                    }
                }
            }
        }
    }

    private static Path findCatalogJson(Path root) throws IOException {
        Path direct = root.resolve(ModelCatalogIO.CATALOG_FILENAME);
        if (Files.isRegularFile(direct)) {
            return direct;
        }
        try (Stream<Path> stream = Files.walk(root)) {
            final List<Path> matches = new ArrayList<Path>();
            stream.forEach(path -> {
                if (Files.isRegularFile(path)
                        && ModelCatalogIO.CATALOG_FILENAME.equals(path.getFileName().toString())) {
                    matches.add(path);
                }
            });
            if (!matches.isEmpty()) {
                Collections.sort(matches);
                return matches.get(0);
            }
        }
        throw new IOException("Catalog zip does not contain " + ModelCatalogIO.CATALOG_FILENAME + ".");
    }

    private static void removeEntry(List<ModelEntry> entries, String modelKey) {
        for (int i = entries.size() - 1; i >= 0; i--) {
            if (modelKey.equals(entries.get(i).modelKey)) {
                entries.remove(i);
            }
        }
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (root == null || !Files.exists(root)) {
            return;
        }
        List<Path> paths = new ArrayList<Path>();
        try (Stream<Path> stream = Files.walk(root)) {
            stream.forEach(paths::add);
        }
        Collections.sort(paths, new Comparator<Path>() {
            @Override
            public int compare(Path left, Path right) {
                int byDepth = Integer.compare(right.getNameCount(), left.getNameCount());
                return byDepth != 0 ? byDepth : right.compareTo(left);
            }
        });
        for (Path path : paths) {
            Files.deleteIfExists(path);
        }
    }

    public static final class ImportSummary {
        public int importedCount;
        public int skippedCount;
        public int conflictCount;

        public String message() {
            return "Imported " + importedCount
                    + (importedCount == 1 ? " model" : " models")
                    + ", skipped " + skippedCount
                    + (conflictCount > 0 ? " (" + conflictCount + " conflict"
                    + (conflictCount == 1 ? "" : "s") + ")" : "")
                    + ".";
        }
    }
}
