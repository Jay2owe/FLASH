package flash.pipeline.ui.config;

import javax.swing.SwingWorker;

final class TestPreviewWorkerExecutors {

    static final PreviewWorkerExecutor QUEUED = new PreviewWorkerExecutor() {
        @Override public void execute(SwingWorker<?, ?> worker) {
        }
    };

    private TestPreviewWorkerExecutors() {
    }

    static PreviewWorkerExecutor rejecting(final RuntimeException failure) {
        return new PreviewWorkerExecutor() {
            @Override public void execute(SwingWorker<?, ?> worker) {
                throw failure;
            }
        };
    }
}
