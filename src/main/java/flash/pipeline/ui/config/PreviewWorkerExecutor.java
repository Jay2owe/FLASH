package flash.pipeline.ui.config;

import javax.swing.SwingWorker;

/** Injection seam for deterministic queued-worker and rejected-execution tests. */
interface PreviewWorkerExecutor {

    PreviewWorkerExecutor DEFAULT = new PreviewWorkerExecutor() {
        @Override public void execute(SwingWorker<?, ?> worker) {
            worker.execute();
        }
    };

    void execute(SwingWorker<?, ?> worker);
}
