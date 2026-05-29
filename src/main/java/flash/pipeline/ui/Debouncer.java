package flash.pipeline.ui;

import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;

public final class Debouncer {

    private final Runnable action;
    private final javax.swing.Timer timer;

    public Debouncer(int delayMs, Runnable action) {
        this.action = action == null ? new Runnable() {
            @Override
            public void run() {
            }
        } : action;
        this.timer = new javax.swing.Timer(delayMs, new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                Debouncer.this.action.run();
            }
        });
        this.timer.setRepeats(false);
    }

    public void trigger() {
        timer.restart();
    }

    public void cancel() {
        timer.stop();
    }

    public void flushNow() {
        timer.stop();
        action.run();
    }
}
