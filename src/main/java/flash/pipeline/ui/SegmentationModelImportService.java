package flash.pipeline.ui;

import flash.pipeline.segmentation.catalog.ModelCatalog;
import flash.pipeline.segmentation.catalog.ModelEntry;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Validation and key-generation logic for project segmentation model imports.
 */
public final class SegmentationModelImportService {
    private static final Logger LOGGER =
            Logger.getLogger(SegmentationModelImportService.class.getName());
    static final String INVALID_STARDIST_ZIP_MESSAGE =
            "Not a StarDist / CSBDeep SavedModel: missing saved_model.pb "
                    + "(or config.json + thresholds.json). Make sure the zip is the output "
                    + "of model.export_TF() or a ZeroCostDL4Mic StarDist 2D notebook.";

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

        StarDistZipScan scan;
        try (ZipFile zip = new ZipFile(file.toFile())) {
            scan = scanStarDistZip(zip);
        } catch (IOException e) {
            throw new IOException("StarDist model zip could not be read: " + e.getMessage(), e);
        }
        if (!scan.hasFileEntry) {
            throw new IOException("StarDist model zip is empty.");
        }
        if (scan.marker == null) {
            throw new IOException(INVALID_STARDIST_ZIP_MESSAGE);
        }
        LOGGER.log(Level.INFO, "Accepted StarDist model zip {0}: matched {1}.",
                new Object[]{file, scan.marker});
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

    private static StarDistZipScan scanStarDistZip(ZipFile zip) {
        boolean hasFileEntry = false;
        boolean hasTopLevelSavedModel = false;
        boolean hasSingleDirectorySavedModel = false;
        boolean hasModelTfSavedModel = false;
        Set<String> configParents = new HashSet<String>();
        Set<String> thresholdsParents = new HashSet<String>();

        Enumeration<? extends ZipEntry> entries = zip.entries();
        while (entries.hasMoreElements()) {
            ZipEntry entry = entries.nextElement();
            if (entry.isDirectory()) {
                continue;
            }
            String name = normalizedZipEntryName(entry.getName());
            if (name.isEmpty()) {
                continue;
            }
            hasFileEntry = true;

            if ("saved_model.pb".equals(name)) {
                hasTopLevelSavedModel = true;
            }
            if (isSingleDirectorySavedModel(name)) {
                hasSingleDirectorySavedModel = true;
            }
            if (isModelTfSavedModel(name)) {
                hasModelTfSavedModel = true;
            }

            String fileName = zipFileName(name);
            String parent = zipParent(name);
            if ("config.json".equals(fileName)) {
                configParents.add(parent);
            } else if ("thresholds.json".equals(fileName)) {
                thresholdsParents.add(parent);
            }
        }

        String marker = null;
        if (hasTopLevelSavedModel) {
            marker = "top-level saved_model.pb";
        } else if (hasModelTfSavedModel) {
            marker = "model.tf SavedModel layout";
        } else if (hasSingleDirectorySavedModel) {
            marker = "single-directory saved_model.pb";
        } else if (hasMatchingCsbDeepMetadata(configParents, thresholdsParents)) {
            marker = "CSBDeep config.json + thresholds.json";
        }
        return new StarDistZipScan(hasFileEntry, marker);
    }

    private static String normalizedZipEntryName(String rawName) {
        String name = rawName == null ? "" : rawName.replace('\\', '/').trim();
        while (name.startsWith("/")) {
            name = name.substring(1);
        }
        while (name.startsWith("./")) {
            name = name.substring(2);
        }
        while (name.indexOf("//") >= 0) {
            name = name.replace("//", "/");
        }
        return name;
    }

    private static boolean isSingleDirectorySavedModel(String name) {
        int firstSlash = name.indexOf('/');
        return firstSlash > 0
                && firstSlash == name.lastIndexOf('/')
                && name.endsWith("/saved_model.pb");
    }

    private static boolean isModelTfSavedModel(String name) {
        return "model.tf/saved_model.pb".equals(name)
                || name.endsWith("/model.tf/saved_model.pb");
    }

    private static boolean hasMatchingCsbDeepMetadata(Set<String> configParents,
                                                      Set<String> thresholdsParents) {
        for (String parent : configParents) {
            if (thresholdsParents.contains(parent)) {
                return true;
            }
        }
        return false;
    }

    private static String zipParent(String name) {
        int slash = name.lastIndexOf('/');
        return slash < 0 ? "" : name.substring(0, slash);
    }

    private static String zipFileName(String name) {
        int slash = name.lastIndexOf('/');
        return slash < 0 ? name : name.substring(slash + 1);
    }

    private static final class StarDistZipScan {
        final boolean hasFileEntry;
        final String marker;

        StarDistZipScan(boolean hasFileEntry, String marker) {
            this.hasFileEntry = hasFileEntry;
            this.marker = marker;
        }
    }
}
