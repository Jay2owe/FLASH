package flash.pipeline.ui;

import flash.pipeline.testutil.TestWait;
import flash.pipeline.testutil.UiTestAssumptions;
import ij.ImagePlus;
import ij.WindowManager;
import ij.plugin.frame.Recorder;
import ij.process.ByteProcessor;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Container;
import java.awt.GraphicsEnvironment;
import java.awt.TextArea;
import java.awt.Window;
import java.lang.reflect.Field;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class RecorderDialogAsyncSampleTest {

    @Before
    public void setUp() throws Exception {
        UiTestAssumptions.assumeInteractiveUiTestsEnabled();
        closeRecorderDialogsAndImages();
        resetRecorderText();
    }

    @After
    public void tearDown() throws Exception {
        if (!GraphicsEnvironment.isHeadless()) {
            closeRecorderDialogsAndImages();
            closeRecorder();
        }
        Recorder.record = false;
        Recorder.recordInMacros = false;
    }

    @Test(timeout = 10000)
    public void dialogIsVisibleAndControlsAreDisabledWhileSampleLoads() throws Exception {
        final CountDownLatch supplierEntered = new CountDownLatch(1);
        final CountDownLatch releaseSupplier = new CountDownLatch(1);
        RecorderDialog.SampleSupplier supplier = new RecorderDialog.SampleSupplier() {
            @Override public ImagePlus openSample() {
                supplierEntered.countDown();
                awaitIgnoringInterrupts(releaseSupplier);
                return sample("Async sample");
            }
        };

        DialogRun run = startDialog("Async", supplier);
        assertTrue("sample supplier did not start", supplierEntered.await(2, TimeUnit.SECONDS));
        JDialog dialog = waitForDialog("Record Filter - Async", 1000);

        assertTrue(dialog.isVisible());
        assertNotNull(findLabel(dialog, "Loading sample image..."));
        assertFalse(isButtonEnabled(dialog, "Start over"));
        assertFalse(isButtonEnabled(dialog, "Preview"));
        assertFalse(isButtonEnabled(dialog, "Use this filter"));
        assertTrue(isButtonEnabled(dialog, "Cancel"));

        releaseSupplier.countDown();
        TestWait.until("controls were not enabled after sample load", new TestWait.Condition() {
            @Override public boolean isMet() throws Exception {
                return isButtonEnabled(dialog, "Start over")
                        && isButtonEnabled(dialog, "Preview")
                        && isButtonEnabled(dialog, "Use this filter");
            }
        }, 3000);

        click(dialog, "Cancel");
        run.awaitCleanExit();
    }

    @Test(timeout = 10000)
    public void closingDialogWhileSampleWorkerIsBlockedDropsLateSample() throws Exception {
        final CountDownLatch supplierEntered = new CountDownLatch(1);
        final CountDownLatch releaseSupplier = new CountDownLatch(1);
        final AtomicReference<CloseTrackingImagePlus> sampleRef =
                new AtomicReference<CloseTrackingImagePlus>();
        RecorderDialog.SampleSupplier supplier = new RecorderDialog.SampleSupplier() {
            @Override public ImagePlus openSample() {
                supplierEntered.countDown();
                awaitIgnoringInterrupts(releaseSupplier);
                CloseTrackingImagePlus sample = new CloseTrackingImagePlus("Late sample");
                sampleRef.set(sample);
                return sample;
            }
        };

        DialogRun run = startDialog("Cancel", supplier);
        assertTrue("sample supplier did not start", supplierEntered.await(2, TimeUnit.SECONDS));
        final JDialog dialog = waitForDialog("Record Filter - Cancel", 1000);

        SwingUtilities.invokeAndWait(new Runnable() {
            @Override public void run() {
                dialog.dispose();
            }
        });
        run.awaitCleanExit();

        releaseSupplier.countDown();
        TestWait.until("late sample was not closed", new TestWait.Condition() {
            @Override public boolean isMet() {
                CloseTrackingImagePlus sample = sampleRef.get();
                return sample != null && sample.closed && sample.flushed;
            }
        }, 3000);
        assertNull(findShowingWindow("Late sample"));
    }

    @Test(timeout = 10000)
    public void displayHookCommandsAreExcludedFromCapturedDiff() throws Exception {
        final CountDownLatch hookRan = new CountDownLatch(1);
        RecorderDialog.SampleSupplier supplier = new RecorderDialog.SampleSupplier() {
            @Override public ImagePlus openSample() {
                return sample("Hook sample");
            }

            @Override public void afterSampleShown(ImagePlus sample) {
                Recorder.recordString("run(\"Red\");\n");
                hookRan.countDown();
            }
        };

        DialogRun run = startDialog("Hook", supplier);
        final JDialog dialog = waitForDialog("Record Filter - Hook", 1000);
        assertTrue("display hook did not run", hookRan.await(2, TimeUnit.SECONDS));
        TestWait.until("recorder did not enable after hook", new TestWait.Condition() {
            @Override public boolean isMet() throws Exception {
                return isButtonEnabled(dialog, "Use this filter");
            }
        }, 3000);

        SwingUtilities.invokeAndWait(new Runnable() {
            @Override public void run() {
                Recorder.recordString("run(\"Median...\", \"radius=2\");\n");
            }
        });
        TestWait.until("captured diff did not update", new TestWait.Condition() {
            @Override public boolean isMet() throws Exception {
                return textArea(dialog).getText().contains("Median");
            }
        }, 2000);

        String diff = textArea(dialog).getText();
        assertFalse(diff.contains("run(\"Red\")"));
        assertTrue(diff.contains("run(\"Median...\", \"radius=2\")"));

        click(dialog, "Cancel");
        run.awaitCleanExit();
    }

    private static DialogRun startDialog(final String label,
                                         final RecorderDialog.SampleSupplier supplier) {
        final DialogRun run = new DialogRun();
        run.thread = new Thread(new Runnable() {
            @Override public void run() {
                try {
                    SwingUtilities.invokeAndWait(new Runnable() {
                        @Override public void run() {
                            run.result.set(RecorderDialog.show(label, noopPreviewHandler(), supplier));
                        }
                    });
                } catch (Throwable t) {
                    run.error.set(t);
                }
            }
        }, "RecorderDialogAsyncSampleTest-" + label);
        run.thread.setDaemon(true);
        run.thread.start();
        return run;
    }

    private static CustomFilterEntryDialog.PreviewHandler noopPreviewHandler() {
        return new CustomFilterEntryDialog.PreviewHandler() {
            @Override public void preview(String macroContent) {}
            @Override public void cleanup() {}
        };
    }

    private static ImagePlus sample(String title) {
        return new ImagePlus(title, new ByteProcessor(4, 4));
    }

    private static void awaitIgnoringInterrupts(CountDownLatch latch) {
        boolean done = false;
        while (!done) {
            try {
                latch.await();
                done = true;
            } catch (InterruptedException ignored) {
                // Simulates image-loading libraries that do not stop promptly.
            }
        }
    }

    private static JDialog waitForDialog(final String title, long timeoutMillis) throws Exception {
        final AtomicReference<JDialog> found = new AtomicReference<JDialog>();
        TestWait.until("dialog not visible: " + title, new TestWait.Condition() {
            @Override public boolean isMet() throws Exception {
                found.set(findDialog(title));
                return found.get() != null && found.get().isVisible();
            }
        }, timeoutMillis);
        return found.get();
    }

    private static JDialog findDialog(final String title) throws Exception {
        final AtomicReference<JDialog> found = new AtomicReference<JDialog>();
        SwingUtilities.invokeAndWait(new Runnable() {
            @Override public void run() {
                for (Window window : Window.getWindows()) {
                    if (window instanceof JDialog) {
                        JDialog dialog = (JDialog) window;
                        if (title.equals(dialog.getTitle()) && dialog.isDisplayable()) {
                            found.set(dialog);
                            return;
                        }
                    }
                }
            }
        });
        return found.get();
    }

    private static Window findShowingWindow(final String title) throws Exception {
        final AtomicReference<Window> found = new AtomicReference<Window>();
        SwingUtilities.invokeAndWait(new Runnable() {
            @Override public void run() {
                for (Window window : Window.getWindows()) {
                    if (title.equals(window.getName()) && window.isShowing()) {
                        found.set(window);
                        return;
                    }
                    if (window instanceof java.awt.Frame
                            && title.equals(((java.awt.Frame) window).getTitle())
                            && window.isShowing()) {
                        found.set(window);
                        return;
                    }
                }
            }
        });
        return found.get();
    }

    private static boolean isButtonEnabled(final Container root, final String text) throws Exception {
        final AtomicReference<Boolean> enabled = new AtomicReference<Boolean>();
        SwingUtilities.invokeAndWait(new Runnable() {
            @Override public void run() {
                JButton button = findButton(root, text);
                assertNotNull("button not found: " + text, button);
                enabled.set(Boolean.valueOf(button.isEnabled()));
            }
        });
        return enabled.get().booleanValue();
    }

    private static void click(final Container root, final String text) throws Exception {
        SwingUtilities.invokeAndWait(new Runnable() {
            @Override public void run() {
                JButton button = findButton(root, text);
                assertNotNull("button not found: " + text, button);
                button.doClick();
            }
        });
    }

    private static JLabel findLabel(final Container root, final String text) throws Exception {
        final AtomicReference<JLabel> found = new AtomicReference<JLabel>();
        SwingUtilities.invokeAndWait(new Runnable() {
            @Override public void run() {
                found.set(findLabelContaining(root, text));
            }
        });
        return found.get();
    }

    private static JTextArea textArea(final Container root) throws Exception {
        final AtomicReference<JTextArea> found = new AtomicReference<JTextArea>();
        SwingUtilities.invokeAndWait(new Runnable() {
            @Override public void run() {
                found.set(findTextArea(root));
            }
        });
        JTextArea area = found.get();
        assertNotNull("recorder text area not found", area);
        return area;
    }

    private static JButton findButton(Container root, String text) {
        for (Component component : root.getComponents()) {
            if (component instanceof JButton && text.equals(((JButton) component).getText())) {
                return (JButton) component;
            }
            if (component instanceof Container) {
                JButton nested = findButton((Container) component, text);
                if (nested != null) return nested;
            }
        }
        return null;
    }

    private static JLabel findLabelContaining(Container root, String text) {
        for (Component component : root.getComponents()) {
            if (component instanceof JLabel) {
                String label = ((JLabel) component).getText();
                if (label != null && label.contains(text)) return (JLabel) component;
            }
            if (component instanceof Container) {
                JLabel nested = findLabelContaining((Container) component, text);
                if (nested != null) return nested;
            }
        }
        return null;
    }

    private static JTextArea findTextArea(Container root) {
        for (Component component : root.getComponents()) {
            if (component instanceof JTextArea) return (JTextArea) component;
            if (component instanceof Container) {
                JTextArea nested = findTextArea((Container) component);
                if (nested != null) return nested;
            }
        }
        return null;
    }

    private static void resetRecorderText() throws Exception {
        RecorderDialog.resolveRecorder();
        SwingUtilities.invokeAndWait(new Runnable() {
            @Override public void run() {
                try {
                    Field textAreaField = Recorder.class.getDeclaredField("textArea");
                    textAreaField.setAccessible(true);
                    TextArea recorderArea = (TextArea) textAreaField.get(null);
                    if (recorderArea != null) recorderArea.setText("");
                    Recorder.record = false;
                    Recorder.recordInMacros = false;
                } catch (Exception e) {
                    throw new RuntimeException("Failed to reset ImageJ recorder text for async sample test", e);
                }
            }
        });
    }

    private static void closeRecorderDialogsAndImages() throws Exception {
        SwingUtilities.invokeAndWait(new Runnable() {
            @Override public void run() {
                for (Window window : Window.getWindows()) {
                    if (window instanceof JDialog
                            && ((JDialog) window).getTitle().startsWith("Record Filter - ")) {
                        window.dispose();
                    }
                }
                int[] ids = WindowManager.getIDList();
                if (ids != null) {
                    for (int id : ids) {
                        ImagePlus image = WindowManager.getImage(id);
                        if (image != null) {
                            image.changes = false;
                            image.close();
                            image.flush();
                        }
                    }
                }
            }
        });
    }

    private static void closeRecorder() throws Exception {
        SwingUtilities.invokeAndWait(new Runnable() {
            @Override public void run() {
                Recorder recorder = Recorder.getInstance();
                if (recorder != null) recorder.close();
            }
        });
    }

    private static final class DialogRun {
        private final AtomicReference<RecorderDialog.Result> result =
                new AtomicReference<RecorderDialog.Result>();
        private final AtomicReference<Throwable> error = new AtomicReference<Throwable>();
        private Thread thread;

        void awaitCleanExit() throws Exception {
            thread.join(3000);
            assertFalse("dialog thread still running", thread.isAlive());
            if (error.get() != null) {
                AssertionError assertionError = new AssertionError("dialog failed");
                assertionError.initCause(error.get());
                throw assertionError;
            }
            RecorderDialog.Result value = result.get();
            assertNotNull("dialog did not return a result", value);
            assertNull(value.macroText);
        }
    }

    private static final class CloseTrackingImagePlus extends ImagePlus {
        volatile boolean closed;
        volatile boolean flushed;

        CloseTrackingImagePlus(String title) {
            super(title, new ByteProcessor(4, 4));
        }

        @Override public void close() {
            closed = true;
            super.close();
        }

        @Override public void flush() {
            flushed = true;
            super.flush();
        }
    }
}
