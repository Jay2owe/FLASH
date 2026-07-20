package flash.pipeline.ui.variations;

import flash.pipeline.ui.config.ConfigQcContext;

import ij.IJ;
import ij.ImagePlus;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class VariationCache {

    private static final int MEMORY_CAPACITY = 50;

    private final File cacheDir;
    private final LinkedHashMap<String, ImagePlus> memory =
            new LinkedHashMap<String, ImagePlus>(MEMORY_CAPACITY + 1, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, ImagePlus> eldest) {
                    return size() > MEMORY_CAPACITY;
                }
            };

    public VariationCache(ConfigQcContext context) {
        this(context == null ? null : context.getBinFolder());
    }

    public VariationCache(File binFolder) {
        this.cacheDir = cacheDir(binFolder);
    }

    public static int purgeOlderThan(File binFolder, long maxAgeMillis) {
        File dir = cacheDir(binFolder);
        if (dir == null) {
            return 0;
        }
        File[] files;
        try {
            files = dir.listFiles();
        } catch (RuntimeException ignored) {
            return 0;
        }
        if (files == null || files.length == 0) {
            return 0;
        }
        long cutoff = System.currentTimeMillis() - maxAgeMillis;
        int deleted = 0;
        for (int i = 0; i < files.length; i++) {
            File file = files[i];
            if (file == null || !file.isFile()) {
                continue;
            }
            String name = file.getName();
            if (name == null
                    || !name.toLowerCase(java.util.Locale.ROOT).endsWith(".tif")) {
                continue;
            }
            try {
                if (file.lastModified() < cutoff && file.delete()) {
                    deleted++;
                }
            } catch (RuntimeException ignored) {
                // Best-effort cleanup only; preview sweeps should not fail here.
            }
        }
        return deleted;
    }

    public static String keyFor(ParameterSweep sweep, ParameterCombo combo) {
        if (sweep == null) {
            throw new IllegalArgumentException("sweep must not be null");
        }
        if (combo == null) {
            throw new IllegalArgumentException("combo must not be null");
        }
        String raw = sweep.sourceImageHash()
                + ":" + sweep.method().label()
                + ":";
        String namespace = sweep.cacheNamespace();
        if (namespace != null && !namespace.trim().isEmpty()) {
            raw += namespace.trim() + ":";
        }
        raw += sweep.cropSpec().toCanonicalJson()
                + ":" + combo.toCanonicalJson()
                + ":" + macroIdentityForCombo(sweep, combo);
        return sha256(raw).substring(0, 16);
    }

    public static String downstreamCacheNamespace(String filteredSourceKey,
                                                  String downstreamMethodToken,
                                                  String strategyCacheTag,
                                                  CropSpec cropSpec,
                                                  ParameterCombo downstreamCombo) {
        String raw = "downstream:v1"
                + ":filtered=" + safe(filteredSourceKey)
                + ":method=" + safe(downstreamMethodToken)
                + ":strategy=" + safe(strategyCacheTag)
                + ":crop=" + (cropSpec == null
                ? CropSpec.full().toCanonicalJson()
                : cropSpec.toCanonicalJson())
                + ":params=" + (downstreamCombo == null
                ? "{}"
                : downstreamCombo.toCanonicalJson());
        return "downstream:" + sha256(raw).substring(0, 16);
    }

    public synchronized ImagePlus get(String key) {
        if (key == null || key.trim().isEmpty()) {
            return null;
        }
        ImagePlus cached = memory.get(key);
        if (cached != null) {
            return cached;
        }
        File file = fileFor(key);
        if (file == null || !file.isFile()) {
            return null;
        }
        try {
            ImagePlus loaded = IJ.openImage(file.getAbsolutePath());
            if (loaded == null) {
                return null;
            }
            memory.put(key, loaded);
            return loaded;
        } catch (Throwable ignored) {
            return null;
        }
    }

    public synchronized void put(String key, ImagePlus label) {
        if (key == null || key.trim().isEmpty() || label == null) {
            return;
        }
        memory.put(key, label);
    }

    /**
     * Writes a single label image to the on-disk cache, overwriting any
     * existing entry for {@code key}. {@link #put} deliberately never touches
     * disk; disk persistence happens only through this method (driven by the
     * grid's "Save variations cache" button) so that ordinary preview sweeps do
     * not fill the project folder with TIFFs. Returns {@code true} on success.
     */
    public synchronized boolean writeToDisk(String key, ImagePlus image) {
        if (cacheDir == null || key == null || key.trim().isEmpty()
                || image == null) {
            return false;
        }
        File file = fileFor(key);
        if (file == null) {
            return false;
        }
        try {
            if (!file.getParentFile().isDirectory()) {
                //noinspection ResultOfMethodCallIgnored
                file.getParentFile().mkdirs();
            }
            File temp = new File(file.getParentFile(),
                    key + ".tmp-" + Thread.currentThread().getId() + ".tif");
            IJ.saveAs(image, "Tiff", temp.getAbsolutePath());
            moveIntoPlace(temp.toPath(), file.toPath());
            return true;
        } catch (Throwable ignored) {
            // Disk cache failures should not break a preview sweep.
            return false;
        }
    }

    /**
     * Persists every successful result in {@code results} to the on-disk cache,
     * keyed exactly as the sweep strategies key their writes, so a later run can
     * reuse them. Skips baseline/failed/empty results. Returns the number of
     * entries written.
     */
    public synchronized int snapshotResultsToDisk(ParameterSweep sweep,
                                                  List<VariationResult> results) {
        if (cacheDir == null || sweep == null || results == null) {
            return 0;
        }
        int written = 0;
        for (int i = 0; i < results.size(); i++) {
            VariationResult result = results.get(i);
            if (result == null || result.hasError() || result.combo() == null) {
                continue;
            }
            ImagePlus label = result.label();
            if (label == null) {
                continue;
            }
            if (writeToDisk(keyFor(sweep, result.combo()), label)) {
                written++;
            }
        }
        return written;
    }

    int memorySizeForTest() {
        return memory.size();
    }

    boolean isInMemoryForTest(String key) {
        return memory.containsKey(key);
    }

    File fileForTest(String key) {
        return fileFor(key);
    }

    private File fileFor(String key) {
        if (cacheDir == null || key == null || key.trim().isEmpty()) {
            return null;
        }
        return new File(cacheDir, key + ".tif");
    }

    private static File cacheDir(File binFolder) {
        return binFolder == null ? null : new File(binFolder, "variations_cache");
    }

    private static String macroIdentityForCombo(ParameterSweep sweep,
                                                ParameterCombo combo) {
        if (sweep == null
                || combo == null
                || !sweep.valueLists().containsKey(ParameterId.MACRO)) {
            return "macro:none";
        }
        Object value = combo.get(ParameterId.MACRO);
        if (value == null) {
            return "macro:none";
        }
        try {
            return sweep.macroVariations().identityForToken(
                    MacroToken.tokenString(value));
        } catch (RuntimeException e) {
            return "macro:invalid:" + String.valueOf(value);
        }
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(bytes.length * 2);
            for (int i = 0; i < bytes.length; i++) {
                out.append(String.format(java.util.Locale.ROOT, "%02x", Integer.valueOf(bytes[i] & 0xff)));
            }
            return out.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }

    private static void moveIntoPlace(Path temp, Path target) throws java.io.IOException {
        // Atomic move with retry/backoff for transient locks (cloud-sync, AV).
        // No in-place fallback: cached images can be large, never read into memory.
        try {
            flash.pipeline.io.IoUtils.moveReplacing(temp, target);
        } finally {
            Files.deleteIfExists(temp);
        }
    }
}
