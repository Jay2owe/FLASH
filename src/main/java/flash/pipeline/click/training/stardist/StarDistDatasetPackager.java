package flash.pipeline.click.training.stardist;

import flash.pipeline.bin.BinConfig;
import flash.pipeline.bin.BinConfigIO;
import flash.pipeline.click.ClickStore;
import flash.pipeline.click.training.ImagePlusProvider;
import flash.pipeline.naming.ChannelFilenameCodec;
import flash.pipeline.ui.wizard.JsonIO;
import ij.ImagePlus;
import ij.ImageStack;
import ij.io.FileSaver;
import ij.process.ImageProcessor;

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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

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
    private static final String CONFIGURATION_DIR = "Configuration";
    private static final String TRAINING_DATASETS_DIR = "Training Datasets";
    private static final String ENGINE_DIR = "StarDist";
    private static final String RAW_DIR = "raw";
    private static final String LABELS_DIR = "labels";
    private static final String README_FILENAME = "README.txt";
    private static final String METADATA_FILENAME = "metadata.json";
    private static final String SOURCE_CLICKS_JSON_PATH = "../../.bin/Clicks.json";

    public static final class PackagingResult {
        public final Path outputDir;
        public final int imagesWritten;
        public final int positiveLabelsRetained;
        public final int negativeLabelsRemoved;

        public PackagingResult(Path outputDir,
                               int imagesWritten,
                               int positiveLabelsRetained,
                               int negativeLabelsRemoved) {
            this.outputDir = outputDir;
            this.imagesWritten = imagesWritten;
            this.positiveLabelsRetained = positiveLabelsRetained;
            this.negativeLabelsRemoved = negativeLabelsRemoved;
        }
    }

    public PackagingResult packageDataset(Path projectRoot,
                                          String sessionName,
                                          int channelOneBased,
                                          ClickStore clickStore,
                                          ImagePlusProvider rawImageProvider,
                                          ImagePlusProvider labelImageProvider) throws IOException {
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

        Path root = projectRoot.toAbsolutePath().normalize();
        String safeSessionName = safePathSegment(sessionName, "StarDist dataset");
        Path outputDir = root.resolve(CONFIGURATION_DIR)
                .resolve(TRAINING_DATASETS_DIR)
                .resolve(ENGINE_DIR)
                .resolve(safeSessionName)
                .toAbsolutePath()
                .normalize();
        ensureInside(root.resolve(CONFIGURATION_DIR).toAbsolutePath().normalize(), outputDir);

        Map<String, List<ClickStore.Click>> clicksByImage =
                clicksByImage(clickStore, channelOneBased);
        String channelName = channelName(root, channelOneBased);

        Path tempDir = outputDir.resolveSibling(outputDir.getFileName().toString()
                + ".tmp-" + System.currentTimeMillis() + "-" + Thread.currentThread().getId());
        deleteRecursivelyIfExists(tempDir);

        int originalImagesWritten = 0;
        int trainingImagesWritten = 0;
        int positiveLabelsRetained = 0;
        int negativeLabelsRemoved = 0;

        try {
            Path rawDir = tempDir.resolve(RAW_DIR);
            Path labelsDir = tempDir.resolve(LABELS_DIR);
            Files.createDirectories(rawDir);
            Files.createDirectories(labelsDir);

            List<String> imageNames = new ArrayList<String>(clicksByImage.keySet());
            Collections.sort(imageNames);
            for (String imageName : imageNames) {
                List<ClickStore.Click> clicks = clicksByImage.get(imageName);
                if (clicks == null || clicks.isEmpty()) {
                    continue;
                }

                ImagePlus rawImage = rawImageProvider.get(imageName);
                ImagePlus labelImage = labelImageProvider.get(imageName);
                requireImage(rawImage, "Raw", imageName);
                requireImage(labelImage, "StarDist label", imageName);

                Correction correction = correctLabels(labelImage, clicks);
                positiveLabelsRetained += correction.positiveLabelsRetained;
                negativeLabelsRemoved += correction.negativeLabelsRemoved;

                int slices = labelSliceCount(correction.labels);
                for (int z = 1; z <= slices; z++) {
                    String fileName = outputFileName(imageName, channelOneBased, z);
                    ImagePlus rawSlice = rawSlice(rawImage, channelOneBased, z);
                    ImagePlus labelSlice = labelSlice(correction.labels, z);
                    saveTiff(rawSlice, rawDir.resolve(fileName));
                    saveTiff(labelSlice, labelsDir.resolve(fileName));
                    trainingImagesWritten++;
                }
                originalImagesWritten++;
            }

            writeReadme(tempDir, channelOneBased, channelName);
            writeMetadata(tempDir, channelOneBased, channelName, originalImagesWritten,
                    trainingImagesWritten, positiveLabelsRetained, negativeLabelsRemoved);

            replaceDirectory(tempDir, outputDir);
            tempDir = null;
            return new PackagingResult(outputDir, trainingImagesWritten,
                    positiveLabelsRetained, negativeLabelsRemoved);
        } finally {
            if (tempDir != null) {
                deleteRecursivelyIfExists(tempDir);
            }
        }
    }

    private static Map<String, List<ClickStore.Click>> clicksByImage(ClickStore clickStore,
                                                                     int channelOneBased) {
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
        ImagePlus labels = duplicateAsShort(labelImage, "corrected-labels");
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
        return new Correction(labels, retained, labelsToRemove.size());
    }

    private static void removeLabels(ImagePlus labels, Set<Integer> labelsToRemove) {
        ImageStack stack = labels.getImageStack();
        for (int s = 1; s <= stack.getSize(); s++) {
            ImageProcessor ip = stack.getProcessor(s);
            for (int i = 0; i < ip.getPixelCount(); i++) {
                int label = labelFromPixel(ip.getf(i));
                if (label > 0 && labelsToRemove.contains(Integer.valueOf(label))) {
                    ip.setf(i, 0f);
                }
            }
        }
    }

    private static Set<Integer> labelsPresent(ImagePlus image) {
        Set<Integer> labels = new HashSet<Integer>();
        if (image == null || image.getImageStack() == null) {
            return labels;
        }
        ImageStack stack = image.getImageStack();
        for (int s = 1; s <= stack.getSize(); s++) {
            ImageProcessor ip = stack.getProcessor(s);
            for (int i = 0; i < ip.getPixelCount(); i++) {
                int label = labelFromPixel(ip.getf(i));
                if (label > 0) {
                    labels.add(Integer.valueOf(label));
                }
            }
        }
        return labels;
    }

    private static int labelFromPixel(float value) {
        if (!Float.isFinite(value) || value <= 0f) {
            return 0;
        }
        return value > Integer.MAX_VALUE ? 0 : Math.round(value);
    }

    private static ImagePlus duplicateAsShort(ImagePlus src, String title) throws IOException {
        if (src == null || src.getImageStack() == null || src.getImageStack().getSize() == 0) {
            throw new IOException("Cannot duplicate an empty image.");
        }
        ImageStack in = src.getImageStack();
        ImageStack out = new ImageStack(src.getWidth(), src.getHeight());
        for (int s = 1; s <= in.getSize(); s++) {
            out.addSlice(in.getSliceLabel(s), in.getProcessor(s).convertToShort(false));
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
        return singlePlane(labelImage, 1, z, "label");
    }

    private static ImagePlus singlePlane(ImagePlus src,
                                         int channel,
                                         int z,
                                         String title) throws IOException {
        if (src == null || src.getImageStack() == null || src.getImageStack().getSize() == 0) {
            throw new IOException("Cannot export an empty " + title + " image.");
        }
        int slices = Math.max(1, src.getNSlices());
        if (z < 1 || z > slices) {
            throw new IOException("Image '" + src.getTitle() + "' has " + slices
                    + " Z slices, cannot export z" + z + ".");
        }
        int stackIndex = src.getStackIndex(channel, z, 1);
        ImageProcessor processor = src.getImageStack().getProcessor(stackIndex)
                .convertToShort(false);
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

    private static void writeReadme(Path outputDir,
                                    int channelOneBased,
                                    String channelName) throws IOException {
        List<String> lines = new ArrayList<String>();
        lines.add("FLASH StarDist 2D training dataset");
        lines.add("");
        lines.add("Channel: C" + channelOneBased + " (" + channelName + ")");
        lines.add("raw/ contains 16-bit source image slices.");
        lines.add("labels/ contains matching 16-bit integer StarDist label masks.");
        lines.add("Each 3D stack is exported as independent 2D Z slices.");
        lines.add("");
        lines.add("Recommended notebook:");
        lines.add(RECOMMENDED_NOTEBOOK);
        lines.add("");
        lines.add("This v1 export uses whole-image slices. Tiled exports are a follow-up.");
        Files.write(outputDir.resolve(README_FILENAME), lines, StandardCharsets.UTF_8);
    }

    private static void writeMetadata(Path outputDir,
                                      int channelOneBased,
                                      String channelName,
                                      int imageCount,
                                      int sliceCount,
                                      int positiveLabelsRetained,
                                      int negativeLabelsRemoved) throws IOException {
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
        root.put("sourceClicksJsonPath", SOURCE_CLICKS_JSON_PATH);
        root.put("recommendedNotebook", RECOMMENDED_NOTEBOOK);
        String json = JsonIO.write(root) + "\n";
        Files.write(outputDir.resolve(METADATA_FILENAME),
                json.getBytes(StandardCharsets.UTF_8));
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

        String direct = channelNameFromCandidate(projectRoot.resolve(CONFIGURATION_DIR)
                .resolve(".bin")
                .resolve("Channel_Data.txt"), channelOneBased);
        if (direct != null) {
            return direct;
        }
        direct = channelNameFromCandidate(projectRoot.resolve(CONFIGURATION_DIR)
                .resolve("Channel_Data.txt"), channelOneBased);
        if (direct != null) {
            return direct;
        }
        return "C" + channelOneBased;
    }

    private static String channelNameFromCandidate(Path channelData,
                                                   int channelOneBased) {
        if (channelData == null || !Files.isRegularFile(channelData)) {
            return null;
        }
        try {
            List<String> lines = Files.readAllLines(channelData, StandardCharsets.UTF_8);
            if (lines.isEmpty()) {
                return null;
            }
            String[] names = splitTokens(lines.get(0));
            if (names.length >= channelOneBased) {
                String name = names[channelOneBased - 1];
                if (name != null && !name.trim().isEmpty()) {
                    return name.trim();
                }
            }
        } catch (IOException ignored) {
            return null;
        }
        return null;
    }

    private static String[] splitTokens(String line) {
        if (line == null) {
            return new String[0];
        }
        if (line.indexOf('\t') >= 0) {
            String[] tokens = line.split("\t", -1);
            for (int i = 0; i < tokens.length; i++) {
                tokens[i] = tokens[i] == null ? "" : tokens[i].trim();
            }
            return tokens;
        }
        String trimmed = line.trim();
        return trimmed.isEmpty() ? new String[0] : trimmed.split("\\s+");
    }

    private static String outputFileName(String imageName, int channelOneBased, int z) {
        String safeImage = safePathSegment(stripTiffExtension(imageName), "Image");
        return safeImage + "_C" + channelOneBased + "_z"
                + String.format(Locale.ROOT, "%03d", Integer.valueOf(z)) + ".tif";
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

    private static void ensureInside(Path root, Path candidate) throws IOException {
        if (!candidate.startsWith(root)) {
            throw new IOException("Output directory escapes the project Configuration folder: " + candidate);
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
}
