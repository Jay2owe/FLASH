package flash.pipeline.deconv;

import flash.pipeline.io.DeferredImageSupplier;

import ij.ImagePlus;
import ij.ImageStack;
import ij.measure.Calibration;
import ij.process.FloatProcessor;
import ij.process.ImageProcessor;

import java.io.File;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Per-channel composition core (Option 2) for the deconvolution routing layer.
 *
 * <p>This class assembles a single {@link ImagePlus} stack from a set of already-resolved
 * per-channel sources (each channel independently raw or deconvolved-mirror) according to the
 * §C1 composition-assembly contract:</p>
 * <ul>
 *   <li>Promote every channel to <b>32-bit float</b> before merge (deconv mirrors are float,
 *       raw is 8/16-bit; mixing types crashes {@code RGBStackMerge}/{@code saveTiff}). Float
 *       promotion is value-preserving so numeric thresholds still fire on raw channels.</li>
 *   <li>Reconcile depth to {@code min(stackSize)} across channels and trim trailing slices to it,
 *       <b>then</b> apply the configured Z-subset (a consumption-time crop). Never pads.</li>
 *   <li>Channel count is authoritative from the source series {@code sizeC}; the config channel
 *       count is validated against it and a mismatch <b>fails loudly</b> (never silently
 *       {@code min()}-ed away).</li>
 *   <li>Assembled channel index == config index (per-channel threshold/LUT/color are indexed by
 *       {@code c}).</li>
 *   <li>Calibration (pixel size / z-step) is stamped from the raw source series after merge.</li>
 *   <li>NaN / negative floats are sanitized (clamped to 0) at assembly.</li>
 * </ul>
 *
 * <p>The {@link #compose} core is deliberately free of any disk or Bio-Formats I/O so it can be
 * unit-tested with synthetic in-memory stacks without booting ImageJ. The disk/supplier plumbing
 * (raw-channel opening, mirror freshness, caching) lives in
 * {@link DeconvolvedInputResolver#resolveRoutedInput}.</p>
 */
public final class RoutedInputComposer {

    private RoutedInputComposer() {}

    // ------------------------------------------------------------------
    // Pure composition core (unit-tested; no disk/Bio-Formats/ImageJ boot)
    // ------------------------------------------------------------------

    /**
     * Assemble a 32-bit float stack from per-channel single-channel sources.
     *
     * @param title            title for the assembled image
     * @param sourceChannels   one single-channel z-stack per <b>source</b> channel, indexed by
     *                         config/source channel index; must be non-null with no null entries
     * @param configNumChannels the channel count declared by the project config; must equal
     *                         {@code sourceChannels.length} (the source series {@code sizeC}) or an
     *                         {@link IllegalArgumentException} is thrown (fail-loud, never min())
     * @param rawCalibration   calibration copied from the raw source series (nullable)
     * @param zStart           1-based inclusive first z-slice of the consumption-time subset, or
     *                         {@code <= 0} for "from the first slice"
     * @param zEnd             1-based inclusive last z-slice of the subset, or {@code <= 0} for
     *                         "through the last (reconciled) slice"
     * @return the assembled 32-bit float {@link ImagePlus} (hyperstack when multi-channel)
     */
    public static ImagePlus compose(String title,
                                    ImagePlus[] sourceChannels,
                                    int configNumChannels,
                                    Calibration rawCalibration,
                                    int zStart,
                                    int zEnd) {
        if (sourceChannels == null) {
            throw new IllegalArgumentException("sourceChannels must not be null");
        }
        int sourceSizeC = sourceChannels.length;
        // Authoritative channel count from the source series; the config must agree. Fail loudly
        // rather than silently min()-ing away a mismatch (that would drop or duplicate a channel
        // and misalign every per-channel threshold/LUT downstream).
        if (configNumChannels != sourceSizeC) {
            throw new IllegalArgumentException(
                    "Channel count mismatch: project config declares " + configNumChannels
                            + " channel(s) but the source series has " + sourceSizeC
                            + ". Re-run Set Up Configuration for this dataset.");
        }
        if (sourceSizeC == 0) {
            throw new IllegalArgumentException("source series has no channels");
        }
        for (int c = 0; c < sourceSizeC; c++) {
            if (sourceChannels[c] == null) {
                throw new IllegalArgumentException("channel " + c + " could not be resolved (null)");
            }
        }

        int width = Math.max(1, sourceChannels[0].getWidth());
        int height = Math.max(1, sourceChannels[0].getHeight());
        for (int c = 1; c < sourceSizeC; c++) {
            if (sourceChannels[c].getWidth() != width || sourceChannels[c].getHeight() != height) {
                throw new IllegalArgumentException("channel " + c + " XY dimensions ("
                        + sourceChannels[c].getWidth() + "x" + sourceChannels[c].getHeight()
                        + ") do not match channel 0 (" + width + "x" + height + ")");
            }
        }

        // Depth = min(stackSize) across resolved channels; trailing slices trimmed to it. A mirror
        // longer than a sibling is not padded onto the others — it is capped away here.
        int targetDepth = Integer.MAX_VALUE;
        for (int c = 0; c < sourceSizeC; c++) {
            targetDepth = Math.min(targetDepth, Math.max(1, sourceChannels[c].getStackSize()));
        }

        // Consumption-time Z-subset applied AFTER the depth reconcile: clamp the requested range
        // into [1, targetDepth] so every channel (all of which have >= targetDepth slices) is read
        // strictly in range.
        int lo = zStart <= 0 ? 1 : Math.min(zStart, targetDepth);
        int hi = (zEnd <= 0 || zEnd > targetDepth) ? targetDepth : zEnd;
        if (hi < lo) {
            hi = lo;
        }

        // Assemble the interleaved 32-bit float hyperstack directly (channel-fastest CZT order, so
        // getStackIndex(c, z, 1) addresses it). Building the stack by hand keeps this a pure data op
        // with no CompositeImage/AWT/window-manager dependency, so it is safe headless and in tests.
        ImageStack out = new ImageStack(width, height);
        for (int z = lo; z <= hi; z++) {
            for (int c = 0; c < sourceSizeC; c++) {
                ImageProcessor ip = processorAt(sourceChannels[c], z);
                // convertToFloatProcessor() always returns a NEW FloatProcessor (value-preserving
                // for byte/short/float inputs), so the source's pixels are never aliased or mutated.
                FloatProcessor fp = ip.convertToFloatProcessor();
                sanitize(fp);
                out.addSlice(fp);
            }
        }

        int outDepth = hi - lo + 1;
        ImagePlus result = new ImagePlus(title == null || title.isEmpty() ? "composed" : title, out);
        result.setDimensions(sourceSizeC, outDepth, 1);
        result.setOpenAsHyperStack(sourceSizeC > 1);
        if (rawCalibration != null) {
            result.setCalibration(rawCalibration.copy());
        }
        return result;
    }

    /** Convenience overload composing the full reconciled depth (no Z-subset). */
    public static ImagePlus compose(String title,
                                    ImagePlus[] sourceChannels,
                                    int configNumChannels,
                                    Calibration rawCalibration) {
        return compose(title, sourceChannels, configNumChannels, rawCalibration, 0, 0);
    }

    private static ImageProcessor processorAt(ImagePlus src, int z) {
        ImageStack in = src.getStack();
        if (in == null || in.getSize() < 1) {
            return src.getProcessor();
        }
        int clamped = Math.max(1, Math.min(z, in.getSize()));
        return in.getProcessor(clamped);
    }

    /** Clamp NaN, +/-Inf and negative values to 0 (value-preserving for non-negative raw pixels). */
    private static void sanitize(FloatProcessor fp) {
        float[] px = (float[]) fp.getPixels();
        for (int i = 0; i < px.length; i++) {
            float v = px[i];
            if (Float.isNaN(v) || Float.isInfinite(v) || v < 0.0f) {
                px[i] = 0.0f;
            }
        }
    }

    /**
     * Crop a (possibly multi-channel) hyperstack to the 1-based inclusive Z range, preserving
     * channel count and calibration. Returns the same instance when the range covers the whole
     * depth. Never reads outside {@code [1, nSlices]}.
     */
    public static ImagePlus cropZ(ImagePlus img, int zStart, int zEnd) {
        if (img == null) return null;
        int channels = Math.max(1, img.getNChannels());
        int slices = Math.max(1, img.getNSlices());
        int lo = zStart <= 0 ? 1 : Math.min(zStart, slices);
        int hi = (zEnd <= 0 || zEnd > slices) ? slices : zEnd;
        if (hi < lo) hi = lo;
        if (lo == 1 && hi == slices) {
            return img; // full range, no-op
        }
        ImageStack src = img.getStack();
        ImageStack out = new ImageStack(img.getWidth(), img.getHeight());
        for (int z = lo; z <= hi; z++) {
            for (int c = 1; c <= channels; c++) {
                int idx = img.getStackIndex(c, z, 1);
                ImageProcessor ip = src.getProcessor(idx);
                out.addSlice(src.getSliceLabel(idx), ip.duplicate());
            }
        }
        ImagePlus cropped = new ImagePlus(img.getTitle(), out);
        cropped.setDimensions(channels, hi - lo + 1, 1);
        cropped.setOpenAsHyperStack(channels > 1);
        if (img.getCalibration() != null) {
            cropped.setCalibration(img.getCalibration().copy());
        }
        return cropped;
    }

    // ------------------------------------------------------------------
    // Raw-series source seam (adapts a DeferredImageSupplier; not unit-tested)
    // ------------------------------------------------------------------

    /**
     * The raw pixel/metadata source the composer needs: the authoritative channel count and depth,
     * the calibration to stamp, and a materialized (never virtual) single raw channel. Kept as an
     * interface so {@link DeconvolvedInputResolver#resolveRoutedInput} does not hard-code source
     * discovery and so alternative sources can be substituted.
     */
    public interface RawSeriesSource {
        int sizeC() throws Exception;

        int sizeZ() throws Exception;

        Calibration calibration() throws Exception;

        /** A fresh, materialized, single-channel z-stack for the given source channel index. */
        ImagePlus openRawChannel(int channelIndex) throws Exception;

        void close();
    }

    /**
     * Adapt a {@link DeferredImageSupplier} + series index to a {@link RawSeriesSource}.
     *
     * <p>The series is materialized once (never virtual) to serve {@code sizeC}/{@code sizeZ}/
     * calibration and to extract individual raw channels; this reads the container a single time
     * rather than re-opening it per raw channel. The extracted channels are deep-copied so callers
     * may mutate them freely.</p>
     */
    public static RawSeriesSource forSupplier(final DeferredImageSupplier supplier, final int seriesIndex) {
        return new RawSeriesSource() {
            private ImagePlus full;

            private ImagePlus full() throws Exception {
                if (full == null) {
                    full = supplier.openSeriesMaterialized(seriesIndex);
                    if (full == null) {
                        throw new java.io.IOException("Could not open source series " + seriesIndex);
                    }
                }
                return full;
            }

            @Override
            public int sizeC() throws Exception {
                return Math.max(1, full().getNChannels());
            }

            @Override
            public int sizeZ() throws Exception {
                return Math.max(1, full().getNSlices());
            }

            @Override
            public Calibration calibration() throws Exception {
                Calibration cal = full().getCalibration();
                return cal == null ? null : cal.copy();
            }

            @Override
            public ImagePlus openRawChannel(int channelIndex) throws Exception {
                return extractChannel(full(), channelIndex);
            }

            @Override
            public void close() {
                closeQuietly(full);
                full = null;
            }
        };
    }

    /**
     * Extract a single channel from a materialized (non-virtual) hyperstack as a fresh
     * single-channel z-stack. Processors are duplicated so the returned image aliases nothing.
     */
    static ImagePlus extractChannel(ImagePlus full, int channelIndex) {
        int channels = Math.max(1, full.getNChannels());
        int slices = Math.max(1, full.getNSlices());
        if (channelIndex < 0 || channelIndex >= channels) {
            throw new IllegalArgumentException("channel " + channelIndex + " out of range [0, " + channels + ")");
        }
        ImageStack src = full.getStack();
        ImageStack out = new ImageStack(full.getWidth(), full.getHeight());
        int c = channelIndex + 1; // ImagePlus channels are 1-based
        for (int z = 1; z <= slices; z++) {
            int idx = full.getStackIndex(c, z, 1);
            ImageProcessor ip = src.getProcessor(idx);
            out.addSlice(src.getSliceLabel(idx), ip.duplicate());
        }
        ImagePlus channel = new ImagePlus(full.getTitle() + "_C" + channelIndex, out);
        channel.setDimensions(1, slices, 1);
        if (full.getCalibration() != null) {
            channel.setCalibration(full.getCalibration().copy());
        }
        return channel;
    }

    /**
     * Thread-safe deep copy of an assembled stack (processor.duplicate() per slice), avoiding
     * {@code ij.plugin.Duplicator} which is not thread-safe. Preserves dimensions and calibration.
     */
    static ImagePlus deepCopy(ImagePlus img) {
        if (img == null) return null;
        int channels = Math.max(1, img.getNChannels());
        int slices = Math.max(1, img.getNSlices());
        int frames = Math.max(1, img.getNFrames());
        ImageStack src = img.getStack();
        ImageStack out = new ImageStack(img.getWidth(), img.getHeight());
        for (int i = 1; i <= src.getSize(); i++) {
            out.addSlice(src.getSliceLabel(i), src.getProcessor(i).duplicate());
        }
        ImagePlus copy = new ImagePlus(img.getTitle(), out);
        copy.setDimensions(channels, slices, frames);
        copy.setOpenAsHyperStack(channels > 1 || frames > 1);
        if (img.getCalibration() != null) {
            copy.setCalibration(img.getCalibration().copy());
        }
        return copy;
    }

    static void closeQuietly(ImagePlus image) {
        if (image == null) return;
        try {
            image.changes = false;
            image.close();
        } catch (RuntimeException ignored) {
            // best-effort cleanup
        }
    }

    // ------------------------------------------------------------------
    // Assembled-stack cache (keyed on the per-channel decision + freshness inputs)
    // ------------------------------------------------------------------

    private static final int MAX_CACHE_ENTRIES = 4;
    private static final LinkedHashMap<ComposeKey, ImagePlus> CACHE =
            new LinkedHashMap<ComposeKey, ImagePlus>(8, 0.75f, true);

    /** Returns a private deep copy of the cached assembled stack, or {@code null} on a miss. */
    public static synchronized ImagePlus getCached(ComposeKey key) {
        if (key == null) return null;
        ImagePlus cached = CACHE.get(key);
        return cached == null ? null : deepCopy(cached);
    }

    /** Store a private deep copy of the assembled stack, evicting the eldest entry if full. */
    public static synchronized void putCached(ComposeKey key, ImagePlus assembled) {
        if (key == null || assembled == null) return;
        ImagePlus previous = CACHE.put(key, deepCopy(assembled));
        closeQuietly(previous);
        while (CACHE.size() > MAX_CACHE_ENTRIES) {
            Iterator<Map.Entry<ComposeKey, ImagePlus>> it = CACHE.entrySet().iterator();
            if (!it.hasNext()) break;
            Map.Entry<ComposeKey, ImagePlus> eldest = it.next();
            it.remove();
            closeQuietly(eldest.getValue());
        }
    }

    /** Test/maintenance hook: drop all cached stacks. */
    public static synchronized void clearCache() {
        for (ImagePlus img : CACHE.values()) {
            closeQuietly(img);
        }
        CACHE.clear();
    }

    /**
     * Value key identifying an assembled stack: the project + series, the raw source fingerprint
     * (size + mtime), the per-channel mirror-vs-raw decision vector, the mtime of each used mirror,
     * and the Z-subset. Two consumer reads that would assemble byte-identical pixels share a key.
     */
    public static final class ComposeKey {
        private final String rootPath;
        private final String baseName;
        private final String group;
        private final int seriesIndex;
        private final long sourceSize;
        private final long sourceMtime;
        private final boolean[] usedDeconv;
        private final long[] mirrorMtimes;
        private final int zStart;
        private final int zEnd;

        public ComposeKey(File rootDir,
                          String baseName,
                          String group,
                          int seriesIndex,
                          long sourceSize,
                          long sourceMtime,
                          boolean[] usedDeconv,
                          long[] mirrorMtimes,
                          int zStart,
                          int zEnd) {
            this.rootPath = rootDir == null ? "" : rootDir.getAbsolutePath();
            this.baseName = baseName == null ? "" : baseName;
            this.group = group == null ? "" : group;
            this.seriesIndex = seriesIndex;
            this.sourceSize = sourceSize;
            this.sourceMtime = sourceMtime;
            this.usedDeconv = usedDeconv == null ? new boolean[0] : usedDeconv.clone();
            this.mirrorMtimes = mirrorMtimes == null ? new long[0] : mirrorMtimes.clone();
            this.zStart = zStart;
            this.zEnd = zEnd;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof ComposeKey)) return false;
            ComposeKey that = (ComposeKey) o;
            return seriesIndex == that.seriesIndex
                    && sourceSize == that.sourceSize
                    && sourceMtime == that.sourceMtime
                    && zStart == that.zStart
                    && zEnd == that.zEnd
                    && rootPath.equals(that.rootPath)
                    && baseName.equals(that.baseName)
                    && group.equals(that.group)
                    && Arrays.equals(usedDeconv, that.usedDeconv)
                    && Arrays.equals(mirrorMtimes, that.mirrorMtimes);
        }

        @Override
        public int hashCode() {
            int result = rootPath.hashCode();
            result = 31 * result + baseName.hashCode();
            result = 31 * result + group.hashCode();
            result = 31 * result + seriesIndex;
            result = 31 * result + (int) (sourceSize ^ (sourceSize >>> 32));
            result = 31 * result + (int) (sourceMtime ^ (sourceMtime >>> 32));
            result = 31 * result + Arrays.hashCode(usedDeconv);
            result = 31 * result + Arrays.hashCode(mirrorMtimes);
            result = 31 * result + zStart;
            result = 31 * result + zEnd;
            return result;
        }

        @Override
        public String toString() {
            return "ComposeKey{" + baseName + "#" + seriesIndex + " group=" + group
                    + " used=" + Arrays.toString(usedDeconv)
                    + " z=[" + zStart + "," + zEnd + "]}";
        }
    }
}
