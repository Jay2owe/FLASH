package flash.pipeline.deconv.psf;

import ij.ImagePlus;

import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

public final class PsfCache {

    private static final Object LOCK = new Object();
    private static final int DEFAULT_MAX_ENTRIES = 16;

    private static final LinkedHashMap<CacheKey, ImagePlus> CACHE =
            new LinkedHashMap<CacheKey, ImagePlus>(16, 0.75f, true);
    private static final Set<CacheKey> IN_FLIGHT = new HashSet<CacheKey>();

    private static volatile Synthesizer synthesizer = new Synthesizer() {
        @Override
        public ImagePlus synthesize(PsfSpec spec, PsfModel model) {
            return EpflPsfGeneratorAdapter.synthesize(spec, model);
        }
    };

    private static volatile int maxEntries = DEFAULT_MAX_ENTRIES;

    private PsfCache() {}

    public static ImagePlus get(PsfSpec spec, PsfModel model) {
        if (spec == null) throw new IllegalArgumentException("spec is required.");
        if (model == null) throw new IllegalArgumentException("model is required.");

        CacheKey key = new CacheKey(spec, model);
        while (true) {
            synchronized (LOCK) {
                ImagePlus cached = CACHE.get(key);
                if (cached != null) {
                    return cached.duplicate();
                }
                if (!IN_FLIGHT.contains(key)) {
                    IN_FLIGHT.add(key);
                    break;
                }
                try {
                    LOCK.wait();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("Interrupted while waiting for cached PSF.", e);
                }
            }
        }

        ImagePlus synthesized = null;
        RuntimeException runtimeFailure = null;
        Error errorFailure = null;
        try {
            synthesized = synthesizer.synthesize(spec, model);
        } catch (RuntimeException e) {
            runtimeFailure = e;
        } catch (Error e) {
            errorFailure = e;
        }

        ImagePlus result = null;
        synchronized (LOCK) {
            try {
                if (runtimeFailure == null && errorFailure == null && synthesized != null) {
                    CACHE.put(key, synthesized);
                    trimToCapacityLocked();
                    result = synthesized.duplicate();
                }
            } finally {
                IN_FLIGHT.remove(key);
                LOCK.notifyAll();
            }
        }

        if (runtimeFailure != null) throw runtimeFailure;
        if (errorFailure != null) throw errorFailure;
        return result;
    }

    static void setSynthesizerForTest(Synthesizer customSynthesizer) {
        synthesizer = customSynthesizer == null ? synthesizer : customSynthesizer;
    }

    static void setMaxEntriesForTest(int entries) {
        if (entries <= 0) throw new IllegalArgumentException("entries must be > 0.");
        synchronized (LOCK) {
            maxEntries = entries;
            trimToCapacityLocked();
        }
    }

    static int sizeForTest() {
        synchronized (LOCK) {
            return CACHE.size();
        }
    }

    static void resetForTest() {
        synchronized (LOCK) {
            clearLocked();
            IN_FLIGHT.clear();
            maxEntries = DEFAULT_MAX_ENTRIES;
        }
        synthesizer = new Synthesizer() {
            @Override
            public ImagePlus synthesize(PsfSpec spec, PsfModel model) {
                return EpflPsfGeneratorAdapter.synthesize(spec, model);
            }
        };
    }

    interface Synthesizer {
        ImagePlus synthesize(PsfSpec spec, PsfModel model);
    }

    private static void trimToCapacityLocked() {
        while (CACHE.size() > maxEntries) {
            Iterator<Map.Entry<CacheKey, ImagePlus>> iterator = CACHE.entrySet().iterator();
            if (!iterator.hasNext()) return;
            Map.Entry<CacheKey, ImagePlus> eldest = iterator.next();
            iterator.remove();
            disposeImage(eldest.getValue());
        }
    }

    private static void clearLocked() {
        for (ImagePlus image : CACHE.values()) {
            disposeImage(image);
        }
        CACHE.clear();
    }

    private static void disposeImage(ImagePlus image) {
        if (image == null) return;
        image.changes = false;
        try {
            image.close();
        } finally {
            image.flush();
        }
    }

    private static final class CacheKey {
        private final PsfSpec spec;
        private final PsfModel model;

        CacheKey(PsfSpec spec, PsfModel model) {
            this.spec = spec;
            this.model = model;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof CacheKey)) return false;
            CacheKey that = (CacheKey) other;
            return model == that.model && spec.equals(that.spec);
        }

        @Override
        public int hashCode() {
            return 31 * spec.hashCode() + model.hashCode();
        }
    }
}
