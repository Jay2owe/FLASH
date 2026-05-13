package flash.pipeline.ui;

import flash.pipeline.image.FilterMacroParser;
import flash.pipeline.image.FilterMacroParser.Op;
import flash.pipeline.image.FilterMacroParser.OpType;
import flash.pipeline.image.dag.DagIR;
import ij.IJ;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.awt.GridLayout;
import java.awt.Window;
import java.awt.event.ActionEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

/**
 * Entry point for authoring a Custom channel filter.
 */
public final class CustomFilterEntryDialog {

    private CustomFilterEntryDialog() {}

    public enum Choice { IMPORT, RECORD, SANDBOX, CANCEL }

    public interface PreviewHandler {
        void preview(String macroContent) throws Exception;
        void cleanup();
    }

    public interface SandboxHandler {
        Result openSandbox();
    }

    public static final class Result {
        public final Choice choice;
        public final String macroContent;
        public final String demotedPreset;
        public final DagIR dag;
        public final String ijmFallback;

        private Result(Choice choice, String macroContent, String demotedPreset,
                       DagIR dag, String ijmFallback) {
            this.choice = choice == null ? Choice.CANCEL : choice;
            this.macroContent = macroContent;
            this.demotedPreset = demotedPreset;
            this.dag = dag;
            this.ijmFallback = ijmFallback;
        }

        public static Result cancel() {
            return new Result(Choice.CANCEL, null, null, null, null);
        }

        public static Result sandbox(DagIR dag, String ijmFallback) {
            return new Result(Choice.SANDBOX, null, null, dag, ijmFallback);
        }
    }

    public static Result show(String channelLabel, PreviewHandler previewHandler) {
        return show(channelLabel, previewHandler, null, null, null);
    }

    public static Result show(Window owner, String channelLabel, PreviewHandler previewHandler) {
        return show(owner, channelLabel, previewHandler, null, null, null);
    }

    public static Result show(String channelLabel, PreviewHandler previewHandler,
                              SandboxHandler sandboxHandler) {
        return show(channelLabel, previewHandler, sandboxHandler, null, null);
    }

    public static Result show(Window owner, String channelLabel, PreviewHandler previewHandler,
                              SandboxHandler sandboxHandler) {
        return show(owner, channelLabel, previewHandler, sandboxHandler, null, null);
    }

    public static Result show(String channelLabel, PreviewHandler previewHandler,
                              SandboxHandler sandboxHandler,
                              RecorderDialog.SampleSupplier sampleSupplier) {
        return show(channelLabel, previewHandler, sandboxHandler, sampleSupplier, null);
    }

    public static Result show(Window owner, String channelLabel, PreviewHandler previewHandler,
                              SandboxHandler sandboxHandler,
                              RecorderDialog.SampleSupplier sampleSupplier) {
        return show(owner, channelLabel, previewHandler, sandboxHandler, sampleSupplier, null);
    }

    public static Result show(String channelLabel, PreviewHandler previewHandler,
                              SandboxHandler sandboxHandler,
                              RecorderDialog.SampleSupplier sampleSupplier,
                              String seedMacro) {
        return show(null, channelLabel, previewHandler, sandboxHandler, sampleSupplier, seedMacro);
    }

    public static Result show(Window owner, String channelLabel, PreviewHandler previewHandler,
                              SandboxHandler sandboxHandler,
                              RecorderDialog.SampleSupplier sampleSupplier,
                              String seedMacro) {
        if (GraphicsEnvironment.isHeadless()) return Result.cancel();

        String action = showTileChooser(owner, channelLabel);
        if ("record".equals(action)) {
            RecorderDialog.Result rr = RecorderDialog.show(owner, channelLabel, previewHandler, sampleSupplier, seedMacro);
            if (rr == null || rr.macroText == null) return Result.cancel();
            return new Result(Choice.RECORD, rr.macroText, rr.demotedPreset, null, null);
        }
        if ("sandbox".equals(action)) {
            if (sandboxHandler == null) {
                IJ.showMessage("Coming soon", "The visual builder is built in a later stage.");
                return Result.cancel();
            }
            Result sandboxResult = sandboxHandler.openSandbox();
            return sandboxResult == null ? Result.cancel() : sandboxResult;
        }
        if (!"import".equals(action)) return Result.cancel();

        File selected = chooseImportFile(owner);
        if (selected == null) return Result.cancel();
        try {
            String macro = new String(Files.readAllBytes(selected.toPath()), StandardCharsets.UTF_8);
            ParseSummary summary = summarize(macro);
            PresetMatcher.Match match = PresetMatcher.match(macro);
            boolean confirmed = showImportConfirmation(owner, channelLabel, selected, macro, summary, match, previewHandler);
            if (!confirmed) return Result.cancel();
            return new Result(Choice.IMPORT, macro, match == null ? null : match.presetName, null, null);
        } catch (IOException e) {
            IJ.showMessage("Import Filter Macro", "Could not read the selected macro as UTF-8:\n" + e.getMessage());
            return Result.cancel();
        }
    }

    private static String showTileChooser(Window owner, String channelLabel) {
        final PipelineDialog dialog = owner == null
                ? new PipelineDialog("Custom Filter — " + safe(channelLabel))
                : new PipelineDialog(owner, "Custom Filter — " + safe(channelLabel));
        dialog.setDefaultButtonsVisible(false);
        dialog.addHeader("How do you want to build this filter?");
        dialog.addMessage("You can preview your work and switch methods by cancelling at any time.");

        JPanel tiles = new JPanel(new GridLayout(1, 3, 10, 0));
        tiles.setOpaque(false);
        final JPanel firstTile = makeTile("Build it step-by-step",
                "Pick filter steps from a list and arrange them visually. Best place to start.",
                dialog, "sandbox", true, flash.pipeline.ui.FlashIcons.build());
        tiles.add(firstTile);
        tiles.add(makeTile("Record what I do in Fiji",
                "Run the filters you want in Fiji normally; the steps are captured as you go.",
                dialog, "record", false, flash.pipeline.ui.FlashIcons.record()));
        tiles.add(makeTile("Import an existing macro file",
                "Use a saved .ijm or .txt macro you already have.",
                dialog, "import", false, flash.pipeline.ui.FlashIcons.importFile()));
        dialog.addComponent(tiles);

        JButton help = dialog.addFooterButton("?");
        help.setToolTipText("What do these options do?");
        help.addActionListener(e -> showEntryChooserHelp(owner));
        JButton cancel = dialog.addRightFooterButton("Cancel");
        cancel.addActionListener(e -> dialog.closeWithAction("cancel"));
        dialog.requestFocusOnShow(firstTile);
        dialog.showDialog();
        return dialog.getActionCommand();
    }

    private static void showEntryChooserHelp(Window owner) {
        String msg = "<html><body style='width:340px;'>"
                + "Pick how you want to author the custom filter for this channel."
                + "<br><br>"
                + "<b>Build it step-by-step</b><br>"
                + "Pick filter steps from a list and arrange them visually. "
                + "Best place to start &mdash; you see every step and can preview as you go."
                + "<br><br>"
                + "<b>Record what I do in Fiji</b><br>"
                + "Run the filters you want in Fiji normally; the steps are captured "
                + "from Fiji's macro recorder as you go."
                + "<br><br>"
                + "<b>Import an existing macro file</b><br>"
                + "Use a saved <code>.ijm</code> or <code>.txt</code> macro you already have."
                + "<br><br>"
                + "<b>Cancel</b><br>"
                + "Close this dialog without changing the channel's filter."
                + "</body></html>";
        JOptionPane.showMessageDialog(owner, msg, "Custom Filter — Help",
                JOptionPane.INFORMATION_MESSAGE);
    }

    /**
     * Shared tile factory. Returns a focusable, fully click-targeted panel that
     * fires {@code action} on the dialog when activated by mouse, keyboard, or
     * the inner {@link JButton}. Used by both the entry chooser and the
     * continue picker so the a11y wiring lives in one place.
     */
    static JPanel makeTile(String title, String description, final PipelineDialog dialog,
                           final String action, boolean recommended) {
        return makeTile(title, description, dialog, action, recommended, null);
    }

    static JPanel makeTile(String title, String description, final PipelineDialog dialog,
                           final String action, boolean recommended, javax.swing.Icon icon) {
        final JPanel panel = new JPanel(new BorderLayout(0, 6));
        final Color borderColor = recommended ? new Color(70, 120, 200) : new Color(180, 180, 180);
        final Color baseBg = new Color(250, 250, 250);
        final Color hoverBg = new Color(240, 245, 252);
        panel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(borderColor, recommended ? 2 : 1),
                BorderFactory.createEmptyBorder(10, 10, 10, 10)));
        panel.setPreferredSize(new Dimension(200, 150));
        panel.setBackground(baseBg);
        panel.setOpaque(true);
        panel.setFocusable(true);

        if (recommended) {
            JLabel hint = new JLabel("New here?");
            hint.setForeground(borderColor);
            hint.setFont(hint.getFont().deriveFont(Font.BOLD, 10f));
            panel.add(hint, BorderLayout.NORTH);
        }

        final JButton button = new JButton(title);
        flash.pipeline.ui.FlashIcons.apply(button, icon);
        button.setFocusPainted(false);
        button.addActionListener(e -> dialog.closeWithAction(action));

        JPanel center = new JPanel(new BorderLayout(0, 6));
        center.setOpaque(false);
        center.add(button, BorderLayout.NORTH);
        final JLabel desc = new JLabel("<html><body style='width:170px;'>" + description + "</body></html>");
        center.add(desc, BorderLayout.CENTER);
        panel.add(center, BorderLayout.CENTER);

        MouseAdapter forward = new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                panel.requestFocusInWindow();
                button.doClick();
            }
            @Override public void mouseEntered(MouseEvent e) {
                panel.setBackground(hoverBg);
            }
            @Override public void mouseExited(MouseEvent e) {
                panel.setBackground(baseBg);
            }
        };
        panel.addMouseListener(forward);
        desc.addMouseListener(forward);

        panel.getInputMap(JComponent.WHEN_FOCUSED)
                .put(KeyStroke.getKeyStroke("SPACE"), "click");
        panel.getInputMap(JComponent.WHEN_FOCUSED)
                .put(KeyStroke.getKeyStroke("ENTER"), "click");
        panel.getActionMap().put("click", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) { button.doClick(); }
        });

        panel.getAccessibleContext().setAccessibleName(title);
        panel.getAccessibleContext().setAccessibleDescription(description);

        return panel;
    }

    private static File chooseImportFile(Window owner) {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Import Filter Macro");
        chooser.setFileSelectionMode(JFileChooser.FILES_ONLY);
        chooser.setAcceptAllFileFilterUsed(true);
        chooser.addChoosableFileFilter(new FileNameExtensionFilter("ImageJ macro (*.ijm)", "ijm"));
        chooser.addChoosableFileFilter(new FileNameExtensionFilter("Text file (*.txt)", "txt"));
        if (chooser.showOpenDialog(owner) != JFileChooser.APPROVE_OPTION) return null;
        return chooser.getSelectedFile();
    }

    private static boolean showImportConfirmation(Window owner, String channelLabel, File file, String macro,
                                                  ParseSummary summary, PresetMatcher.Match match,
                                                  final PreviewHandler previewHandler) {
        final PipelineDialog dialog = owner == null
                ? new PipelineDialog("Import Filter - " + safe(channelLabel))
                : new PipelineDialog(owner, "Import Filter - " + safe(channelLabel));
        dialog.addHeader("Imported Macro");
        dialog.addMessage("File: " + file.getAbsolutePath());
        dialog.addMessage("Parsed operations: " + summary.total + " total, "
                + summary.tier1 + " Tier 1 native, " + summary.unknown + " UNKNOWN.");
        if (match != null) {
            String mode = match.structural
                    ? "The command sequence matches '" + match.presetName + "' with parameter overrides."
                    : "This macro matches bundled preset '" + match.presetName + "'.";
            dialog.addMessage(mode);
        }
        if (previewHandler != null) {
            JButton preview = dialog.addFooterButton("Preview");
            preview.addActionListener(e -> {
                try {
                    previewHandler.preview(macro);
                } catch (Exception ex) {
                    IJ.showMessage("Import Filter Macro",
                            "Preview failed for " + safe(channelLabel) + ":\n" + ex.getMessage());
                }
            });
        }

        boolean ok = dialog.showDialog();
        if (previewHandler != null) previewHandler.cleanup();
        return ok;
    }

    private static ParseSummary summarize(String macro) {
        List<Op> ops = FilterMacroParser.parseString(macro);
        int unknown = 0;
        for (Op op : ops) {
            if (op.type == OpType.UNKNOWN) unknown++;
        }
        return new ParseSummary(ops.size(), ops.size() - unknown, unknown);
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static final class ParseSummary {
        final int total;
        final int tier1;
        final int unknown;

        ParseSummary(int total, int tier1, int unknown) {
            this.total = total;
            this.tier1 = tier1;
            this.unknown = unknown;
        }
    }
}
