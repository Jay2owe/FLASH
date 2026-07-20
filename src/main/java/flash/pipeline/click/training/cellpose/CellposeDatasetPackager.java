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
import ij.Prefs;
import ij.io.FileSaver;
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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public final class CellposeDatasetPackager {
    private static final int MAX_EXACT_FLOAT_LABEL = 16_777_216;
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
        List<ImageClicks> groupedClicks = groupClicksByImage(clickStore, channelOneBased);
        List<PreparedImage> preparedImages = prepareImages(channelOneBased, groupedClicks,
                rawImageProvider, labelImageProvider);
        String channelName = channelName(root, channelOneBased);
        String pretrainedModel = resolvePretrainedModel(root, baseModel);
        String trainCommand = buildTrainCommand(outputDir, pretrainedModel);

        Files.createDirectories(parent);
        Path tempDir = parent.resolve(outputDir.getFileName().toString()
                + ".tmp-" + UUID.randomUUID().toString());

        try {
            Files.createDirectory(tempDir);
            Counters counters = writeImagePairs(tempDir, channelOneBased, preparedImages);

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
        return FlashProjectLayout.forDirectory(projectRoot.toString())
                .trainingDatasetsRoot()
                .toPath()
                .resolve("Cellpose");
    }

    private static List<ImageClicks> groupClicksByImage(ClickStore clickStore,
                                                        int channelOneBased) throws IOException {
        LinkedHashMap<String, ImageClicks> byImage = new LinkedHashMap<String, ImageClicks>();
        List<ClickStore.Click> clicks = clickStore == null
                ? new ArrayList<ClickStore.Click>()
                : clickStore.forChannel(channelOneBased);
        for (ClickStore.Click click : clicks) {
            if (click == null || click.channelOneBased != channelOneBased) {
                continue;
            }
            requireSupportedClickLabel(click.label, click.imageName);
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
                                            List<PreparedImage> preparedImages) throws IOException {
        Counters counters = new Counters();
        for (PreparedImage prepared : preparedImages) {
            ImageClicks clicks = prepared.clicks;
            ImagePlus raw = prepared.raw;
            ImagePlus labels = prepared.labels;

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
                Path maskPath = outputDir.resolve(stem + "_masks.tif");
                saveProcessor(mask.processor, stem + "_masks", maskPath);
                verifySavedMask(mask.processor, maskPath, clicks.imageName, z);
                counters.slicesWritten++;
                addLabelKeys(counters.negativeLabelKeysSeen, clicks.imageName, mask.negativeLabelsRemoved);
                addLabelKeys(counters.positiveLabelKeysSeen, clicks.imageName, mask.positiveLabelsRetained);
            }
        }
        counters.negativeLabelsRemoved = counters.negativeLabelKeysSeen.size();
        counters.positiveLabelsRetained = counters.positiveLabelKeysSeen.size();
        return counters;
    }

    private static List<PreparedImage> prepareImages(int channelOneBased,
                                                     List<ImageClicks> groupedClicks,
                                                     ImagePlusProvider rawImageProvider,
                                                     ImagePlusProvider labelImageProvider)
            throws IOException {
        List<PreparedImage> prepared = new ArrayList<PreparedImage>();
        for (ImageClicks clicks : groupedClicks) {
            ImagePlus raw = rawImageProvider.get(clicks.imageName);
            ImagePlus labels = labelImageProvider.get(clicks.imageName);
            validateImagePair(clicks.imageName, raw, labels);
            int slices = sliceCount(raw);
            for (int z = 1; z <= slices; z++) {
                rawSliceProcessor(raw, channelOneBased, z);
                validateLabelSlice(labels, z);
            }
            prepared.add(new PreparedImage(clicks, raw, labels));
        }
        return prepared;
    }

    private static void validateLabelSlice(ImagePlus labels, int z) throws IOException {
        ImageProcessor processor = labelSliceProcessor(labels, z);
        for (int y = 0; y < processor.getHeight(); y++) {
            for (int x = 0; x < processor.getWidth(); x++) {
                labelValue(processor, labels, z, x, y);
            }
        }
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
        if (raw.getNFrames() != 1) {
            throw new IOException("Unsupported raw image geometry for " + imageName
                    + ": T=" + raw.getNFrames()
                    + ". Cellpose training datasets do not encode a time axis; expected T=1.");
        }
        if (labels.getNFrames() != 1) {
            throw new IOException("Unsupported label image geometry for " + imageName
                    + ": T=" + labels.getNFrames()
                    + ". Cellpose training datasets do not encode a time axis; expected T=1.");
        }
        if (labels.getNChannels() != 1) {
            throw new IOException("Unsupported label image geometry for " + imageName
                    + ": C=" + labels.getNChannels()
                    + ". Cellpose masks must be single-channel; expected C=1.");
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
        try {
            FileSaver saver = new FileSaver(out);
            if (!saver.saveAsTiff(target.toString())) {
                throw new IOException("Could not write TIFF: " + target);
            }
        } finally {
            out.changes = false;
            out.close();
            out.flush();
        }
    }

    private static CorrectedMask correctedMask(ImagePlus labels,
                                               int z,
                                               Set<Integer> negativeLabels,
                                               Set<Integer> positiveLabels) throws IOException {
        ImageProcessor source = labelSliceProcessor(labels, z);
        int width = source.getWidth();
        int height = source.getHeight();
        int maximum = 0;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                maximum = Math.max(maximum, labelValue(source, labels, z, x, y));
            }
        }
        ImageProcessor out = maximum <= 65_535
                ? new ShortProcessor(width, height)
                : new FloatProcessor(width, height);
        Set<Integer> removed = new LinkedHashSet<Integer>();
        Set<Integer> retained = new LinkedHashSet<Integer>();
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int label = labelValue(source, labels, z, x, y);
                if (label <= 0) {
                    out.setf(x, y, 0.0f);
                } else if (negativeLabels.contains(Integer.valueOf(label))) {
                    out.setf(x, y, 0.0f);
                    removed.add(Integer.valueOf(label));
                } else {
                    out.setf(x, y, label);
                    if (positiveLabels.contains(Integer.valueOf(label))) {
                        retained.add(Integer.valueOf(label));
                    }
                }
            }
        }
        return new CorrectedMask(out, removed, retained);
    }

    private static void verifySavedMask(ImageProcessor intended,
                                        Path path,
                                        String imageName,
                                        int z) throws IOException {
        ImagePlus reopened = IJ.openImage(path.toString());
        if (reopened == null || reopened.getProcessor() == null) {
            closeImage(reopened);
            throw labelFailure("Cellpose mask round-trip could not reopen '" + path
                    + "' for image '" + imageName + "', z=" + z + ".");
        }
        try {
            int expectedBitDepth = intended instanceof FloatProcessor ? 32 : 16;
            if (reopened.getWidth() != intended.getWidth()
                    || reopened.getHeight() != intended.getHeight()
                    || reopened.getBitDepth() != expectedBitDepth) {
                throw labelFailure("Cellpose mask round-trip changed geometry/type for image '"
                        + imageName + "', z=" + z + ": expected " + intended.getWidth()
                        + "x" + intended.getHeight() + " @ " + expectedBitDepth
                        + "-bit, reopened " + reopened.getWidth() + "x"
                        + reopened.getHeight() + " @ " + reopened.getBitDepth() + "-bit.");
            }
            ImageProcessor actual = reopened.getProcessor();
            for (int pixel = 0; pixel < intended.getPixelCount(); pixel++) {
                float expected = intended.getf(pixel);
                float observed = actual.getf(pixel);
                if (observed != expected) {
                    throw labelFailure("Cellpose mask round-trip changed canonical label at image '"
                            + imageName + "', z=" + z + ", pixel=" + pixel
                            + ": expected " + expected + ", reopened " + observed + ".");
                }
            }
        } finally {
            closeImage(reopened);
        }
    }

    private static void closeImage(ImagePlus image) {
        if (image != null) {
            image.changes = false;
            image.close();
            image.flush();
        }
    }

    private static int labelValue(ImageProcessor processor,
                                  ImagePlus image,
                                  int z,
                                  int x,
                                  int y) throws IOException {
        float value = processor.getf(x, y);
        if (value == 0.0f) return 0;
        if (!Float.isFinite(value) || value <= 0.0f
                || value > MAX_EXACT_FLOAT_LABEL || value != Math.rint(value)) {
            throw labelFailure("Cellpose mask source '" + safeTitle(image) + "' at z=" + z
                    + ", x=" + x + ", y=" + y + " must contain zero or a positive "
                    + "integral label no greater than " + MAX_EXACT_FLOAT_LABEL
                    + "; found " + value + ".");
        }
        return (int) value;
    }

    private static void requireSupportedClickLabel(int label, String imageName) throws IOException {
        if (label <= 0 || label > MAX_EXACT_FLOAT_LABEL) {
            throw labelFailure("Cellpose click for image '" + (imageName == null ? "" : imageName)
                    + "' has label " + label + "; supported canonical labels are 1.."
                    + MAX_EXACT_FLOAT_LABEL + ".");
        }
    }

    private static IOException labelFailure(String message) {
        return new IOException("LABEL_IDENTITY_UNSUPPORTED: " + message
                + " No training dataset was installed.");
    }

    private static String safeTitle(ImagePlus image) {
        String title = image == null ? "" : image.getTitle();
        return title == null ? "" : title;
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

    private static final class PreparedImage {
        final ImageClicks clicks;
        final ImagePlus raw;
        final ImagePlus labels;

        PreparedImage(ImageClicks clicks, ImagePlus raw, ImagePlus labels) {
            this.clicks = clicks;
            this.raw = raw;
            this.labels = labels;
        }
    }

    private static final class CorrectedMask {
        final ImageProcessor processor;
        final Set<Integer> negativeLabelsRemoved;
        final Set<Integer> positiveLabelsRetained;

        CorrectedMask(ImageProcessor processor,
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
