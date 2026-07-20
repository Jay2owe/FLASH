package flash.pipeline.ui.config;

import ij.ImagePlus;
import ij.process.ByteProcessor;
import org.junit.Test;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class PreviewWorkerOwnershipTest {

    @Test
    public void preStartFinishWinsAtomicallyAgainstWorkerStart() {
        PreviewWorkerHandoff<ImagePlus> cancelled =
                new PreviewWorkerHandoff<ImagePlus>();

        assertTrue(cancelled.finishBeforeStart(null));
        assertFalse(cancelled.tryStart());
        assertTrue(cancelled.claimPhysicalCompletion());
        assertFalse(cancelled.claimPhysicalCompletion());

        PreviewWorkerHandoff<ImagePlus> running =
                new PreviewWorkerHandoff<ImagePlus>();
        assertTrue(running.tryStart());
        assertFalse(running.finishBeforeStart(null));
        assertFalse(running.claimPhysicalCompletion());
        running.markPhysicallyFinished(new Runnable() {
            @Override public void run() {
            }
        });
        assertTrue(running.claimPhysicalCompletion());
    }

    @Test
    public void activeInputOutlivesAliasedStaleResultReservation() {
        PreviewInputLeaseRegistry registry = new PreviewInputLeaseRegistry();
        ImagePlus shared = image("shared");
        PreviewInputLeaseRegistry.Lease activeInput = registry.acquire(shared);
        PreviewInputLeaseRegistry.Reservation staleResult = registry.reserve(shared);

        assertTrue(registry.deferClose(shared));
        assertEquals(0, staleResult.release().length);
        ImagePlus pending = activeInput.release();

        assertSame(shared, pending);
        assertFalse(registry.deferClose(shared));
    }

    @Test
    public void activeInputCloseRequestIsCancelledWhenTransferredBackToStage() {
        PreviewInputLeaseRegistry registry = new PreviewInputLeaseRegistry();
        ImagePlus shared = image("active-input");
        PreviewInputLeaseRegistry.Lease activeInput = registry.acquire(shared);
        PreviewInputLeaseRegistry.Reservation staleResult = registry.reserve(shared);
        Set<ImagePlus> stageOwned = Collections.newSetFromMap(
                new IdentityHashMap<ImagePlus, Boolean>());
        stageOwned.add(shared);

        assertTrue(registry.deferClose(shared));
        assertEquals(0, staleResult.release().length);
        assertSame(null, activeInput.transferTo(stageOwned));
        assertFalse(registry.deferClose(shared));
    }

    @Test
    public void sharedResultIdentityIsSafeInBothCompletionOrders() {
        assertSharedResultOrder(true);
        assertSharedResultOrder(false);
    }

    private static void assertSharedResultOrder(boolean staleFirst) {
        PreviewInputLeaseRegistry registry = new PreviewInputLeaseRegistry();
        ImagePlus shared = image(staleFirst ? "stale-first" : "current-first");
        PreviewInputLeaseRegistry.Reservation stale = registry.reserve(shared);
        PreviewInputLeaseRegistry.Reservation current = registry.reserve(shared);
        Set<ImagePlus> installed = Collections.newSetFromMap(
                new IdentityHashMap<ImagePlus, Boolean>());
        installed.add(shared);

        if (staleFirst) {
            assertTrue(registry.deferClose(shared));
            assertEquals(0, stale.release().length);
            assertEquals(0, current.transferTo(installed).length);
        } else {
            assertEquals(0, current.transferTo(installed).length);
            // The later stale completion observes the installed identity and skips closing it.
            assertEquals(0, stale.release().length);
        }
        assertFalse(registry.deferClose(shared));
    }

    private static ImagePlus image(String title) {
        return new ImagePlus(title, new ByteProcessor(2, 2));
    }
}
