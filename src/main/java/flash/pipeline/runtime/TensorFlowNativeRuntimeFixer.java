package flash.pipeline.runtime;

final class TensorFlowNativeRuntimeFixer extends AbstractJarDependencyFixer {

    TensorFlowNativeRuntimeFixer() {
        super(11, "TensorFlow native runtime verified.", "TensorFlow native runtime repair completed.");
    }

    @Override
    public DependencyFixResult apply(DependencySpec spec, String actionId, ProgressCallback callback) {
        DependencyFixResult base = super.apply(spec, actionId, callback);

        // The jar-based repair above never touches the imagej-tensorflow crash
        // sentinel (Fiji.app/lib/<platform>/.crashed), which blocks TensorFlow even
        // when every jar is present. Clear it here so this manual repair actually
        // fixes the most common "Could not load TensorFlow" cause.
        String sentinel = TensorFlowCrashSentinel.clearNow();
        if (sentinel == null || sentinel.trim().isEmpty()) {
            return base;
        }

        String message = base.getMessage();
        String merged = (message == null || message.trim().isEmpty())
                ? sentinel.trim()
                : message.trim() + "\n\n" + sentinel.trim();
        return new DependencyFixResult(
                base.getDependencyId(),
                base.wasAttempted(),
                base.isSuccess(),
                base.isRestartRequired(),
                merged);
    }
}
