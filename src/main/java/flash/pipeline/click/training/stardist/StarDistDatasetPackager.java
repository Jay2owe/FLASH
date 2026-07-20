package flash.pipeline.click.training.stardist;

import flash.pipeline.bin.BinConfig;
import flash.pipeline.bin.BinConfigIO;
import flash.pipeline.click.ClickStore;
import flash.pipeline.click.ClicksConfigIO;
import flash.pipeline.click.training.ImagePlusProvider;
import flash.pipeline.io.FlashProjectLayout;
import flash.pipeline.naming.ChannelFilenameCodec;
import flash.pipeline.ui.wizard.JsonIO;
import ij.ImagePlus;
import ij.ImageStack;
import ij.io.FileSaver;
import ij.io.Opener;
import ij.measure.Calibration;
import ij.process.FloatProcessor;
import ij.process.ImageProcessor;
import ij.process.ShortProcessor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Exports click-corrected StarDist labels as a standard 2D training dataset.
 *
 * <p>3D stacks are exported one Z plane at a time. Negative clicks remove the
 * clicked label ID. Positive clicks are retained when that label is already in
 * the StarDist label image; this stage never invents new objects.</p>
 */
public final class StarDistDatasetPackager {
    public static final String RECOMMENDED_NOTEBOOK =
            "https://colab.research.google.com/github/HenriquesLab/ZeroCostDL4Mic/blob/master/"
                    + "Colab_notebooks/StarDist_2D_ZeroCostDL4Mic.ipynb";

    private static final int METADATA_VERSION = 1;
    private static final String TRAINING_DATASETS_DIR = FlashProjectLayout.TRAINING_DATASETS_DIR;
    private static final String ENGINE_DIR = "StarDist";
    private static final String RAW_DIR = "raw";
    private static final String LABELS_DIR = "labels";
    private static final String README_FILENAME = "README.txt";
    private static final String METADATA_FILENAME = "metadata.json";
    static final String SAMPLE_MANIFEST_FILENAME = "sample_manifest.json";
    private static final int SAMPLE_MANIFEST_VERSION = 1;
    private static final int MAX_EXACT_FLOAT_LABEL = 16_777_216;

    /** Typed failure raised before a mismatched training pair can be published. */
    public static final class GeometryMismatchException extends IOException {
        private static final long serialVersionUID = 1L;

        GeometryMismatchException(String message) {
            super(message);
        }
    }

    public static final class PackagingResult {
        public final Path outputDir;
        public final int imagesWritten;
        public final int positiveLabelsRetained;
        public final int negativeLabelsRemoved;
        public final int tileCount;

        public PackagingResult(Path outputDir,
                               int imagesWritten,
                               int positiveLabelsRetained,
                               int negativeLabelsRemoved) {
            this(outputDir, imagesWritten, positiveLabelsRetained,
                    negativeLabelsRemoved, -1);
        }

        public PackagingResult(Path outputDir,
                               int imagesWritten,
                               int positiveLabelsRetained,
                               int negativeLabelsRemoved,
                               int tileCount) {
            this.outputDir = outputDir;
            this.imagesWritten = imagesWritten;
            this.positiveLabelsRetained = positiveLabelsRetained;
            this.negativeLabelsRemoved = negativeLabelsRemoved;
            this.tileCount = tileCount;
        }
    }

    public PackagingResult packageDataset(Path projectRoot,
                                          String sessionName,
                                          int channelOneBased,
                                          ClickStore clickStore,
                                          ImagePlusProvider rawImageProvider,
                                          ImagePlusProvider labelImageProvider) throws IOException {
        return packageDataset(projectRoot, sessionName, channelOneBased, clickStore,
                rawImageProvider, labelImageProvider, 0);
    }

    public PackagingResult packageDataset(Path projectRoot,
                                          String sessionName,
                                          int channelOneBased,
                                          ClickStore clickStore,
                                          ImagePlusProvider rawImageProvider,
                                          ImagePlusProvider labelImageProvider,
                                          int tileSize) throws IOException {
        if (projectRoot == null) {
            throw new IOException("Project root must not be null.");
        }
        if (channelOneBased <= 0) {
            throw new IOException("Channel must be 1-based and positive.");
        }
        if (rawImageProvider == null) {
            throw new IOException("Raw image provider must not be null.");
        }
        if (labelImageProvider == null) {
            throw new IOException("Label image provider must not be null.");
        }
        if (tileSize < 0) {
            throw new IOException("Tile size must be 0 for whole-image export or a positive pixel size.");
        }

        Path root = projectRoot.toAbsolutePath().normalize();
        String safeSessionName = safePathSegment(sessionName, "StarDist dataset");
        Path configurationRoot = FlashProjectLayout.forDirectory(root.toString())
                .visibleConfigurationDir()
                .toPath()
                .toAbsolutePath()
                .normalize();
        Path outputDir = configurationRoot
                .resolve(TRAINING_DATASETS_DIR)
                .resolve(ENGINE_DIR)
                .resolve(safeSessionName)
                .toAbsolutePath()
                .normalize();
        ensureInside(configurationRoot, outputDir);

        Map<String, List<ClickStore.Click>> clicksByImage =
                clicksByImage(clickStore, channelOneBased);
        String channelName = channelName(root, channelOneBased);
        boolean tiled = tileSize > 0;
        List<PreparedImage> preparedImages = prepareImages(clicksByImage, channelOneBased,
                rawImageProvider, labelImageProvider, tiled, tileSize);

        Path tempDir = outputDir.resolveSibling(outputDir.getFileName().toString()
                + ".tmp-" + System.currentTimeMillis() + "-" + Thread.currentThread().getId());

        int originalImagesWritten = 0;
        int trainingImagesWritten = 0;
        int positiveLabelsRetained = 0;
        int negativeLabelsRemoved = 0;
        int tileCount = 0;
        List<SampleProvenance> sampleProvenance = new ArrayList<SampleProvenance>();

        try {
            deleteRecursivelyIfExists(tempDir);
            Path rawDir = tempDir.resolve(RAW_DIR);
            Path labelsDir = tempDir.resolve(LABELS_DIR);
            Files.createDirectories(rawDir);
            Files.createDirectories(labelsDir);

            for (PreparedImage prepared : preparedImages) {
                String imageName = prepared.imageName;
                List<ClickStore.Click> clicks = prepared.clicks;
                ImagePlus rawImage = prepared.rawImage;
                Correction correction = prepared.correction;
                positiveLabelsRetained += correction.positiveLabelsRetained;
                negativeLabelsRemoved += correction.negativeLabelsRemoved;

                if (tiled) {
                    int tilesForImage = writeTilesForImage(imageName, channelOneBased,
                            clicks, rawImage, correction.labels, rawDir, labelsDir, tileSize,
                            safeSessionName, sampleProvenance);
                    tileCount += tilesForImage;
                    trainingImagesWritten += tilesForImage;
                } else {
                    int slices = labelSliceCount(correction.labels);
                    for (int z = 1; z <= slices; z++) {
                        String fileName = outputFileName(imageName, channelOneBased, z);
                        ImagePlus rawSlice = null;
                        ImagePlus labelSlice = null;
                        try {
                            rawSlice = rawSlice(rawImage, channelOneBased, z);
                            labelSlice = labelSlice(correction.labels, z);
                            Path labelPath = labelsDir.resolve(fileName);
                            saveTiff(rawSlice, rawDir.resolve(fileName));
                            saveTiff(labelSlice, labelPath);
                            verifySavedLabelTiff(labelSlice, labelPath, imageName, z);
                            sampleProvenance.add(new SampleProvenance(fileName, imageName,
                                    safeSessionName, z, -1));
                            trainingImagesWritten++;
                        } finally {
                            closeImage(labelSlice);
                            closeImage(rawSlice);
                        }
                    }
                }
                originalImagesWritten++;
            }

            writeReadme(tempDir, channelOneBased, channelName, tiled, tileSize);
            writeSampleManifest(tempDir, safeSessionName, sampleProvenance);
            writeMetadata(tempDir, root, outputDir, channelOneBased, channelName, originalImagesWritten,
                    trainingImagesWritten, positiveLabelsRetained, negativeLabelsRemoved,
                    tiled, tileSize, tileCount);

            replaceDirectory(tempDir, outputDir);
            tempDir = null;
            return new PackagingResult(outputDir, trainingImagesWritten,
                    positiveLabelsRetained, negativeLabelsRemoved, tiled ? tileCount : -1);
        } finally {
            try {
                if (tempDir != null) {
                    deleteRecursivelyIfExists(tempDir);
                }
            } finally {
                closePreparedImages(preparedImages);
            }
        }
    }

    private static List<PreparedImage> prepareImages(
            Map<String, List<ClickStore.Click>> clicksByImage,
            int channelOneBased,
            ImagePlusProvider rawImageProvider,
            ImagePlusProvider labelImageProvider,
            boolean tiled,
            int tileSize) throws IOException {
        List<String> imageNames = new ArrayList<String>(clicksByImage.keySet());
        Collections.sort(imageNames);
        List<PreparedImage> prepared = new ArrayList<PreparedImage>();
        boolean complete = false;
        try {
            for (String imageName : imageNames) {
                List<ClickStore.Click> clicks = clicksByImage.get(imageName);
                if (clicks == null || clicks.isEmpty()) continue;
                ImagePlus rawImage = rawImageProvider.get(imageName);
                ImagePlus labelImage = labelImageProvider.get(imageName);
                requireImage(rawImage, "Raw", imageName);
                requireImage(labelImage, "StarDist label", imageName);
                requireTrainingPairGeometry(rawImage, labelImage, channelOneBased, imageName);
                Correction correction = correctLabels(labelImage, clicks);
                PreparedImage item = new PreparedImage(imageName, clicks, rawImage, correction);
                prepared.add(item);
                if (tiled) {
                    requireTileFits(correction.labels, tileSize, imageName);
                }
            }
            complete = true;
            return prepared;
        } finally {
            if (!complete) closePreparedImages(prepared);
        }
    }

    private static Map<String, List<ClickStore.Click>> clicksByImage(ClickStore clickStore,
                                                                     int channelOneBased)
            throws IOException {
        Map<String, List<ClickStore.Click>> grouped =
                new LinkedHashMap<String, List<ClickStore.Click>>();
        if (clickStore == null || channelOneBased <= 0) {
            return grouped;
        }
        List<ClickStore.Click> clicks = clickStore.forChannel(channelOneBased);
        for (ClickStore.Click click : clicks) {
            if (click == null || click.channelOneBased != channelOneBased) {
                continue;
            }
            if (click.label <= 0 || click.label > MAX_EXACT_FLOAT_LABEL) {
                throw labelFailure("StarDist click for image '" + click.imageName
                        + "' has label " + click.label + "; supported canonical labels are 1.."
                        + MAX_EXACT_FLOAT_LABEL + ".");
            }
            String imageName = click.imageName == null ? "" : click.imageName.trim();
            if (imageName.isEmpty()) {
                continue;
            }
            List<ClickStore.Click> imageClicks = grouped.get(imageName);
            if (imageClicks == null) {
                imageClicks = new ArrayList<ClickStore.Click>();
                grouped.put(imageName, imageClicks);
            }
            imageClicks.add(click);
        }
        return grouped;
    }

    private static Correction correctLabels(ImagePlus labelImage,
                                            List<ClickStore.Click> clicks) throws IOException {
        ImagePlus labels = duplicateCanonicalLabels(labelImage, "corrected-labels");
        boolean complete = false;
        try {
            Set<Integer> positive = new HashSet<Integer>();
            Set<Integer> negative = new HashSet<Integer>();
            for (ClickStore.Click click : clicks) {
                if (click == null || click.label <= 0) {
                    continue;
                }
                Integer label = Integer.valueOf(click.label);
                if (click.verdict == ClickStore.Verdict.POSITIVE) {
                    positive.add(label);
                } else {
                    negative.add(label);
                }
            }

            Set<Integer> presentBefore = labelsPresent(labels);
            Set<Integer> labelsToRemove = new HashSet<Integer>(negative);
            labelsToRemove.removeAll(positive);
            labelsToRemove.retainAll(presentBefore);
            if (!labelsToRemove.isEmpty()) {
                removeLabels(labels, labelsToRemove);
            }

            Set<Integer> presentAfter = labelsPresent(labels);
            int retained = 0;
            for (Integer label : positive) {
                if (presentAfter.contains(label)) {
                    retained++;
                }
            }
            complete = true;
            return new Correction(labels, retained, labelsToRemove.size());
        } finally {
            if (!complete) closeImage(labels);
        }
    }

    private static int writeTilesForImage(String imageName,
                                          int channelOneBased,
                                          List<ClickStore.Click> clicks,
                                          ImagePlus rawImage,
                                          ImagePlus labelImage,
                                          Path rawDir,
                                          Path labelsDir,
                                          int tileSize,
                                          String sessionId,
                                          List<SampleProvenance> provenance) throws IOException {
        List<ClickStore.Click> positiveClicks = positiveClicks(clicks);
        if (positiveClicks.isEmpty()) {
            return 0;
        }
        Set<Integer> positiveLabels = labelsFromClicks(positiveClicks);
        int written = 0;
        int tileIndex = 0;
        for (ClickStore.Click click : positiveClicks) {
            LabelCentroid centroid = centroidForClick(labelImage, click);
            if (centroid == null) {
                continue;
            }

            ImagePlus labelSlice = null;
            ImagePlus rawSlice = null;
            ImagePlus labelTile = null;
            ImagePlus rawTile = null;
            try {
                labelSlice = labelSlice(labelImage, centroid.z);
                rawSlice = rawSlice(rawImage, channelOneBased, centroid.z);
                requireSamePlaneSize(rawSlice, labelSlice, imageName);
                requireTileFits(labelSlice, tileSize, imageName);

                int x = tileOrigin(centroid.x, tileSize, labelSlice.getWidth());
                int y = tileOrigin(centroid.y, tileSize, labelSlice.getHeight());
                labelTile = cropPlane(labelSlice, x, y, tileSize, tileSize,
                        "label-tile", false);
                if (!containsAnyLabel(labelTile.getProcessor(), positiveLabels)) {
                    continue;
                }

                rawTile = cropPlane(rawSlice, x, y, tileSize, tileSize,
                        "raw-tile", true);
                tileIndex++;
                String fileName = tileOutputFileName(imageName, channelOneBased,
                        centroid.z, tileIndex);
                Path labelPath = labelsDir.resolve(fileName);
                saveTiff(rawTile, rawDir.resolve(fileName));
                saveTiff(labelTile, labelPath);
                verifySavedLabelTiff(labelTile, labelPath, imageName, centroid.z);
                provenance.add(new SampleProvenance(fileName, imageName, sessionId,
                        centroid.z, tileIndex));
                written++;
            } finally {
                closeImage(rawTile);
                closeImage(labelTile);
                closeImage(rawSlice);
                closeImage(labelSlice);
            }
        }
        return written;
    }

    private static List<ClickStore.Click> positiveClicks(List<ClickStore.Click> clicks) {
        List<ClickStore.Click> out = new ArrayList<ClickStore.Click>();
        if (clicks == null) {
            return out;
        }
        for (ClickStore.Click click : clicks) {
            if (click != null && click.verdict == ClickStore.Verdict.POSITIVE
                    && click.label > 0) {
                out.add(click);
            }
        }
        Collections.sort(out, new Comparator<ClickStore.Click>() {
            @Override
            public int compare(ClickStore.Click left, ClickStore.Click right) {
                if (left.z != right.z) {
                    return left.z < right.z ? -1 : 1;
                }
                if (left.label != right.label) {
                    return left.label < right.label ? -1 : 1;
                }
                int xCompare = Double.compare(left.x, right.x);
                if (xCompare != 0) {
                    return xCompare;
                }
                return Double.compare(left.y, right.y);
            }
        });
        return out;
    }

    private static Set<Integer> labelsFromClicks(List<ClickStore.Click> clicks) {
        Set<Integer> labels = new HashSet<Integer>();
        if (clicks == null) {
            return labels;
        }
        for (ClickStore.Click click : clicks) {
            if (click != null && click.label > 0) {
                labels.add(Integer.valueOf(click.label));
            }
        }
        return labels;
    }

    private static LabelCentroid centroidForClick(ImagePlus labels,
                                                  ClickStore.Click click) throws IOException {
        if (click == null || click.label <= 0) {
            return null;
        }
        int slices = labelSliceCount(labels);
        if (click.z >= 1 && click.z <= slices) {
            LabelCentroid centroid = centroidForLabelInSlice(labels, click.label, click.z);
            if (centroid != null) {
                return centroid;
            }
        }
        for (int z = 1; z <= slices; z++) {
            if (z == click.z) {
                continue;
            }
            LabelCentroid centroid = centroidForLabelInSlice(labels, click.label, z);
            if (centroid != null) {
                return centroid;
            }
        }
        return null;
    }

    private static LabelCentroid centroidForLabelInSlice(ImagePlus labels,
                                                         int targetLabel,
                                                         int z) throws IOException {
        ImageProcessor ip = labelSlice(labels, z).getProcessor();
        double sumX = 0.0;
        double sumY = 0.0;
        long count = 0L;
        int width = ip.getWidth();
        int height = ip.getHeight();
        for (int y = 0; y < height; y++) {
            int row = y * width;
            for (int x = 0; x < width; x++) {
                int label = checkedLabel(ip.getf(row + x),
                        "StarDist centroid pixel x=" + x + ", y=" + y + ", z=" + z);
                if (label == targetLabel) {
                    sumX += x;
                    sumY += y;
                    count++;
                }
            }
        }
        if (count == 0L) {
            return null;
        }
        return new LabelCentroid(z, sumX / count, sumY / count);
    }

    private static void requireSamePlaneSize(ImagePlus rawSlice,
                                             ImagePlus labelSlice,
                                             String imageName) throws IOException {
        if (rawSlice.getWidth() != labelSlice.getWidth()
                || rawSlice.getHeight() != labelSlice.getHeight()) {
            throw new IOException("Raw and StarDist label image sizes differ for '"
                    + imageName + "'.");
        }
    }

    private static void requireTrainingPairGeometry(ImagePlus rawImage,
                                                    ImagePlus labelImage,
                                                    int channelOneBased,
                                                    String imageName) throws GeometryMismatchException {
        requireConsistentAxes(rawImage, "raw", imageName);
        requireConsistentAxes(labelImage, "label", imageName);
        boolean rawChannelSupported = rawImage.getNChannels() == 1
                || channelOneBased <= rawImage.getNChannels();
        boolean supported = rawImage.getNFrames() == 1
                && labelImage.getNFrames() == 1
                && labelImage.getNChannels() == 1
                && rawChannelSupported;
        boolean registered = rawImage.getWidth() == labelImage.getWidth()
                && rawImage.getHeight() == labelImage.getHeight()
                && rawImage.getNSlices() == labelImage.getNSlices()
                && sameSpatialCalibration(rawImage, labelImage);
        if (!supported || !registered) {
            throw new GeometryMismatchException("StarDist training geometry mismatch for '"
                    + imageName + "': raw " + geometry(rawImage) + ", labels "
                    + geometry(labelImage) + ". Expected identical X/Y/Z and spatial calibration, "
                    + "label C=1, available raw channel C" + channelOneBased
                    + ", and T=1; time must not be flattened into Z.");
        }
    }

    private static void requireConsistentAxes(ImagePlus image,
                                              String role,
                                              String imageName) throws GeometryMismatchException {
        if (image == null || image.getImageStack() == null
                || image.getImageStack().getSize() <= 0) {
            throw new GeometryMismatchException("StarDist " + role + " image for '"
                    + imageName + "' is missing or empty.");
        }
        long planes = (long) image.getNChannels() * (long) image.getNSlices()
                * (long) image.getNFrames();
        if (image.getWidth() <= 0 || image.getHeight() <= 0
                || image.getNChannels() <= 0 || image.getNSlices() <= 0
                || image.getNFrames() <= 0 || planes != image.getStackSize()) {
            throw new GeometryMismatchException("Inconsistent StarDist " + role
                    + " axes for '" + imageName + "': " + geometry(image) + ".");
        }
    }

    private static boolean sameSpatialCalibration(ImagePlus first, ImagePlus second) {
        Calibration left = first == null ? null : first.getCalibration();
        Calibration right = second == null ? null : second.getCalibration();
        if (left == null || right == null) return false;
        return validSpatialCalibration(left) && validSpatialCalibration(right)
                && Double.compare(left.pixelWidth, right.pixelWidth) == 0
                && Double.compare(left.pixelHeight, right.pixelHeight) == 0
                && Double.compare(left.pixelDepth, right.pixelDepth) == 0
                && Double.compare(left.xOrigin, right.xOrigin) == 0
                && Double.compare(left.yOrigin, right.yOrigin) == 0
                && Double.compare(left.zOrigin, right.zOrigin) == 0
                && safeUnit(left).equals(safeUnit(right));
    }

    private static boolean validSpatialCalibration(Calibration calibration) {
        return calibration != null
                && Double.isFinite(calibration.pixelWidth) && calibration.pixelWidth > 0.0d
                && Double.isFinite(calibration.pixelHeight) && calibration.pixelHeight > 0.0d
                && Double.isFinite(calibration.pixelDepth) && calibration.pixelDepth > 0.0d
                && Double.isFinite(calibration.xOrigin)
                && Double.isFinite(calibration.yOrigin)
                && Double.isFinite(calibration.zOrigin);
    }

    private static String safeUnit(Calibration calibration) {
        String unit = calibration == null ? "" : calibration.getUnit();
        return unit == null ? "" : unit;
    }

    private static String geometry(ImagePlus image) {
        if (image == null) return "<null>";
        return "X=" + image.getWidth() + ",Y=" + image.getHeight()
                + ",Z=" + image.getNSlices() + ",C=" + image.getNChannels()
                + ",T=" + image.getNFrames() + ",stack=" + image.getStackSize();
    }

    private static void requireTileFits(ImagePlus image,
                                        int tileSize,
                                        String imageName) throws IOException {
        if (tileSize > image.getWidth() || tileSize > image.getHeight()) {
            throw new IOException("Tile size " + tileSize + " does not fit image '"
                    + imageName + "' (" + image.getWidth() + "x"
                    + image.getHeight() + ").");
        }
    }

    private static int tileOrigin(double centroid, int tileSize, int dimension) {
        int origin = (int) Math.round(centroid - (tileSize / 2.0));
        if (origin < 0) {
            return 0;
        }
        int maxOrigin = dimension - tileSize;
        return origin > maxOrigin ? maxOrigin : origin;
    }

    private static ImagePlus cropPlane(ImagePlus image,
                                       int x,
                                       int y,
                                       int width,
                                       int height,
                                       String title,
                                       boolean forceShort) {
        ImageProcessor processor = image.getProcessor();
        ImageProcessor cropped;
        processor.setRoi(x, y, width, height);
        try {
            cropped = processor.crop();
            if (forceShort) {
                cropped = cropped.convertToShort(false);
            }
        } finally {
            processor.resetRoi();
        }
        ImageStack stack = new ImageStack(width, height);
        stack.addSlice(cropped);
        ImagePlus out = new ImagePlus(title, stack);
        out.setDimensions(1, 1, 1);
        if (image.getCalibration() != null) {
            out.setCalibration(image.getCalibration().copy());
        }
        return out;
    }

    private static boolean containsAnyLabel(ImageProcessor ip,
                                            Set<Integer> labels) throws IOException {
        if (ip == null || labels == null || labels.isEmpty()) {
            return false;
        }
        for (int i = 0; i < ip.getPixelCount(); i++) {
            int label = checkedLabel(ip.getf(i), "StarDist tile pixel " + i);
            if (label > 0 && labels.contains(Integer.valueOf(label))) {
                return true;
            }
        }
        return false;
    }

    private static void removeLabels(ImagePlus labels,
                                     Set<Integer> labelsToRemove) throws IOException {
        ImageStack stack = labels.getImageStack();
        for (int s = 1; s <= stack.getSize(); s++) {
            ImageProcessor ip = stack.getProcessor(s);
            for (int i = 0; i < ip.getPixelCount(); i++) {
                int label = checkedLabel(ip.getf(i),
                        "StarDist correction pixel " + i + " in slice " + s);
                if (label > 0 && labelsToRemove.contains(Integer.valueOf(label))) {
                    ip.setf(i, 0f);
                }
            }
        }
    }

    private static Set<Integer> labelsPresent(ImagePlus image) throws IOException {
        Set<Integer> labels = new HashSet<Integer>();
        if (image == null || image.getImageStack() == null) {
            return labels;
        }
        ImageStack stack = image.getImageStack();
        for (int s = 1; s <= stack.getSize(); s++) {
            ImageProcessor ip = stack.getProcessor(s);
            for (int i = 0; i < ip.getPixelCount(); i++) {
                int label = checkedLabel(ip.getf(i),
                        "StarDist label pixel " + i + " in slice " + s);
                if (label > 0) {
                    labels.add(Integer.valueOf(label));
                }
            }
        }
        return labels;
    }

    private static int checkedLabel(float value, String location) throws IOException {
        if (value == 0.0f) return 0;
        if (!Float.isFinite(value) || value <= 0.0f
                || value > MAX_EXACT_FLOAT_LABEL || value != Math.rint(value)) {
            throw labelFailure(location + " must contain zero or a positive integral label "
                    + "no greater than " + MAX_EXACT_FLOAT_LABEL + "; found " + value + ".");
        }
        return (int) value;
    }

    private static ImagePlus duplicateCanonicalLabels(ImagePlus src,
                                                      String title) throws IOException {
        if (src == null || src.getImageStack() == null || src.getImageStack().getSize() == 0) {
            throw new IOException("Cannot duplicate an empty image.");
        }
        ImageStack in = src.getImageStack();
        int maximum = 0;
        for (int s = 1; s <= in.getSize(); s++) {
            ImageProcessor processor = in.getProcessor(s);
            for (int i = 0; i < processor.getPixelCount(); i++) {
                maximum = Math.max(maximum, checkedLabel(processor.getf(i),
                        "StarDist source pixel " + i + " in slice " + s));
            }
        }
        boolean wide = maximum > 65_535;
        ImageStack out = new ImageStack(src.getWidth(), src.getHeight());
        for (int s = 1; s <= in.getSize(); s++) {
            ImageProcessor source = in.getProcessor(s);
            ImageProcessor target = wide
                    ? new FloatProcessor(src.getWidth(), src.getHeight())
                    : new ShortProcessor(src.getWidth(), src.getHeight());
            for (int i = 0; i < source.getPixelCount(); i++) {
                target.setf(i, checkedLabel(source.getf(i),
                        "StarDist source pixel " + i + " in slice " + s));
            }
            out.addSlice(in.getSliceLabel(s), target);
        }
        ImagePlus dup = new ImagePlus(title, out);
        int channels = Math.max(1, src.getNChannels());
        int slices = Math.max(1, src.getNSlices());
        int frames = Math.max(1, src.getNFrames());
        if (channels * slices * frames == out.getSize()) {
            dup.setDimensions(channels, slices, frames);
            dup.setOpenAsHyperStack(src.isHyperStack());
        } else {
            dup.setDimensions(1, out.getSize(), 1);
        }
        if (src.getCalibration() != null) {
            dup.setCalibration(src.getCalibration().copy());
        }
        return dup;
    }

    private static IOException labelFailure(String message) {
        return new IOException("LABEL_IDENTITY_UNSUPPORTED: " + message
                + " No StarDist training dataset was installed.");
    }

    private static ImagePlus rawSlice(ImagePlus rawImage,
                                      int channelOneBased,
                                      int z) throws IOException {
        int channel = Math.max(1, channelOneBased);
        if (rawImage.getNChannels() < channel) {
            if (rawImage.getNChannels() == 1) {
                channel = 1;
            } else {
                throw new IOException("Raw image '" + rawImage.getTitle()
                        + "' does not contain channel " + channelOneBased + ".");
            }
        }
        return singlePlane(rawImage, channel, z, "raw");
    }

    private static ImagePlus labelSlice(ImagePlus labelImage, int z) throws IOException {
        return singlePlane(labelImage, 1, z, "label", false);
    }

    private static ImagePlus singlePlane(ImagePlus src,
                                         int channel,
                                         int z,
                                         String title) throws IOException {
        return singlePlane(src, channel, z, title, true);
    }

    private static ImagePlus singlePlane(ImagePlus src,
                                         int channel,
                                         int z,
                                         String title,
                                         boolean forceShort) throws IOException {
        if (src == null || src.getImageStack() == null || src.getImageStack().getSize() == 0) {
            throw new IOException("Cannot export an empty " + title + " image.");
        }
        int slices = Math.max(1, src.getNSlices());
        if (z < 1 || z > slices) {
            throw new IOException("Image '" + src.getTitle() + "' has " + slices
                    + " Z slices, cannot export z" + z + ".");
        }
        int stackIndex = src.getStackIndex(channel, z, 1);
        ImageProcessor processor = src.getImageStack().getProcessor(stackIndex).duplicate();
        if (forceShort) {
            processor = processor.convertToShort(false);
        }
        ImageStack stack = new ImageStack(src.getWidth(), src.getHeight());
        stack.addSlice(processor);
        ImagePlus out = new ImagePlus(title, stack);
        out.setDimensions(1, 1, 1);
        if (src.getCalibration() != null) {
            out.setCalibration(src.getCalibration().copy());
        }
        return out;
    }

    private static int labelSliceCount(ImagePlus labelImage) throws IOException {
        if (labelImage == null || labelImage.getImageStack() == null
                || labelImage.getImageStack().getSize() == 0) {
            throw new IOException("StarDist label image is empty.");
        }
        return Math.max(1, labelImage.getNSlices());
    }

    private static void saveTiff(ImagePlus image, Path target) throws IOException {
        Files.createDirectories(target.getParent());
        boolean ok = new FileSaver(image).saveAsTiff(target.toString());
        if (!ok || !Files.isRegularFile(target)) {
            throw new IOException("Failed to write TIFF: " + target);
        }
    }

    private static void verifySavedLabelTiff(ImagePlus intended,
                                             Path path,
                                             String imageName,
                                             int z) throws IOException {
        ImagePlus reopened = new Opener().openImage(path.toString());
        if (reopened == null || reopened.getProcessor() == null) {
            closeImage(reopened);
            throw labelFailure("StarDist label round-trip could not reopen '" + path
                    + "' for image '" + imageName + "', z=" + z + ".");
        }
        try {
            int expectedBitDepth = intended.getProcessor() instanceof FloatProcessor ? 32 : 16;
            if (reopened.getWidth() != intended.getWidth()
                    || reopened.getHeight() != intended.getHeight()
                    || reopened.getStackSize() != intended.getStackSize()
                    || reopened.getBitDepth() != expectedBitDepth) {
                throw labelFailure("StarDist label round-trip changed geometry/type for image '"
                        + imageName + "', z=" + z + ": expected " + intended.getWidth()
                        + "x" + intended.getHeight() + "x" + intended.getStackSize()
                        + " @ " + expectedBitDepth + "-bit, reopened " + reopened.getWidth()
                        + "x" + reopened.getHeight() + "x" + reopened.getStackSize()
                        + " @ " + reopened.getBitDepth() + "-bit.");
            }
            for (int slice = 1; slice <= intended.getStackSize(); slice++) {
                ImageProcessor expected = intended.getStack().getProcessor(slice);
                ImageProcessor actual = reopened.getStack().getProcessor(slice);
                for (int pixel = 0; pixel < expected.getPixelCount(); pixel++) {
                    float expectedValue = expected.getf(pixel);
                    float observed = actual.getf(pixel);
                    if (observed != expectedValue) {
                        throw labelFailure("StarDist label round-trip changed canonical label at "
                                + "image '" + imageName + "', z=" + z + ", slice=" + slice
                                + ", pixel=" + pixel + ": expected " + expectedValue
                                + ", reopened " + observed + ".");
                    }
                }
            }
        } finally {
            closeImage(reopened);
        }
    }

    private static void writeReadme(Path outputDir,
                                    int channelOneBased,
                                    String channelName,
                                    boolean tiled,
                                    int tileSize) throws IOException {
        List<String> lines = new ArrayList<String>();
        lines.add("FLASH StarDist 2D training dataset");
        lines.add("");
        lines.add("Channel: C" + channelOneBased + " (" + channelName + ")");
        lines.add("raw/ contains 16-bit source image slices.");
        lines.add("labels/ contains matching lossless integer StarDist label masks; IDs above "
                + "65,535 use exact 32-bit floating-point TIFF pixels.");
        lines.add("Each 3D stack is exported as independent 2D Z slices.");
        lines.add("");
        lines.add("Recommended notebook:");
        lines.add(RECOMMENDED_NOTEBOOK);
        lines.add("");
        if (tiled) {
            lines.add("This input is pre-tiled into " + tileSize + "x" + tileSize
                    + " pixel crops centered on positive StarDist labels.");
        } else {
            lines.add("This export uses whole-image slices.");
        }
        Files.write(outputDir.resolve(README_FILENAME), lines, StandardCharsets.UTF_8);
    }

    private static void writeMetadata(Path outputDir,
                                      Path projectRoot,
                                      Path finalOutputDir,
                                      int channelOneBased,
                                      String channelName,
                                      int imageCount,
                                      int sliceCount,
                                      int positiveLabelsRetained,
                                      int negativeLabelsRemoved,
                                      boolean tiled,
                                      int tileSize,
                                      int tileCount) throws IOException {
        Map<String, Object> root = JsonIO.object();
        root.put("version", Integer.valueOf(METADATA_VERSION));
        root.put("channel", Integer.valueOf(channelOneBased));
        root.put("channelName", channelName);
        root.put("createdAt", Long.valueOf(System.currentTimeMillis()));
        root.put("imageCount", Integer.valueOf(imageCount));
        root.put("sliceCount", Integer.valueOf(sliceCount));
        Map<String, Object> counts = JsonIO.object();
        counts.put("positive", Integer.valueOf(positiveLabelsRetained));
        counts.put("negative", Integer.valueOf(negativeLabelsRemoved));
        root.put("objectCount", counts);
        root.put("sourceClicksJsonPath", sourceClicksPath(projectRoot, finalOutputDir));
        root.put("recommendedNotebook", RECOMMENDED_NOTEBOOK);
        root.put("tileMode", tiled ? "tiled" : "whole");
        if (tiled) {
            root.put("tileSize", Integer.valueOf(tileSize));
            root.put("tileCount", Integer.valueOf(tileCount));
        }
        String json = JsonIO.write(root) + "\n";
        Files.write(outputDir.resolve(METADATA_FILENAME),
                json.getBytes(StandardCharsets.UTF_8));
    }

    private static void writeSampleManifest(Path outputDir,
                                            String sessionId,
                                            List<SampleProvenance> samples) throws IOException {
        Map<String, Object> root = JsonIO.object();
        root.put("version", Integer.valueOf(SAMPLE_MANIFEST_VERSION));
        root.put("sessionId", sessionId);
        List<Object> records = new ArrayList<Object>();
        for (SampleProvenance sample : samples) {
            Map<String, Object> record = JsonIO.object();
            record.put("sample", sample.fileName);
            record.put("raw", RAW_DIR + "/" + sample.fileName);
            record.put("label", LABELS_DIR + "/" + sample.fileName);
            record.put("sourceImage", sample.sourceImage);
            record.put("sessionId", sample.sessionId);
            record.put("groupId", sourceGroupId(sample.sessionId, sample.sourceImage));
            record.put("z", Integer.valueOf(sample.z));
            if (sample.tileIndex > 0) {
                record.put("tile", Integer.valueOf(sample.tileIndex));
            }
            records.add(record);
        }
        root.put("samples", records);
        Files.write(outputDir.resolve(SAMPLE_MANIFEST_FILENAME),
                (JsonIO.write(root) + "\n").getBytes(StandardCharsets.UTF_8));
    }

    private static String sourceGroupId(String sessionId, String sourceImage) {
        String identity = (sessionId == null ? "" : sessionId) + "\u0000"
                + (sourceImage == null ? "" : sourceImage);
        return UUID.nameUUIDFromBytes(identity.getBytes(StandardCharsets.UTF_8)).toString();
    }

    private static String sourceClicksPath(Path projectRoot, Path outputDir) {
        Path clicks = sourceClicksJson(projectRoot);
        try {
            return outputDir.toAbsolutePath().normalize().relativize(clicks)
                    .toString().replace('\\', '/');
        } catch (IllegalArgumentException e) {
            return clicks.toString();
        }
    }

    private static Path sourceClicksJson(Path projectRoot) {
        FlashProjectLayout layout = FlashProjectLayout.forDirectory(projectRoot.toString());
        Path writePath = layout.configurationWriteDir().toPath()
                .resolve(ClicksConfigIO.FILE_NAME)
                .toAbsolutePath()
                .normalize();
        if (Files.isRegularFile(writePath)) {
            return writePath;
        }
        return writePath;
    }

    private static String channelName(Path projectRoot, int channelOneBased) {
        try {
            BinConfig config = BinConfigIO.readPartialFromDirectory(projectRoot.toString());
            if (config != null && config.channelNames.size() >= channelOneBased) {
                String name = config.channelNames.get(channelOneBased - 1);
                if (name != null && !name.trim().isEmpty()) {
                    return name.trim();
                }
            }
        } catch (RuntimeException ignored) {
            // Fall through to stage-plan configuration paths.
        }

        return "C" + channelOneBased;
    }

    private static String outputFileName(String imageName, int channelOneBased, int z) {
        String safeImage = safePathSegment(stripTiffExtension(imageName), "Image");
        return safeImage + "_C" + channelOneBased + "_z"
                + String.format(Locale.ROOT, "%03d", Integer.valueOf(z)) + ".tif";
    }

    private static String tileOutputFileName(String imageName,
                                             int channelOneBased,
                                             int z,
                                             int tileIndex) {
        String safeImage = safePathSegment(stripTiffExtension(imageName), "Image");
        return safeImage + "_C" + channelOneBased + "_z"
                + String.format(Locale.ROOT, "%03d", Integer.valueOf(z))
                + "_tile"
                + String.format(Locale.ROOT, "%03d", Integer.valueOf(tileIndex))
                + ".tif";
    }

    private static String stripTiffExtension(String imageName) {
        if (imageName == null) {
            return "";
        }
        String trimmed = imageName.trim();
        String lower = trimmed.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".ome.tif")) {
            return trimmed.substring(0, trimmed.length() - ".ome.tif".length());
        }
        if (lower.endsWith(".ome.tiff")) {
            return trimmed.substring(0, trimmed.length() - ".ome.tiff".length());
        }
        if (lower.endsWith(".tif")) {
            return trimmed.substring(0, trimmed.length() - ".tif".length());
        }
        if (lower.endsWith(".tiff")) {
            return trimmed.substring(0, trimmed.length() - ".tiff".length());
        }
        return trimmed;
    }

    private static String safePathSegment(String raw, String fallback) {
        String trimmed = raw == null ? "" : raw.trim();
        if (trimmed.isEmpty()) {
            trimmed = fallback;
        }
        String safe = ChannelFilenameCodec.toSafe(trimmed);
        safe = safe.replaceAll("[\\p{Cntrl}]+", "_").trim();
        if (safe.isEmpty()) {
            safe = fallback;
        }
        while (safe.equals(".") || safe.equals("..") || safe.indexOf('/') >= 0
                || safe.indexOf('\\') >= 0) {
            safe = safe.replace("/", "_").replace("\\", "_");
            safe = ChannelFilenameCodec.toSafe(safe);
            if (safe.equals(".") || safe.equals("..")) {
                safe = "_" + safe.replace(".", "%2E");
            }
        }
        return safe;
    }

    private static void requireImage(ImagePlus image,
                                     String kind,
                                     String imageName) throws IOException {
        if (image == null || image.getImageStack() == null || image.getImageStack().getSize() == 0) {
            throw new IOException(kind + " image provider returned no image for '" + imageName + "'.");
        }
    }

    private static void closePreparedImages(List<PreparedImage> prepared) {
        if (prepared == null) return;
        for (PreparedImage item : prepared) {
            if (item != null && item.correction != null) {
                closeImage(item.correction.labels);
            }
        }
    }

    private static void closeImage(ImagePlus image) {
        if (image != null) {
            image.changes = false;
            image.close();
            image.flush();
        }
    }

    private static void ensureInside(Path root, Path candidate) throws IOException {
        if (!candidate.startsWith(root)) {
            throw new IOException("Output directory escapes the project Config folder: " + candidate);
        }
    }

    private static void replaceDirectory(Path preparedDir, Path outputDir) throws IOException {
        Files.createDirectories(outputDir.getParent());
        if (!Files.exists(outputDir)) {
            moveDirectory(preparedDir, outputDir);
            return;
        }

        Path backup = outputDir.resolveSibling(outputDir.getFileName().toString()
                + ".backup-" + System.currentTimeMillis());
        deleteRecursivelyIfExists(backup);
        boolean installedReplacement = false;
        try {
            moveDirectory(outputDir, backup);
            moveDirectory(preparedDir, outputDir);
            installedReplacement = true;
        } catch (IOException e) {
            if (!Files.exists(outputDir) && Files.exists(backup)) {
                try {
                    moveDirectory(backup, outputDir);
                } catch (IOException restoreFailure) {
                    e.addSuppressed(restoreFailure);
                }
            }
            throw e;
        } finally {
            if (installedReplacement) {
                deleteRecursivelyIfExists(backup);
            }
        }
    }

    private static void moveDirectory(Path source, Path target) throws IOException {
        try {
            Files.move(source, target,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void deleteRecursivelyIfExists(Path path) throws IOException {
        if (path == null || !Files.exists(path)) {
            return;
        }
        Files.walkFileTree(path, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.deleteIfExists(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                if (exc != null) {
                    throw exc;
                }
                Files.deleteIfExists(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static final class Correction {
        final ImagePlus labels;
        final int positiveLabelsRetained;
        final int negativeLabelsRemoved;

        Correction(ImagePlus labels, int positiveLabelsRetained, int negativeLabelsRemoved) {
            this.labels = labels;
            this.positiveLabelsRetained = positiveLabelsRetained;
            this.negativeLabelsRemoved = negativeLabelsRemoved;
        }
    }

    private static final class PreparedImage {
        final String imageName;
        final List<ClickStore.Click> clicks;
        final ImagePlus rawImage;
        final Correction correction;

        PreparedImage(String imageName,
                      List<ClickStore.Click> clicks,
                      ImagePlus rawImage,
                      Correction correction) {
            this.imageName = imageName;
            this.clicks = clicks;
            this.rawImage = rawImage;
            this.correction = correction;
        }
    }

    private static final class LabelCentroid {
        final int z;
        final double x;
        final double y;

        LabelCentroid(int z, double x, double y) {
            this.z = z;
            this.x = x;
            this.y = y;
        }
    }

    private static final class SampleProvenance {
        final String fileName;
        final String sourceImage;
        final String sessionId;
        final int z;
        final int tileIndex;

        SampleProvenance(String fileName,
                         String sourceImage,
                         String sessionId,
                         int z,
                         int tileIndex) {
            this.fileName = fileName;
            this.sourceImage = sourceImage;
            this.sessionId = sessionId;
            this.z = z;
            this.tileIndex = tileIndex;
        }
    }
}
