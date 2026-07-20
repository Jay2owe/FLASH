package flash.pipeline.io;

import flash.pipeline.intelligence.JunkFileFilter;
import flash.pipeline.naming.ChannelFilenameCodec;

import ij.IJ;
import ij.ImagePlus;
import ij.io.FileSaver;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

/**
 * Manages TIFFs materialized from source-container series.
 *
 * <p>Source-bound entries are immutable generations. The TIFF and its manifest
 * are completely written and verified before a small completion pointer is
 * atomically replaced. Readers therefore see either the previous valid
 * generation or the next valid generation, never a partly published one.</p>
 */
public class TifCache {

    /** Cache directory name, created inside the working directory. */
    public static final String CACHE_DIR = FlashProjectLayout.TIF_CACHE_DIR;

    private static final String SCHEMA_VERSION = "1";
    private static final String KIND_SOURCE = "source";
    private static final String KIND_LEGACY = "legacy";
    private static final String MATERIALIZATION_SETTINGS = "imagej-materialized-tiff-v1";
    private static final int MAX_MANIFEST_BYTES = 64 * 1024;
    private static final int MAX_POINTER_BYTES = 1024;
    private static final int FULL_HASH_LIMIT = 8 * 1024 * 1024;
    private static final int SAMPLE_SIZE = 64 * 1024;

    /** Immutable identity needed to decide whether one source series may hit. */
    static final class CacheRequest {
        final String canonicalSource;
        final long sourceSize;
        final long sourceModified;
        final String sourceFingerprint;
        final int localSeriesIndex;
        final String settings;

        private CacheRequest(String canonicalSource, long sourceSize,
                             long sourceModified, String sourceFingerprint,
                             int localSeriesIndex, String settings) {
            this.canonicalSource = canonicalSource;
            this.sourceSize = sourceSize;
            this.sourceModified = sourceModified;
            this.sourceFingerprint = sourceFingerprint;
            this.localSeriesIndex = localSeriesIndex;
            this.settings = settings;
        }
    }

    enum PublicationStep {
        BEFORE_TIFF_WRITE,
        BEFORE_TIFF_REOPEN,
        BEFORE_MANIFEST_WRITE,
        BEFORE_TIFF_MOVE,
        BEFORE_MANIFEST_MOVE,
        BEFORE_COMPLETION_MOVE
    }

    interface PublicationFault {
        void before(PublicationStep step, Path affectedPath) throws IOException;
    }

    private static final PublicationFault NO_FAULT = new PublicationFault() {
        @Override
        public void before(PublicationStep step, Path affectedPath) {
            // Production path: no injected fault.
        }
    };

    /** Returns the write cache directory for the given working directory. */
    public static File getCacheDir(String directory) {
        return FlashProjectLayout.forDirectory(directory).tifCacheWriteDir();
    }

    /**
     * Computes the live source identity for one source-local series.
     * The sampled SHA-256 includes deterministic first/quarter/middle/three-
     * quarter/last blocks for large files and hashes small files in full.
     */
    static CacheRequest requestFor(File source, int localSeriesIndex) throws IOException {
        if (source == null || !source.isFile()) {
            throw new IOException("TIFF cache source is not a readable file: " + source);
        }
        if (localSeriesIndex < 0) {
            throw new IOException("TIFF cache local series index must be non-negative");
        }
        File canonical = source.getCanonicalFile();
        long size = canonical.length();
        long modified = canonical.lastModified();
        String fingerprint = sampledSha256(canonical);
        if (canonical.length() != size || canonical.lastModified() != modified) {
            throw new IOException("TIFF cache source changed while it was fingerprinted: "
                    + canonical);
        }
        return new CacheRequest(canonical.getPath(), size,
                modified, fingerprint,
                localSeriesIndex, MATERIALIZATION_SETTINGS);
    }

    static boolean sameRequest(CacheRequest first, CacheRequest second) {
        return first != null && second != null
                && first.canonicalSource.equals(second.canonicalSource)
                && first.sourceSize == second.sourceSize
                && first.sourceModified == second.sourceModified
                && first.sourceFingerprint.equals(second.sourceFingerprint)
                && first.localSeriesIndex == second.localSeriesIndex
                && first.settings.equals(second.settings);
    }

    /** Checks whether the legacy representative-figure cache has a valid entry. */
    public static boolean cacheExists(String directory) {
        File cacheDir = firstExistingCacheDir(directory);
        File[] pointers = listCompletionPointers(cacheDir);
        if (pointers == null) return false;
        for (File pointer : pointers) {
            if (validatedUsableEntry(cacheDir, seriesIndex(pointer), false) != null) {
                return true;
            }
        }
        return false;
    }

    /**
     * Saves an entry for legacy callers that do not have source provenance.
     * Such entries are never accepted by the source-bound loader APIs below.
     */
    public static void saveToCache(String directory, ImagePlus imp, int index) {
        try {
            saveInternal(directory, imp, index, null, NO_FAULT);
        } catch (IOException failure) {
            IJ.log("WARNING: Could not publish legacy TIFF cache series "
                    + (index + 1) + ": " + failure.getMessage());
        }
    }

    /** Publishes a source-bound entry, preserving the prior generation on failure. */
    static void saveToCache(String directory, ImagePlus imp, int index,
                            CacheRequest request) throws IOException {
        saveInternal(directory, imp, index, request, NO_FAULT);
    }

    /** Test seam for deterministic publication-boundary faults. */
    static void saveToCache(String directory, ImagePlus imp, int index,
                            CacheRequest request, PublicationFault fault) throws IOException {
        saveInternal(directory, imp, index, request, fault == null ? NO_FAULT : fault);
    }

    private static void saveInternal(String directory, ImagePlus imp, int index,
                                     CacheRequest request, PublicationFault fault)
            throws IOException {
        if (imp == null) throw new IOException("Cannot cache a null image");
        if (index < 0) throw new IOException("Cache series index must be non-negative");

        File cacheDir = getCacheDir(directory);
        if (!cacheDir.isDirectory() && !cacheDir.mkdirs() && !cacheDir.isDirectory()) {
            throw new IOException("Could not create TIFF cache directory " + cacheDir);
        }

        String prefix = String.format(Locale.ROOT, "%04d_", index);
        String generation = UUID.randomUUID().toString().replace("-", "");
        String safeName = sanitizeFilename(imp.getTitle());
        if (safeName.length() > 80) safeName = safeName.substring(0, 80);
        File finalTiff = new File(cacheDir,
                prefix + safeName + "." + generation + ".tif");
        File finalManifest = new File(cacheDir,
                prefix + generation + ".manifest");
        File finalPointer = completionPointer(cacheDir, index);

        Path tempTiff = null;
        Path tempManifest = null;
        Path tempPointer = null;
        boolean tiffPublished = false;
        boolean manifestPublished = false;
        boolean committed = false;
        try {
            tempTiff = Files.createTempFile(cacheDir.toPath(), prefix + "candidate-", ".tif.tmp");
            tempManifest = Files.createTempFile(cacheDir.toPath(), prefix + "candidate-", ".manifest.tmp");
            tempPointer = Files.createTempFile(cacheDir.toPath(), prefix + "candidate-", ".complete.tmp");
            fault.before(PublicationStep.BEFORE_TIFF_WRITE, tempTiff);
            FileSaver saver = new FileSaver(imp);
            boolean saved = imp.getStackSize() > 1
                    ? saver.saveAsTiffStack(tempTiff.toString())
                    : saver.saveAsTiff(tempTiff.toString());
            if (!saved || !Files.isRegularFile(tempTiff) || Files.size(tempTiff) <= 0L) {
                throw new IOException("TIFF writer did not produce a non-empty cache candidate");
            }

            fault.before(PublicationStep.BEFORE_TIFF_REOPEN, tempTiff);
            validateImageFile(tempTiff.toFile(), imp.getWidth(), imp.getHeight(),
                    imp.getStackSize(), imp.getBitDepth());

            Properties manifest = buildManifest(request, generation,
                    finalTiff.getName(), imp, tempTiff.toFile());
            fault.before(PublicationStep.BEFORE_MANIFEST_WRITE, tempManifest);
            writePropertiesDurably(tempManifest, manifest);
            Properties reopenedManifest = readProperties(tempManifest.toFile());
            if (!manifest.equals(reopenedManifest)) {
                throw new IOException("Reopened TIFF cache manifest did not match its candidate");
            }

            writeBytesDurably(tempPointer,
                    (finalManifest.getName() + "\n").getBytes(StandardCharsets.UTF_8));

            fault.before(PublicationStep.BEFORE_TIFF_MOVE, finalTiff.toPath());
            moveAtomically(tempTiff, finalTiff.toPath(), false);
            tiffPublished = true;

            fault.before(PublicationStep.BEFORE_MANIFEST_MOVE, finalManifest.toPath());
            moveAtomically(tempManifest, finalManifest.toPath(), false);
            manifestPublished = true;

            // Verify the immutable generation at its final names before exposing it.
            if (validatedManifestEntry(cacheDir, finalManifest, request,
                    request == null, false) == null) {
                throw new IOException("Published TIFF cache generation failed final validation");
            }

            fault.before(PublicationStep.BEFORE_COMPLETION_MOVE, finalPointer.toPath());
            moveAtomically(tempPointer, finalPointer.toPath(), true);
            committed = true;
            removeObsoleteSeriesFiles(cacheDir, index, finalTiff, finalManifest, finalPointer);
        } finally {
            deleteQuietly(tempTiff);
            deleteQuietly(tempManifest);
            deleteQuietly(tempPointer);
            if (!committed) {
                if (manifestPublished) deleteQuietly(finalManifest.toPath());
                if (tiffPublished) deleteQuietly(finalTiff.toPath());
            }
        }
    }

    /** Loads all completed legacy entries in series order. */
    public static List<ImagePlus> loadAll(String directory) {
        File cacheDir = firstExistingCacheDir(directory);
        File[] pointers = listCompletionPointers(cacheDir);
        if (pointers == null || pointers.length == 0) return new ArrayList<ImagePlus>();
        Arrays.sort(pointers, new Comparator<File>() {
            @Override
            public int compare(File a, File b) {
                return a.getName().compareTo(b.getName());
            }
        });
        List<ImagePlus> images = new ArrayList<ImagePlus>();
        for (File pointer : pointers) {
            int index = seriesIndex(pointer);
            ImagePlus imp = loadSingle(directory, index);
            if (imp != null) images.add(imp);
        }
        return images;
    }

    /** Opens one completed legacy cache entry. */
    public static ImagePlus loadSingle(String directory, int index) {
        File cacheDir = firstExistingCacheDir(directory);
        ValidatedEntry entry = validatedUsableEntry(cacheDir, index, true);
        return openValidated(entry);
    }

    /** Opens one entry only if it still matches the supplied live source identity. */
    static ImagePlus loadSingle(String directory, int index, CacheRequest request) {
        File cacheDir = firstExistingCacheDir(directory);
        ValidatedEntry entry = validatedEntry(cacheDir, index, request, false, true);
        return openValidated(entry);
    }

    /** Returns the completed legacy TIFF file without opening it. */
    public static File cachedFileForSeries(String directory, int index) {
        File cacheDir = firstExistingCacheDir(directory);
        ValidatedEntry entry = validatedUsableEntry(cacheDir, index, false);
        if (entry != null) return entry.tiff;
        // Very old representative-figure exports used a raw index-prefixed
        // TIFF without a manifest. Keep that copy-only compatibility path, but
        // never open it as image pixels and never prefer it over a completion
        // pointer (valid or invalid).
        if (completionPointer(cacheDir, index).exists()) return null;
        return findUnmanifestedLegacyTiff(cacheDir, index);
    }

    /** Legacy API: unmanifested filename-only TIFFs are deliberately no longer hits. */
    public static boolean hasAllSeries(String directory, List<Integer> indices) {
        if (indices == null || indices.isEmpty()) return false;
        File cacheDir = firstExistingCacheDir(directory);
        for (Integer index : indices) {
            if (index == null || validatedUsableEntry(cacheDir,
                    index.intValue(), false) == null) return false;
        }
        return true;
    }

    /** Returns true only when every global series matches its live source request. */
    static boolean hasAllSeries(String directory, Map<Integer, CacheRequest> requests) {
        if (requests == null || requests.isEmpty()) return false;
        File cacheDir = firstExistingCacheDir(directory);
        for (Map.Entry<Integer, CacheRequest> entry : requests.entrySet()) {
            Integer index = entry.getKey();
            if (index == null || entry.getValue() == null
                    || validatedEntry(cacheDir, index.intValue(), entry.getValue(),
                    false, false) == null) return false;
        }
        return true;
    }

    /** Number of completed generations (source-bound or legacy). */
    public static int cacheSize(String directory) {
        File[] pointers = listCompletionPointers(firstExistingCacheDir(directory));
        return pointers == null ? 0 : pointers.length;
    }

    private static Properties buildManifest(CacheRequest request, String generation,
                                            String tiffName, ImagePlus imp,
                                            File stagedTiff)
            throws IOException {
        Properties properties = new Properties();
        properties.setProperty("schema", SCHEMA_VERSION);
        properties.setProperty("kind", request == null ? KIND_LEGACY : KIND_SOURCE);
        properties.setProperty("generation", generation);
        properties.setProperty("tiff", tiffName);
        properties.setProperty("title", imp.getTitle() == null ? "" : imp.getTitle());
        properties.setProperty("width", Integer.toString(imp.getWidth()));
        properties.setProperty("height", Integer.toString(imp.getHeight()));
        properties.setProperty("stackSize", Integer.toString(imp.getStackSize()));
        properties.setProperty("bitDepth", Integer.toString(imp.getBitDepth()));
        properties.setProperty("tiffSize", Long.toString(stagedTiff.length()));
        properties.setProperty("tiffFingerprint", sampledSha256(stagedTiff));
        if (request != null) {
            properties.setProperty("sourcePath", request.canonicalSource);
            properties.setProperty("sourceSize", Long.toString(request.sourceSize));
            properties.setProperty("sourceModified", Long.toString(request.sourceModified));
            properties.setProperty("sourceFingerprint", request.sourceFingerprint);
            properties.setProperty("localSeries", Integer.toString(request.localSeriesIndex));
            properties.setProperty("settings", request.settings);
        }
        return properties;
    }

    private static ValidatedEntry validatedEntry(File cacheDir, int index,
                                                 CacheRequest request,
                                                 boolean requireLegacy,
                                                 boolean keepOpen) {
        if (cacheDir == null || !cacheDir.isDirectory() || index < 0) return null;
        File pointer = completionPointer(cacheDir, index);
        if (!pointer.isFile() || pointer.length() <= 0L
                || pointer.length() > MAX_POINTER_BYTES) return null;
        try {
            String manifestName = new String(Files.readAllBytes(pointer.toPath()),
                    StandardCharsets.UTF_8).trim();
            String expectedPrefix = String.format(Locale.ROOT, "%04d_", index);
            if (!isSafeSiblingName(manifestName)
                    || !manifestName.startsWith(expectedPrefix)
                    || !manifestName.endsWith(".manifest")) return null;
            return validatedManifestEntry(cacheDir, new File(cacheDir, manifestName),
                    request, requireLegacy, keepOpen);
        } catch (IOException ignored) {
            return null;
        }
    }

    /**
     * Validates the current pointer for legacy callers. Source-bound entries
     * are accepted only after recomputing identity from the manifest's source
     * path; a missing/replaced source is therefore still a miss.
     */
    private static ValidatedEntry validatedUsableEntry(File cacheDir, int index,
                                                       boolean keepOpen) {
        File manifestFile = currentManifestFile(cacheDir, index);
        if (manifestFile == null) return null;
        try {
            Properties manifest = readProperties(manifestFile);
            String kind = manifest.getProperty("kind");
            if (KIND_LEGACY.equals(kind)) {
                return validatedEntry(cacheDir, index, null, true, keepOpen);
            }
            if (!KIND_SOURCE.equals(kind)) return null;
            String sourcePath = manifest.getProperty("sourcePath");
            int localSeries = parseInt(manifest, "localSeries");
            CacheRequest live = requestFor(new File(sourcePath), localSeries);
            return validatedEntry(cacheDir, index, live, false, keepOpen);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static File currentManifestFile(File cacheDir, int index) {
        if (cacheDir == null || !cacheDir.isDirectory() || index < 0) return null;
        File pointer = completionPointer(cacheDir, index);
        if (!pointer.isFile() || pointer.length() <= 0L
                || pointer.length() > MAX_POINTER_BYTES) return null;
        try {
            String manifestName = new String(Files.readAllBytes(pointer.toPath()),
                    StandardCharsets.UTF_8).trim();
            String expectedPrefix = String.format(Locale.ROOT, "%04d_", index);
            if (!isSafeSiblingName(manifestName)
                    || !manifestName.startsWith(expectedPrefix)
                    || !manifestName.endsWith(".manifest")) return null;
            return new File(cacheDir, manifestName);
        } catch (IOException ignored) {
            return null;
        }
    }

    private static ValidatedEntry validatedManifestEntry(File cacheDir, File manifestFile,
                                                          CacheRequest request,
                                                          boolean requireLegacy,
                                                          boolean keepOpen) {
        ImagePlus opened = null;
        try {
            if (!manifestFile.isFile() || manifestFile.length() <= 0L
                    || manifestFile.length() > MAX_MANIFEST_BYTES) return null;
            Properties manifest = readProperties(manifestFile);
            if (!SCHEMA_VERSION.equals(manifest.getProperty("schema"))) return null;
            String kind = manifest.getProperty("kind");
            if (requireLegacy) {
                if (!KIND_LEGACY.equals(kind)) return null;
            } else {
                if (!KIND_SOURCE.equals(kind) || request == null
                        || !requestMatches(manifest, request)) return null;
            }
            String tiffName = manifest.getProperty("tiff");
            String generation = manifest.getProperty("generation");
            if (!isSafeSiblingName(tiffName) || generation == null || generation.length() != 32
                    || !manifestFile.getName().endsWith(generation + ".manifest")
                    || !tiffName.endsWith("." + generation + ".tif")) return null;
            File tiff = new File(cacheDir, tiffName);
            long expectedSize = parseLong(manifest, "tiffSize");
            if (!tiff.isFile() || expectedSize <= 0L || tiff.length() != expectedSize) return null;
            if (!sampledSha256(tiff).equals(manifest.getProperty("tiffFingerprint"))) return null;
            int width = parseInt(manifest, "width");
            int height = parseInt(manifest, "height");
            int stackSize = parseInt(manifest, "stackSize");
            int bitDepth = parseInt(manifest, "bitDepth");
            opened = IJ.openImage(tiff.getAbsolutePath());
            if (!matchesGeometry(opened, width, height, stackSize, bitDepth)) return null;
            ValidatedEntry result = new ValidatedEntry(tiff,
                    manifest.getProperty("title", ""), width, height, stackSize, bitDepth,
                    keepOpen ? opened : null);
            if (keepOpen) opened = null;
            return result;
        } catch (Exception ignored) {
            return null;
        } finally {
            closeQuietly(opened);
        }
    }

    private static boolean requestMatches(Properties manifest, CacheRequest request) {
        return request.canonicalSource.equals(manifest.getProperty("sourcePath"))
                && Long.toString(request.sourceSize).equals(manifest.getProperty("sourceSize"))
                && Long.toString(request.sourceModified).equals(manifest.getProperty("sourceModified"))
                && request.sourceFingerprint.equals(manifest.getProperty("sourceFingerprint"))
                && Integer.toString(request.localSeriesIndex).equals(manifest.getProperty("localSeries"))
                && request.settings.equals(manifest.getProperty("settings"));
    }

    private static ImagePlus openValidated(ValidatedEntry entry) {
        if (entry == null) return null;
        ImagePlus imp = entry.opened != null ? entry.opened : IJ.openImage(entry.tiff.getAbsolutePath());
        if (!matchesGeometry(imp, entry.width, entry.height, entry.stackSize, entry.bitDepth)) {
            closeQuietly(imp);
            return null;
        }
        imp.setTitle(entry.title);
        return imp;
    }

    private static void validateImageFile(File file, int width, int height,
                                          int stackSize, int bitDepth) throws IOException {
        ImagePlus reopened = IJ.openImage(file.getAbsolutePath());
        try {
            if (!matchesGeometry(reopened, width, height, stackSize, bitDepth)) {
                throw new IOException("Reopened TIFF cache candidate has unexpected geometry or type");
            }
        } finally {
            closeQuietly(reopened);
        }
    }

    private static boolean matchesGeometry(ImagePlus image, int width, int height,
                                           int stackSize, int bitDepth) {
        return image != null && image.getWidth() == width && image.getHeight() == height
                && image.getStackSize() == stackSize && image.getBitDepth() == bitDepth;
    }

    private static void closeQuietly(ImagePlus image) {
        if (image == null) return;
        image.changes = false;
        image.close();
        image.flush();
    }

    private static void writePropertiesDurably(Path path, Properties properties) throws IOException {
        FileOutputStream file = new FileOutputStream(path.toFile());
        try {
            OutputStream output = new BufferedOutputStream(file);
            properties.store(output, "FLASH TIFF cache manifest");
            output.flush();
            file.getFD().sync();
        } finally {
            file.close();
        }
    }

    private static Properties readProperties(File file) throws IOException {
        if (!file.isFile() || file.length() <= 0L || file.length() > MAX_MANIFEST_BYTES) {
            throw new IOException("Invalid TIFF cache manifest size");
        }
        Properties properties = new Properties();
        InputStream input = new BufferedInputStream(new FileInputStream(file));
        try {
            properties.load(input);
        } finally {
            input.close();
        }
        return properties;
    }

    private static void writeBytesDurably(Path path, byte[] bytes) throws IOException {
        FileOutputStream file = new FileOutputStream(path.toFile());
        try {
            OutputStream output = new BufferedOutputStream(file);
            output.write(bytes);
            output.flush();
            file.getFD().sync();
        } finally {
            file.close();
        }
    }

    private static void moveAtomically(Path source, Path target, boolean replace) throws IOException {
        if (replace) {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } else {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        }
    }

    private static String sampledSha256(File file) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IOException("SHA-256 is unavailable", impossible);
        }
        long length = file.length();
        updateLong(digest, length);
        RandomAccessFile input = new RandomAccessFile(file, "r");
        try {
            if (length <= FULL_HASH_LIMIT) {
                hashRange(input, digest, 0L, length);
            } else {
                long maxStart = Math.max(0L, length - SAMPLE_SIZE);
                long[] starts = new long[] {
                        0L,
                        clamp(length / 4L - SAMPLE_SIZE / 2L, maxStart),
                        clamp(length / 2L - SAMPLE_SIZE / 2L, maxStart),
                        clamp((length * 3L) / 4L - SAMPLE_SIZE / 2L, maxStart),
                        maxStart
                };
                long previous = -1L;
                for (long start : starts) {
                    if (start == previous) continue;
                    updateLong(digest, start);
                    hashRange(input, digest, start, Math.min(SAMPLE_SIZE, length - start));
                    previous = start;
                }
            }
        } finally {
            input.close();
        }
        return toHex(digest.digest());
    }

    private static long clamp(long value, long max) {
        return Math.max(0L, Math.min(value, max));
    }

    private static void hashRange(RandomAccessFile input, MessageDigest digest,
                                  long start, long length) throws IOException {
        input.seek(start);
        byte[] buffer = new byte[32 * 1024];
        long remaining = length;
        while (remaining > 0L) {
            int read = input.read(buffer, 0, (int) Math.min(buffer.length, remaining));
            if (read < 0) throw new IOException("Unexpected end of file while hashing " + input);
            digest.update(buffer, 0, read);
            remaining -= read;
        }
    }

    private static void updateLong(MessageDigest digest, long value) {
        for (int shift = 56; shift >= 0; shift -= 8) {
            digest.update((byte) (value >>> shift));
        }
    }

    private static String toHex(byte[] bytes) {
        StringBuilder text = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            text.append(String.format(Locale.ROOT, "%02x", value & 0xff));
        }
        return text.toString();
    }

    private static int parseInt(Properties properties, String key) {
        return Integer.parseInt(properties.getProperty(key, "-1"));
    }

    private static long parseLong(Properties properties, String key) {
        return Long.parseLong(properties.getProperty(key, "-1"));
    }

    private static File completionPointer(File cacheDir, int index) {
        return new File(cacheDir, String.format(Locale.ROOT, "%04d.complete", index));
    }

    private static File firstExistingCacheDir(String directory) {
        List<File> dirs = FlashProjectLayout.forDirectory(directory).tifCacheReadDirs();
        for (File dir : dirs) {
            if (dir.isDirectory()) return dir;
        }
        return dirs.get(0);
    }

    private static File[] listCompletionPointers(File dir) {
        if (dir == null || !dir.isDirectory()) return null;
        File[] files = JunkFileFilter.listCleanFiles(dir);
        if (files == null) return null;
        List<File> pointers = new ArrayList<File>();
        for (File file : files) {
            if (file.getName().matches("[0-9]+\\.complete")) pointers.add(file);
        }
        return pointers.toArray(new File[pointers.size()]);
    }

    private static File findUnmanifestedLegacyTiff(File cacheDir, int index) {
        if (cacheDir == null || !cacheDir.isDirectory() || index < 0) return null;
        File[] files = JunkFileFilter.listCleanFiles(cacheDir);
        if (files == null) return null;
        String prefix = String.format(Locale.ROOT, "%04d_", index);
        for (File file : files) {
            String name = file.getName();
            if (file.isFile() && name.startsWith(prefix)
                    && name.toLowerCase(Locale.ROOT).endsWith(".tif")
                    && !name.matches(".*\\.[0-9a-fA-F]{32}\\.tif")) {
                return file;
            }
        }
        return null;
    }

    private static int seriesIndex(File pointer) {
        if (pointer == null) return -1;
        try {
            int dot = pointer.getName().indexOf('.');
            if (dot <= 0) return -1;
            return Integer.parseInt(pointer.getName().substring(0, dot));
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }

    private static boolean isSafeSiblingName(String name) {
        return name != null && !name.isEmpty() && name.equals(new File(name).getName())
                && name.indexOf('/') < 0 && name.indexOf('\\') < 0;
    }

    private static void removeObsoleteSeriesFiles(File cacheDir, int index,
                                                  File currentTiff,
                                                  File currentManifest,
                                                  File currentPointer) {
        File[] files = JunkFileFilter.listCleanFiles(cacheDir);
        if (files == null) return;
        String prefix = String.format(Locale.ROOT, "%04d_", index);
        for (File file : files) {
            if (file.equals(currentTiff) || file.equals(currentManifest)
                    || file.equals(currentPointer)) continue;
            String name = file.getName();
            if (name.startsWith(prefix)
                    && (name.endsWith(".tif") || name.endsWith(".manifest"))) {
                deleteQuietly(file.toPath());
            }
        }
    }

    private static void deleteQuietly(Path path) {
        if (path == null) return;
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // An orphan immutable generation is harmless because no pointer names it.
        }
    }

    private static String sanitizeFilename(String title) {
        if (title == null) return "image";
        String safe = ChannelFilenameCodec.toSafe(title);
        return safe == null || safe.isEmpty() ? "image" : safe;
    }

    private static final class ValidatedEntry {
        final File tiff;
        final String title;
        final int width;
        final int height;
        final int stackSize;
        final int bitDepth;
        final ImagePlus opened;

        private ValidatedEntry(File tiff, String title, int width, int height,
                               int stackSize, int bitDepth, ImagePlus opened) {
            this.tiff = tiff;
            this.title = title;
            this.width = width;
            this.height = height;
            this.stackSize = stackSize;
            this.bitDepth = bitDepth;
            this.opened = opened;
        }
    }
}
