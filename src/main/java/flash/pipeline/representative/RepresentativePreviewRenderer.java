package flash.pipeline.representative;

import flash.pipeline.bin.BinConfig;
import flash.pipeline.bin.BinConfigIO;
import flash.pipeline.image.ImageOps;
import flash.pipeline.image.OrientationOps;
import flash.pipeline.io.DeferredImageSupplier;
import flash.pipeline.io.FlashProjectLayout;
import flash.pipeline.io.ImageCache;
import flash.pipeline.io.ImageSourceDispatcher;
import flash.pipeline.io.IoUtils;
import flash.pipeline.io.OrientationManifestIO;
import flash.pipeline.io.SeriesMeta;
import flash.pipeline.io.TifCache;
import flash.pipeline.naming.ChannelFilenameCodec;
import flash.pipeline.naming.ImageNameParser;
import flash.pipeline.naming.NameParts;
import flash.pipeline.naming.OrientationManifestRow;
import flash.pipeline.naming.ResolvedImageMetadata;
import flash.pipeline.presentation.PresentationTileRecord;
import flash.pipeline.presentation.PresentationTileWriter;
import flash.pipeline.qc.QcMinMaxPerConditionSelector;
import flash.pipeline.qc.QcSelectionCandidate;
import flash.pipeline.zslice.ZSliceOps;
import ij.IJ;
import ij.ImagePlus;
import ij.ImageStack;
import ij.measure.Calibration;
import ij.plugin.ChannelSplitter;
import ij.plugin.ContrastEnhancer;
import ij.plugin.ZProjector;
import ij.process.ImageProcessor;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
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
import java.util.Iterator;
import java.util.Optional;
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
    private static final Object ORIGINAL_TIF_CACHE_LOCK = new Object();

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
        return render(directory, config, imageCache, parallelThreads, useTifCache, null, null);
    }

    public static List<RepresentativeSeries> render(String directory,
                                                    RepresentativeFigureConfig config,
                                                    ImageCache imageCache,
                                                    int parallelThreads,
                                                    boolean useTifCache,
                                                    List<SeriesMeta> metas,
                                                    List<QcSelectionCandidate> candidates)
            throws Exception {
        long start = System.currentTimeMillis();
        FlashProjectLayout layout = FlashProjectLayout.forDirectory(directory);
        BinConfig binConfig = BinConfigIO.readPartialFromDirectory(directory);
        RepresentativeFigureConfig safeConfig =
                config == null ? new RepresentativeFigureConfig() : config;
        List<SeriesMeta> effectiveMetas =
                metas == null ? ImageSourceDispatcher.readAllMetadata(directory) : metas;
        List<QcSelectionCandidate> effectiveCandidates = candidates == null
                ? QcMinMaxPerConditionSelector.buildCandidates(directory, effectiveMetas)
                : candidates;
        List<PresentationTileRecord> presentationRecords =
                readPresentationManifest(layout);

        SourceAccess sourceAccess = new SourceAccess(
                directory, imageCache, useTifCache, false);
        try {
            MetadataResolver metadataResolver = new MetadataResolver(directory);
            List<WorkItem> items = buildWorkItems(
                    directory, effectiveMetas, effectiveCandidates, binConfig,
                    presentationRecords, sourceAccess, metadataResolver);
            RenderContext context = new RenderContext(
                    previewCacheDir(layout), binConfig, safeConfig, DEFAULT_THUMBNAIL_LONG_EDGE);
            IJ.log("[Representative Figure] Preparing preview thumbnails for "
                    + items.size() + " image series"
                    + " (" + presentationRecords.size() + " presentation manifest record"
                    + (presentationRecords.size() == 1 ? "" : "s") + ").");
            List<RepresentativeSeries> rendered =
                    renderItems(items, context, sourceAccess, parallelThreads);
            IJ.log("[Representative Figure] Preview thumbnails ready in "
                    + elapsed(start) + ": " + previewSummary(rendered) + ".");
            return rendered;
        } finally {
            sourceAccess.close();
        }
    }

    public static List<RenderedFinalSeries> renderFinal(String directory,
                                                        RepresentativeFigureConfig config,
                                                        ImageCache imageCache,
                                                        int parallelThreads,
                                                        boolean useTifCache) throws Exception {
        return renderFinal(directory, config, imageCache, parallelThreads, useTifCache, null);
    }

    public static List<RenderedFinalSeries> renderFinal(String directory,
                                                        RepresentativeFigureConfig config,
                                                        ImageCache imageCache,
                                                        int parallelThreads,
                                                        boolean useTifCache,
                                                        List<SeriesMeta> metas)
            throws Exception {
        long start = System.currentTimeMillis();
        RepresentativeFigureConfig safeConfig =
                config == null ? new RepresentativeFigureConfig() : config;
        RepresentativeSelection selection = safeConfig.selection;
        if (selection == null || !selection.isComplete()) {
            throw new IllegalStateException("Representative selection is not complete.");
        }

        BinConfig binConfig = BinConfigIO.readPartialFromDirectory(directory);
        List<SeriesMeta> effectiveMetas =
                metas == null ? ImageSourceDispatcher.readAllMetadata(directory) : metas;
        SourceAccess sourceAccess = new SourceAccess(
                directory, imageCache, useTifCache, true);
        try {
            MetadataResolver metadataResolver = new MetadataResolver(directory);
            List<WorkItem> items = buildFinalWorkItems(
                    directory, selection, binConfig, effectiveMetas, sourceAccess,
                    metadataResolver);
            RenderContext context = RenderContext.finalRender(binConfig, safeConfig);
            IJ.log("[Representative Figure] Rendering final full-resolution representatives for "
                    + items.size() + " condition" + (items.size() == 1 ? "" : "s") + ".");
            List<RenderedFinalSeries> rendered =
                    renderFinalItems(items, context, sourceAccess, parallelThreads);
            IJ.log("[Representative Figure] Final representative images rendered in "
                    + elapsed(start) + ".");
            return rendered;
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
        return renderPresentationSeriesForTests(
                null, cacheDir, meta, candidate, binConfig, config, records);
    }

    static RepresentativeSeries renderPresentationSeriesForTests(String directory,
                                                                 File cacheDir,
                                                                 SeriesMeta meta,
                                                                 QcSelectionCandidate candidate,
                                                                 BinConfig binConfig,
                                                                 RepresentativeFigureConfig config,
                                                                 List<PresentationTileRecord> records)
            throws Exception {
        return renderPresentationSeriesForTests(
                directory, cacheDir, meta, candidate, binConfig, config, records, null);
    }

    static RepresentativeSeries renderPresentationSeriesForTests(String directory,
                                                                 File cacheDir,
                                                                 SeriesMeta meta,
                                                                 QcSelectionCandidate candidate,
                                                                 BinConfig binConfig,
                                                                 RepresentativeFigureConfig config,
                                                                 List<PresentationTileRecord> records,
                                                                 File sourcePath)
            throws Exception {
        List<PresentationTileRecord> safeRecords =
                records == null ? Collections.<PresentationTileRecord>emptyList() : records;
        WorkItem item = workItemFor(directory, meta, candidate, binConfig, safeRecords, sourcePath);
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
        return renderGeneratedSeriesForTests(
                null, cacheDir, meta, candidate, binConfig, config, source, null);
    }

    static RepresentativeSeries renderGeneratedSeriesForTests(String directory,
                                                              File cacheDir,
                                                              SeriesMeta meta,
                                                              QcSelectionCandidate candidate,
                                                              BinConfig binConfig,
                                                              RepresentativeFigureConfig config,
                                                              ImagePlus source,
                                                              File sourcePath)
            throws Exception {
        WorkItem item = workItemFor(directory, meta, candidate, binConfig,
                Collections.<PresentationTileRecord>emptyList(), sourcePath);
        RenderContext context = new RenderContext(cacheDir, binConfig,
                config == null ? new RepresentativeFigureConfig() : config,
                DEFAULT_THUMBNAIL_LONG_EDGE);
        return renderItem(item, context, SourceAccess.fixed(source));
    }

    static RenderedFinalSeries renderFinalSeriesForTests(BinConfig binConfig,
                                                         RepresentativeFigureConfig config,
                                                         RepresentativeSeries series,
                                                         ImagePlus source)
            throws Exception {
        return renderFinalSeriesForTests(null, binConfig, config, series, source, null);
    }

    static RenderedFinalSeries renderFinalSeriesForTests(String directory,
                                                         BinConfig binConfig,
                                                         RepresentativeFigureConfig config,
                                                         RepresentativeSeries series,
                                                         ImagePlus source,
                                                         File sourcePath)
            throws Exception {
        WorkItem item = workItemForSeries(series, null, binConfig, sourcePath, directory);
        RenderContext context = RenderContext.finalRender(binConfig,
                config == null ? new RepresentativeFigureConfig() : config);
        return renderFinalItem(item, context,
                SourceAccess.fixed(source, directory, true));
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
        IJ.log("[Representative Figure] Rendering preview thumbnails with "
                + threadLabel(threads) + ".");
        if (threads == 1) {
            List<RepresentativeSeries> out = new ArrayList<RepresentativeSeries>();
            for (WorkItem item : items) {
                RepresentativeSeries rendered = renderItem(item, context, sourceAccess);
                out.add(rendered);
                logPreviewProgress(out.size(), items.size(), rendered);
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
                RepresentativeSeries rendered = future.get();
                out.add(rendered);
                logPreviewProgress(out.size(), items.size(), rendered);
            }
            return out;
        } finally {
            pool.shutdownNow();
        }
    }

    private static List<RenderedFinalSeries> renderFinalItems(List<WorkItem> items,
                                                              final RenderContext context,
                                                              final SourceAccess sourceAccess,
                                                              int parallelThreads) throws Exception {
        if (items == null || items.isEmpty()) {
            return Collections.emptyList();
        }
        int threads = Math.max(1, Math.min(Math.max(1, parallelThreads), items.size()));
        IJ.log("[Representative Figure] Rendering final images with "
                + threadLabel(threads) + ".");
        if (threads == 1) {
            List<RenderedFinalSeries> out = new ArrayList<RenderedFinalSeries>();
            for (WorkItem item : items) {
                RenderedFinalSeries rendered = renderFinalItem(item, context, sourceAccess);
                out.add(rendered);
                logFinalProgress(out.size(), items.size(), rendered);
            }
            return out;
        }

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<RenderedFinalSeries>> futures =
                    new ArrayList<Future<RenderedFinalSeries>>();
            for (final WorkItem item : items) {
                futures.add(pool.submit(new Callable<RenderedFinalSeries>() {
                    @Override
                    public RenderedFinalSeries call() throws Exception {
                        return renderFinalItem(item, context, sourceAccess);
                    }
                }));
            }

            List<RenderedFinalSeries> out = new ArrayList<RenderedFinalSeries>();
            for (Future<RenderedFinalSeries> future : futures) {
                RenderedFinalSeries rendered = future.get();
                out.add(rendered);
                logFinalProgress(out.size(), items.size(), rendered);
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

        IJ.log("[Representative Figure] Rendering preview for series "
                + item.seriesNumber + " (" + safe(item.seriesName) + ") from "
                + (item.presentationCoverage.covers()
                        ? "presentation images"
                        : "source image")
                + ".");
        RenderedPreviews previews = item.presentationCoverage.covers()
                ? renderFromPresentation(item, plan)
                : renderFromSource(item, plan.longEdge, context, sourceAccess);
        writePreviews(previews, plan);
        return buildSeries(item, plan, previews.channelImages, previews.mergeImage,
                item.presentationCoverage.covers()
                        ? RepresentativeSeries.PreviewSource.PRESENTATION
                        : RepresentativeSeries.PreviewSource.GENERATED,
                false);
    }

    private static RenderedFinalSeries renderFinalItem(WorkItem item,
                                                       RenderContext context,
                                                       SourceAccess sourceAccess)
            throws Exception {
        IJ.log("[Representative Figure] Rendering final image for "
                + RepresentativeSelection.conditionLabel(item.condition)
                + " from series " + item.seriesNumber
                + " (" + safe(item.seriesName) + ").");
        RenderedPreviews rendered = renderFromSource(item, context.longEdge,
                context, sourceAccess);
        return buildFinalSeries(item, rendered);
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

    private static RenderedFinalSeries buildFinalSeries(WorkItem item,
                                                        RenderedPreviews rendered) {
        List<RenderedFinalChannel> channels =
                new ArrayList<RenderedFinalChannel>();
        List<BufferedImage> images = rendered == null
                ? Collections.<BufferedImage>emptyList()
                : rendered.channelImages;
        for (int i = 0; i < item.channels.size() && i < images.size(); i++) {
            ChannelSpec spec = item.channels.get(i);
            channels.add(new RenderedFinalChannel(
                    spec.channelIndex,
                    spec.channelName,
                    spec.colorName,
                    images.get(i)));
        }
        BufferedImage merge = rendered == null
                ? new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB)
                : rendered.mergeImage;
        return new RenderedFinalSeries(
                item.id,
                item.seriesIndex,
                item.seriesNumber,
                item.seriesName,
                item.animal,
                item.condition,
                item.hemisphere,
                item.region,
                item.sourcePath,
                channels,
                merge,
                rendered == null ? Double.NaN : rendered.pixelWidthUm,
                rendered == null ? Double.NaN : rendered.pixelHeightUm);
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
                                                     int longEdge,
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
            OrientationOps.applyTransform(working, item.orientationMetadata);
            double pixelWidthUm = calibratedPixelWidthUm(working, true);
            double pixelHeightUm = calibratedPixelWidthUm(working, false);
            split = ChannelSplitter.split(working);
            if (split == null || split.length == 0) {
                throw new IOException("No channels found in " + item.seriesName);
            }

            int n = Math.min(split.length, item.channels.size());
            List<BufferedImage> channelImages = new ArrayList<BufferedImage>();
            for (int i = 0; i < n; i++) {
                ChannelSpec spec = item.channels.get(i);
                ImagePlus mip = ZProjector.run(split[i], "max");
                ImagePlus scaled = downscaleImagePlus(mip, longEdge);
                closeQuietly(mip);

                DisplayRange range = context.rangeResolver.resolve(item, spec);
                applyDisplayRange(scaled, range, context.allowAutoEnhanceFallback,
                        item.seriesName, spec.channelName);
                projected.add(scaled);
                channelImages.add(renderPseudoColor(scaled, spec.colorName));
            }
            BufferedImage merge = mergePseudoColorImages(channelImages);
            return new RenderedPreviews(channelImages, merge, pixelWidthUm, pixelHeightUm);
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
                "Representative Figure");
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

    private static void applyDisplayRange(ImagePlus imp,
                                          DisplayRange range,
                                          boolean allowAutoEnhance,
                                          String seriesName,
                                          String channelName) throws IOException {
        if (imp == null) return;
        if (range != null && range.isValid()) {
            imp.setDisplayRange(range.min, range.max);
            return;
        }
        if (!allowAutoEnhance) {
            throw new IOException("No locked display range is available for "
                    + safe(channelName) + " in " + safe(seriesName) + ".");
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

    private static void logPreviewProgress(int done,
                                           int total,
                                           RepresentativeSeries series) {
        if (!shouldLogProgress(done, total)) {
            return;
        }
        IJ.log("[Representative Figure] Preview thumbnails "
                + done + "/" + total
                + " (" + previewSourceLabel(series) + ").");
    }

    private static void logFinalProgress(int done,
                                         int total,
                                         RenderedFinalSeries series) {
        if (!shouldLogProgress(done, total)) {
            return;
        }
        String condition = series == null ? "" : series.condition();
        IJ.log("[Representative Figure] Final images "
                + done + "/" + total
                + (condition.isEmpty() ? "" : " (" + condition + ")")
                + ".");
    }

    private static boolean shouldLogProgress(int done, int total) {
        if (total <= 0) return false;
        if (done <= 0 || done == total) return true;
        if (total <= 20) return true;
        int interval = Math.max(1, total / 10);
        return done % interval == 0;
    }

    private static String previewSummary(List<RepresentativeSeries> rendered) {
        int total = rendered == null ? 0 : rendered.size();
        int cache = 0;
        int presentation = 0;
        int generated = 0;
        if (rendered != null) {
            for (RepresentativeSeries series : rendered) {
                if (series == null) continue;
                if (series.cacheHit()
                        || series.previewSource() == RepresentativeSeries.PreviewSource.CACHE) {
                    cache++;
                } else if (series.previewSource()
                        == RepresentativeSeries.PreviewSource.PRESENTATION) {
                    presentation++;
                } else if (series.previewSource()
                        == RepresentativeSeries.PreviewSource.GENERATED) {
                    generated++;
                }
            }
        }
        return total + " series (" + cache + " cache hit"
                + (cache == 1 ? "" : "s")
                + ", " + presentation + " presentation"
                + ", " + generated + " generated)";
    }

    private static String previewSourceLabel(RepresentativeSeries series) {
        if (series == null) {
            return "unknown";
        }
        if (series.cacheHit()
                || series.previewSource() == RepresentativeSeries.PreviewSource.CACHE) {
            return "cache";
        }
        if (series.previewSource() == RepresentativeSeries.PreviewSource.PRESENTATION) {
            return "presentation";
        }
        if (series.previewSource() == RepresentativeSeries.PreviewSource.GENERATED) {
            return "generated";
        }
        return "unknown";
    }

    private static String elapsed(long startMillis) {
        long elapsed = Math.max(0L, System.currentTimeMillis() - startMillis);
        if (elapsed < 1000L) {
            return elapsed + " ms";
        }
        return String.format(Locale.ROOT, "%.1f s", elapsed / 1000.0);
    }

    private static String threadLabel(int threads) {
        return threads + " thread" + (threads == 1 ? "" : "s");
    }

    private static List<WorkItem> buildWorkItems(String directory,
                                                 List<SeriesMeta> metas,
                                                 List<QcSelectionCandidate> candidates,
                                                 BinConfig cfg,
                                                 List<PresentationTileRecord> records,
                                                 SourceAccess sourceAccess,
                                                 MetadataResolver metadataResolver) {
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
            File sourcePath = sourceAccess == null ? null : sourceAccess.sourcePath(meta.index);
            List<PresentationTileRecord> matching =
                    matchingPresentationRecords(directory, meta, candidate, sourcePath,
                            records, metadataResolver);
            items.add(workItemFor(directory, meta, candidate, cfg, matching, sourcePath,
                    metadataResolver));
        }
        return items;
    }

    private static List<WorkItem> buildFinalWorkItems(String directory,
                                                      RepresentativeSelection selection,
                                                      BinConfig cfg,
                                                      List<SeriesMeta> metas,
                                                      SourceAccess sourceAccess,
                                                      MetadataResolver metadataResolver) {
        Map<Integer, SeriesMeta> metasByIndex =
                new LinkedHashMap<Integer, SeriesMeta>();
        if (metas != null) {
            for (SeriesMeta meta : metas) {
                if (meta != null) metasByIndex.put(Integer.valueOf(meta.index), meta);
            }
        }

        List<WorkItem> items = new ArrayList<WorkItem>();
        List<RepresentativeSeries> selected = selection == null
                ? Collections.<RepresentativeSeries>emptyList()
                : selection.series();
        for (int i = 0; i < selected.size(); i++) {
            RepresentativeSeries series = selected.get(i);
            if (series == null) continue;
            SeriesMeta meta = metasByIndex.get(Integer.valueOf(series.seriesIndex()));
            File sourcePath = sourceAccess == null
                    ? series.sourcePath()
                    : sourceAccess.sourcePath(series.seriesIndex());
            if (sourcePath == null) sourcePath = series.sourcePath();
            items.add(workItemForSeries(series, meta, cfg, sourcePath, directory,
                    metadataResolver));
        }
        return items;
    }

    private static WorkItem workItemFor(String directory,
                                        SeriesMeta meta,
                                        QcSelectionCandidate candidate,
                                        BinConfig cfg,
                                        List<PresentationTileRecord> matchingRecords,
                                        File sourcePath) {
        return workItemFor(directory, meta, candidate, cfg, matchingRecords, sourcePath,
                new MetadataResolver(directory));
    }

    private static WorkItem workItemFor(String directory,
                                        SeriesMeta meta,
                                        QcSelectionCandidate candidate,
                                        BinConfig cfg,
                                        List<PresentationTileRecord> matchingRecords,
                                        File sourcePath,
                                        MetadataResolver metadataResolver) {
        int seriesIndex = meta == null ? -1 : meta.index;
        int seriesNumber = seriesIndex + 1;
        String seriesName = candidate != null && !candidate.seriesName.trim().isEmpty()
                ? candidate.seriesName
                : (meta == null ? "" : safe(meta.name));
        MetadataResolver resolver = metadataResolver == null
                ? new MetadataResolver(directory) : metadataResolver;
        ResolvedImageMetadata resolved = resolver.resolve(seriesName, seriesNumber, sourcePath);
        NameParts parts = resolved == null
                ? ImageNameParser.parse(seriesName)
                : resolved.toNameParts();
        String animal = !parts.animal.isEmpty()
                ? parts.animal
                : (candidate != null && !candidate.animalName.trim().isEmpty()
                        ? candidate.animalName
                        : parts.animal);
        String condition = candidate == null ? "" : safe(candidate.conditionName);
        if (condition.isEmpty()) condition = animal;
        List<PresentationTileRecord> effectiveRecords = matchingRecords == null
                ? Collections.<PresentationTileRecord>emptyList()
                : matchingRecords;
        List<ChannelSpec> channels = channelSpecs(cfg, meta, effectiveRecords);
        ManifestCoverage coverage = ManifestCoverage.from(effectiveRecords, channels);
        if (!coverage.covers()) {
            List<PresentationTileRecord> fallbackRecords = savedPresentationPngRecords(
                    directory, parts, sourceImageId(resolved, seriesName, seriesNumber),
                    channels, meta);
            ManifestCoverage fallbackCoverage = ManifestCoverage.from(fallbackRecords, channels);
            if (fallbackCoverage.covers()) {
                effectiveRecords = fallbackRecords;
                coverage = fallbackCoverage;
            }
        }
        ResolvedImageMetadata orientationMetadata = resolved == null
                ? null
                : resolved;
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
                sourceFingerprint(sourcePath, meta, orientationMetadata),
                orientationMetadata);
    }

    private static WorkItem workItemForSeries(RepresentativeSeries series,
                                              SeriesMeta meta,
                                              BinConfig cfg,
                                              File sourcePath,
                                              String directory) {
        return workItemForSeries(series, meta, cfg, sourcePath, directory,
                new MetadataResolver(directory));
    }

    private static WorkItem workItemForSeries(RepresentativeSeries series,
                                              SeriesMeta meta,
                                              BinConfig cfg,
                                              File sourcePath,
                                              String directory,
                                              MetadataResolver metadataResolver) {
        if (series == null) {
            throw new IllegalArgumentException("Representative series is required.");
        }
        SeriesMeta effectiveMeta = meta;
        if (effectiveMeta == null) {
            int channelCount = Math.max(1, series.channelThumbnails().size());
            int width = series.mergeThumbnail() == null ? 1 : series.mergeThumbnail().getWidth();
            int height = series.mergeThumbnail() == null ? 1 : series.mergeThumbnail().getHeight();
            effectiveMeta = new SeriesMeta(
                    series.seriesIndex(),
                    series.seriesName(),
                    width,
                    height,
                    1,
                    channelCount,
                    Double.NaN,
                    Double.NaN,
                    Double.NaN,
                    "");
        }
        List<ChannelSpec> channels = channelSpecs(cfg, effectiveMeta, null);
        File effectiveSourcePath = sourcePath == null ? series.sourcePath() : sourcePath;
        MetadataResolver resolver = metadataResolver == null
                ? new MetadataResolver(directory) : metadataResolver;
        ResolvedImageMetadata orientationMetadata = resolver.resolve(
                series.seriesName(), series.seriesNumber(), effectiveSourcePath);
        return new WorkItem(
                series.id(),
                series.seriesIndex(),
                series.seriesNumber(),
                series.seriesName(),
                series.animal(),
                RepresentativeSelection.conditionLabel(series.condition()),
                series.hemisphere(),
                series.region(),
                effectiveSourcePath,
                channels,
                ManifestCoverage.empty(),
                sourceFingerprint(effectiveSourcePath, effectiveMeta, orientationMetadata),
                orientationMetadata);
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
            String directory,
            SeriesMeta meta,
            QcSelectionCandidate candidate,
            File sourcePath,
            List<PresentationTileRecord> records,
            MetadataResolver metadataResolver) {
        if (records == null || records.isEmpty()) {
            return Collections.emptyList();
        }
        LinkedHashSet<String> sourceIds = candidateSourceIds(
                directory, meta, candidate, sourcePath, metadataResolver);
        String seriesName = candidate != null && !candidate.seriesName.trim().isEmpty()
                ? candidate.seriesName
                : (meta == null ? "" : safe(meta.name));
        MetadataResolver resolver = metadataResolver == null
                ? new MetadataResolver(directory) : metadataResolver;
        ResolvedImageMetadata resolved = resolver.resolve(seriesName,
                (candidate == null ? (meta == null ? -1 : meta.index) : candidate.seriesIndex) + 1,
                sourcePath);
        NameParts parts = resolved == null
                ? ImageNameParser.parse(seriesName)
                : resolved.toNameParts();
        String animal = !parts.animal.isEmpty()
                ? parts.animal
                : (candidate != null && !candidate.animalName.trim().isEmpty()
                        ? candidate.animalName
                        : parts.animal);
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

    private static LinkedHashSet<String> candidateSourceIds(String directory,
                                                            SeriesMeta meta,
                                                            QcSelectionCandidate candidate,
                                                            File sourcePath,
                                                            MetadataResolver metadataResolver) {
        int index = candidate == null ? (meta == null ? -1 : meta.index) : candidate.seriesIndex;
        int seriesNumber = index + 1;
        String seriesName = candidate != null && !candidate.seriesName.trim().isEmpty()
                ? candidate.seriesName
                : (meta == null ? "" : safe(meta.name));
        LinkedHashSet<String> ids = new LinkedHashSet<String>();
        MetadataResolver resolver = metadataResolver == null
                ? new MetadataResolver(directory) : metadataResolver;
        ResolvedImageMetadata resolved = resolver.resolve(seriesName, seriesNumber, sourcePath);
        if (resolved != null && !resolved.imageKey.isEmpty()) {
            ids.add(resolved.imageKey);
        }
        ids.add("series:" + seriesNumber);
        for (String title : metadataTitleCandidates(seriesName, sourcePath)) {
            if (!title.trim().isEmpty()) {
                ids.add("series:" + seriesNumber + "|" + title.trim());
            }
        }
        return ids;
    }

    private static List<PresentationTileRecord> savedPresentationPngRecords(
            String directory,
            NameParts parts,
            String sourceImageId,
            List<ChannelSpec> channels,
            SeriesMeta meta) {
        if (directory == null || directory.trim().isEmpty()
                || parts == null || safe(parts.animal).isEmpty()
                || channels == null || channels.isEmpty()) {
            return Collections.emptyList();
        }
        File animalDir;
        try {
            animalDir = new File(
                    FlashProjectLayout.forDirectory(directory).presentationImagesDir(),
                    parts.animal);
        } catch (IllegalArgumentException e) {
            return Collections.emptyList();
        }
        if (!animalDir.isDirectory()) {
            return Collections.emptyList();
        }

        String suffix = parts.fileSuffix();
        List<PresentationTileRecord> out = new ArrayList<PresentationTileRecord>();
        for (ChannelSpec spec : channels) {
            File file = new File(animalDir,
                    ChannelFilenameCodec.toSafe(spec.channelName)
                            + (suffix.isEmpty() ? "" : "_" + suffix)
                            + ".png");
            if (!file.isFile()) {
                continue;
            }
            int[] size = imageDimensions(file, meta);
            out.add(new PresentationTileRecord(
                    file,
                    parts.animal,
                    parts.hemisphere,
                    parts.csvRegion(),
                    sourceImageId,
                    spec.channelName,
                    spec.channelName,
                    spec.channelIndex,
                    size[0],
                    size[1],
                    pixelSizeUm(meta, true),
                    pixelSizeUm(meta, false)));
        }

        File merge = new File(animalDir,
                suffix.isEmpty() ? "Merge.png" : "Merge_" + suffix + ".png");
        if (merge.isFile()) {
            int[] size = imageDimensions(merge, meta);
            out.add(new PresentationTileRecord(
                    merge,
                    parts.animal,
                    parts.hemisphere,
                    parts.csvRegion(),
                    sourceImageId,
                    MERGE_NAME,
                    MERGE_NAME,
                    -1,
                    size[0],
                    size[1],
                    pixelSizeUm(meta, true),
                    pixelSizeUm(meta, false)));
        }
        return out;
    }

    private static final class MetadataResolver {
        private final String directory;
        private final List<OrientationManifestRow> manifestRows;
        private final Map<String, ResolvedImageMetadata> cache =
                new LinkedHashMap<String, ResolvedImageMetadata>();

        MetadataResolver(String directory) {
            this.directory = safe(directory);
            this.manifestRows = readManifestRows(this.directory);
        }

        ResolvedImageMetadata resolve(String seriesName, int seriesNumber, File sourcePath) {
            if (directory.isEmpty()) {
                return null;
            }
            String key = safe(seriesName) + "\n" + seriesNumber + "\n"
                    + (sourcePath == null ? "" : sourcePath.getAbsolutePath());
            if (cache.containsKey(key)) {
                return cache.get(key);
            }
            ResolvedImageMetadata resolved = resolveUncached(seriesName, seriesNumber, sourcePath);
            cache.put(key, resolved);
            return resolved;
        }

        private ResolvedImageMetadata resolveUncached(String seriesName,
                                                      int seriesNumber,
                                                      File sourcePath) {
            List<String> titles = metadataTitleCandidates(seriesName, sourcePath);
            for (String title : titles) {
                Optional<OrientationManifestRow> row =
                        findConfirmed(title, seriesNumber);
                if (row.isPresent()) {
                    return ResolvedImageMetadata.fromManifest(row.get());
                }
            }
            String fallbackTitle = titles.isEmpty() ? seriesName : titles.get(0);
            NameParts parts = ImageNameParser.parse(fallbackTitle);
            ResolvedImageMetadata.Source source = parts.strictMatch
                    ? ResolvedImageMetadata.Source.STRICT_FILENAME
                    : ResolvedImageMetadata.Source.FILENAME_FALLBACK;
            return ResolvedImageMetadata.fromNameParts(parts, source);
        }

        private Optional<OrientationManifestRow> findConfirmed(String imageTitle,
                                                               int seriesNumber) {
            int normalizedSeriesIndex = normalizeSeriesIndex(seriesNumber);
            OrientationManifestRow uniqueTitleMatch = null;
            boolean ambiguousTitleMatch = false;
            for (OrientationManifestRow row : manifestRows) {
                if (!isUsable(row)) continue;
                if (matches(row, imageTitle, normalizedSeriesIndex)) {
                    return Optional.of(row);
                }
                if (matchesTitle(row, imageTitle)) {
                    if (uniqueTitleMatch == null && !ambiguousTitleMatch) {
                        uniqueTitleMatch = row;
                    } else {
                        uniqueTitleMatch = null;
                        ambiguousTitleMatch = true;
                    }
                }
            }
            if (uniqueTitleMatch != null) {
                return Optional.of(uniqueTitleMatch);
            }
            return Optional.empty();
        }

        private static List<OrientationManifestRow> readManifestRows(String directory) {
            if (directory == null || directory.trim().isEmpty()) {
                return Collections.emptyList();
            }
            try {
                return OrientationManifestIO.readIfExists(directory);
            } catch (RuntimeException e) {
                return Collections.emptyList();
            }
        }

        private static boolean isUsable(OrientationManifestRow row) {
            return row != null
                    && row.isConfirmed()
                    && !row.animalName.isEmpty()
                    && (row.hemisphere == OrientationManifestRow.Hemisphere.LH
                        || row.hemisphere == OrientationManifestRow.Hemisphere.RH
                        || hasManualTransform(row));
        }

        private static boolean hasManualTransform(OrientationManifestRow row) {
            return row.rotateDegrees != OrientationManifestRow.RotationDegrees.DEG_0
                    || row.flipHorizontal
                    || row.flipVertical;
        }

        private static boolean matches(OrientationManifestRow row,
                                       String imageTitle,
                                       int seriesIndex) {
            if (row.seriesIndex != seriesIndex) return false;
            return matchesTitle(row, imageTitle);
        }

        private static boolean matchesTitle(OrientationManifestRow row,
                                            String imageTitle) {
            String title = safe(imageTitle);
            if (title.isEmpty()) return false;

            if (title.equals(row.originalName)) return true;
            if (title.equals(row.displayName)) return true;
            if (title.equals(row.sourceFile)) return true;

            String keySuffix = "|" + title;
            return row.imageKey.endsWith(keySuffix);
        }

        private static int normalizeSeriesIndex(int seriesIndex) {
            return seriesIndex < 1 ? 1 : seriesIndex;
        }
    }

    private static List<String> metadataTitleCandidates(String seriesName, File sourcePath) {
        LinkedHashSet<String> titles = new LinkedHashSet<String>();
        String cleanSeries = safe(seriesName);
        if (!cleanSeries.isEmpty()) {
            titles.add(cleanSeries);
        }
        String extracted = ImageNameParser.extractBioFormatsSeriesName(cleanSeries);
        if (extracted != null && !extracted.trim().isEmpty()) {
            titles.add(extracted.trim());
        }
        String sourceName = sourcePath == null ? "" : safe(sourcePath.getName());
        if (!sourceName.isEmpty()) {
            List<String> bases = new ArrayList<String>(titles);
            for (String base : bases) {
                if (base == null || base.trim().isEmpty() || base.contains(" - ")) {
                    continue;
                }
                titles.add(sourceName + " - " + base.trim());
                String stripped = ImageNameParser.stripExtension(sourceName);
                if (stripped != null && !stripped.trim().isEmpty()
                        && !stripped.equals(sourceName)) {
                    titles.add(stripped.trim() + " - " + base.trim());
                }
            }
        }
        return new ArrayList<String>(titles);
    }

    private static String sourceImageId(ResolvedImageMetadata metadata,
                                        String seriesName,
                                        int seriesNumber) {
        if (metadata != null && !metadata.imageKey.isEmpty()) {
            return metadata.imageKey;
        }
        int normalizedSeries = seriesNumber < 1 ? 1 : seriesNumber;
        String title = safe(seriesName);
        if (title.isEmpty()) {
            return "series:" + normalizedSeries;
        }
        return "series:" + normalizedSeries + "|" + title;
    }

    private static int[] imageDimensions(File file, SeriesMeta meta) {
        ImageInputStream input = null;
        ImageReader reader = null;
        try {
            input = ImageIO.createImageInputStream(file);
            if (input != null) {
                Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
                if (readers.hasNext()) {
                    reader = readers.next();
                    reader.setInput(input);
                    return new int[]{
                            Math.max(1, reader.getWidth(0)),
                            Math.max(1, reader.getHeight(0))};
                }
            }
        } catch (IOException e) {
            // Fall through to metadata dimensions.
        } finally {
            if (reader != null) {
                reader.dispose();
            }
            if (input != null) {
                try {
                    input.close();
                } catch (IOException ignored) {
                }
            }
        }
        return new int[]{
                Math.max(1, meta == null ? 1 : meta.width),
                Math.max(1, meta == null ? 1 : meta.height)};
    }

    private static double pixelSizeUm(SeriesMeta meta, boolean xAxis) {
        if (meta == null) return Double.NaN;
        double value = xAxis ? meta.pixelWidth : meta.pixelHeight;
        if (!Double.isFinite(value) || value <= 0) return Double.NaN;
        double multiplier = calibrationUnitToMicronMultiplier(meta.unit);
        if (!Double.isFinite(multiplier) || multiplier <= 0) return Double.NaN;
        return value * multiplier;
    }

    private static String sourceFingerprint(File sourcePath,
                                            SeriesMeta meta,
                                            ResolvedImageMetadata metadata) {
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
        sb.append("|orientation=").append(orientationFingerprint(metadata));
        return sb.toString();
    }

    private static String orientationFingerprint(ResolvedImageMetadata metadata) {
        if (metadata == null) return "none";
        StringBuilder sb = new StringBuilder();
        sb.append(safe(metadata.imageKey)).append('|')
                .append(safe(metadata.sourceLabel())).append('|')
                .append(metadata.rotateDegrees == null ? "" : metadata.rotateDegrees.toCsv()).append('|')
                .append(metadata.flipHorizontal).append('|')
                .append(metadata.flipVertical).append('|')
                .append(safe(metadata.hemisphere)).append('|')
                .append(metadata.viewPolicy == null ? "" : metadata.viewPolicy.toCsv());
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

    private static double calibratedPixelWidthUm(ImagePlus imp, boolean xAxis) {
        if (imp == null) return Double.NaN;
        Calibration calibration = imp.getCalibration();
        if (calibration == null) return Double.NaN;
        double value = xAxis ? calibration.pixelWidth : calibration.pixelHeight;
        if (!Double.isFinite(value) || value <= 0) return Double.NaN;
        double multiplier = calibrationUnitToMicronMultiplier(calibration.getUnit());
        if (!Double.isFinite(multiplier) || multiplier <= 0) return Double.NaN;
        return value * multiplier;
    }

    private static double calibrationUnitToMicronMultiplier(String unit) {
        String normalized = unit == null ? "" : unit.trim().toLowerCase(Locale.ROOT);
        if (normalized.isEmpty() || "pixel".equals(normalized) || "pixels".equals(normalized)) {
            return Double.NaN;
        }
        if ("um".equals(normalized) || "\u00b5m".equals(normalized) || "\u03bcm".equals(normalized)
                || "micron".equals(normalized) || "microns".equals(normalized)
                || "micrometer".equals(normalized) || "micrometers".equals(normalized)) {
            return 1.0;
        }
        if ("nm".equals(normalized) || "nanometer".equals(normalized) || "nanometers".equals(normalized)) {
            return 0.001;
        }
        if ("mm".equals(normalized) || "millimeter".equals(normalized) || "millimeters".equals(normalized)) {
            return 1000.0;
        }
        return Double.NaN;
    }

    private static String valueAt(List<String> values, int index, String fallback) {
        if (values == null || index < 0 || index >= values.size()) return fallback;
        String value = values.get(index);
        return value == null ? fallback : value;
    }

    private static boolean sameText(String a, String b) {
        return safe(a).equalsIgnoreCase(safe(b));
    }

    private static boolean isReadableTif(File file) {
        if (file == null || !file.isFile()) return false;
        String name = file.getName().toLowerCase(Locale.ROOT);
        return name.endsWith(".tif") || name.endsWith(".tiff");
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
        final boolean allowAutoEnhanceFallback;
        final DisplayRangeResolver rangeResolver;

        RenderContext(File cacheDir,
                      BinConfig binConfig,
                      RepresentativeFigureConfig config,
                      int longEdge) {
            this(cacheDir, binConfig, config, longEdge, true, true);
        }

        RenderContext(File cacheDir,
                      BinConfig binConfig,
                      RepresentativeFigureConfig config,
                      int longEdge,
                      boolean includeQuickFallback,
                      boolean allowAutoEnhanceFallback) {
            this.cacheDir = cacheDir;
            this.binConfig = binConfig == null ? new BinConfig() : binConfig;
            this.config = config == null ? new RepresentativeFigureConfig() : config;
            this.longEdge = Math.max(1, longEdge);
            this.allowAutoEnhanceFallback = allowAutoEnhanceFallback;
            this.rangeResolver = new DisplayRangeResolver(
                    this.binConfig, this.config, includeQuickFallback);
        }

        static RenderContext finalRender(BinConfig binConfig,
                                         RepresentativeFigureConfig config) {
            return new RenderContext(null, binConfig, config,
                    Integer.MAX_VALUE, false, false);
        }
    }

    private static final class RenderPlan {
        final int longEdge;
        final List<File> channelFiles;
        final File mergeFile;

        RenderPlan(int longEdge, List<File> channelFiles, File mergeFile) {
            this.longEdge = longEdge;
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
            return new RenderPlan(context.longEdge, channelFiles, merge);
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
        final ResolvedImageMetadata orientationMetadata;

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
                 String sourceFingerprint,
                 ResolvedImageMetadata orientationMetadata) {
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
            this.orientationMetadata = orientationMetadata;
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
        final double pixelWidthUm;
        final double pixelHeightUm;

        RenderedPreviews(List<BufferedImage> channelImages, BufferedImage mergeImage) {
            this(channelImages, mergeImage, Double.NaN, Double.NaN);
        }

        RenderedPreviews(List<BufferedImage> channelImages,
                         BufferedImage mergeImage,
                         double pixelWidthUm,
                         double pixelHeightUm) {
            this.channelImages = channelImages == null
                    ? Collections.<BufferedImage>emptyList()
                    : channelImages;
            this.mergeImage = mergeImage == null
                    ? new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB)
                    : mergeImage;
            this.pixelWidthUm = pixelWidthUm;
            this.pixelHeightUm = pixelHeightUm;
        }
    }

    /**
     * Full-resolution rendered images for one locked representative series.
     */
    public static final class RenderedFinalSeries {
        private final String id;
        private final int seriesIndex;
        private final int seriesNumber;
        private final String seriesName;
        private final String animal;
        private final String condition;
        private final String hemisphere;
        private final String region;
        private final File sourcePath;
        private final List<RenderedFinalChannel> channels;
        private final BufferedImage mergeImage;
        private final double pixelWidthUm;
        private final double pixelHeightUm;

        RenderedFinalSeries(String id,
                            int seriesIndex,
                            int seriesNumber,
                            String seriesName,
                            String animal,
                            String condition,
                            String hemisphere,
                            String region,
                            File sourcePath,
                            List<RenderedFinalChannel> channels,
                            BufferedImage mergeImage,
                            double pixelWidthUm,
                            double pixelHeightUm) {
            this.id = safe(id);
            this.seriesIndex = seriesIndex;
            this.seriesNumber = seriesNumber;
            this.seriesName = safe(seriesName);
            this.animal = safe(animal);
            this.condition = RepresentativeSelection.conditionLabel(condition);
            this.hemisphere = safe(hemisphere);
            this.region = safe(region);
            this.sourcePath = sourcePath;
            this.channels = channels == null
                    ? Collections.<RenderedFinalChannel>emptyList()
                    : Collections.unmodifiableList(new ArrayList<RenderedFinalChannel>(channels));
            this.mergeImage = mergeImage == null
                    ? new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB)
                    : mergeImage;
            this.pixelWidthUm = pixelWidthUm;
            this.pixelHeightUm = pixelHeightUm;
        }

        public String id() {
            return id;
        }

        public int seriesIndex() {
            return seriesIndex;
        }

        public int seriesNumber() {
            return seriesNumber;
        }

        public String seriesName() {
            return seriesName;
        }

        public String animal() {
            return animal;
        }

        public String condition() {
            return condition;
        }

        public String hemisphere() {
            return hemisphere;
        }

        public String region() {
            return region;
        }

        public File sourcePath() {
            return sourcePath;
        }

        public List<RenderedFinalChannel> channels() {
            return channels;
        }

        public BufferedImage mergeImage() {
            return mergeImage;
        }

        public double pixelWidthUm() {
            return pixelWidthUm;
        }

        public double pixelHeightUm() {
            return pixelHeightUm;
        }
    }

    /**
     * Full-resolution rendered image for one representative channel.
     */
    public static final class RenderedFinalChannel {
        private final int channelIndex;
        private final String channelName;
        private final String colorName;
        private final BufferedImage image;

        RenderedFinalChannel(int channelIndex,
                             String channelName,
                             String colorName,
                             BufferedImage image) {
            this.channelIndex = channelIndex;
            this.channelName = safe(channelName);
            this.colorName = safe(colorName);
            this.image = image;
        }

        public int channelIndex() {
            return channelIndex;
        }

        public String channelName() {
            return channelName;
        }

        public String colorName() {
            return colorName;
        }

        public BufferedImage image() {
            return image;
        }
    }

    private static final class DisplayRangeResolver {
        private final BinConfig cfg;
        private final RepresentativeFigureConfig config;
        private final boolean includeQuickFallback;
        private final Map<String, DisplayRange> quickRanges =
                new LinkedHashMap<String, DisplayRange>();
        private final Map<String, DisplayRange> quickOverall =
                new LinkedHashMap<String, DisplayRange>();

        DisplayRangeResolver(BinConfig cfg,
                             RepresentativeFigureConfig config,
                             boolean includeQuickFallback) {
            this.cfg = cfg == null ? new BinConfig() : cfg;
            this.config = config == null ? new RepresentativeFigureConfig() : config;
            this.includeQuickFallback = includeQuickFallback;
            buildQuickRanges();
        }

        DisplayRange resolve(WorkItem item, ChannelSpec spec) {
            DisplayRange custom = representativeCustomRange(item, spec);
            if (custom != null) return custom;

            DisplayRange setup = parseRange(valueAt(cfg.channelMinMax, spec.channelIndex, ""));
            if (setup != null) return setup.withSource("setup");

            if (includeQuickFallback) {
                DisplayRange quick = quickRange(item, spec);
                if (quick != null) return quick;
            }

            return null;
        }

        String rangeToken(WorkItem item, ChannelSpec spec) {
            DisplayRange range = resolve(item, spec);
            return range == null ? "auto-enhance" : range.source + ":" + range.min + "-" + range.max;
        }

        private DisplayRange representativeCustomRange(WorkItem item, ChannelSpec spec) {
            String token = config.customDisplayRangeForChannel(spec.channelIndex);
            DisplayRange range = parseRange(token);
            return range == null ? null : range.withSource("representative");
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
                    || !includeQuickFallback
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
        private final boolean cacheMissingOriginalTifs;
        private final DeferredImageSupplier supplier;
        private final boolean canUseSessionCache;
        private boolean sessionCacheAttempted = false;
        private List<ImagePlus> sessionImages = null;
        private final ImagePlus fixedImage;

        SourceAccess(String directory,
                     ImageCache imageCache,
                     boolean useTifCache,
                     boolean cacheMissingOriginalTifs)
                throws Exception {
            this.directory = directory;
            this.imageCache = imageCache;
            this.useTifCache = useTifCache;
            this.cacheMissingOriginalTifs = cacheMissingOriginalTifs;
            this.supplier = ImageSourceDispatcher.createSupplier(directory);
            this.canUseSessionCache = imageCache != null && canUseSessionImageCache(directory);
            this.fixedImage = null;
        }

        private SourceAccess(ImagePlus fixedImage,
                             String directory,
                             boolean cacheMissingOriginalTifs) {
            this.directory = directory == null ? "" : directory;
            this.imageCache = null;
            this.useTifCache = false;
            this.cacheMissingOriginalTifs = cacheMissingOriginalTifs;
            this.supplier = null;
            this.canUseSessionCache = false;
            this.fixedImage = fixedImage;
        }

        static SourceAccess none() {
            return new SourceAccess((ImagePlus) null, "", false);
        }

        static SourceAccess fixed(ImagePlus image) {
            return new SourceAccess(image, "", false);
        }

        static SourceAccess fixed(ImagePlus image,
                                  String directory,
                                  boolean cacheMissingOriginalTifs) {
            return new SourceAccess(image, directory, cacheMissingOriginalTifs);
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
                    ImagePlus duplicate = ImageOps.duplicateThreadSafe(fixedImage);
                    ensureOriginalTifForExport(item, duplicate, true);
                    return duplicate;
                }
            }
            ImagePlus cached = openSessionCachedImage(item.seriesIndex);
            if (cached != null) {
                IJ.log("[Representative Figure] Opened series " + item.seriesNumber
                        + " from the in-session image cache.");
                ensureOriginalTifForExport(item, cached, true);
                return cached;
            }
            if (useTifCache && directory != null && !directory.isEmpty()
                    && TifCache.cacheExists(directory)) {
                ImagePlus tif = TifCache.loadSingle(directory, item.seriesIndex);
                if (tif != null) {
                    IJ.log("[Representative Figure] Opened series " + item.seriesNumber
                            + " from the TIFF cache.");
                    return tif;
                }
            }
            if (supplier == null) return null;
            IJ.log("[Representative Figure] Opening source series " + item.seriesNumber
                    + " (" + safe(item.seriesName) + ").");
            ImagePlus opened = supplier.openSeriesMaterialized(item.seriesIndex);
            ensureOriginalTifForExport(item, opened, true);
            return opened;
        }

        private void ensureOriginalTifForExport(WorkItem item,
                                                ImagePlus source,
                                                boolean refreshExisting)
                throws IOException {
            if (!cacheMissingOriginalTifs || source == null || item == null) return;
            if (directory == null || directory.trim().isEmpty()) return;
            if (item.seriesIndex < 0) return;
            if (isReadableTif(item.sourcePath)) return;
            synchronized (ORIGINAL_TIF_CACHE_LOCK) {
                File existing = TifCache.cachedFileForSeries(directory, item.seriesIndex);
                if (!refreshExisting && isReadableTif(existing)) return;
                IJ.log("[Representative Figure] "
                        + (isReadableTif(existing) ? "Refreshing" : "Saving")
                        + " original TIFF for series "
                        + item.seriesNumber + " to the TIFF cache for export.");
                TifCache.saveToCache(directory, source, item.seriesIndex);
                File saved = TifCache.cachedFileForSeries(directory, item.seriesIndex);
                if (!isReadableTif(saved)) {
                    throw new IOException("Could not save original TIFF for representative series "
                            + item.seriesNumber + " (" + safe(item.seriesName) + ").");
                }
            }
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
