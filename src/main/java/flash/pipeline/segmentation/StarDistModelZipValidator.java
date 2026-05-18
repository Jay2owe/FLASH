package flash.pipeline.segmentation;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Shared validation for imported and locally trained StarDist model archives.
 */
public final class StarDistModelZipValidator {
    public static final long MAX_ZIP_BYTES = 2L * 1024L * 1024L * 1024L;
    public static final long MAX_UNCOMPRESSED_BYTES = 4L * 1024L * 1024L * 1024L;
    public static final int MAX_ENTRIES = 20000;

    private StarDistModelZipValidator() {
    }

    public static Scan validate(Path file, String invalidMarkerMessage) throws IOException {
        if (file == null || !Files.isRegularFile(file)) {
            throw new IOException("StarDist model zip does not exist: " + file);
        }
        String name = file.getFileName() == null ? "" : file.getFileName().toString();
        if (!name.toLowerCase(Locale.ROOT).endsWith(".zip")) {
            throw new IOException("StarDist models must be .zip files.");
        }
        long compressedBytes = Files.size(file);
        if (compressedBytes > MAX_ZIP_BYTES) {
            throw new IOException("StarDist model zip is too large: " + compressedBytes
                    + " bytes (max " + MAX_ZIP_BYTES + ").");
        }

        try (ZipFile zip = new ZipFile(file.toFile())) {
            Scan scan = scan(zip);
            if (!scan.hasFileEntry) {
                throw new IOException("StarDist model zip is empty.");
            }
            if (scan.marker == null) {
                throw new IOException(invalidMarkerMessage);
            }
            return scan;
        } catch (IOException e) {
            if (e.getMessage() != null
                    && (e.getMessage().contains("empty")
                    || e.getMessage().contains("too large")
                    || e.getMessage().contains("unsafe")
                    || e.getMessage().equals(invalidMarkerMessage))) {
                throw e;
            }
            throw new IOException("StarDist model zip could not be read: " + e.getMessage(), e);
        }
    }

    private static Scan scan(ZipFile zip) throws IOException {
        boolean hasFileEntry = false;
        boolean hasTopLevelSavedModel = false;
        boolean hasSingleDirectorySavedModel = false;
        boolean hasModelTfSavedModel = false;
        Set<String> configParents = new HashSet<String>();
        Set<String> thresholdsParents = new HashSet<String>();
        long uncompressedBytes = 0L;
        int entriesSeen = 0;

        Enumeration<? extends ZipEntry> entries = zip.entries();
        while (entries.hasMoreElements()) {
            ZipEntry entry = entries.nextElement();
            entriesSeen++;
            if (entriesSeen > MAX_ENTRIES) {
                throw new IOException("StarDist model zip has too many entries: " + entriesSeen
                        + " (max " + MAX_ENTRIES + ").");
            }
            String entryName = normalizedZipEntryName(entry.getName());
            validateSafeEntryName(entryName);
            long size = entry.getSize();
            if (size > 0L) {
                uncompressedBytes += size;
                if (uncompressedBytes > MAX_UNCOMPRESSED_BYTES) {
                    throw new IOException("StarDist model zip expands too large: "
                            + uncompressedBytes + " bytes (max "
                            + MAX_UNCOMPRESSED_BYTES + ").");
                }
            }
            if (entry.isDirectory()) {
                continue;
            }
            if (entryName.isEmpty()) {
                continue;
            }
            hasFileEntry = true;
            if ("saved_model.pb".equals(entryName)) {
                hasTopLevelSavedModel = true;
            }
            if (isSingleDirectorySavedModel(entryName)) {
                hasSingleDirectorySavedModel = true;
            }
            if (isModelTfSavedModel(entryName)) {
                hasModelTfSavedModel = true;
            }
            String fileName = zipFileName(entryName);
            String parent = zipParent(entryName);
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
        return new Scan(hasFileEntry, marker);
    }

    private static String normalizedZipEntryName(String rawName) {
        String name = rawName == null ? "" : rawName.replace('\\', '/').trim();
        while (name.startsWith("./")) {
            name = name.substring(2);
        }
        while (name.indexOf("//") >= 0) {
            name = name.replace("//", "/");
        }
        return name;
    }

    private static void validateSafeEntryName(String name) throws IOException {
        if (name == null || name.isEmpty()) {
            return;
        }
        if (name.startsWith("/") || name.indexOf(':') >= 0) {
            throw new IOException("StarDist model zip has unsafe entry path: " + name);
        }
        String[] parts = name.split("/");
        for (int i = 0; i < parts.length; i++) {
            if ("..".equals(parts[i])) {
                throw new IOException("StarDist model zip has unsafe entry path: " + name);
            }
        }
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

    public static final class Scan {
        public final boolean hasFileEntry;
        public final String marker;

        Scan(boolean hasFileEntry, String marker) {
            this.hasFileEntry = hasFileEntry;
            this.marker = marker;
        }
    }
}
