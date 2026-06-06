package flash.pipeline.representative;

import flash.pipeline.bin.BinConfig;
import flash.pipeline.bin.BinConfigIO;
import flash.pipeline.image.ImageOps;
import flash.pipeline.io.DeferredImageSupplier;
import flash.pipeline.io.FlashProjectLayout;
import flash.pipeline.io.ImageCache;
import flash.pipeline.io.ImageSourceDispatcher;
import flash.pipeline.io.IoUtils;
import flash.pipeline.io.SeriesMeta;
import flash.pipeline.io.TifCache;
import flash.pipeline.naming.ChannelFilenameCodec;
import flash.pipeline.naming.ImageNameParser;
import flash.pipeline.naming.NameParts;
import flash.pipeline.presentation.PresentationTileRecord;
import flash.pipeline.presentation.PresentationTileWriter;
import flash.pipeline.qc.QcMinMaxPerConditionSelector;
import flash.pipeline.qc.QcSelectionCandidate;
import flash.pipeline.zslice.ZSliceOps;
import ij.IJ;
import ij.ImagePlus;
import ij.ImageStack;
import ij.plugin.ChannelSplitter;
import ij.plugin.ContrastEnhancer;
import ij.plugin.ZProjector;
import ij.process.ImageProcessor;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Produces the split-MIP thumbnails shown by the representative-series picker.
 */
public final class RepresentativePreviewRenderer {

    public static final int DEFAULT_THUMBNAIL_LONG_EDGE = 220;
    public static final String CACHE_DIR_NAME = "Representative Previews";
    public static final String PRESENTATION_MANIFEST_FILE = "Presentation_Image_Manifest.csv";

    private static final double AUTO_SATURATION = 0.35;
    private static final String MERGE_NAME = "Merge";
    private static final String CACHE_VERSION = "representative-preview-v1";

    private RepresentativePreviewRenderer() {
    }

    public static List<RepresentativeSeries> render(String directory,
                                                    RepresentativeFigureConfig config,
                                                    ImageCache imageCache,
                                                    int parallelThreads) throws Exception {
        return render(directory, config, imageCache, parallelThreads, true);
    }

    public static List<RepresentativeSeries> render(String directory,
                                                    RepresentativeFigureConfig config,
                                                    ImageCache imageCache,
                                                    int parallelThreads,
                                                    boolean useTifCache) throws Exception {
        FlashProjectLayout layout = FlashProjectLayout.forDirectory(directory);
        BinConfig binConfig = BinConfigIO.readPartialFromDirectory(directory);
        RepresentativeFigureConfig safeConfig =
                config == null ? new RepresentativeFigureConfig() : config;
        List<SeriesMeta> metas = ImageSourceDispatcher.readAllMetadata(directory);
        List<QcSelectionCandidate> candidates =
                QcMinMaxPerConditionSelector.buildCandidates(directory, metas);
        List<PresentationTileRecord> presentationRecords =
                readPresentationManifest(layout);

        SourceAccess sourceAccess = new SourceAccess(directory, imageCache, useTifCache);
        try {
            List<WorkItem> items = buildWorkItems(
                    directory, metas, candidates, binConfig, presentationRecords, sourceAccess);
            RenderContext context = new RenderContext(
                    previewCacheDir(layout), binConfig, safeConfig, DEFAULT_THUMBNAIL_LONG_EDGE);
            return renderItems(items, context, sourceAccess, parallelThreads);
        } finally {
            sourceAccess.close();
        }
    }

    public static File previewCacheDir(String directory) {
        return previewCacheDir(FlashProjectLayout.forDirectory(directory));
    }

    public static File previewCacheDir(FlashProjectLayout layout) {
        return new File(layout.cacheRoot(), CACHE_DIR_NAME);
    }

    public static File presentationManifestFile(FlashProjectLayout layout) {
        return new File(layout.presentationImagesRoot(), PRESENTATION_MANIFEST_FILE);
    }

    static RepresentativeSeries renderPresentationSeriesForTests(File cacheDir,
                                                                 SeriesMeta meta,
                                                                 QcSelectionCandidate candidate,
                                                                 BinConfig binConfig,
                                                                 RepresentativeFigureConfig config,
                                                                 List<PresentationTileRecord> records)
            throws Exception {
        List<PresentationTileRecord> safeRecords =
                records == null ? Collections.<PresentationTileRecord>emptyList() : records;
        WorkItem item = workItemFor(null, meta, candidate, binConfig, safeRecords, null);
        RenderContext context = new RenderContext(cacheDir, binConfig,
                config == null ? new RepresentativeFigureConfig() : config,
                DEFAULT_THUMBNAIL_LONG_EDGE);
        return renderItem(item, context, SourceAccess.none());
    }

    static RepresentativeSeries renderGeneratedSeriesForTests(File cacheDir,
                                                              SeriesMeta meta,
                                                              QcSelectionCandidate candidate,
                                                              BinConfig binConfig,
                                                              RepresentativeFigureConfig config,
                                                              ImagePlus source)
            throws Exception {
        WorkItem item = workItemFor(null, meta, candidate, binConfig,
                Collections.<PresentationTileRecord>emptyList(), null);
        RenderContext context = new RenderContext(cacheDir, binConfig,
                config == null ? new RepresentativeFigureConfig() : config,
                DEFAULT_THUMBNAIL_LONG_EDGE);
        return renderItem(item, context, SourceAccess.fixed(source));
    }

    private static List<PresentationTileRecord> readPresentationManifest(FlashProjectLayout layout) {
        File manifest = presentationManifestFile(layout);
        if (manifest == null || !manifest.isFile()) {
            return Collections.emptyList();
        }
        try {
            return PresentationTileWriter.readManifest(manifest);
        } catch (IOException e) {
            IJ.log("[Representative Figure] Could not read presentation image manifest: "
                    + e.getMessage());
            return Collections.emptyList();
        }
    }

    private static List<RepresentativeSeries> renderItems(List<WorkItem> items,
                                                          final RenderContext context,
                                                          final SourceAccess sourceAccess,
                                                          int parallelThreads) throws Exception {
        if (items == null || items.isEmpty()) {
            return Collections.emptyList();
        }
        int threads = Math.max(1, Math.min(Math.max(1, parallelThreads), items.size()));
        if (threads == 1) {
            List<RepresentativeSeries> out = new ArrayList<RepresentativeSeries>();
            for (WorkItem item : items) {
                out.add(renderItem(item, context, sourceAccess));
            }
            return out;
        }

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<RepresentativeSeries>> futures =
                    new ArrayList<Future<RepresentativeSeries>>();
            for (final WorkItem item : items) {
                futures.add(pool.submit(new Callable<RepresentativeSeries>() {
                    @Override
                    public RepresentativeSeries call() throws Exception {
                        return renderItem(item, context, sourceAccess);
                    }
                }));
            }

            List<RepresentativeSeries> out = new ArrayList<RepresentativeSeries>();
            for (Future<RepresentativeSeries> future : futures) {
                out.add(future.get());
            }
            return out;
        } finally {
            pool.shutdownNow();
        }
    }

    private static RepresentativeSeries renderItem(WorkItem item,
                                                   RenderContext context,
                                                   SourceAccess sourceAccess)
            throws Exception {
        IoUtils.mustMkdirs(context.cacheDir);
        RenderPlan plan = RenderPlan.create(item, context);
        RepresentativeSeries cached = tryReadCached(item, plan);
        if (cached != null) {
            return cached;
        }

        RenderedPreviews previews = item.presentationCoverage.covers()
                ? renderFromPresentation(item, plan)
                : renderFromSource(item, plan, context, sourceAccess);
        writePreviews(previews, plan);
        return buildSeries(item, plan, previews.channelImages, previews.mergeImage,
                item.presentationCoverage.covers()
                        ? RepresentativeSeries.PreviewSource.PRESENTATION
                        : RepresentativeSeries.PreviewSource.GENERATED,
                false);
    }

    private static RepresentativeSeries tryReadCached(WorkItem item, RenderPlan plan)
            throws IOException {
        if (!plan.allFilesExist()) {
            return null;
        }
        List<BufferedImage> channelImages = new ArrayList<BufferedImage>();
        for (int i = 0; i < plan.channelFiles.size(); i++) {
            BufferedImage image = ImageIO.read(plan.channelFiles.get(i));
            if (image == null) return null;
            channelImages.add(image);
        }
        BufferedImage merge = ImageIO.read(plan.mergeFile);
        if (merge == null) return null;
        return buildSeries(item, plan, channelImages, merge,
                RepresentativeSeries.PreviewSource.CACHE, true);
    }

    private static RepresentativeSeries buildSeries(WorkItem item,
                                                    RenderPlan plan,
                                                    List<BufferedImage> channelImages,
                                                    BufferedImage merge,
                                                    RepresentativeSeries.PreviewSource source,
                                                    boolean cacheHit) {
        List<RepresentativeSeries.ChannelThumbnail> thumbnails =
                new ArrayList<RepresentativeSeries.ChannelThumbnail>();
        for (int i = 0; i < item.channels.size() && i < channelImages.size(); i++) {
            ChannelSpec spec = item.channels.get(i);
            thumbnails.add(new RepresentativeSeries.ChannelThumbnail(
                    spec.channelIndex, spec.channelName, channelImages.get(i),
                    plan.channelFiles.get(i)));
        }
        return new RepresentativeSeries(
                item.id,
                item.seriesIndex,
                item.seriesNumber,
                item.seriesName,
                item.animal,
                item.condition,
                item.hemisphere,
                item.region,
                item.sourcePath,
                thumbnails,
                merge,
                plan.mergeFile,
                source,
                cacheHit);
    }

    private static RenderedPreviews renderFromPresentation(WorkItem item, RenderPlan plan)
            throws IOException {
        List<BufferedImage> channelImages = new ArrayList<BufferedImage>();
        for (int i = 0; i < item.channels.size(); i++) {
            PresentationTileRecord record =
                    item.presentationCoverage.channelRecord(item.channels.get(i).channelIndex);
            BufferedImage image = ImageIO.read(record.imageFile());
            if (image == null) {
                throw new IOException("Could not read presentation PNG: "
                        + record.imageFile().getAbsolutePath());
            }
            channelImages.add(scaleToLongEdge(toRgb(image), plan.longEdge));
        }

        PresentationTileRecord mergeRecord = item.presentationCoverage.mergeRecord;
        BufferedImage merge = ImageIO.read(mergeRecord.imageFile());
        if (merge == null) {
            throw new IOException("Could not read presentation merge PNG: "
                    + mergeRecord.imageFile().getAbsolutePath());
        }
        return new RenderedPreviews(channelImages, scaleToLongEdge(toRgb(merge), plan.longEdge));
    }

    private static RenderedPreviews renderFromSource(WorkItem item,
                                                     RenderPlan plan,
                                                     RenderContext context,
                                                     SourceAccess sourceAccess)
            throws Exception {
        ImagePlus source = sourceAccess.openImage(item);
        if (source == null) {
            throw new IOException("Could not open source image for series "
                    + item.seriesNumber + " (" + item.seriesName + ").");
        }

        ImagePlus working = null;
        ImagePlus[] split = null;
        List<ImagePlus> projected = new ArrayList<ImagePlus>();
        try {
            working = applyConfiguredZSliceSubset(context.binConfig, item, source);
            split = ChannelSplitter.split(working);
            if (split == null || split.length == 0) {
                throw new IOException("No channels found in " + item.seriesName);
            }

            int n = Math.min(split.length, item.channels.size());
            List<BufferedImage> channelImages = new ArrayList<BufferedImage>();
            for (int i = 0; i < n; i++) {
                ChannelSpec spec = item.channels.get(i);
                ImagePlus mip = ZProjector.run(split[i], "max");
                ImagePlus scaled = downscaleImagePlus(mip, plan.longEdge);
                closeQuietly(mip);

                DisplayRange range = context.rangeResolver.resolve(item, spec);
                applyDisplayRange(scaled, range);
                projected.add(scaled);
                channelImages.add(renderPseudoColor(scaled, spec.colorName));
            }
            BufferedImage merge = mergePseudoColorImages(channelImages);
            return new RenderedPreviews(channelImages, merge);
        } finally {
            for (ImagePlus imp : projected) {
                closeQuietly(imp);
            }
            closeArray(split);
            if (working != null && working != source) {
                closeQuietly(working);
            }
            closeQuietly(source);
        }
    }

    private static ImagePlus applyConfiguredZSliceSubset(BinConfig cfg,
                                                         WorkItem item,
                                                         ImagePlus source) {
        if (source == null || cfg == null || !cfg.usesZSliceSubset()) {
            return source;
        }
        return ZSliceOps.applyConfiguredRange(source, cfg, item.seriesIndex,
                "Representative Figure preview");
    }

    private static ImagePlus downscaleImagePlus(ImagePlus source, int longEdge) {
        ImageProcessor processor = source.getProcessor().duplicate();
        int[] size = scaledDimensions(source.getWidth(), source.getHeight(), longEdge);
        if (size[0] != source.getWidth() || size[1] != source.getHeight()) {
            processor.setInterpolate(true);
            processor = processor.resize(size[0], size[1], true);
        }
        ImagePlus out = new ImagePlus(source.getTitle(), processor);
        if (source.getCalibration() != null) {
            out.setCalibration(source.getCalibration().copy());
        }
        return out;
    }

    private static void applyDisplayRange(ImagePlus imp, DisplayRange range) {
        if (imp == null) return;
        if (range != null && range.isValid()) {
            imp.setDisplayRange(range.min, range.max);
            return;
        }
        new ContrastEnhancer().stretchHistogram(imp, AUTO_SATURATION);
    }

    private static BufferedImage renderPseudoColor(ImagePlus imp, String colorName) {
        ImageProcessor ip = imp.getProcessor();
        int width = Math.max(1, ip.getWidth());
        int height = Math.max(1, ip.getHeight());
        double min = imp.getDisplayRangeMin();
        double max = imp.getDisplayRangeMax();
        if (!Double.isFinite(min) || !Double.isFinite(max) || max <= min) {
            min = ip.getMin();
            max = ip.getMax();
        }
        if (!Double.isFinite(min) || !Double.isFinite(max) || max <= min) {
            min = 0.0;
            max = imp.getBitDepth() == 16 ? 65535.0 : 255.0;
        }

        ColorMask mask = ColorMask.from(colorName);
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        double range = max - min;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                double scaled = (ip.getPixelValue(x, y) - min) / range;
                int value = greyByte(scaled);
                int r = mask.red ? value : 0;
                int g = mask.green ? value : 0;
                int b = mask.blue ? value : 0;
                image.setRGB(x, y, (r << 16) | (g << 8) | b);
            }
        }
        return image;
    }

    private static BufferedImage mergePseudoColorImages(List<BufferedImage> channelImages) {
        if (channelImages == null || channelImages.isEmpty()) {
            return new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB);
        }
        BufferedImage first = channelImages.get(0);
        int width = first.getWidth();
        int height = first.getHeight();
        BufferedImage out = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        for (BufferedImage channel : channelImages) {
            if (channel == null) continue;
            if (channel.getWidth() != width || channel.getHeight() != height) {
                throw new IllegalArgumentException("Representative preview channels have mismatched sizes.");
            }
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    int existing = out.getRGB(x, y);
                    int next = channel.getRGB(x, y);
                    int r = Math.min(255, ((existing >> 16) & 0xff) + ((next >> 16) & 0xff));
                    int g = Math.min(255, ((existing >> 8) & 0xff) + ((next >> 8) & 0xff));
                    int b = Math.min(255, (existing & 0xff) + (next & 0xff));
                    out.setRGB(x, y, (r << 16) | (g << 8) | b);
                }
            }
        }
        return out;
    }

    private static int greyByte(double scaled) {
        if (Double.isNaN(scaled) || scaled <= 0.0) return 0;
        if (scaled >= 1.0) return 255;
        return (int) Math.round(scaled * 255.0);
    }

    private static void writePreviews(RenderedPreviews previews, RenderPlan plan)
            throws IOException {
        for (int i = 0; i < previews.channelImages.size(); i++) {
            writePngAtomically(previews.channelImages.get(i), plan.channelFiles.get(i));
        }
        writePngAtomically(previews.mergeImage, plan.mergeFile);
    }

    private static void writePngAtomically(BufferedImage image, File outputFile)
            throws IOException {
        File parent = outputFile.getParentFile();
        if (parent != null) IoUtils.mustMkdirs(parent);
        File temp = File.createTempFile("." + outputFile.getName() + ".", ".png",
                parent == null ? new File(".") : parent);
        boolean moved = false;
        try {
            if (!ImageIO.write(image, "png", temp)) {
                throw new IOException("No PNG writer available");
            }
            IoUtils.moveReplacing(temp.toPath(), outputFile.toPath());
            moved = true;
        } finally {
            if (!moved) {
                try {
                    java.nio.file.Files.deleteIfExists(temp.toPath());
                } catch (IOException ignored) {
                }
            }
        }
    }

    private static List<WorkItem> buildWorkItems(String directory,
                                                 List<SeriesMeta> metas,
                                                 List<QcSelectionCandidate> candidates,
                                                 BinConfig cfg,
                                                 List<PresentationTileRecord> records,
                                                 SourceAccess sourceAccess) {
        Map<Integer, QcSelectionCandidate> byIndex =
                new LinkedHashMap<Integer, QcSelectionCandidate>();
        if (candidates != null) {
            for (QcSelectionCandidate candidate : candidates) {
                byIndex.put(Integer.valueOf(candidate.seriesIndex), candidate);
            }
        }

        List<WorkItem> items = new ArrayList<WorkItem>();
        for (SeriesMeta meta : metas == null ? Collections.<SeriesMeta>emptyList() : metas) {
            if (meta == null || ImageNameParser.isPreviewSeriesName(meta.name)) {
                continue;
            }
            QcSelectionCandidate candidate = byIndex.get(Integer.valueOf(meta.index));
            List<PresentationTileRecord> matching =
                    matchingPresentationRecords(meta, candidate, records);
            File sourcePath = sourceAccess == null ? null : sourceAccess.sourcePath(meta.index);
            items.add(workItemFor(directory, meta, candidate, cfg, matching, sourcePath));
        }
        return items;
    }

    private static WorkItem workItemFor(String directory,
                                        SeriesMeta meta,
                                        QcSelectionCandidate candidate,
                                        BinConfig cfg,
                                        List<PresentationTileRecord> matchingRecords,
                                        File sourcePath) {
        int seriesIndex = meta == null ? -1 : meta.index;
        int seriesNumber = seriesIndex + 1;
        String seriesName = candidate != null && !candidate.seriesName.trim().isEmpty()
                ? candidate.seriesName
                : (meta == null ? "" : safe(meta.name));
        NameParts parts = ImageNameParser.parse(seriesName);
        String animal = candidate != null && !candidate.animalName.trim().isEmpty()
                ? candidate.animalName
                : parts.animal;
        String condition = candidate == null ? "" : safe(candidate.conditionName);
        if (condition.isEmpty()) condition = animal;
        List<ChannelSpec> channels = channelSpecs(cfg, meta, matchingRecords);
        ManifestCoverage coverage = ManifestCoverage.from(matchingRecords, channels);
        return new WorkItem(
                RepresentativeStatTable.seriesIdForIndex(seriesIndex),
                seriesIndex,
                seriesNumber,
                seriesName,
                animal,
                condition,
                parts.hemisphere,
                parts.csvRegion(),
                sourcePath,
                channels,
                coverage,
                sourceFingerprint(sourcePath, meta));
    }

    private static List<ChannelSpec> channelSpecs(BinConfig cfg,
                                                  SeriesMeta meta,
                                                  List<PresentationTileRecord> records) {
        int count = 0;
        if (cfg != null) {
            count = Math.max(count, cfg.channelNames.size());
            count = Math.max(count, cfg.channelColors.size());
        }
        if (meta != null) {
            count = Math.max(count, meta.nChannels);
        }
        if (records != null) {
            for (PresentationTileRecord record : records) {
                if (record != null && record.channelIndex() >= 0) {
                    count = Math.max(count, record.channelIndex() + 1);
                }
            }
        }
        if (count <= 0) count = 1;

        List<ChannelSpec> specs = new ArrayList<ChannelSpec>();
        for (int i = 0; i < count; i++) {
            String name = valueAt(cfg == null ? null : cfg.channelNames, i, "C" + (i + 1));
            String color = valueAt(cfg == null ? null : cfg.channelColors, i, "Grays");
            if (name.trim().isEmpty()) name = "C" + (i + 1);
            if (color.trim().isEmpty()) color = "Grays";
            specs.add(new ChannelSpec(i, name, color));
        }
        return specs;
    }

    private static List<PresentationTileRecord> matchingPresentationRecords(
            SeriesMeta meta,
            QcSelectionCandidate candidate,
            List<PresentationTileRecord> records) {
        if (records == null || records.isEmpty()) {
            return Collections.emptyList();
        }
        LinkedHashSet<String> sourceIds = candidateSourceIds(meta, candidate);
        String seriesName = candidate != null && !candidate.seriesName.trim().isEmpty()
                ? candidate.seriesName
                : (meta == null ? "" : safe(meta.name));
        NameParts parts = ImageNameParser.parse(seriesName);
        String animal = candidate != null && !candidate.animalName.trim().isEmpty()
                ? candidate.animalName
                : parts.animal;
        List<PresentationTileRecord> out = new ArrayList<PresentationTileRecord>();
        for (PresentationTileRecord record : records) {
            if (record == null || record.imageFile() == null || !record.imageFile().isFile()) {
                continue;
            }
            String imageId = safe(record.imageId());
            if (!imageId.isEmpty() && sourceIds.contains(imageId)) {
                out.add(record);
                continue;
            }
            if (imageId.isEmpty()
                    && sameText(record.animal(), animal)
                    && sameText(record.hemisphere(), parts.hemisphere)
                    && sameText(record.region(), parts.csvRegion())) {
                out.add(record);
            }
        }
        return out;
    }

    private static LinkedHashSet<String> candidateSourceIds(SeriesMeta meta,
                                                            QcSelectionCandidate candidate) {
        int index = candidate == null ? (meta == null ? -1 : meta.index) : candidate.seriesIndex;
        int seriesNumber = index + 1;
        String seriesName = candidate != null && !candidate.seriesName.trim().isEmpty()
                ? candidate.seriesName
                : (meta == null ? "" : safe(meta.name));
        LinkedHashSet<String> ids = new LinkedHashSet<String>();
        ids.add("series:" + seriesNumber);
        if (!seriesName.trim().isEmpty()) {
            ids.add("series:" + seriesNumber + "|" + seriesName.trim());
            String extracted = ImageNameParser.extractBioFormatsSeriesName(seriesName);
            if (extracted != null && !extracted.trim().isEmpty()) {
                ids.add("series:" + seriesNumber + "|" + extracted.trim());
            }
        }
        return ids;
    }

    private static String sourceFingerprint(File sourcePath, SeriesMeta meta) {
        StringBuilder sb = new StringBuilder();
        sb.append("source=");
        if (sourcePath != null) {
            sb.append(sourcePath.getAbsolutePath())
                    .append('|').append(sourcePath.length())
                    .append('|').append(sourcePath.lastModified());
        }
        if (meta != null) {
            sb.append("|meta=")
                    .append(meta.index).append('|')
                    .append(safe(meta.name)).append('|')
                    .append(meta.width).append('x').append(meta.height)
                    .append('z').append(meta.nSlices)
                    .append('c').append(meta.nChannels);
        }
        return sb.toString();
    }

    private static BufferedImage scaleToLongEdge(BufferedImage image, int longEdge) {
        if (image == null) return null;
        int[] size = scaledDimensions(image.getWidth(), image.getHeight(), longEdge);
        if (size[0] == image.getWidth() && size[1] == image.getHeight()) {
            return toRgb(image);
        }
        BufferedImage scaled = new BufferedImage(size[0], size[1], BufferedImage.TYPE_INT_RGB);
        Graphics2D g = scaled.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(RenderingHints.KEY_RENDERING,
                    RenderingHints.VALUE_RENDER_QUALITY);
            g.drawImage(image, 0, 0, size[0], size[1], null);
        } finally {
            g.dispose();
        }
        return scaled;
    }

    private static BufferedImage toRgb(BufferedImage image) {
        if (image.getType() == BufferedImage.TYPE_INT_RGB) {
            return image;
        }
        BufferedImage out = new BufferedImage(image.getWidth(), image.getHeight(),
                BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        try {
            g.drawImage(image, 0, 0, null);
        } finally {
            g.dispose();
        }
        return out;
    }

    private static int[] scaledDimensions(int width, int height, int longEdge) {
        int safeWidth = Math.max(1, width);
        int safeHeight = Math.max(1, height);
        int max = Math.max(safeWidth, safeHeight);
        int target = Math.max(1, longEdge);
        if (max <= target) {
            return new int[]{safeWidth, safeHeight};
        }
        double scale = (double) target / (double) max;
        return new int[]{
                Math.max(1, (int) Math.round(safeWidth * scale)),
                Math.max(1, (int) Math.round(safeHeight * scale))};
    }

    private static void closeArray(ImagePlus[] images) {
        if (images == null) return;
        for (ImagePlus image : images) {
            closeQuietly(image);
        }
    }

    private static void closeQuietly(ImagePlus image) {
        if (image == null) return;
        image.changes = false;
        image.close();
        image.flush();
    }

    private static String valueAt(List<String> values, int index, String fallback) {
        if (values == null || index < 0 || index >= values.size()) return fallback;
        String value = values.get(index);
        return value == null ? fallback : value;
    }

    private static boolean sameText(String a, String b) {
        return safe(a).equalsIgnoreCase(safe(b));
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }

    private static String safeFileName(String value) {
        String clean = ChannelFilenameCodec.toSafe(value == null ? "" : value);
        return clean.isEmpty() ? "series" : clean;
    }

    private static String sha1(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            byte[] bytes = digest.digest((text == null ? "" : text).getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < bytes.length; i++) {
                String hex = Integer.toHexString(bytes[i] & 0xff);
                if (hex.length() == 1) sb.append('0');
                sb.append(hex);
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-1 digest unavailable", e);
        } catch (java.io.UnsupportedEncodingException e) {
            throw new IllegalStateException("UTF-8 encoding unavailable", e);
        }
    }

    private static final class RenderContext {
        final File cacheDir;
        final BinConfig binConfig;
        final RepresentativeFigureConfig config;
        final int longEdge;
        final DisplayRangeResolver rangeResolver;

        RenderContext(File cacheDir,
                      BinConfig binConfig,
                      RepresentativeFigureConfig config,
                      int longEdge) {
            this.cacheDir = cacheDir;
            this.binConfig = binConfig == null ? new BinConfig() : binConfig;
            this.config = config == null ? new RepresentativeFigureConfig() : config;
            this.longEdge = Math.max(1, longEdge);
            this.rangeResolver = new DisplayRangeResolver(this.binConfig, this.config);
        }
    }

    private static final class RenderPlan {
        final int longEdge;
        final String cacheKey;
        final List<File> channelFiles;
        final File mergeFile;

        RenderPlan(int longEdge, String cacheKey, List<File> channelFiles, File mergeFile) {
            this.longEdge = longEdge;
            this.cacheKey = cacheKey;
            this.channelFiles = channelFiles;
            this.mergeFile = mergeFile;
        }

        static RenderPlan create(WorkItem item, RenderContext context) {
            String baseKey = buildCacheKey(item, context);
            String hash = sha1(baseKey).substring(0, 16);
            String prefix = String.format(Locale.ROOT, "%04d_%s_%s",
                    Integer.valueOf(Math.max(0, item.seriesIndex)),
                    safeFileName(item.animal),
                    hash);
            List<File> channelFiles = new ArrayList<File>();
            for (ChannelSpec spec : item.channels) {
                String name = prefix + "_C" + (spec.channelIndex + 1)
                        + "_" + safeFileName(spec.channelName) + ".png";
                channelFiles.add(new File(context.cacheDir, name));
            }
            File merge = new File(context.cacheDir, prefix + "_" + MERGE_NAME + ".png");
            return new RenderPlan(context.longEdge, baseKey, channelFiles, merge);
        }

        boolean allFilesExist() {
            if (mergeFile == null || !mergeFile.isFile()) return false;
            for (File file : channelFiles) {
                if (file == null || !file.isFile()) return false;
            }
            return true;
        }

        private static String buildCacheKey(WorkItem item, RenderContext context) {
            StringBuilder sb = new StringBuilder(CACHE_VERSION);
            sb.append("|long=").append(context.longEdge);
            sb.append("|series=").append(item.id)
                    .append('|').append(item.seriesIndex)
                    .append('|').append(item.seriesName)
                    .append('|').append(item.animal)
                    .append('|').append(item.condition)
                    .append('|').append(item.hemisphere)
                    .append('|').append(item.region);
            sb.append('|').append(item.sourceFingerprint);
            sb.append("|mode=").append(item.presentationCoverage.covers() ? "presentation" : "generated");
            for (ChannelSpec spec : item.channels) {
                sb.append("|channel=")
                        .append(spec.channelIndex)
                        .append(':').append(spec.channelName)
                        .append(':').append(spec.colorName)
                        .append(':').append(context.rangeResolver.rangeToken(item, spec));
            }
            sb.append(item.presentationCoverage.fingerprint());
            return sb.toString();
        }
    }

    private static final class WorkItem {
        final String id;
        final int seriesIndex;
        final int seriesNumber;
        final String seriesName;
        final String animal;
        final String condition;
        final String hemisphere;
        final String region;
        final File sourcePath;
        final List<ChannelSpec> channels;
        final ManifestCoverage presentationCoverage;
        final String sourceFingerprint;

        WorkItem(String id,
                 int seriesIndex,
                 int seriesNumber,
                 String seriesName,
                 String animal,
                 String condition,
                 String hemisphere,
                 String region,
                 File sourcePath,
                 List<ChannelSpec> channels,
                 ManifestCoverage presentationCoverage,
                 String sourceFingerprint) {
            this.id = safe(id);
            this.seriesIndex = seriesIndex;
            this.seriesNumber = seriesNumber;
            this.seriesName = safe(seriesName);
            this.animal = safe(animal);
            this.condition = safe(condition);
            this.hemisphere = safe(hemisphere);
            this.region = safe(region);
            this.sourcePath = sourcePath;
            this.channels = channels == null
                    ? Collections.<ChannelSpec>emptyList()
                    : Collections.unmodifiableList(new ArrayList<ChannelSpec>(channels));
            this.presentationCoverage = presentationCoverage == null
                    ? ManifestCoverage.empty()
                    : presentationCoverage;
            this.sourceFingerprint = safe(sourceFingerprint);
        }
    }

    private static final class ChannelSpec {
        final int channelIndex;
        final String channelName;
        final String colorName;

        ChannelSpec(int channelIndex, String channelName, String colorName) {
            this.channelIndex = channelIndex;
            this.channelName = safe(channelName);
            this.colorName = safe(colorName).isEmpty() ? "Grays" : safe(colorName);
        }
    }

    private static final class ManifestCoverage {
        final Map<Integer, PresentationTileRecord> channelsByIndex;
        final PresentationTileRecord mergeRecord;
        final boolean complete;

        ManifestCoverage(Map<Integer, PresentationTileRecord> channelsByIndex,
                         PresentationTileRecord mergeRecord,
                         boolean complete) {
            this.channelsByIndex = channelsByIndex == null
                    ? Collections.<Integer, PresentationTileRecord>emptyMap()
                    : channelsByIndex;
            this.mergeRecord = mergeRecord;
            this.complete = complete;
        }

        static ManifestCoverage empty() {
            return new ManifestCoverage(null, null, false);
        }

        static ManifestCoverage from(List<PresentationTileRecord> records,
                                     List<ChannelSpec> specs) {
            if (records == null || records.isEmpty()) {
                return empty();
            }
            Map<Integer, PresentationTileRecord> byIndex =
                    new LinkedHashMap<Integer, PresentationTileRecord>();
            PresentationTileRecord merge = null;
            for (PresentationTileRecord record : records) {
                if (record == null || record.imageFile() == null || !record.imageFile().isFile()) {
                    continue;
                }
                if (record.channelIndex() < 0
                        || MERGE_NAME.equalsIgnoreCase(record.outputName())
                        || MERGE_NAME.equalsIgnoreCase(record.stainName())) {
                    if (merge == null) merge = record;
                } else {
                    byIndex.put(Integer.valueOf(record.channelIndex()), record);
                }
            }
            for (ChannelSpec spec : specs) {
                if (!byIndex.containsKey(Integer.valueOf(spec.channelIndex))) {
                    return new ManifestCoverage(byIndex, merge, false);
                }
            }
            return new ManifestCoverage(byIndex, merge, merge != null);
        }

        boolean covers() {
            return complete
                    && mergeRecord != null
                    && mergeRecord.imageFile() != null
                    && mergeRecord.imageFile().isFile()
                    && !channelsByIndex.isEmpty();
        }

        PresentationTileRecord channelRecord(int channelIndex) {
            return channelsByIndex.get(Integer.valueOf(channelIndex));
        }

        String fingerprint() {
            StringBuilder sb = new StringBuilder("|presentation=");
            for (Map.Entry<Integer, PresentationTileRecord> entry : channelsByIndex.entrySet()) {
                sb.append(entry.getKey()).append(':')
                        .append(fileFingerprint(entry.getValue().imageFile())).append(';');
            }
            if (mergeRecord != null) {
                sb.append("merge:").append(fileFingerprint(mergeRecord.imageFile()));
            }
            return sb.toString();
        }

        private static String fileFingerprint(File file) {
            if (file == null) return "";
            return file.getAbsolutePath() + "|" + file.length() + "|" + file.lastModified();
        }
    }

    private static final class RenderedPreviews {
        final List<BufferedImage> channelImages;
        final BufferedImage mergeImage;

        RenderedPreviews(List<BufferedImage> channelImages, BufferedImage mergeImage) {
            this.channelImages = channelImages == null
                    ? Collections.<BufferedImage>emptyList()
                    : channelImages;
            this.mergeImage = mergeImage == null
                    ? new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB)
                    : mergeImage;
        }
    }

    private static final class DisplayRangeResolver {
        private final BinConfig cfg;
        private final RepresentativeFigureConfig config;
        private final Map<String, DisplayRange> quickRanges =
                new LinkedHashMap<String, DisplayRange>();
        private final Map<String, DisplayRange> quickOverall =
                new LinkedHashMap<String, DisplayRange>();

        DisplayRangeResolver(BinConfig cfg, RepresentativeFigureConfig config) {
            this.cfg = cfg == null ? new BinConfig() : cfg;
            this.config = config == null ? new RepresentativeFigureConfig() : config;
            buildQuickRanges();
        }

        DisplayRange resolve(WorkItem item, ChannelSpec spec) {
            DisplayRange custom = representativeCustomRange(item, spec);
            if (custom != null) return custom;

            DisplayRange setup = parseRange(valueAt(cfg.channelMinMax, spec.channelIndex, ""));
            if (setup != null) return setup.withSource("setup");

            DisplayRange quick = quickRange(item, spec);
            if (quick != null) return quick;

            return null;
        }

        String rangeToken(WorkItem item, ChannelSpec spec) {
            DisplayRange range = resolve(item, spec);
            return range == null ? "auto-enhance" : range.source + ":" + range.min + "-" + range.max;
        }

        private DisplayRange representativeCustomRange(WorkItem item, ChannelSpec spec) {
            // TODO(representative-image-figure stage 10): read representative-custom
            // display ranges from persisted project.json extras before setup ranges.
            return null;
        }

        private DisplayRange quickRange(WorkItem item, ChannelSpec spec) {
            String conditionKey = conditionChannelKey(item.condition, spec.channelName);
            DisplayRange range = quickRanges.get(conditionKey);
            if (range != null) return range;
            range = quickRanges.get(conditionChannelKey(item.condition, "C" + (spec.channelIndex + 1)));
            if (range != null) return range;
            range = quickOverall.get(channelKey(spec.channelName));
            if (range != null) return range;
            return quickOverall.get(channelKey("C" + (spec.channelIndex + 1)));
        }

        private void buildQuickRanges() {
            if (config.statistic != RepresentativeStatistic.QUICK
                    || config.statTable == null
                    || config.statTable.isEmpty()) {
                return;
            }
            for (RepresentativeStatTable.Row row : config.statTable.rows()) {
                if (row == null || row.valuesByChannel == null) continue;
                for (Map.Entry<String, Double> entry : row.valuesByChannel.entrySet()) {
                    Double value = entry.getValue();
                    if (value == null || !Double.isFinite(value.doubleValue()) || value.doubleValue() <= 0) {
                        continue;
                    }
                    putMax(quickRanges, conditionChannelKey(row.conditionName, entry.getKey()),
                            value.doubleValue());
                    putMax(quickOverall, channelKey(entry.getKey()), value.doubleValue());
                }
            }
        }

        private static void putMax(Map<String, DisplayRange> ranges, String key, double max) {
            DisplayRange existing = ranges.get(key);
            if (existing == null || max > existing.max) {
                ranges.put(key, new DisplayRange(0.0, max, "quick"));
            }
        }

        private static DisplayRange parseRange(String token) {
            String text = token == null ? "" : token.trim();
            if (text.isEmpty() || "none".equalsIgnoreCase(text)) return null;
            int dash = text.indexOf('-');
            if (dash <= 0 || dash >= text.length() - 1) return null;
            try {
                double min = Double.parseDouble(text.substring(0, dash).trim());
                double max = Double.parseDouble(text.substring(dash + 1).trim());
                DisplayRange range = new DisplayRange(min, max, "setup");
                return range.isValid() ? range : null;
            } catch (NumberFormatException e) {
                return null;
            }
        }

        private static String conditionChannelKey(String condition, String channel) {
            return safe(condition).toLowerCase(Locale.ROOT) + "|"
                    + channelKey(channel);
        }

        private static String channelKey(String channel) {
            return safe(channel).toLowerCase(Locale.ROOT);
        }
    }

    private static final class DisplayRange {
        final double min;
        final double max;
        final String source;

        DisplayRange(double min, double max, String source) {
            this.min = min;
            this.max = max;
            this.source = safe(source);
        }

        boolean isValid() {
            return Double.isFinite(min) && Double.isFinite(max) && max > min;
        }

        DisplayRange withSource(String newSource) {
            return new DisplayRange(min, max, newSource);
        }
    }

    private static final class ColorMask {
        final boolean red;
        final boolean green;
        final boolean blue;

        ColorMask(boolean red, boolean green, boolean blue) {
            this.red = red;
            this.green = green;
            this.blue = blue;
        }

        static ColorMask from(String colorName) {
            String c = safe(colorName).toLowerCase(Locale.ROOT);
            boolean gray = c.isEmpty() || "gray".equals(c) || "grey".equals(c)
                    || "grays".equals(c) || "greys".equals(c);
            return new ColorMask(
                    gray || "red".equals(c) || "magenta".equals(c) || "yellow".equals(c),
                    gray || "green".equals(c) || "cyan".equals(c) || "yellow".equals(c),
                    gray || "blue".equals(c) || "cyan".equals(c) || "magenta".equals(c));
        }
    }

    private static class SourceAccess {
        private final String directory;
        private final ImageCache imageCache;
        private final boolean useTifCache;
        private final DeferredImageSupplier supplier;
        private final boolean canUseSessionCache;
        private boolean sessionCacheAttempted = false;
        private List<ImagePlus> sessionImages = null;
        private final ImagePlus fixedImage;

        SourceAccess(String directory, ImageCache imageCache, boolean useTifCache)
                throws Exception {
            this.directory = directory;
            this.imageCache = imageCache;
            this.useTifCache = useTifCache;
            this.supplier = ImageSourceDispatcher.createSupplier(directory);
            this.canUseSessionCache = imageCache != null && canUseSessionImageCache(directory);
            this.fixedImage = null;
        }

        private SourceAccess(ImagePlus fixedImage) {
            this.directory = "";
            this.imageCache = null;
            this.useTifCache = false;
            this.supplier = null;
            this.canUseSessionCache = false;
            this.fixedImage = fixedImage;
        }

        static SourceAccess none() {
            return new SourceAccess((ImagePlus) null);
        }

        static SourceAccess fixed(ImagePlus image) {
            return new SourceAccess(image);
        }

        File sourcePath(int seriesIndex) {
            if (supplier == null) return null;
            try {
                return supplier.getContainerFileForSeries(seriesIndex);
            } catch (RuntimeException e) {
                return null;
            }
        }

        ImagePlus openImage(WorkItem item) throws Exception {
            if (fixedImage != null) {
                synchronized (fixedImage) {
                    return ImageOps.duplicateThreadSafe(fixedImage);
                }
            }
            ImagePlus cached = openSessionCachedImage(item.seriesIndex);
            if (cached != null) return cached;
            if (useTifCache && directory != null && !directory.isEmpty()
                    && TifCache.cacheExists(directory)) {
                ImagePlus tif = TifCache.loadSingle(directory, item.seriesIndex);
                if (tif != null) return tif;
            }
            if (supplier == null) return null;
            return supplier.openSeriesMaterialized(item.seriesIndex);
        }

        void close() {
            if (supplier != null) {
                supplier.shutdownPrefetch();
            }
        }

        private ImagePlus openSessionCachedImage(int seriesIndex) {
            List<ImagePlus> images = sessionImages();
            if (images == null || seriesIndex < 0 || seriesIndex >= images.size()) {
                return null;
            }
            ImagePlus source = images.get(seriesIndex);
            if (source == null) return null;
            synchronized (source) {
                return ImageOps.duplicateThreadSafe(source);
            }
        }

        private synchronized List<ImagePlus> sessionImages() {
            if (!canUseSessionCache || sessionCacheAttempted) {
                return sessionImages;
            }
            sessionCacheAttempted = true;
            try {
                sessionImages = imageCache.getImages(directory);
            } catch (RuntimeException e) {
                sessionImages = null;
            }
            return sessionImages;
        }

        private static boolean canUseSessionImageCache(String directory) {
            try {
                if (ImageSourceDispatcher.hasProjectManifest(directory)) {
                    return false;
                }
                File dir = new File(directory);
                return ImageSourceDispatcher.detectMode(directory)
                        == ImageSourceDispatcher.SourceMode.CONTAINER
                        && ImageSourceDispatcher.listContainers(dir).size() == 1;
            } catch (RuntimeException e) {
                return false;
            }
        }
    }
}
