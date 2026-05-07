package flash.pipeline.orientation;

import flash.pipeline.io.ImageSourceDispatcher;
import flash.pipeline.naming.ImageNameParser;
import flash.pipeline.naming.OrientationManifestRow;

import java.io.File;
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

        private SourceContext(String sourceKind,
                              String containerFile,
                              List<File> tiffFiles,
                              String tiffPrefix) {
            this.sourceKind = trimToEmpty(sourceKind);
            this.containerFile = trimToEmpty(containerFile);
            this.tiffFiles = tiffFiles == null ? Collections.<File>emptyList() : tiffFiles;
            this.tiffPrefix = trimToEmpty(tiffPrefix);
        }

        public static SourceContext resolve(String directory) {
            File dir = new File(directory);
            ImageSourceDispatcher.SourceMode mode = ImageSourceDispatcher.detectMode(directory);
            if (mode == ImageSourceDispatcher.SourceMode.CONTAINER) {
                File container = ImageSourceDispatcher.selectContainer(dir);
                return new SourceContext("CONTAINER", container.getName(), null, "");
            }
            if (mode == ImageSourceDispatcher.SourceMode.TIFF_INPUT_SUBFOLDER) {
                return new SourceContext(
                        "TIFF",
                        "",
                        ImageSourceDispatcher.listTiffs(new File(dir, "input")),
                        "input/");
            }
            return new SourceContext("TIFF", "", ImageSourceDispatcher.listTiffs(dir), "");
        }

        public OrientationImageIdentity identityFor(int zeroBasedSeriesIndex, String imageTitle) {
            int index = zeroBasedSeriesIndex < 0 ? 0 : zeroBasedSeriesIndex;
            String sourceFile = sourceFileFor(index, imageTitle);
            String originalName = trimToEmpty(imageTitle);
            if (originalName.isEmpty()) originalName = sourceFile;
            int oneBasedSeriesIndex = index + 1;
            String imageKey = OrientationManifestRow.buildImageKey(
                    sourceKind, sourceFile, oneBasedSeriesIndex, originalName);
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
                return tiffPrefix + tiffFiles.get(index).getName();
            }
            return trimToEmpty(fallbackName);
        }
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
