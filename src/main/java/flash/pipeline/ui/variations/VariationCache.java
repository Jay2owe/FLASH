package flash.pipeline.ui.variations;

import flash.pipeline.ui.config.ConfigQcContext;

import ij.IJ;
import ij.ImagePlus;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
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
        this.cacheDir = binFolder == null ? null : new File(binFolder, "variations_cache");
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
        File file = fileFor(key);
        if (file == null) {
            return;
        }
        try {
            if (!file.getParentFile().isDirectory()) {
                //noinspection ResultOfMethodCallIgnored
                file.getParentFile().mkdirs();
            }
            IJ.saveAs(label, "Tiff", file.getAbsolutePath());
        } catch (Throwable ignored) {
            // Disk cache failures should not break a preview sweep.
        }
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
                out.append(String.format("%02x", Integer.valueOf(bytes[i] & 0xff)));
            }
            return out.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
