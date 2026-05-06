package flash.pipeline.runtime;

final class FijiPluginRuntimeFixer extends AbstractJarDependencyFixer {

    FijiPluginRuntimeFixer() {
        this(5);
    }

    FijiPluginRuntimeFixer(int executionOrder) {
        super(executionOrder, "Fiji plugin runtime verified.", "Fiji plugin runtime repair completed.");
    }
}
