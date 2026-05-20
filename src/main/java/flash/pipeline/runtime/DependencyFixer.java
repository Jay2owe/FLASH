package flash.pipeline.runtime;

/**
 * Adapter for one in-app dependency repair path.
 */
public interface DependencyFixer {
    DependencyFixResult apply(DependencySpec spec, String actionId, ProgressCallback callback);

    int getExecutionOrder();
}
