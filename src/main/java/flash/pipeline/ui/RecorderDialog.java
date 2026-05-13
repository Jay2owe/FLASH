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
import javax.swing.WindowConstants;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Dialog;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Frame;
import java.awt.GraphicsEnvironment;
import java.awt.SecondaryLoop;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.List;
import java.util.concurrent.CountDownLatch;

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
        /** Returns a displayed ImagePlus for the user to operate on, or null if none could be opened. */
        ImagePlus openSample();
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
        Recorder rec = resolveRecorder();
        if (rec == null) {
            IJ.showMessage("Record Filter Macro", "Could not open the ImageJ Recorder.");
            return Result.cancel();
        }

        Session session = new Session(owner, channelLabel, rec, previewHandler, sampleSupplier, seedMacro);
        try {
            session.open();
            session.await();
            return session.result;
        } finally {
            session.shutdown();
        }
    }

    static Recorder resolveRecorder() {
        Frame frame = WindowManager.getFrame("Recorder");
        if (frame instanceof Recorder) return (Recorder) frame;
        try {
            return new Recorder();
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
        private final CountDownLatch done = new CountDownLatch(1);

        private final JDialog dialog;
        private final JTextArea area = new JTextArea();
        private final JLabel summary = new JLabel(" ");
        private final JPanel banner = new JPanel(new BorderLayout(8, 0));
        private JButton bannerOpenButton;
        private JButton saveButton;
        private final javax.swing.Timer timer;

        private String baseline;
        private String lastShown = "";
        private String lastSummarised = null;
        private boolean lastBannerVisible = true;
        private SecondaryLoop loop;
        private Result result = Result.cancel();
        private boolean closed = false;

        Session(Window owner, String channelLabel, Recorder rec,
                CustomFilterEntryDialog.PreviewHandler previewHandler,
                SampleSupplier sampleSupplier,
                String seedMacro) {
            this.channelLabel = channelLabel == null ? "" : channelLabel;
            this.rec = rec;
            this.previewHandler = previewHandler;
            this.sampleSupplier = sampleSupplier;
            this.seedMacro = seedMacro;
            this.hasSeed = seedMacro != null && !seedMacro.trim().isEmpty();
            this.priorRecord = Recorder.record;
            this.priorRecordInMacros = Recorder.recordInMacros;
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
            if (sampleSupplier != null) {
                try {
                    ImagePlus sample = sampleSupplier.openSample();
                    if (sample != null) {
                        if (sample.getWindow() == null) sample.show();
                        WindowManager.setCurrentWindow(sample.getWindow());
                    }
                } catch (Throwable t) {
                    IJ.log("Record Filter Macro: could not auto-open sample image: " + cleanMessage(t));
                }
            }
            updateBannerVisibility();
            Recorder.record = true;
            Recorder.recordInMacros = true;
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
            dialog.setLocationRelativeTo(null);
            timer.start();
            dialog.setVisible(true);
            dialog.toFront();
            dialog.requestFocus();
            SwingUtilities.invokeLater(new Runnable() {
                @Override public void run() {
                    JButton target = lastBannerVisible && bannerOpenButton != null
                            ? bannerOpenButton
                            : saveButton;
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
                ack.setForeground(new Color(70, 110, 70));
                ack.setBorder(BorderFactory.createEmptyBorder(8, 12, 0, 12));
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
            intro.setBorder(BorderFactory.createEmptyBorder(10, 12, 4, 12));
            intro.setAlignmentX(Component.LEFT_ALIGNMENT);
            top.add(intro);

            summary.setText("Recorded so far: nothing yet.");
            summary.setBorder(BorderFactory.createEmptyBorder(0, 12, 6, 12));
            summary.setAlignmentX(Component.LEFT_ALIGNMENT);
            top.add(summary);

            dialog.add(top, BorderLayout.NORTH);

            area.setEditable(false);
            area.setLineWrap(false);
            area.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
            area.setText("");
            JScrollPane scroll = new JScrollPane(area);
            scroll.setBorder(BorderFactory.createEmptyBorder(0, 12, 0, 12));
            dialog.add(scroll, BorderLayout.CENTER);

            JPanel footer = new JPanel(new BorderLayout());
            JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 8));
            JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 8));
            JButton startOver = new JButton("Start over");
            JButton help = new JButton("?");
            JButton cancel = new JButton("Cancel");
            JButton preview = new JButton("Preview");
            JButton save = new JButton("Use this filter");
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
            banner.setBackground(new Color(255, 244, 204));
            banner.setOpaque(true);
            banner.setBorder(BorderFactory.createEmptyBorder(8, 12, 8, 12));
            JLabel msg = new JLabel("You need a sample image to record on.");
            JButton open = new JButton("Open sample");
            flash.pipeline.ui.FlashIcons.apply(open, flash.pipeline.ui.FlashIcons.folderOpen());
            open.addActionListener(e -> onOpenSample());
            this.bannerOpenButton = open;
            banner.add(msg, BorderLayout.CENTER);
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
            boolean shouldShow = WindowManager.getCurrentImage() == null;
            if (shouldShow == lastBannerVisible) return;
            banner.setVisible(shouldShow);
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
            if (sampleSupplier == null) {
                IJ.showMessage("Record Filter Macro",
                        "No sample loader is available. Open an image manually through File > Open"
                                + " or via Bio-Formats, then continue recording.");
                return;
            }
            ImagePlus sample;
            try {
                sample = sampleSupplier.openSample();
            } catch (Throwable t) {
                IJ.showMessage("Record Filter Macro",
                        "Could not open a sample image:\n" + cleanMessage(t));
                return;
            }
            if (sample == null) {
                IJ.showMessage("Record Filter Macro",
                        "No sample image is available for this channel. Open an image manually"
                                + " through File > Open or via Bio-Formats, then continue recording.");
                return;
            }
            if (sample.getWindow() == null) sample.show();
            WindowManager.setCurrentWindow(sample.getWindow());
            updateBannerVisibility();
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
            PipelineDialog confirm = new PipelineDialog("Save Recorded Filter - " + channelLabel);
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
            timer.stop();
            Recorder.record = priorRecord;
            Recorder.recordInMacros = priorRecordInMacros;
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
