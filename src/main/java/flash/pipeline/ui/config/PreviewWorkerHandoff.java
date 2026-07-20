package flash.pipeline.ui.config;

import javax.swing.SwingUtilities;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Carries a preview result or failure across SwingWorker cancellation until the
 * worker method has physically returned. SwingWorker.done() may run as soon as
 * cancellation is accepted, before cancellation-ignoring work has stopped.
 */
final class PreviewWorkerHandoff<T> {

    private enum State { NEW, RUNNING, FINISHED }

    private final AtomicReference<PublishedResult<T>> result =
            new AtomicReference<PublishedResult<T>>();
    private final AtomicReference<Throwable> failure = new AtomicReference<Throwable>();
    private State state = State.NEW;
    private boolean completionClaimed;

    boolean tryStart() {
        synchronized (this) {
            if (state != State.NEW) return false;
            state = State.RUNNING;
            return true;
        }
    }

    void setResult(T value, PreviewInputLeaseRegistry.Reservation reservation) {
        result.set(new PublishedResult<T>(value, reservation));
    }

    PublishedResult<T> takeResult() {
        return result.getAndSet(null);
    }

    void setFailure(Throwable value) {
        failure.set(value);
    }

    Throwable takeFailure() {
        return failure.getAndSet(null);
    }

    boolean finishBeforeStart(Throwable value) {
        synchronized (this) {
            if (state != State.NEW) return false;
            if (value != null) failure.set(value);
            state = State.FINISHED;
            return true;
        }
    }

    void markPhysicallyFinished(Runnable completion) {
        synchronized (this) {
            if (state != State.RUNNING) return;
            state = State.FINISHED;
        }
        SwingUtilities.invokeLater(completion);
    }

    boolean claimPhysicalCompletion() {
        synchronized (this) {
            if (state != State.FINISHED || completionClaimed) return false;
            completionClaimed = true;
            return true;
        }
    }

    static final class PublishedResult<T> {
        final T value;
        final PreviewInputLeaseRegistry.Reservation reservation;

        private PublishedResult(T value,
                                PreviewInputLeaseRegistry.Reservation reservation) {
            this.value = value;
            this.reservation = reservation;
        }
    }
}
