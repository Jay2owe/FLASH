package flash.pipeline.representative;

import flash.pipeline.bin.BinConfig;
import flash.pipeline.image.ImageOps;
import flash.pipeline.io.DeferredImageSupplier;
import flash.pipeline.io.FlashProjectLayout;
import flash.pipeline.io.ImageCache;
import flash.pipeline.io.ImageSourceDispatcher;
import flash.pipeline.io.TifCache;
import flash.pipeline.ui.config.ConfigQcContext;
import flash.pipeline.ui.config.ConfigQcDialog;
import flash.pipeline.ui.config.ConfigQcResult;
import flash.pipeline.ui.config.ConfigQcStage;
import flash.pipeline.ui.config.DisplayRangeStage;
import flash.pipeline.zslice.ZSliceOps;
import ij.IJ;
import ij.ImagePlus;
import ij.ImageStack;
import ij.measure.Calibration;
import ij.plugin.ZProjector;
import ij.process.ImageProcessor;

import javax.swing.SwingUtilities;
import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Runs the setup display-range editor over the locked representative images.
 */
public final class RepresentativeRangeStage {

    private static final String[] STAGE_PATH =
            new String[]{"Representative Figure", "Display Ranges"};

    public boolean run(String directory,
                       RepresentativeFigureConfig config,
                       BinConfig setupConfig,
                       ImageCache imageCache,
                       boolean useTifCache) throws Exception {
        RepresentativeFigureConfig safeConfig =
                config == null ? new RepresentativeFigureConfig() : config;
        RepresentativeSelection selection = safeConfig.selection;
        if (selection == null || !selection.isComplete()) {
            throw new IllegalStateException("Representative selection is not complete.");
        }

        List<ChannelRef> channels = channelsForSelection(selection, setupConfig);
        if (channels.isEmpty()) {
            throw new IllegalStateException("No representative channels are available.");
        }

        Map<Integer, String> originalRanges =
                new LinkedHashMap<Integer, String>(safeConfig.customDisplayRangesByChannel);
        SourceAccess sourceAccess = new SourceAccess(directory, imageCache, useTifCache);
        try {
            for (int i = 0; i < channels.size(); i++) {
                ChannelRef channel = channels.get(i);
                String seed = seedRangeForChannel(safeConfig, setupConfig, selection, channel);
                ConfigQcContext context = contextForChannel(
                        directory, safeConfig, setupConfig, selection, channel, seed, sourceAccess);
                DisplayRangeStage stage = createDisplayRangeStage(safeConfig, channel, seed);
                ConfigQcResult result;
                try {
                    result = showDisplayRangeDialog(context, stage);
                } finally {
                    closeContextImages(context);
                }
                if (result != ConfigQcResult.DONE
                        && result != ConfigQcResult.SKIP_CURRENT_IMAGE) {
                    safeConfig.customDisplayRangesByChannel.clear();
                    safeConfig.customDisplayRangesByChannel.putAll(originalRanges);
                    return false;
                }
            }
            return true;
        } finally {
            sourceAccess.close();
        }
    }

    public static boolean hasCompleteSetupRanges(BinConfig setupConfig,
                                                 RepresentativeSelection selection) {
        List<ChannelRef> channels = channelsForSelection(selection, setupConfig);
        if (channels.isEmpty()) return false;
        for (ChannelRef channel : channels) {
            if (parseRange(setupRangeToken(setupConfig, channel.channelIndex)) == null) {
                return false;
            }
        }
        return true;
    }

    static List<ChannelRef> channelsForSelection(RepresentativeSelection selection,
                                                 BinConfig setupConfig) {
        LinkedHashMap<Integer, ChannelRef> byIndex = new LinkedHashMap<Integer, ChannelRef>();
        int configuredCount = configuredChannelCount(setupConfig);
        if (selection != null) {
            List<RepresentativeSeries> series = selection.series();
            for (int s = 0; s < series.size(); s++) {
                RepresentativeSeries representative = series.get(s);
                if (representative == null) continue;
                List<RepresentativeSeries.ChannelThumbnail> thumbnails =
                        representative.channelThumbnails();
                for (int i = 0; i < thumbnails.size(); i++) {
                    RepresentativeSeries.ChannelThumbnail thumbnail = thumbnails.get(i);
                    if (thumbnail == null) continue;
                    int channelIndex = Math.max(0, thumbnail.channelIndex());
                    ChannelRef existing = byIndex.get(Integer.valueOf(channelIndex));
                    if (existing == null) {
                        byIndex.put(Integer.valueOf(channelIndex), new ChannelRef(
                                channelIndex,
                                channelName(setupConfig, channelIndex, thumbnail.channelName()),
                                channelColor(setupConfig, channelIndex)));
                    }
                }
            }
        }

        if (byIndex.isEmpty()) {
            for (int i = 0; i < configuredCount; i++) {
                byIndex.put(Integer.valueOf(i), new ChannelRef(
                        i,
                        channelName(setupConfig, i, "C" + (i + 1)),
                        channelColor(setupConfig, i)));
            }
        }
        return Collections.unmodifiableList(new ArrayList<ChannelRef>(byIndex.values()));
    }

    static String seedRangeForChannel(RepresentativeFigureConfig config,
                                      BinConfig setupConfig,
                                      RepresentativeSelection selection,
                                      ChannelRef channel) {
        if (channel == null) return "None";
        if (config != null) {
            String custom = config.customDisplayRangeForChannel(channel.channelIndex);
            if (parseRange(custom) != null) return custom.trim();
        }
        String setup = setupRangeToken(setupConfig, channel.channelIndex);
        if (parseRange(setup) != null) return setup.trim();
        String quick = quickRangeToken(config, selection, channel);
        return quick == null ? "None" : quick;
    }

    private ConfigQcContext contextForChannel(String directory,
                                              RepresentativeFigureConfig config,
                                              BinConfig setupConfig,
                                              RepresentativeSelection selection,
                                              ChannelRef channel,
                                              String seedRange,
                                              SourceAccess sourceAccess) throws Exception {
        List<ConfigQcContext.ConfigQcImage> images = new ArrayList<ConfigQcContext.ConfigQcImage>();
        for (Map.Entry<String, RepresentativeSeries> entry : selection.asMap().entrySet()) {
            RepresentativeSeries series = entry.getValue();
            ImagePlus projection = createProjectedChannel(
                    setupConfig, series, channel, seedRange, sourceAccess);
            images.add(new ConfigQcContext.ConfigQcImage(
                    series.seriesIndex(),
                    series.seriesName(),
                    projection,
                    entry.getKey(),
                    ""));
        }
        return new ConfigQcContext(
                new File(directory),
                FlashProjectLayout.forDirectory(directory).configurationWriteDir(),
                config,
                images,
                channelNamesForContext(setupConfig, channelsForSelection(selection, setupConfig)),
                channel.channelIndex);
    }

    private DisplayRangeStage createDisplayRangeStage(final RepresentativeFigureConfig config,
                                                      final ChannelRef channel,
                                                      final String seedRange) {
        return new DisplayRangeStage(
                new DisplayRangeStage.RangeStore() {
                    @Override public String get() {
                        String custom = config.customDisplayRangeForChannel(channel.channelIndex);
                        return parseRange(custom) == null ? seedRange : custom;
                    }

                    @Override public void set(String token) {
                        config.setCustomDisplayRangeForChannel(channel.channelIndex, token);
                    }
                },
                new DisplayRangeStage.PreviewAdapter() {
                    @Override public ImagePlus createSource(ConfigQcContext context) {
                        ImagePlus current = context == null ? null : context.getCurrentImagePlus();
                        if (current == null) return null;
                        ImagePlus duplicate = ImageOps.duplicateThreadSafe(current);
                        if (duplicate != null) {
                            duplicate.setTitle("Representative display range | "
                                    + channel.label() + " | "
                                    + context.getCurrentImageDisplayName());
                            copyDisplayRange(current, duplicate);
                            applyPreviewLut(duplicate, channel.colorName);
                        }
                        return duplicate;
                    }

                    @Override public void close(ImagePlus image) {
                        closeImageQuietly(image);
                    }
                });
    }

    private ConfigQcResult showDisplayRangeDialog(final ConfigQcContext context,
                                                  final DisplayRangeStage stage) {
        final List<ConfigQcStage> stages =
                Collections.<ConfigQcStage>singletonList(stage);
        final List<String> stagePath = Arrays.asList(STAGE_PATH);
        if (SwingUtilities.isEventDispatchThread()) {
            ConfigQcDialog dialog = ConfigQcDialog.createModeless(
                    null, context, stages, stagePath, 1);
            return dialog.showDialog();
        }
        final ConfigQcResult[] result = new ConfigQcResult[]{ConfigQcResult.CANCEL};
        try {
            SwingUtilities.invokeAndWait(new Runnable() {
                @Override public void run() {
                    ConfigQcDialog dialog = ConfigQcDialog.createModeless(
                            null, context, stages, stagePath, 1);
                    result[0] = dialog.showDialog();
                }
            });
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ConfigQcResult.CANCEL;
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException) throw (RuntimeException) cause;
            if (cause instanceof Error) throw (Error) cause;
            throw new RuntimeException("Representative display-range dialog failed.", cause);
        }
        return result[0] == null ? ConfigQcResult.CANCEL : result[0];
    }

    private ImagePlus createProjectedChannel(BinConfig setupConfig,
                                             RepresentativeSeries series,
                                             ChannelRef channel,
                                             String seedRange,
                                             SourceAccess sourceAccess) throws Exception {
        ImagePlus source = null;
        ImagePlus working = null;
        ImagePlus channelStack = null;
        try {
            source = sourceAccess.openSeries(series.seriesIndex());
            if (source == null) {
                throw new IllegalStateException("Could not open representative series: "
                        + series.seriesName());
            }
            working = applyConfiguredZSliceSubset(setupConfig, series, source);
            channelStack = duplicateChannel(
                    working, channel.channelIndex + 1, configuredChannelCount(setupConfig));
            ImagePlus projection = ZProjector.run(channelStack, "max");
            if (projection == null) {
                throw new IllegalStateException("Could not project " + channel.label()
                        + " for " + series.seriesName());
            }
            projection.setTitle("Representative display range | "
                    + channel.label() + " | "
                    + RepresentativeSelection.conditionLabel(series.condition())
                    + " | " + series.seriesName());
            double[] seed = parseRange(seedRange);
            if (seed != null) {
                projection.setDisplayRange(seed[0], seed[1]);
            }
            return applyPreviewLut(projection, channel.colorName);
        } finally {
            closeImageQuietly(channelStack);
            if (working != null && working != source) {
                closeImageQuietly(working);
            }
            closeImageQuietly(source);
        }
    }

    private static ImagePlus applyConfiguredZSliceSubset(BinConfig setupConfig,
                                                         RepresentativeSeries series,
                                                         ImagePlus source) {
        if (source == null || setupConfig == null || !setupConfig.usesZSliceSubset()) {
            return source;
        }
        return ZSliceOps.applyConfiguredRange(source, setupConfig, series.seriesIndex(),
                "Representative Figure display range");
    }

    private static ImagePlus duplicateChannel(ImagePlus source,
                                              int channelNum,
                                              int configuredChannels) {
        if (source == null) return null;
        int requested = Math.max(1, channelNum);
        int reportedChannels = Math.max(1, source.getNChannels());
        int safeConfiguredChannels = Math.max(0, configuredChannels);
        if (safeConfiguredChannels > reportedChannels
                && canExtractInterleavedConfiguredChannel(source, requested, safeConfiguredChannels)) {
            return duplicateInterleavedConfiguredChannel(source, requested, safeConfiguredChannels);
        }
        if (reportedChannels >= requested) {
            return ImageOps.duplicateThreadSafe(
                    source,
                    requested,
                    requested,
                    1,
                    Math.max(1, source.getNSlices()),
                    1,
                    Math.max(1, source.getNFrames()));
        }
        if (canExtractInterleavedConfiguredChannel(source, requested, safeConfiguredChannels)) {
            return duplicateInterleavedConfiguredChannel(source, requested, safeConfiguredChannels);
        }
        if (requested == 1 && reportedChannels == 1) {
            return ImageOps.duplicateThreadSafe(source);
        }
        throw new IllegalStateException("Cannot extract C" + requested
                + " from " + safe(source.getTitle())
                + ": image reports " + reportedChannels
                + " channel(s), setup has " + safeConfiguredChannels + ".");
    }

    private static boolean canExtractInterleavedConfiguredChannel(ImagePlus source,
                                                                 int channelNum,
                                                                 int configuredChannels) {
        return configuredChannels >= channelNum
                && source != null
                && source.getStack() != null
                && source.getStackSize() >= configuredChannels
                && source.getStackSize() % configuredChannels == 0;
    }

    private static ImagePlus duplicateInterleavedConfiguredChannel(ImagePlus source,
                                                                  int channelNum,
                                                                  int configuredChannels) {
        ImageStack in = source.getStack();
        if (in == null) return null;
        int totalPlanes = Math.max(1, source.getStackSize());
        int frames = 1;
        int reportedFrames = Math.max(1, source.getNFrames());
        if (reportedFrames > 1
                && totalPlanes % (configuredChannels * reportedFrames) == 0) {
            frames = reportedFrames;
        }
        int zSlices = Math.max(1, totalPlanes / (configuredChannels * frames));
        ImageStack out = new ImageStack(source.getWidth(), source.getHeight());
        for (int t = 0; t < frames; t++) {
            for (int z = 0; z < zSlices; z++) {
                int sourceIndex = (t * configuredChannels * zSlices)
                        + (z * configuredChannels)
                        + channelNum;
                ImageProcessor processor = in.getProcessor(sourceIndex);
                if (processor == null) {
                    throw new IllegalStateException("Cannot extract C" + channelNum
                            + " from " + safe(source.getTitle())
                            + ": source plane " + sourceIndex + " is empty.");
                }
                out.addSlice(in.getSliceLabel(sourceIndex), processor.duplicate());
            }
        }
        ImagePlus duplicate = new ImagePlus(source.getTitle(), out);
        Calibration calibration = source.getCalibration();
        if (calibration != null) duplicate.setCalibration(calibration.copy());
        duplicate.setDimensions(1, zSlices, frames);
        duplicate.setOpenAsHyperStack(frames > 1);
        return duplicate;
    }

    private static List<String> channelNamesForContext(BinConfig setupConfig,
                                                       List<ChannelRef> channels) {
        int maxIndex = configuredChannelCount(setupConfig) - 1;
        for (int i = 0; i < channels.size(); i++) {
            maxIndex = Math.max(maxIndex, channels.get(i).channelIndex);
        }
        List<String> names = new ArrayList<String>();
        for (int i = 0; i <= maxIndex; i++) {
            names.add(channelName(setupConfig, i, "C" + (i + 1)));
        }
        for (int i = 0; i < channels.size(); i++) {
            ChannelRef channel = channels.get(i);
            while (names.size() <= channel.channelIndex) {
                names.add("C" + (names.size() + 1));
            }
            names.set(channel.channelIndex, channel.channelName);
        }
        return names;
    }

    private static String setupRangeToken(BinConfig setupConfig, int channelIndex) {
        return valueAt(setupConfig == null ? null : setupConfig.channelMinMax,
                channelIndex, "None");
    }

    private static String quickRangeToken(RepresentativeFigureConfig config,
                                          RepresentativeSelection selection,
                                          ChannelRef channel) {
        if (config == null
                || config.statistic != RepresentativeStatistic.QUICK
                || config.statTable == null
                || config.statTable.isEmpty()) {
            return null;
        }
        Set<String> selectedConditions = new LinkedHashSet<String>();
        if (selection != null) {
            selectedConditions.addAll(selection.conditionNames());
        }
        double max = Double.NaN;
        for (RepresentativeStatTable.Row row : config.statTable.rows()) {
            if (row == null || row.valuesByChannel == null) continue;
            String rowCondition = RepresentativeSelection.conditionLabel(row.conditionName);
            if (!selectedConditions.isEmpty() && !selectedConditions.contains(rowCondition)) {
                continue;
            }
            Double value = valueForChannel(row.valuesByChannel, channel);
            if (value == null || !Double.isFinite(value.doubleValue())
                    || value.doubleValue() <= 0) {
                continue;
            }
            max = Double.isNaN(max)
                    ? value.doubleValue()
                    : Math.max(max, value.doubleValue());
        }
        return Double.isNaN(max) ? null : formatRange(0.0, max);
    }

    private static Double valueForChannel(Map<String, Double> valuesByChannel,
                                          ChannelRef channel) {
        if (valuesByChannel == null || channel == null) return null;
        Double value = valuesByChannel.get(channel.channelName);
        if (value != null) return value;
        String fallback = "C" + (channel.channelIndex + 1);
        value = valuesByChannel.get(fallback);
        if (value != null) return value;
        String target = key(channel.channelName);
        String fallbackKey = key(fallback);
        for (Map.Entry<String, Double> entry : valuesByChannel.entrySet()) {
            String entryKey = key(entry.getKey());
            if (entryKey.equals(target) || entryKey.equals(fallbackKey)) {
                return entry.getValue();
            }
        }
        return null;
    }

    private static int configuredChannelCount(BinConfig setupConfig) {
        if (setupConfig == null) return 0;
        int count = 0;
        count = Math.max(count, setupConfig.channelNames.size());
        count = Math.max(count, setupConfig.channelColors.size());
        count = Math.max(count, setupConfig.channelMinMax.size());
        return count;
    }

    private static String channelName(BinConfig setupConfig,
                                      int channelIndex,
                                      String fallback) {
        String name = valueAt(setupConfig == null ? null : setupConfig.channelNames,
                channelIndex, fallback);
        return name.trim().isEmpty() ? fallback : name.trim();
    }

    private static String channelColor(BinConfig setupConfig, int channelIndex) {
        String color = valueAt(setupConfig == null ? null : setupConfig.channelColors,
                channelIndex, "Grays");
        return color.trim().isEmpty() ? "Grays" : color.trim();
    }

    private static String valueAt(List<String> values, int index, String fallback) {
        if (values == null || index < 0 || index >= values.size()) {
            return fallback == null ? "" : fallback;
        }
        String value = values.get(index);
        return value == null ? (fallback == null ? "" : fallback) : value;
    }

    private static ImagePlus applyPreviewLut(ImagePlus image, String colorName) {
        if (image == null) return null;
        try {
            IJ.run(image, toLutName(colorName), "");
        } catch (RuntimeException e) {
            IJ.log("WARNING: could not apply representative display-range LUT: "
                    + e.getMessage());
        }
        image.updateAndDraw();
        return image;
    }

    private static void copyDisplayRange(ImagePlus source, ImagePlus target) {
        if (source == null || target == null) return;
        try {
            target.setDisplayRange(source.getDisplayRangeMin(), source.getDisplayRangeMax());
        } catch (RuntimeException ignored) {
        }
    }

    private static void closeContextImages(ConfigQcContext context) {
        if (context == null || context.getImages() == null) return;
        List<ConfigQcContext.ConfigQcImage> images = context.getImages();
        for (int i = 0; i < images.size(); i++) {
            ConfigQcContext.ConfigQcImage image = images.get(i);
            if (image != null) closeImageQuietly(image.getImage());
        }
    }

    private static void closeImageQuietly(ImagePlus image) {
        if (image == null) return;
        image.changes = false;
        if (image.getWindow() != null) {
            image.close();
        }
        image.flush();
    }

    static double[] parseRange(String token) {
        String text = token == null ? "" : token.trim();
        if (text.isEmpty() || "none".equalsIgnoreCase(text)) return null;
        int dash = text.indexOf('-');
        if (dash <= 0 || dash >= text.length() - 1) return null;
        try {
            double min = Double.parseDouble(text.substring(0, dash).trim());
            double max = Double.parseDouble(text.substring(dash + 1).trim());
            if (!Double.isFinite(min) || !Double.isFinite(max) || max <= min) {
                return null;
            }
            return new double[]{min, max};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String formatRange(double min, double max) {
        return String.valueOf((int) Math.round(min))
                + "-" + String.valueOf((int) Math.round(max));
    }

    private static String toLutName(String color) {
        if (color == null) return "Grays";
        String normalized = color.trim().toUpperCase(Locale.ROOT);
        if ("RED".equals(normalized)) return "Red";
        if ("GREEN".equals(normalized)) return "Green";
        if ("BLUE".equals(normalized)) return "Blue";
        if ("CYAN".equals(normalized)) return "Cyan";
        if ("MAGENTA".equals(normalized)) return "Magenta";
        if ("YELLOW".equals(normalized)) return "Yellow";
        return "Grays";
    }

    private static String key(String text) {
        return safe(text).trim().toLowerCase(Locale.ROOT);
    }

    private static String safe(String text) {
        return text == null ? "" : text;
    }

    static final class ChannelRef {
        final int channelIndex;
        final String channelName;
        final String colorName;

        ChannelRef(int channelIndex, String channelName, String colorName) {
            this.channelIndex = Math.max(0, channelIndex);
            this.channelName = safe(channelName).trim().isEmpty()
                    ? "C" + (this.channelIndex + 1)
                    : channelName.trim();
            this.colorName = safe(colorName).trim().isEmpty() ? "Grays" : colorName.trim();
        }

        String label() {
            return "C" + (channelIndex + 1) + " - " + channelName;
        }
    }

    private static final class SourceAccess {
        private final String directory;
        private final ImageCache imageCache;
        private final boolean useTifCache;
        private final DeferredImageSupplier supplier;
        private final boolean canUseSessionCache;
        private boolean sessionCacheAttempted = false;
        private List<ImagePlus> sessionImages = null;

        SourceAccess(String directory, ImageCache imageCache, boolean useTifCache)
                throws Exception {
            this.directory = directory;
            this.imageCache = imageCache;
            this.useTifCache = useTifCache;
            this.supplier = ImageSourceDispatcher.createSupplier(directory);
            this.canUseSessionCache = imageCache != null && canUseSessionImageCache(directory);
        }

        ImagePlus openSeries(int seriesIndex) throws Exception {
            ImagePlus cached = openSessionCachedImage(seriesIndex);
            if (cached != null) return cached;
            if (useTifCache && directory != null && !directory.isEmpty()
                    && TifCache.cacheExists(directory)) {
                ImagePlus tif = TifCache.loadSingle(directory, seriesIndex);
                if (tif != null) return tif;
            }
            return supplier.openSeriesMaterialized(seriesIndex);
        }

        void close() {
            supplier.shutdownPrefetch();
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
