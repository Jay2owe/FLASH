package flash.pipeline.runtime;

import loci.plugins.in.ImporterOptions;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Tracks whether Bio-Formats was touched during an analysis run so windowless
 * mode is reset only after real Bio-Formats use.
 */
public final class BioFormatsRuntime {

    private static final AtomicBoolean WINDOWLESS_TOUCH = new AtomicBoolean(false);

    private BioFormatsRuntime() {}

    public static void markUsage() {
        WINDOWLESS_TOUCH.set(true);
    }

    public static void resetWindowlessModeIfTouched() {
        if (!WINDOWLESS_TOUCH.getAndSet(false)) {
            return;
        }
        try {
            ImporterOptions opts = new ImporterOptions();
            opts.setWindowless(false);
        } catch (Exception ignored) {
        }
    }
}
