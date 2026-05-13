package flash.pipeline.ui.sandbox.variation;

import javax.swing.Timer;

public final class FlickerController {

    private final Timer timer;
    private final Runnable toggle;
    private boolean leftVisible = true;

    public FlickerController(double hz, Runnable toggle) {
        this.toggle = toggle == null ? new Runnable() {
            @Override public void run() {}
        } : toggle;
        int delay = delayMillisFor(hz);
        timer = new Timer(delay, e -> fireOnce());
        timer.setInitialDelay(delay);
    }

    public void start() {
        leftVisible = true;
        timer.restart();
    }

    public void stop() {
        timer.stop();
    }

    public boolean isRunning() {
        return timer.isRunning();
    }

    public boolean leftVisible() {
        return leftVisible;
    }

    public void setRate(double hz) {
        int delay = delayMillisFor(hz);
        timer.setDelay(delay);
        timer.setInitialDelay(delay);
    }

    void fireOnceForTest() {
        fireOnce();
    }

    static int delayMillisFor(double hz) {
        double safeHz = Math.max(0.5, Math.min(5.0, hz));
        return (int) Math.max(50, Math.round(1000.0 / (2.0 * safeHz)));
    }

    private void fireOnce() {
        leftVisible = !leftVisible;
        toggle.run();
    }
}
