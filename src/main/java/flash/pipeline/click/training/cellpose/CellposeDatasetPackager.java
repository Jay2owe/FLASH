package flash.pipeline.click.training.cellpose;

import flash.pipeline.bin.BinConfig;
import flash.pipeline.bin.BinConfigIO;
import flash.pipeline.cellpose.CellposeModelResolver;
import flash.pipeline.cellpose.CellposeRuntime;
import flash.pipeline.click.ClickStore;
import flash.pipeline.click.ClicksConfigIO;
import flash.pipeline.click.training.ImagePlusProvider;
import flash.pipeline.io.FlashProjectLayout;
import flash.pipeline.naming.ChannelFilenameCodec;
import flash.pipeline.segmentation.SegmentationMethod;
import flash.pipeline.segmentation.catalog.ModelCatalog;
import flash.pipeline.segmentation.catalog.ModelCatalogIO;
import flash.pipeline.ui.wizard.JsonIO;
import ij.IJ;
import ij.ImagePlus;
import ij.ImageStack;
import ij.Prefs;
import ij.io.FileSaver;
import ij.process.ImageProcessor;
import ij.process.ShortProcessor;

import java.io.File;
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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public final class CellposeDatasetPackager {
    private static final int METADATA_VERSION = 1;
    private static final String FALLBACK_BASE_MODEL = "cyto3";
    public static final String EXPORT_MODE_PER_Z_SLICES = "per_z_slices";
    public static final String CELLPOSE_3D_TRAINING_WARNING =
            "Cellpose 3 training in FLASH is 2D-oriented. This dataset was exported as "
                    + "per-Z 2D image and mask pairs from 3D input; keep that limitation "
                    + "in mind when training and validating the model.";

    public static final class PackagingResult {
        public final Path outputDir;
        public final Path trainCommandFile;
        public final int imagesWritten;
        public final int slicesWritten;
        public final int positiveLabelsRetained;
        public final int negativeLabelsRemoved;
        public final String exportMode;
        public final boolean sourceHad3D;
        public final String trainingWarning;

        public PackagingResult(Path outputDir,
                               Path trainCommandFile,
                               int imagesWritten,
                               int slicesWritten,
                               int positiveLabelsRetained,
                               int negativeLabelsRemoved) {
            this(outputDir, trainCommandFile, imagesWritten, slicesWritten,
                    positiveLabelsRetained, negativeLabelsRemoved,
                    EXPORT_MODE_PER_Z_SLICES, false, "");
        }

        public PackagingResult(Path outputDir,
                               Path trainCommandFile,
                               int imagesWritten,
                               int slicesWritten,
                               int positiveLabelsRetained,
                               int negativeLabelsRemoved,
                               String exportMode,
                               boolean sourceHad3D,
                               String trainingWarning) {
            this.outputDir = outputDir;
            this.trainCommandFile = trainCommandFile;
            this.imagesWritten = imagesWritten;
            this.slicesWritten = slicesWritten;
            this.positiveLabelsRetained = positiveLabelsRetained;
            this.negativeLabelsRemoved = negativeLabelsRemoved;
            this.exportMode = exportMode == null || exportMode.trim().isEmpty()
                    ? EXPORT_MODE_PER_Z_SLICES
                    : exportMode.trim();
            this.sourceHad3D = sourceHad3D;
            this.trainingWarning = trainingWarning == null ? "" : trainingWarning.trim();
        }
    }

    public PackagingResult packageDataset(Path projectRoot,
                                          String sessionName,
                                          int channelOneBased,
                                          ClickStore clickStore,
                                          ImagePlusProvider rawImageProvider,
                                          ImagePlusProvider labelImageProvider,
                                          String baseModel) throws IOException {
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
        Path outputDir = datasetRoot(root).resolve(sanitizeDirectoryName(sessionName));
        Path parent = outputDir.getParent();
        Files.createDirectories(parent);
        Path tempDir = parent.resolve(outputDir.getFileName().toString()
                + ".tmp-" + UUID.randomUUID().toString());

        try {
            Files.createDirectory(tempDir);
            List<ImageClicks> groupedClicks = groupClicksByImage(clickStore, channelOneBased);
            String channelName = channelName(root, channelOneBased);
            String pretrainedModel = resolvePretrainedModel(root, baseModel);
            String trainCommand = buildTrainCommand(outputDir, pretrainedModel);
            Counters counters = writeImagePairs(tempDir, channelOneBased, groupedClicks,
                    rawImageProvider, labelImageProvider);

            writeMetadata(tempDir, root, outputDir, channelOneBased, channelName,
                    pretrainedModel, trainCommand, counters);
            Path tempCommand = tempDir.resolve("train_command.txt");
            Files.write(tempCommand,
                    Collections.singletonList(trainCommand),
                    StandardCharsets.UTF_8);

            moveDirectoryIntoPlace(tempDir, outputDir);
            tempDir = null;
            return new PackagingResult(outputDir,
                    outputDir.resolve("train_command.txt"),
                    counters.imagesWritten,
                    counters.slicesWritten,
                    counters.positiveLabelsRetained,
                    counters.negativeLabelsRemoved,
                    EXPORT_MODE_PER_Z_SLICES,
                    counters.source3DImages > 0,
                    counters.source3DImages > 0 ? CELLPOSE_3D_TRAINING_WARNING : "");
        } finally {
            if (tempDir != null) {
                deleteRecursively(tempDir);
            }
        }
    }

    private static Path datasetRoot(Path projectRoot) {
        return projectRoot.resolve("Configuration")
                .resolve("Training Datasets")
                .resolve("Cellpose");
    }

    private static List<ImageClicks> groupClicksByImage(ClickStore clickStore, int channelOneBased) {
        LinkedHashMap<String, ImageClicks> byImage = new LinkedHashMap<String, ImageClicks>();
        List<ClickStore.Click> clicks = clickStore == null
                ? new ArrayList<ClickStore.Click>()
                : clickStore.forChannel(channelOneBased);
        for (ClickStore.Click click : clicks) {
            if (click == null || click.channelOneBased != channelOneBased || click.label <= 0) {
                continue;
            }
            ImageClicks grouped = byImage.get(click.imageName);
            if (grouped == null) {
                grouped = new ImageClicks(click.imageName);
                byImage.put(click.imageName, grouped);
            }
            if (click.verdict == ClickStore.Verdict.POSITIVE) {
                grouped.positiveLabels.add(Integer.valueOf(click.label));
            } else {
                grouped.negativeLabels.add(Integer.valueOf(click.label));
            }
        }
        return new ArrayList<ImageClicks>(byImage.values());
    }

    private static Counters writeImagePairs(Path outputDir,
                                            int channelOneBased,
                                            List<ImageClicks> groupedClicks,
                                            ImagePlusProvider rawImageProvider,
                                            ImagePlusProvider labelImageProvider) throws IOException {
        Counters counters = new Counters();
        for (ImageClicks clicks : groupedClicks) {
            ImagePlus raw = rawImageProvider.get(clicks.imageName);
            ImagePlus labels = labelImageProvider.get(clicks.imageName);
            validateImagePair(clicks.imageName, raw, labels);

            int slices = sliceCount(raw);
            counters.imagesWritten++;
            if (slices > 1) {
                counters.source3DImages++;
            }
            for (int z = 1; z <= slices; z++) {
                String stem = fileStem(clicks.imageName, channelOneBased, z);
                saveSlice(raw, channelOneBased, z, outputDir.resolve(stem + ".tif"), false);
                CorrectedMask mask = correctedMask(labels, z,
                        clicks.negativeLabels, clicks.positiveLabels);
                saveProcessor(mask.processor, stem + "_masks",
                        outputDir.resolve(stem + "_masks.tif"));
                counters.slicesWritten++;
                addLabelKeys(counters.negativeLabelKeysSeen, clicks.imageName, mask.negativeLabelsRemoved);
                addLabelKeys(counters.positiveLabelKeysSeen, clicks.imageName, mask.positiveLabelsRetained);
            }
        }
        counters.negativeLabelsRemoved = counters.negativeLabelKeysSeen.size();
        counters.positiveLabelsRetained = counters.positiveLabelKeysSeen.size();
        return counters;
    }

    private static void addLabelKeys(Set<String> target, String imageName, Set<Integer> labels) {
        for (Integer label : labels) {
            if (label != null && label.intValue() > 0) {
                target.add((imageName == null ? "" : imageName) + "\t" + label);
            }
        }
    }

    private static void validateImagePair(String imageName,
                                          ImagePlus raw,
                                          ImagePlus labels) throws IOException {
        if (raw == null) {
            throw new IOException("Raw image provider returned null for " + imageName + ".");
        }
        if (labels == null) {
            throw new IOException("Label image provider returned null for " + imageName + ".");
        }
        if (raw.getWidth() != labels.getWidth() || raw.getHeight() != labels.getHeight()) {
            throw new IOException("Raw and label dimensions do not match for " + imageName + ".");
        }
        if (sliceCount(raw) != sliceCount(labels)) {
            throw new IOException("Raw and label slice counts do not match for " + imageName + ".");
        }
        if (sliceCount(raw) <= 0) {
            throw new IOException("Image has no slices: " + imageName + ".");
        }
    }

    private static int sliceCount(ImagePlus image) {
        if (image == null) return 0;
        int stackSize = image.getStackSize();
        if (image.getNChannels() <= 1 && image.getNFrames() <= 1) {
            return stackSize;
        }
        int slices = image.getNSlices();
        return slices <= 0 ? stackSize : slices;
    }

    private static ImageProcessor sliceProcessor(ImagePlus image, int channelOneBased, int z) {
        int index;
        if (image.getNChannels() > 1 || image.getNFrames() > 1) {
            index = image.getStackIndex(channelOneBased, z, 1);
        } else {
            index = z;
        }
        index = Math.max(1, Math.min(image.getStackSize(), index));
        return image.getStack().getProcessor(index);
    }

    private static ImageProcessor labelSliceProcessor(ImagePlus image, int z) {
        return sliceProcessor(image, 1, z);
    }

    private static ImageProcessor rawSliceProcessor(ImagePlus image,
                                                    int channelOneBased,
                                                    int z) throws IOException {
        int channel = Math.max(1, channelOneBased);
        if (image.getNChannels() < channel) {
            if (image.getNChannels() == 1) {
                channel = 1;
            } else {
                throw new IOException("Raw image '" + image.getTitle()
                        + "' does not contain channel " + channelOneBased + ".");
            }
        }
        return sliceProcessor(image, channel, z);
    }

    private static void saveSlice(ImagePlus source,
                                  int channelOneBased,
                                  int z,
                                  Path target,
                                  boolean forceShort) throws IOException {
        ImageProcessor processor = rawSliceProcessor(source, channelOneBased, z).duplicate();
        if (forceShort) {
            processor = processor.convertToShort(false);
        }
        saveProcessor(processor, source.getTitle(), target);
    }

    private static void saveProcessor(ImageProcessor processor,
                                      String title,
                                      Path target) throws IOException {
        Files.createDirectories(target.getParent());
        ImagePlus out = new ImagePlus(title == null ? target.getFileName().toString() : title,
                processor);
        FileSaver saver = new FileSaver(out);
        if (!saver.saveAsTiff(target.toString())) {
            throw new IOException("Could not write TIFF: " + target);
        }
        out.changes = false;
        out.close();
        out.flush();
    }

    private static CorrectedMask correctedMask(ImagePlus labels,
                                               int z,
                                               Set<Integer> negativeLabels,
                                               Set<Integer> positiveLabels) {
        ImageProcessor source = labelSliceProcessor(labels, z);
        int width = source.getWidth();
        int height = source.getHeight();
        ShortProcessor out = new ShortProcessor(width, height);
        Set<Integer> removed = new LinkedHashSet<Integer>();
        Set<Integer> retained = new LinkedHashSet<Integer>();
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int label = labelValue(source, x, y);
                if (label <= 0) {
                    out.set(x, y, 0);
                } else if (negativeLabels.contains(Integer.valueOf(label))) {
                    out.set(x, y, 0);
                    removed.add(Integer.valueOf(label));
                } else {
                    out.set(x, y, label);
                    if (positiveLabels.contains(Integer.valueOf(label))) {
                        retained.add(Integer.valueOf(label));
                    }
                }
            }
        }
        return new CorrectedMask(out, removed, retained);
    }

    private static int labelValue(ImageProcessor processor, int x, int y) {
        float value = processor.getf(x, y);
        if (!Float.isFinite(value)) {
            return 0;
        }
        return Math.max(0, Math.round(value));
    }

    private static String fileStem(String imageName, int channelOneBased, int z) {
        String safeImageName = ChannelFilenameCodec.toSafe(imageName == null ? "" : imageName);
        if (safeImageName == null || safeImageName.trim().isEmpty()) {
            safeImageName = "Image";
        }
        return safeImageName + "_C" + channelOneBased + "_z" + String.format(Locale.US, "%03d", z);
    }

    private static void writeMetadata(Path outputDir,
                                      Path projectRoot,
                                      Path finalOutputDir,
                                      int channelOneBased,
                                      String channelName,
                                      String baseModel,
                                      String trainCommand,
                                      Counters counters) throws IOException {
        Map<String, Object> root = JsonIO.object();
        root.put("version", Integer.valueOf(METADATA_VERSION));
        root.put("channel", Integer.valueOf(channelOneBased));
        root.put("channelName", channelName);
        root.put("createdAt", Long.valueOf(System.currentTimeMillis()));
        root.put("imageCount", Integer.valueOf(counters.imagesWritten));
        root.put("sliceCount", Integer.valueOf(counters.slicesWritten));
        root.put("exportMode", EXPORT_MODE_PER_Z_SLICES);
        root.put("sourceHad3D", Boolean.valueOf(counters.source3DImages > 0));
        root.put("source3DImageCount", Integer.valueOf(counters.source3DImages));
        if (counters.source3DImages > 0) {
            root.put("trainingWarning", CELLPOSE_3D_TRAINING_WARNING);
        }
        Map<String, Object> objectCount = JsonIO.object();
        objectCount.put("positive", Integer.valueOf(counters.positiveLabelsRetained));
        objectCount.put("negative", Integer.valueOf(counters.negativeLabelsRemoved));
        root.put("objectCount", objectCount);
        root.put("baseModel", baseModel);
        root.put("trainCommand", trainCommand);
        root.put("sourceClicksJsonPath", sourceClicksPath(projectRoot, finalOutputDir));
        Files.write(outputDir.resolve("metadata.json"),
                Collections.singletonList(JsonIO.write(root)),
                StandardCharsets.UTF_8);
    }

    private static String channelName(Path projectRoot, int channelOneBased) {
        try {
            BinConfig cfg = BinConfigIO.readPartialFromDirectory(projectRoot.toString());
            int index = channelOneBased - 1;
            if (index >= 0 && index < cfg.channelNames.size()) {
                String name = cfg.channelNames.get(index);
                if (name != null && !name.trim().isEmpty()) {
                    return name.trim();
                }
            }
        } catch (RuntimeException ignored) {
        }
        return "C" + channelOneBased;
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
        for (File dir : layout.configurationReadDirs()) {
            Path candidate = dir.toPath().resolve(ClicksConfigIO.FILE_NAME)
                    .toAbsolutePath().normalize();
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        return writePath;
    }

    private static String resolvePretrainedModel(Path projectRoot, String baseModel) {
        String requested = baseModel == null || baseModel.trim().isEmpty()
                ? SegmentationMethod.DEFAULT_CELLPOSE_MODEL_KEY
                : baseModel.trim();
        try {
            ModelCatalog catalog = ModelCatalogIO.read(projectRoot);
            Optional<CellposeModelResolver.Resolved> resolved =
                    new CellposeModelResolver().resolve(requested, catalog);
            if (resolved.isPresent()) {
                CellposeModelResolver.Resolved value = resolved.get();
                return value.built_in ? value.pretrainedName : value.absolutePath;
            }
        } catch (RuntimeException ignored) {
        }
        if (requested.toLowerCase(Locale.ROOT).startsWith("cellpose_")) {
            return requested.substring("cellpose_".length());
        }
        return requested.isEmpty() ? FALLBACK_BASE_MODEL : requested;
    }

    private static String buildTrainCommand(Path outputDir, String baseModel) {
        String python = Prefs.get(CellposeRuntime.PREF_PYTHON_PATH, "").trim();
        String executable;
        if (python.isEmpty()) {
            IJ.log("WARNING: Cellpose Python path is not configured; train_command.txt uses 'python'.");
            executable = "python";
        } else {
            executable = quote(python);
        }
        return executable
                + " -m cellpose --train --dir " + quote(outputDir.toAbsolutePath().normalize().toString())
                + " --pretrained_model " + quoteIfNeeded(baseModel == null || baseModel.trim().isEmpty()
                ? FALLBACK_BASE_MODEL
                : baseModel.trim())
                + " --learning_rate 0.00001 --weight_decay 0.1 --n_epochs 100 --train_batch_size 1";
    }

    private static String quoteIfNeeded(String value) {
        String text = value == null ? "" : value;
        if (text.indexOf(' ') >= 0 || text.indexOf('\t') >= 0 || text.indexOf('"') >= 0) {
            return quote(text);
        }
        return text;
    }

    private static String quote(String value) {
        String text = value == null ? "" : value;
        return "\"" + text.replace("\"", "\\\"") + "\"";
    }

    private static String sanitizeDirectoryName(String raw) {
        String trimmed = raw == null ? "" : raw.trim();
        String sanitized = trimmed.replaceAll("[<>:\"/\\\\|?*\\p{Cntrl}]+", "_").trim();
        while (sanitized.endsWith(".")) {
            sanitized = sanitized.substring(0, sanitized.length() - 1).trim();
        }
        return sanitized.isEmpty() ? "Cellpose Dataset" : sanitized;
    }

    private static void moveDirectoryIntoPlace(Path tempDir, Path outputDir) throws IOException {
        Path backup = null;
        if (Files.exists(outputDir)) {
            backup = outputDir.resolveSibling(outputDir.getFileName().toString()
                    + ".old-" + UUID.randomUUID().toString());
            moveAtomic(outputDir, backup);
        }

        boolean moved = false;
        try {
            moveAtomic(tempDir, outputDir);
            moved = true;
            if (backup != null) {
                deleteRecursively(backup);
            }
        } finally {
            if (!moved && backup != null && Files.exists(backup) && !Files.exists(outputDir)) {
                moveAtomic(backup, outputDir);
            }
        }
    }

    private static void moveAtomic(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(source, target);
        }
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (root == null || !Files.exists(root)) return;
        Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.deleteIfExists(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                if (exc != null) throw exc;
                Files.deleteIfExists(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static final class ImageClicks {
        final String imageName;
        final Set<Integer> positiveLabels = new LinkedHashSet<Integer>();
        final Set<Integer> negativeLabels = new LinkedHashSet<Integer>();

        ImageClicks(String imageName) {
            this.imageName = imageName == null ? "" : imageName;
        }
    }

    private static final class CorrectedMask {
        final ShortProcessor processor;
        final Set<Integer> negativeLabelsRemoved;
        final Set<Integer> positiveLabelsRetained;

        CorrectedMask(ShortProcessor processor,
                      Set<Integer> negativeLabelsRemoved,
                      Set<Integer> positiveLabelsRetained) {
            this.processor = processor;
            this.negativeLabelsRemoved = negativeLabelsRemoved;
            this.positiveLabelsRetained = positiveLabelsRetained;
        }
    }

    private static final class Counters {
        int imagesWritten;
        int slicesWritten;
        int positiveLabelsRetained;
        int negativeLabelsRemoved;
        int source3DImages;
        final Set<String> positiveLabelKeysSeen = new LinkedHashSet<String>();
        final Set<String> negativeLabelKeysSeen = new LinkedHashSet<String>();
    }
}
