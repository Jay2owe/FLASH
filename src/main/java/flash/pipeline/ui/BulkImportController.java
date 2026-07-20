package flash.pipeline.ui;

import flash.pipeline.segmentation.catalog.ModelCatalog;
import flash.pipeline.segmentation.catalog.ModelCatalogIO;
import flash.pipeline.segmentation.catalog.ModelEntry;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Non-Swing bulk importer for segmentation model files.
 */
public final class BulkImportController {
    private final Path projectRoot;
    private final SegmentationModelImportService importService;
    private ModelCatalog catalog;

    public BulkImportController(Path projectRoot) {
        if (projectRoot == null) {
            throw new IllegalArgumentException("Project root must not be null.");
        }
        this.projectRoot = projectRoot.toAbsolutePath().normalize();
        this.importService = new SegmentationModelImportService(this.projectRoot);
        this.catalog = ModelCatalogIO.read(this.projectRoot);
    }

    public synchronized ModelCatalog catalog() {
        return catalog;
    }

    public synchronized Summary importFolder(Path folder) throws IOException {
        if (folder == null) {
            throw new IOException("Choose a folder to import.");
        }
        Path root = folder.isAbsolute()
                ? folder.toAbsolutePath().normalize()
                : projectRoot.resolve(folder).toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) {
            throw new IOException("Bulk import folder does not exist: " + root);
        }

        Summary summary = new Summary();
        List<Path> files = supportedFiles(root);
        for (Path file : files) {
            importOne(file, summary);
        }
        if (summary.importedCount > 0) {
            ModelCatalogIO.writeProject(projectRoot, catalog);
            catalog = ModelCatalogIO.read(projectRoot);
        }
        return summary;
    }

    private void importOne(Path file, Summary summary) {
        ModelEntry.Engine engine = engineFor(file);
        if (engine == null) {
            return;
        }
        String displayName = baseName(file);
        String key = importService.defaultModelKey(engine, displayName);
        if (catalog.get(key).isPresent()) {
            summary.skipped(file, "duplicate modelKey");
            return;
        }
        try {
            Path validated = engine == ModelEntry.Engine.STARDIST
                    ? importService.validateStarDistZip(file)
                    : importService.validateCellposeModelFile(file);
            ModelEntry entry = new ModelEntry(
                    key,
                    displayName,
                    null,
                    engine,
                    ModelEntry.Source.USER_IMPORTED,
                    null,
                    null,
                    null,
                    null,
                    null,
                    engine == ModelEntry.Engine.STARDIST
                            ? defaultStarDistDefaults()
                            : defaultCellposeDefaults(),
                    importMetadata(engine == ModelEntry.Engine.STARDIST
                            ? "bulk_stardist_file" : "bulk_cellpose_file"),
                    engine == ModelEntry.Engine.CELLPOSE);
            catalog.add(entry, validated);
            summary.imported(file);
        } catch (IOException e) {
            summary.skipped(file, engine == ModelEntry.Engine.STARDIST
                    ? "invalid zip" : "invalid file");
        }
    }

    private static List<Path> supportedFiles(Path folder) throws IOException {
        List<Path> out = new ArrayList<Path>();
        try (Stream<Path> stream = Files.walk(folder)) {
            stream.forEach(path -> {
                if (Files.isRegularFile(path) && engineFor(path) != null) {
                    out.add(path);
                }
            });
        }
        Collections.sort(out);
        return out;
    }

    private static ModelEntry.Engine engineFor(Path file) {
        if (file == null || file.getFileName() == null) {
            return null;
        }
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".zip")) {
            return ModelEntry.Engine.STARDIST;
        }
        if (name.endsWith(".pth") || name.endsWith(".npy") || name.endsWith(".cellpose")) {
            return ModelEntry.Engine.CELLPOSE;
        }
        return null;
    }

    private static String baseName(Path file) {
        if (file == null || file.getFileName() == null) {
            return "Model";
        }
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private static Map<String, Object> defaultStarDistDefaults() {
        Map<String, Object> defaults = new LinkedHashMap<String, Object>();
        defaults.put("probThresh", Double.valueOf(0.5));
        defaults.put("nmsThresh", Double.valueOf(0.3));
        return defaults;
    }

    private static Map<String, Object> defaultCellposeDefaults() {
        Map<String, Object> defaults = new LinkedHashMap<String, Object>();
        defaults.put("diameter", Double.valueOf(30.0));
        defaults.put("flowThreshold", Double.valueOf(0.4));
        defaults.put("cellprobThreshold", Double.valueOf(0.0));
        return defaults;
    }

    private static Map<String, Object> importMetadata(String importKind) {
        Map<String, Object> metadata = new LinkedHashMap<String, Object>();
        metadata.put("importKind", importKind);
        metadata.put("importedAt", Instant.now().toString());
        metadata.put("qualityFlag", "USER_PROVIDED");
        return metadata;
    }

    public static final class Summary {
        public int importedCount;
        public int skippedCount;
        public final Map<String, Integer> skippedByReason =
                new LinkedHashMap<String, Integer>();
        public final List<String> warnings = new ArrayList<String>();

        private void imported(Path file) {
            importedCount++;
        }

        private void skipped(Path file, String reason) {
            skippedCount++;
            String cleanReason = reason == null || reason.trim().isEmpty()
                    ? "skipped" : reason.trim();
            Integer count = skippedByReason.get(cleanReason);
            skippedByReason.put(cleanReason, Integer.valueOf(count == null ? 1 : count + 1));
            warnings.add((file == null ? "<unknown>" : file.toString()) + ": " + cleanReason);
        }

        public String message() {
            StringBuilder sb = new StringBuilder();
            sb.append("Imported ").append(importedCount).append(importedCount == 1 ? " model" : " models");
            sb.append(", skipped ").append(skippedCount);
            if (skippedCount == 1) {
                sb.append(" model");
            }
            if (!skippedByReason.isEmpty()) {
                sb.append(" (");
                boolean first = true;
                for (Map.Entry<String, Integer> entry : skippedByReason.entrySet()) {
                    if (!first) {
                        sb.append(", ");
                    }
                    int count = entry.getValue().intValue();
                    sb.append(count).append(' ').append(entry.getKey());
                    first = false;
                }
                sb.append(")");
            }
            sb.append('.');
            return sb.toString();
        }
    }
}
