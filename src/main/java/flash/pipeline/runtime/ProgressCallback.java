package flash.pipeline.runtime;

/**
 * Lightweight progress hook for long-running dependency repair actions.
 */
public interface ProgressCallback {
    void onProgress(DependencySpec spec, String message);
}
