package flash.pipeline.ui.variations;

import ij.ImagePlus;
import ij.process.ByteProcessor;
import org.junit.Test;

import javax.swing.SwingUtilities;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class VariationCellPanelFallbackCleanupTest {

    @Test
    public void replacedFallbackRetriesAfterFieldsMoveOnWithoutDoubleCleanup()
            throws Exception {
        final RuntimeException transientFailure =
                new RuntimeException("fallback close failed");
        final FailingOnceCloseImage first =
                new FailingOnceCloseImage("fallback-first", transientFailure);
        final TrackingImage second = new TrackingImage("fallback-second");
        final VariationCellPanel cell = cell();

        SwingUtilities.invokeAndWait(new Runnable() {
            @Override public void run() {
                invokeSetCachedLabel(cell, first, true);
                try {
                    invokeSetCachedLabel(cell, second, true);
                    fail("Expected transient fallback cleanup failure.");
                } catch (RuntimeException expected) {
                    assertSame(transientFailure, expected);
                }
                assertSame(second, cell.cachedLabelForTest());
                cell.disposeImages();
                cell.disposeImages();
            }
        });

        assertEquals(2, first.closeAttempts);
        assertEquals(1, first.closeCalls);
        assertEquals(1, first.flushCalls);
        assertEquals(1, second.closeCalls);
        assertEquals(1, second.flushCalls);
    }

    @Test
    public void fallbackFatalOutranksOrdinaryAndAllAliasesRetryAfterClear()
            throws Exception {
        final InterruptedException interrupted =
                new InterruptedException("fallback interrupted");
        final RuntimeException ordinaryFailure =
                new RuntimeException("ordinary fallback failure", interrupted);
        final ThreadDeath fatalFailure = new ThreadDeath();
        final FailingOnceCloseImage ordinary =
                new FailingOnceCloseImage("fallback-ordinary", ordinaryFailure);
        final FailingOnceFatalCloseImage fatal =
                new FailingOnceFatalCloseImage("fallback-fatal", fatalFailure);
        final TrackingImage normal = new TrackingImage("fallback-normal");
        final VariationCellPanel cell = cell();

        SwingUtilities.invokeAndWait(new Runnable() {
            @Override public void run() {
                setField(cell, "cachedLabel", ordinary);
                setField(cell, "ownedCachedLabel", ordinary);
                setField(cell, "displayedPreviewImage", fatal);
                setField(cell, "currentPreviewImage", fatal);
                setField(cell, "ownedDisplayedPreviewImage", fatal);
                setField(cell, "cachedOverlayImage", normal);
                try {
                    cell.disposeImages();
                    fail("Expected VM-fatal fallback cleanup.");
                } catch (ThreadDeath expected) {
                    assertSame(fatalFailure, expected);
                    assertEquals(1, expected.getSuppressed().length);
                    assertSame(ordinaryFailure, expected.getSuppressed()[0]);
                    assertFalse(Thread.currentThread().isInterrupted());
                    assertNull(cell.cachedLabelForTest());
                    assertNull(cell.currentPreviewImageForTest());
                } finally {
                    Thread.interrupted();
                }
                cell.disposeImages();
                cell.disposeImages();
            }
        });

        assertEquals(2, ordinary.closeAttempts);
        assertEquals(1, ordinary.closeCalls);
        assertEquals(1, ordinary.flushCalls);
        assertEquals(2, fatal.closeAttempts);
        assertEquals(1, fatal.closeCalls);
        assertEquals(1, fatal.flushCalls);
        assertEquals(1, normal.closeCalls);
        assertEquals(1, normal.flushCalls);
    }

    private static VariationCellPanel cell() {
        return new VariationCellPanel(ParameterCombo.builder().build(),
                new ImagePlus("source", new ByteProcessor(1, 1)), null, null);
    }

    private static void invokeSetCachedLabel(VariationCellPanel cell,
                                             ImagePlus image,
                                             boolean owned) {
        try {
            Method method = VariationCellPanel.class.getDeclaredMethod(
                    "setCachedLabel", ImagePlus.class, boolean.class);
            method.setAccessible(true);
            method.invoke(cell, image, Boolean.valueOf(owned));
        } catch (InvocationTargetException e) {
            rethrow(e.getCause());
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static void setField(VariationCellPanel cell,
                                 String name,
                                 ImagePlus image) {
        try {
            Field field = VariationCellPanel.class.getDeclaredField(name);
            field.setAccessible(true);
            field.set(cell, image);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static void rethrow(Throwable failure) {
        if (failure instanceof ThreadDeath) {
            throw (ThreadDeath) failure;
        }
        if (failure instanceof VirtualMachineError) {
            throw (VirtualMachineError) failure;
        }
        if (failure instanceof RuntimeException) {
            throw (RuntimeException) failure;
        }
        if (failure instanceof Error) {
            throw (Error) failure;
        }
        throw new AssertionError(failure);
    }

    private static class TrackingImage extends ImagePlus {
        int closeCalls;
        int flushCalls;

        TrackingImage(String title) {
            super(title, new ByteProcessor(1, 1));
        }

        @Override public void close() {
            closeCalls++;
            super.close();
        }

        @Override public void flush() {
            flushCalls++;
            super.flush();
        }
    }

    private static final class FailingOnceCloseImage extends TrackingImage {
        private final RuntimeException failure;
        int closeAttempts;

        FailingOnceCloseImage(String title, RuntimeException failure) {
            super(title);
            this.failure = failure;
        }

        @Override public void close() {
            closeAttempts++;
            if (closeAttempts == 1) {
                throw failure;
            }
            super.close();
        }
    }

    private static final class FailingOnceFatalCloseImage extends TrackingImage {
        private final ThreadDeath failure;
        int closeAttempts;

        FailingOnceFatalCloseImage(String title, ThreadDeath failure) {
            super(title);
            this.failure = failure;
        }

        @Override public void close() {
            closeAttempts++;
            if (closeAttempts == 1) {
                throw failure;
            }
            super.close();
        }
    }
}
