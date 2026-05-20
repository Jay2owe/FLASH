package flash.pipeline.ui;

import flash.pipeline.image.FilterMacroParser;
import flash.pipeline.image.FilterMacroParser.Op;
import ij.IJ;
import ij.ImagePlus;
import ij.WindowManager;
import ij.plugin.frame.Recorder;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.WindowConstants;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Dialog;
import java.awt.FlowLayout;
import java.awt.Frame;
import java.awt.GraphicsEnvironment;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.SecondaryLoop;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.lang.reflect.InvocationTargetException;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;

/**
 * Embedded macro recorder for the Custom filter authoring flow.
 *
 * The dialog is modeless so the user can interact with Fiji windows underneath.
 * A 500 ms swing Timer polls {@link Recorder#getText()} and mirrors any new
 * content into a read-only text area. On Save the captured text (everything
 * recorded after the dialog opened) is returned along with an optional preset
 * demotion match. The pre-existing {@code Recorder.record} flag is always
 * restored, including on cancel and when the window is closed via the title bar.
 *
 * <p>An optional {@code seedMacro} can be passed in to extend an existing
 * custom filter without overwriting it. The seed is held silently — never
 * displayed to the user — and concatenated to the captured diff only on save.
 */
public final class RecorderDialog {

    public interface SampleSupplier {
        /**
         * Prepares and returns an ImagePlus for the user to operate on, or null
         * if none could be opened. This method may run on a worker thread and
         * must not show windows or run recorder-visible setup commands.
         */
        ImagePlus openSample();

        /**
         * Runs on the EDT after the sample window is shown but before this
         * dialog starts recording. Use this for display-only setup such as LUTs.
         */
        default void afterSampleShown(ImagePlus sample) {}
    }

    public static final class Result {
        public final String macroText;
        public final String demotedPreset;

        Result(String macroText, String demotedPreset) {
            this.macroText = macroText;
            this.demotedPreset = demotedPreset;
        }

        public static Result cancel() {
            return new Result(null, null);
        }
    }

    private RecorderDialog() {}

    public static Result show(String channelLabel,
                              CustomFilterEntryDialog.PreviewHandler previewHandler,
                              SampleSupplier sampleSupplier) {
        return show(channelLabel, previewHandler, sampleSupplier, null);
    }

    public static Result show(Window owner,
                              String channelLabel,
                              CustomFilterEntryDialog.PreviewHandler previewHandler,
                              SampleSupplier sampleSupplier) {
        return show(owner, channelLabel, previewHandler, sampleSupplier, null);
    }

    public static Result show(String channelLabel,
                              CustomFilterEntryDialog.PreviewHandler previewHandler,
                              SampleSupplier sampleSupplier,
                              String seedMacro) {
        return show(null, channelLabel, previewHandler, sampleSupplier, seedMacro);
    }

    public static Result show(Window owner,
                              String channelLabel,
                              CustomFilterEntryDialog.PreviewHandler previewHandler,
                              SampleSupplier sampleSupplier,
                              String seedMacro) {
        if (GraphicsEnvironment.isHeadless()) return Result.cancel();
        boolean priorRecord = Recorder.record;
        boolean priorRecordInMacros = Recorder.recordInMacros;
        Recorder rec = resolveRecorder();
        if (rec == null) {
            IJ.showMessage("Record Filter Macro", "Could not open the ImageJ Recorder.");
            return Result.cancel();
        }
        Recorder.record = priorRecord;
        Recorder.recordInMacros = priorRecordInMacros;

        Session session = new Session(owner, channelLabel, rec, previewHandler, sampleSupplier,
                seedMacro, priorRecord, priorRecordInMacros);
        try {
            session.open();
            session.await();
            return session.result;
        } finally {
            session.shutdown();
        }
    }

    static Recorder resolveRecorder() {
        Recorder existing = Recorder.getInstance();
        if (existing != null) return existing;
        Frame frame = WindowManager.getFrame("Recorder");
        if (frame instanceof Recorder) return (Recorder) frame;
        try {
            return new Recorder(false);
        } catch (Throwable t) {
            return null;
        }
    }

    private static final class Session {
        private final String channelLabel;
        private final Recorder rec;
        private final CustomFilterEntryDialog.PreviewHandler previewHandler;
        private final SampleSupplier sampleSupplier;
        private final String seedMacro;
        private final boolean hasSeed;
        private final boolean priorRecord;
        private final boolean priorRecordInMacros;
        private final Window owner;
        private final CountDownLatch done = new CountDownLatch(1);

        private final JDialog dialog;
        private final JTextArea area = new JTextArea();
        private final JLabel summary = new JLabel(" ");
        private final JPanel banner = new JPanel(new BorderLayout(8, 0));
        private final JLabel bannerMessage = new JLabel("You need a sample image to record on.");
        private JButton bannerOpenButton;
        private JButton startOverButton;
        private JButton previewButton;
        private JButton cancelButton;
        private JButton saveButton;
        private final javax.swing.Timer timer;

        private String baseline;
        private String lastShown = "";
        private String lastSummarised = null;
        private boolean lastBannerVisible = true;
        private SecondaryLoop loop;
        private Result result = Result.cancel();
        private boolean closed = false;
        private boolean sampleLoading = false;
        private SwingWorker<ImagePlus, Void> sampleWorker;
        private volatile ImagePlus openedSample;
        private volatile boolean closeRequested = false;

        Session(Window owner, String channelLabel, Recorder rec,
                CustomFilterEntryDialog.PreviewHandler previewHandler,
                SampleSupplier sampleSupplier,
                String seedMacro,
                boolean priorRecord,
                boolean priorRecordInMacros) {
            this.owner = owner;
            this.channelLabel = channelLabel == null ? "" : channelLabel;
            this.rec = rec;
            this.previewHandler = previewHandler;
            this.sampleSupplier = sampleSupplier;
            this.seedMacro = seedMacro;
            this.hasSeed = seedMacro != null && !seedMacro.trim().isEmpty();
            this.priorRecord = priorRecord;
            this.priorRecordInMacros = priorRecordInMacros;
            this.baseline = safeText();

            this.dialog = owner == null
                    ? new JDialog((Frame) null, "Record Filter - " + this.channelLabel, false)
                    : new JDialog(owner, "Record Filter - " + this.channelLabel,
                            Dialog.ModalityType.MODELESS);
            try {
                this.dialog.setModalExclusionType(Dialog.ModalExclusionType.APPLICATION_EXCLUDE);
            } catch (RuntimeException ignored) {
                // Best effort: the owner chain is the primary focus fix.
            }
            this.dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
            this.dialog.setLayout(new BorderLayout(8, 8));

            this.timer = new javax.swing.Timer(500, e -> tick());
            this.timer.setRepeats(true);

            buildUi();
        }

        void open() {
            if (SwingUtilities.isEventDispatchThread()) {
                openOnEdt();
                return;
            }
            try {
                SwingUtilities.invokeAndWait(new Runnable() {
                    @Override public void run() {
                        openOnEdt();
                    }
                });
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Interrupted while opening recorder dialog on the UI thread", ie);
            } catch (InvocationTargetException ite) {
                Throwable cause = ite.getCause();
                if (cause instanceof RuntimeException) throw (RuntimeException) cause;
                if (cause instanceof Error) throw (Error) cause;
                throw new RuntimeException("Recorder dialog failed while opening on the UI thread", cause);
            }
        }

        private void openOnEdt() {
            moveOwnerBehindRecordingWorkspace();
            allowFijiRecordingInteraction(null);
            sampleLoading = sampleSupplier != null;
            setControlsEnabled(!sampleLoading);
            updateBannerVisibility();
            dialog.addWindowListener(new WindowAdapter() {
                @Override public void windowClosed(WindowEvent e) {
                    onClosed();
                }
            });
            dialog.pack();
            Dimension pref = dialog.getPreferredSize();
            int width = Math.max(580, pref.width);
            int height = Math.max(440, pref.height);
            dialog.setSize(width, height);
            positionRecorderBesideSample(null);
            timer.start();
            dialog.setVisible(true);
            dialog.toFront();
            dialog.requestFocus();
            if (sampleSupplier != null) {
                launchSampleWorker();
            } else {
                startRecordingNow();
                refocusEnabledControl();
            }
        }

        private void launchSampleWorker() {
            if (sampleSupplier == null || closeRequested || closed) return;
            sampleLoading = true;
            openedSample = null;
            setControlsEnabled(false);
            updateBannerVisibility();
            sampleWorker = new SwingWorker<ImagePlus, Void>() {
                @Override protected ImagePlus doInBackground() throws Exception {
                    ImagePlus sample = null;
                    try {
                        sample = sampleSupplier.openSample();
                        openedSample = sample;
                        if (isCancelled() || closeRequested) {
                            closeImageQuietly(sample);
                            openedSample = null;
                            return null;
                        }
                        return sample;
                    } catch (Throwable t) {
                        closeImageQuietly(sample);
                        throw new RuntimeException("Recorder dialog failed while loading the sample image", t);
                    }
                }

                @Override protected void done() {
                    finishSampleWorker(this);
                }
            };
            sampleWorker.execute();
            refocusEnabledControl();
        }

        private void finishSampleWorker(SwingWorker<ImagePlus, Void> worker) {
            ImagePlus sample = null;
            Throwable failure = null;
            boolean cancelled = worker.isCancelled();
            if (!cancelled) {
                try {
                    sample = worker.get();
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    failure = ie;
                } catch (ExecutionException ee) {
                    failure = ee.getCause() == null ? ee : ee.getCause();
                }
            } else {
                sample = openedSample;
            }

            sampleLoading = false;
            if (cancelled || closeRequested || closed || !dialog.isDisplayable()) {
                closeImageQuietly(sample);
                openedSample = null;
                updateBannerVisibility();
                return;
            }

            if (failure != null) {
                IJ.log("Record Filter Macro: could not auto-open sample image: " + cleanMessage(failure));
            } else if (sample == null) {
                IJ.log("Record Filter Macro: could not auto-open sample image: no sample image returned");
            } else {
                try {
                    displayPreparedSample(sample);
                } catch (Throwable t) {
                    IJ.log("Record Filter Macro: could not auto-open sample image: " + cleanMessage(t));
                }
            }

            startRecordingNow();
            setControlsEnabled(true);
            updateBannerVisibility();
            refocusEnabledControl();
        }

        private void displayPreparedSample(final ImagePlus sample) {
            if (sample == null) return;
            final boolean createdWindow = sample.getWindow() == null;
            if (createdWindow) sample.show();
            sampleSupplier.afterSampleShown(sample);
            allowFijiRecordingInteraction(sample);
            if (sample.getWindow() != null) {
                WindowManager.setCurrentWindow(sample.getWindow());
            }
            positionRecorderBesideSample(sample);
            if (createdWindow) {
                SwingUtilities.invokeLater(new Runnable() {
                    @Override public void run() {
                        if (!closed && !closeRequested && dialog.isDisplayable()) {
                            positionRecorderBesideSample(sample);
                        }
                    }
                });
            }
            baseline = safeText();
        }

        private void startRecordingNow() {
            baseline = safeText();
            Recorder.record = true;
            Recorder.recordInMacros = true;
        }

        private void setControlsEnabled(boolean enabled) {
            if (startOverButton != null) startOverButton.setEnabled(enabled);
            if (previewButton != null) previewButton.setEnabled(enabled && previewHandler != null);
            if (saveButton != null) saveButton.setEnabled(enabled);
        }

        private void refocusEnabledControl() {
            SwingUtilities.invokeLater(new Runnable() {
                @Override public void run() {
                    JButton target = banner.isVisible() && bannerOpenButton != null
                            && bannerOpenButton.isVisible() && bannerOpenButton.isEnabled()
                            ? bannerOpenButton
                            : !sampleLoading && saveButton != null && saveButton.isEnabled()
                                    ? saveButton
                                    : cancelButton;
                    if (target != null) target.requestFocusInWindow();
                }
            });
        }

        void await() {
            if (SwingUtilities.isEventDispatchThread()) {
                loop = Toolkit.getDefaultToolkit().getSystemEventQueue().createSecondaryLoop();
                loop.enter();
            } else {
                try {
                    done.await();
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
            }
        }

        void shutdown() {
            timer.stop();
            Recorder.record = priorRecord;
            Recorder.recordInMacros = priorRecordInMacros;
            if (previewHandler != null) previewHandler.cleanup();
        }

        private void buildUi() {
            JPanel top = new JPanel();
            top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));

            if (hasSeed) {
                JLabel ack = new JLabel("Continuing from your existing filter — "
                        + "new commands you record will be added on top.");
                ack.setForeground(FlashTheme.SUCCESS_FG);
                ack.setBorder(FlashTheme.pad(8, 12, 0, 12));
                ack.setAlignmentX(Component.LEFT_ALIGNMENT);
                top.add(ack);
            }

            buildBanner();
            banner.setAlignmentX(Component.LEFT_ALIGNMENT);
            top.add(banner);

            JLabel intro = new JLabel("<html><body style='width:540px;'>"
                    + "Use Fiji normally — filters, duplicates, thresholds, whatever. The plain-English"
                    + " summary below updates as you work. When you're done, click <b>Use this filter</b>."
                    + "</body></html>");
            intro.setBorder(FlashTheme.pad(10, 12, 4, 12));
            intro.setAlignmentX(Component.LEFT_ALIGNMENT);
            top.add(intro);

            summary.setText("Recorded so far: nothing yet.");
            summary.setBorder(FlashTheme.pad(0, 12, 6, 12));
            summary.setAlignmentX(Component.LEFT_ALIGNMENT);
            top.add(summary);

            dialog.add(top, BorderLayout.NORTH);

            area.setEditable(false);
            area.setLineWrap(false);
            area.setFont(FlashTheme.mono(12));
            area.setText("");
            JScrollPane scroll = new JScrollPane(area);
            scroll.setBorder(FlashTheme.pad(0, 12, 0, 12));
            dialog.add(scroll, BorderLayout.CENTER);

            JPanel footer = new JPanel(new BorderLayout());
            JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 8));
            JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 8));
            JButton startOver = new JButton("Start over");
            JButton help = new JButton("?");
            JButton cancel = new JButton("Cancel");
            JButton preview = new JButton("Preview");
            JButton save = new JButton("Use this filter");
            this.startOverButton = startOver;
            this.previewButton = preview;
            this.cancelButton = cancel;
            this.saveButton = save;

            help.setToolTipText("What do these buttons do?");

            startOver.addActionListener(e -> onClearBuffer());
            help.addActionListener(e -> showRecorderHelp());
            cancel.addActionListener(e -> onCancel());
            preview.addActionListener(e -> onInlinePreview());
            save.addActionListener(e -> onSave());

            if (previewHandler == null) preview.setEnabled(false);

            left.add(startOver);
            left.add(help);
            right.add(cancel);
            right.add(preview);
            right.add(save);
            footer.add(left, BorderLayout.WEST);
            footer.add(right, BorderLayout.EAST);
            dialog.add(footer, BorderLayout.SOUTH);
        }

        private void showRecorderHelp() {
            String msg = "<html><body style='width:360px;'>"
                    + "Run filters in Fiji on the sample image; the steps are captured here as you go."
                    + "<br><br>"
                    + "<b>Open sample</b> (banner)<br>"
                    + "Opens a sample image to record on. Only shown when no image is open."
                    + "<br><br>"
                    + "<b>Start over</b><br>"
                    + "Clears the recorded steps from this session and starts capturing fresh."
                    + "<br><br>"
                    + "<b>Preview</b><br>"
                    + "Runs the recorded steps (plus any existing filter you're extending) "
                    + "on the current image without saving."
                    + "<br><br>"
                    + "<b>Use this filter</b><br>"
                    + "Saves the recorded steps as the channel's custom filter."
                    + "<br><br>"
                    + "<b>Cancel</b><br>"
                    + "Closes the recorder without saving anything."
                    + "</body></html>";
            JOptionPane.showMessageDialog(dialog, msg, "Record Filter — Help",
                    JOptionPane.INFORMATION_MESSAGE);
        }

        private void buildBanner() {
            banner.setBackground(FlashTheme.WARNING_BG);
            banner.setOpaque(true);
            banner.setBorder(FlashTheme.pad(8, 12, 8, 12));
            JButton open = new JButton("Open sample");
            flash.pipeline.ui.FlashIcons.apply(open, flash.pipeline.ui.FlashIcons.folderOpen());
            open.addActionListener(e -> onOpenSample());
            this.bannerOpenButton = open;
            banner.add(bannerMessage, BorderLayout.CENTER);
            banner.add(open, BorderLayout.EAST);
            banner.setMaximumSize(new Dimension(Integer.MAX_VALUE, banner.getPreferredSize().height + 16));
        }

        private void tick() {
            String full = safeText();
            if (full.length() < baseline.length()) {
                baseline = full;
                lastShown = "";
                area.setText("");
                refreshSummary("");
                updateBannerVisibility();
                return;
            }
            String diff = full.substring(baseline.length());
            if (!diff.equals(lastShown)) {
                area.setText(diff);
                lastShown = diff;
                area.setCaretPosition(area.getDocument().getLength());
                refreshSummary(diff);
            }
            updateBannerVisibility();
        }

        private void updateBannerVisibility() {
            if (!SwingUtilities.isEventDispatchThread()) {
                SwingUtilities.invokeLater(new Runnable() {
                    @Override public void run() {
                        updateBannerVisibility();
                    }
                });
                return;
            }
            bannerMessage.setText(sampleLoading
                    ? "Loading sample image..."
                    : "You need a sample image to record on.");
            if (bannerOpenButton != null) {
                bannerOpenButton.setVisible(!sampleLoading);
                bannerOpenButton.setEnabled(!sampleLoading);
            }
            boolean shouldShow = sampleLoading || WindowManager.getCurrentImage() == null;
            boolean changed = shouldShow != lastBannerVisible;
            if (changed) banner.setVisible(shouldShow);
            lastBannerVisible = shouldShow;
            if (banner.getParent() != null) {
                banner.getParent().revalidate();
                banner.getParent().repaint();
            }
        }

        private void refreshSummary(String diff) {
            if (diff != null && diff.equals(lastSummarised)) return;
            lastSummarised = diff;
            summary.setText("Recorded so far: " + summariseDiff(diff));
        }

        private void onOpenSample() {
            if (sampleLoading) return;
            if (sampleSupplier == null) {
                IJ.showMessage("Record Filter Macro",
                        "No sample loader is available. Open an image manually through File > Open"
                                + " or via Bio-Formats, then continue recording.");
                return;
            }
            launchSampleWorker();
        }

        private void onInlinePreview() {
            if (previewHandler == null) return;
            String diff = currentDiff();
            String combined = combine(diff);
            if (combined.trim().isEmpty()) {
                IJ.showMessage("Record Filter Macro",
                        "There is nothing to preview yet. Run a filter in Fiji first.");
                return;
            }
            try {
                previewHandler.preview(combined);
            } catch (Exception ex) {
                IJ.showMessage("Record Filter Macro",
                        "Preview failed for " + channelLabel + ":\n" + cleanMessage(ex));
            }
        }

        private void onClearBuffer() {
            baseline = safeText();
            lastShown = "";
            area.setText("");
            refreshSummary("");
        }

        private void onSave() {
            timer.stop();
            String captured = currentDiff();
            captured = captured == null ? "" : captured.trim();

            if (captured.isEmpty()) {
                IJ.showMessage("Record Filter Macro",
                        "No new commands were recorded during this session. Run something in Fiji first.");
                timer.start();
                return;
            }

            Recorder.record = priorRecord;
            Recorder.recordInMacros = priorRecordInMacros;

            String combined = combine(captured);
            PresetMatcher.Match match = PresetMatcher.match(combined);
            boolean confirmed = showSaveConfirmation(captured, combined, match);
            if (!confirmed) {
                Recorder.record = true;
                Recorder.recordInMacros = true;
                timer.start();
                return;
            }

            result = new Result(combined, match == null ? null : match.presetName);
            disposeAndExit();
        }

        private void onCancel() {
            timer.stop();
            result = Result.cancel();
            disposeAndExit();
        }

        private String currentDiff() {
            String full = safeText();
            return full.length() >= baseline.length()
                    ? full.substring(baseline.length())
                    : full;
        }

        private String combine(String diff) {
            return combineSeedAndDiff(hasSeed ? seedMacro : null, diff);
        }

        private boolean showSaveConfirmation(String captured, String combined, PresetMatcher.Match match) {
            PipelineDialog confirm = new PipelineDialog(dialog, "Save Recorded Filter - " + channelLabel);
            confirm.addHeader("Recorded Macro");
            confirm.addMessage("Captured " + lineCount(captured) + " new line(s) for " + channelLabel + ".");
            if (match != null) {
                String mode = match.structural
                        ? "The combined macro matches '" + match.presetName + "' with parameter overrides."
                        : "The combined macro matches bundled preset '" + match.presetName + "'.";
                confirm.addMessage(mode);
            }
            if (previewHandler != null) {
                final String macro = combined;
                JButton preview = confirm.addFooterButton("Preview");
                preview.addActionListener(e -> {
                    try {
                        previewHandler.preview(macro);
                    } catch (Exception ex) {
                        IJ.showMessage("Record Filter Macro",
                                "Preview failed for " + channelLabel + ":\n" + cleanMessage(ex));
                    }
                });
            }
            boolean ok = confirm.showDialog();
            if (previewHandler != null) previewHandler.cleanup();
            return ok;
        }

        private void disposeAndExit() {
            if (dialog.isDisplayable()) dialog.dispose();
        }

        private void onClosed() {
            if (closed) return;
            closed = true;
            closeRequested = true;
            SwingWorker<ImagePlus, Void> worker = sampleWorker;
            if (worker != null && !worker.isDone()) worker.cancel(true);
            if (sampleLoading) {
                closeImageQuietly(openedSample);
                openedSample = null;
            }
            timer.stop();
            Recorder.record = priorRecord;
            Recorder.recordInMacros = priorRecordInMacros;
            restoreOwnerAfterRecording();
            done.countDown();
            if (loop != null) loop.exit();
        }

        private String safeText() {
            try {
                String text = rec.getText();
                return text == null ? "" : text;
            } catch (Throwable t) {
                return "";
            }
        }

        private static int lineCount(String text) {
            if (text == null || text.isEmpty()) return 0;
            int count = 1;
            for (int i = 0; i < text.length(); i++) {
                if (text.charAt(i) == '\n') count++;
            }
            return count;
        }

        private static String cleanMessage(Throwable t) {
            if (t == null) return "";
            String message = t.getMessage();
            if (message == null || message.trim().isEmpty()) return t.getClass().getSimpleName();
            return message;
        }

        private static void allowFijiRecordingInteraction(ImagePlus sample) {
            allowWindowThroughModalBlock(IJ.getInstance());
            if (sample != null) {
                allowWindowThroughModalBlock(sample.getWindow());
            }
            allowWindowThroughModalBlock(WindowManager.getFrame("Recorder"));
        }

        private static void allowWindowThroughModalBlock(Window window) {
            if (window == null) return;
            try {
                window.setModalExclusionType(Dialog.ModalExclusionType.APPLICATION_EXCLUDE);
            } catch (RuntimeException ignored) {
                // Best effort: recording still works where modal exclusion is unavailable.
            }
        }

        private static void closeImageQuietly(ImagePlus image) {
            if (image == null) return;
            try {
                image.changes = false;
                image.close();
            } catch (Throwable ignored) {
                // Best effort cleanup for cancelled background sample opens.
            } finally {
                try {
                    image.flush();
                } catch (Throwable ignored) {
                    // Best effort cleanup for cancelled background sample opens.
                }
            }
        }

        private void moveOwnerBehindRecordingWorkspace() {
            if (owner == null || !owner.isDisplayable()) return;
            try {
                owner.toBack();
            } catch (RuntimeException ignored) {
                // Best effort: the modeless QC shell no longer blocks image interaction.
            }
        }

        private void restoreOwnerAfterRecording() {
            if (owner == null || !owner.isDisplayable() || !owner.isShowing()) return;
            SwingUtilities.invokeLater(new Runnable() {
                @Override public void run() {
                    try {
                        owner.toFront();
                        owner.requestFocus();
                    } catch (RuntimeException ignored) {
                        // Best effort only.
                    }
                }
            });
        }

        private void positionRecorderBesideSample(ImagePlus sample) {
            Window sampleWindow = sample == null ? null : sample.getWindow();
            if (sampleWindow == null || !sampleWindow.isShowing()) {
                dialog.setLocationRelativeTo(null);
                return;
            }
            Point preferred = new Point(
                    sampleWindow.getX() + sampleWindow.getWidth() + 20,
                    sampleWindow.getY());
            Rectangle bounds = GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
            int x = Math.min(
                    Math.max(bounds.x, preferred.x),
                    Math.max(bounds.x, bounds.x + bounds.width - dialog.getWidth()));
            int y = Math.min(
                    Math.max(bounds.y, preferred.y),
                    Math.max(bounds.y, bounds.y + bounds.height - dialog.getHeight()));
            dialog.setLocation(x, y);
        }
    }

    /**
     * Plain-English summary of the captured diff. Visible to the user above
     * the raw macro text. The seed macro is never passed in here — only the
     * user's own current-session capture.
     */
    static String summariseDiff(String captured) {
        if (captured == null || captured.trim().isEmpty()) return "nothing yet.";
        List<Op> ops = FilterMacroParser.parseString(captured);
        if (ops.isEmpty()) return "nothing parseable yet.";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < ops.size(); i++) {
            if (i > 0) sb.append(" → ");
            sb.append(formatOp(ops.get(i)));
        }
        sb.append(".");
        return sb.toString();
    }

    static String combineSeedAndDiff(String seedMacro, String diff) {
        String d = diff == null ? "" : diff;
        if (seedMacro == null || seedMacro.trim().isEmpty()) return d;
        return seedMacro.trim() + "\n" + d;
    }

    private static String formatOp(Op op) {
        if (op == null || op.type == null) return "?";
        switch (op.type) {
            case GAUSSIAN_BLUR:        return "Gaussian Blur" + paramSummary(op, new String[]{"sigma"});
            case SUBTRACT_BACKGROUND:  return "Subtract Background" + paramSummary(op, new String[]{"rolling"});
            case MEDIAN:               return "Median" + paramSummary(op, new String[]{"radius"});
            case MEAN:                 return "Mean" + paramSummary(op, new String[]{"radius"});
            case UNSHARP_MASK:         return "Unsharp Mask" + paramSummary(op, new String[]{"radius", "mask"});
            case MINIMUM:              return "Minimum" + paramSummary(op, new String[]{"radius"});
            case MAXIMUM:              return "Maximum" + paramSummary(op, new String[]{"radius"});
            case VARIANCE:             return "Variance" + paramSummary(op, new String[]{"radius"});
            case DILATE:               return "Dilate";
            case ERODE:                return "Erode";
            case OPEN:                 return "Open";
            case CLOSE_:               return "Close";
            case FILL_HOLES:           return "Fill Holes";
            case SKELETONIZE:          return "Skeletonize";
            case INVERT:               return "Invert";
            case ADD:                  return "Add" + paramSummary(op, new String[]{"value"});
            case SUBTRACT:             return "Subtract" + paramSummary(op, new String[]{"value"});
            case MULTIPLY:             return "Multiply" + paramSummary(op, new String[]{"value"});
            case DIVIDE:               return "Divide" + paramSummary(op, new String[]{"value"});
            case AUTO_LOCAL_THRESHOLD: return "Auto Local Threshold" + autoLocalParams(op);
            case CONVERT_8BIT:         return "8-bit";
            case CONVERT_16BIT:        return "16-bit";
            case CONVERT_32BIT:        return "32-bit";
            case ENHANCE_CONTRAST:     return "Enhance Contrast" + paramSummary(op, new String[]{"saturated"});
            case GAUSSIAN_BLUR_3D:     return "Gaussian Blur 3D" + paramSummary(op, new String[]{"x", "y", "z"});
            case MEDIAN_3D:            return "Median 3D" + paramSummary(op, new String[]{"x", "y", "z"});
            case MINIMUM_3D:           return "Minimum 3D" + paramSummary(op, new String[]{"x", "y", "z"});
            case UNKNOWN:              return op.type.name();
            default:                   return op.type.name();
        }
    }

    private static String paramSummary(Op op, String[] keys) {
        StringBuilder sb = new StringBuilder();
        int written = 0;
        for (String key : keys) {
            double v = op.getParam(key);
            if (Double.isNaN(v)) continue;
            sb.append(written == 0 ? " (" : ", ");
            sb.append(key).append("=").append(formatNumber(v));
            written++;
        }
        if (written > 0) sb.append(")");
        return sb.toString();
    }

    private static String autoLocalParams(Op op) {
        StringBuilder sb = new StringBuilder();
        int written = 0;
        String method = op.getStringParam("method");
        if (method != null && !method.isEmpty()) {
            sb.append(" (method=").append(method);
            written++;
        }
        double r = op.getParam("radius");
        if (!Double.isNaN(r)) {
            sb.append(written == 0 ? " (" : ", ").append("radius=").append(formatNumber(r));
            written++;
        }
        if (written > 0) sb.append(")");
        return sb.toString();
    }

    private static String formatNumber(double v) {
        if (v == Math.floor(v) && !Double.isInfinite(v) && Math.abs(v) < 1e15) {
            return Long.toString((long) v);
        }
        return Double.toString(v);
    }
}
