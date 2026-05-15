package flash.pipeline.ui;

import flash.pipeline.segmentation.catalog.ModelCatalog;
import flash.pipeline.segmentation.catalog.ModelEntry;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Validation and key-generation logic for project segmentation model imports.
 */
public final class SegmentationModelImportService {
    private final Path projectRoot;

    public SegmentationModelImportService(Path projectRoot) {
        if (projectRoot == null) {
            throw new IllegalArgumentException("Project root must not be null.");
        }
        this.projectRoot = projectRoot.toAbsolutePath().normalize();
    }

    public Path validateStarDistZip(Path sourceFile) throws IOException {
        Path file = requireProjectFile(sourceFile, "StarDist model");
        String name = file.getFileName() == null ? "" : file.getFileName().toString();
        if (!name.toLowerCase(Locale.ROOT).endsWith(".zip")) {
            throw new IOException("StarDist models must be .zip files.");
        }

        boolean hasFileEntry = false;
        try (ZipFile zip = new ZipFile(file.toFile())) {
            java.util.Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (!entry.isDirectory()) {
                    hasFileEntry = true;
                    break;
                }
            }
        } catch (IOException e) {
            throw new IOException("StarDist model zip could not be read: " + e.getMessage(), e);
        }
        if (!hasFileEntry) {
            throw new IOException("StarDist model zip is empty.");
        }
        return file;
    }

    public Path validateCellposeModelFile(Path sourceFile) throws IOException {
        return requireProjectFile(sourceFile, "Cellpose model");
    }

    public String validateCellposeRegisteredName(String registeredName) throws IOException {
        String value = registeredName == null ? "" : registeredName.trim();
        if (value.isEmpty()) {
            throw new IOException("Cellpose registered model name must not be empty.");
        }
        if (value.matches(".*\\s+.*")) {
            throw new IOException("Cellpose registered model names cannot contain spaces.");
        }
        return value;
    }

    public String warningForCellposeModelFile(Path sourceFile) {
        if (sourceFile == null || sourceFile.getFileName() == null) {
            return "";
        }
        String fileName = sourceFile.getFileName().toString();
        return fileName.indexOf('.') < 0
                ? "Cellpose model files often have an extensionless name; this file will be accepted as-is."
                : "";
    }

    public String uniqueModelKey(ModelCatalog catalog, ModelEntry.Engine engine, String displayName) {
        String enginePrefix = engine == null ? "model" : engine.jsonValue();
        String slug = slug(displayName);
        if (slug.isEmpty()) {
            slug = "model";
        }
        String base = enginePrefix + "_" + slug;
        String key = base;
        int suffix = 2;
        while (catalog != null && catalog.get(key).isPresent()) {
            key = base + "_" + suffix;
            suffix++;
        }
        return key;
    }

    private Path requireProjectFile(Path sourceFile, String label) throws IOException {
        if (sourceFile == null) {
            throw new IOException(label + " file is required.");
        }
        Path file = sourceFile.isAbsolute()
                ? sourceFile.toAbsolutePath().normalize()
                : projectRoot.resolve(sourceFile).toAbsolutePath().normalize();
        if (!file.startsWith(projectRoot)) {
            throw new IOException(label + " file must be inside the project folder before import: " + file);
        }
        if (!Files.isRegularFile(file)) {
            throw new IOException(label + " file does not exist: " + file);
        }
        if (!Files.isReadable(file)) {
            throw new IOException(label + " file is not readable: " + file);
        }
        return file;
    }

    private static String slug(String value) {
        String input = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        StringBuilder out = new StringBuilder();
        boolean previousSeparator = false;
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) {
                out.append(c);
                previousSeparator = false;
            } else if (!previousSeparator && out.length() > 0) {
                out.append('_');
                previousSeparator = true;
            }
        }
        while (out.length() > 0 && out.charAt(out.length() - 1) == '_') {
            out.deleteCharAt(out.length() - 1);
        }
        return out.toString();
    }
}
