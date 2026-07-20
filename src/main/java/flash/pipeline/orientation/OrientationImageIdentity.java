package flash.pipeline.orientation;

import flash.pipeline.io.FlashProjectLayout;
import flash.pipeline.io.ImageSourceDispatcher;
import flash.pipeline.naming.ImageNameParser;
import flash.pipeline.naming.OrientationManifestRow;

import java.io.File;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;

/**
 * Source identity used for orientation manifest rows.
 */
public final class OrientationImageIdentity {
    public final String imageKey;
    public final String sourceFile;
    public final int seriesIndex;
    public final String originalName;
    public final String displayName;

    private OrientationImageIdentity(String imageKey,
                                     String sourceFile,
                                     int seriesIndex,
                                     String originalName,
                                     String displayName) {
        this.imageKey = trimToEmpty(imageKey);
        this.sourceFile = trimToEmpty(sourceFile);
        this.seriesIndex = seriesIndex < 1 ? 1 : seriesIndex;
        this.originalName = trimToEmpty(originalName);
        this.displayName = trimToEmpty(displayName);
    }

    public static OrientationImageIdentity fromProjectSeries(
            String directory,
            int zeroBasedSeriesIndex,
            String imageTitle) throws Exception {
        return SourceContext.resolve(directory).identityFor(zeroBasedSeriesIndex, imageTitle);
    }

    public static final class SourceContext {
        private final String sourceKind;
        private final String containerFile;
        private final List<File> tiffFiles;
        private final String tiffPrefix;
        private final File projectRoot;

        private SourceContext(String sourceKind,
                              String containerFile,
                              List<File> tiffFiles,
                              String tiffPrefix,
                              File projectRoot) {
            this.sourceKind = trimToEmpty(sourceKind);
            this.containerFile = trimToEmpty(containerFile);
            this.tiffFiles = tiffFiles == null ? Collections.<File>emptyList() : tiffFiles;
            this.tiffPrefix = trimToEmpty(tiffPrefix);
            this.projectRoot = projectRoot;
        }

        public static SourceContext resolve(String directory) {
            File dir = new File(directory);
            ImageSourceDispatcher.SourceMode mode = ImageSourceDispatcher.detectMode(directory);
            if (mode == ImageSourceDispatcher.SourceMode.CONTAINER) {
                List<File> projectContainers = ImageSourceDispatcher.projectContainerFiles(directory);
                File container;
                if (projectContainers.size() == 1) {
                    container = projectContainers.get(0);
                } else if (projectContainers.size() > 1) {
                    throw new IllegalArgumentException(
                            "Orientation identity cannot be resolved for a project with multiple container files.");
                } else {
                    container = ImageSourceDispatcher.selectContainer(dir);
                }
                return new SourceContext("CONTAINER", container.getName(), null, "", null);
            }
            List<File> projectTiffs = ImageSourceDispatcher.projectTiffFiles(directory);
            if (!projectTiffs.isEmpty()) {
                return new SourceContext("TIFF", "", projectTiffs, "",
                        projectRootForSelection(dir));
            }
            if (mode == ImageSourceDispatcher.SourceMode.TIFF_INPUT_SUBFOLDER) {
                return new SourceContext(
                        "TIFF",
                        "",
                        ImageSourceDispatcher.listTiffs(new File(dir, "input")),
                        "input/",
                        null);
            }
            return new SourceContext("TIFF", "", ImageSourceDispatcher.listTiffs(dir), "", null);
        }

        public OrientationImageIdentity identityFor(int zeroBasedSeriesIndex, String imageTitle) {
            int index = zeroBasedSeriesIndex < 0 ? 0 : zeroBasedSeriesIndex;
            String sourceFile = sourceFileFor(index, imageTitle);
            String originalName = trimToEmpty(imageTitle);
            if (originalName.isEmpty()) originalName = sourceFile;
            String keyName = keyNameFor(sourceFile, originalName);
            int oneBasedSeriesIndex = index + 1;
            String imageKey = OrientationManifestRow.buildImageKey(
                    sourceKind, sourceFile, oneBasedSeriesIndex, keyName);
            return new OrientationImageIdentity(
                    imageKey,
                    sourceFile,
                    oneBasedSeriesIndex,
                    originalName,
                    displayNameFor(originalName));
        }

        public String sourceFileFor(int zeroBasedSeriesIndex, String fallbackName) {
            if ("CONTAINER".equals(sourceKind)) return containerFile;
            int index = zeroBasedSeriesIndex < 0 ? 0 : zeroBasedSeriesIndex;
            if (index < tiffFiles.size()) {
                String relative = relativeProjectPath(tiffFiles.get(index));
                if (!relative.isEmpty()) {
                    return relative;
                }
                return tiffPrefix + tiffFiles.get(index).getName();
            }
            return trimToEmpty(fallbackName);
        }

        private String keyNameFor(String sourceFile, String originalName) {
            if (!"TIFF".equals(sourceKind)) {
                return originalName;
            }
            String keyName = displayNameFor(originalName);
            keyName = ImageNameParser.stripExtension(keyName);
            if (keyName == null || keyName.trim().isEmpty()) {
                keyName = ImageNameParser.stripExtension(sourceFile);
            }
            return trimToEmpty(keyName);
        }

        private String relativeProjectPath(File tiffFile) {
            if (projectRoot == null || tiffFile == null) {
                return "";
            }
            try {
                Path root = projectRoot.getCanonicalFile().toPath();
                Path source = tiffFile.getCanonicalFile().toPath();
                if (source.startsWith(root)) {
                    return root.relativize(source).toString().replace(File.separatorChar, '/');
                }
            } catch (Exception ignored) {
            }
            return "";
        }
    }

    private static File projectRootForSelection(File selected) {
        File dir = selected;
        if (dir != null && dir.isFile()) {
            dir = dir.getParentFile();
        }
        File root = FlashProjectLayout.projectRootForConfigurationDir(dir);
        if (root != null) {
            return root;
        }
        if (dir != null
                && FlashProjectLayout.FLASH_DIR.equals(dir.getName())
                && dir.getParentFile() != null) {
            return dir.getParentFile();
        }
        return dir;
    }

    private static String displayNameFor(String originalName) {
        String display = ImageNameParser.extractBioFormatsSeriesName(originalName);
        if (display == null || display.trim().isEmpty()) display = originalName;
        return trimToEmpty(display);
    }

    private static String trimToEmpty(String value) {
        return value == null ? "" : value.trim();
    }
}
