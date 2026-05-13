package flash.pipeline.ui.sandbox.variation;

import org.junit.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class FlickerControllerTest {

    @Test
    public void delayUsesHalfPeriodAndClampsRate() {
        assertEquals(250, FlickerController.delayMillisFor(2.0));
        assertEquals(1000, FlickerController.delayMillisFor(0.5));
        assertEquals(100, FlickerController.delayMillisFor(5.0));
        assertEquals(1000, FlickerController.delayMillisFor(0.01));
        assertEquals(100, FlickerController.delayMillisFor(50.0));
    }

    @Test
    public void fireOnceAlternatesVisibleSideAndRunsCallback() {
        AtomicInteger callbacks = new AtomicInteger();
        FlickerController controller = new FlickerController(2.0, new Runnable() {
            @Override public void run() {
                callbacks.incrementAndGet();
            }
        });

        assertTrue(controller.leftVisible());
        controller.fireOnceForTest();

        assertFalse(controller.leftVisible());
        assertEquals(1, callbacks.get());
    }
}
