package flash.pipeline.ui.variations;

import flash.pipeline.ui.RecorderDialog;
import flash.pipeline.ui.FlashTheme;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultListCellRenderer;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dialog;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.awt.Window;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class MacroVariationPickerDialog {

    private final Window owner;
    private final MacroVariationCatalog catalog;
    private final JDialog dialog;
    private final JList<MacroVariation> list;
    private final JTextArea summary = new JTextArea(4, 42);
    private List<MacroVariation> accepted =
            Collections.<MacroVariation>emptyList();

    private MacroVariationPickerDialog(Window owner,
                                       MacroVariationCatalog catalog) {
        this.owner = owner;
        this.catalog = catalog == null ? MacroVariationCatalog.empty() : catalog;
        this.dialog = owner == null
                ? new JDialog((Window) null, "Pick Macro", Dialog.ModalityType.APPLICATION_MODAL)
                : new JDialog(owner, "Pick Macro", Dialog.ModalityType.APPLICATION_MODAL);
        this.list = new JList<MacroVariation>(
                this.catalog.choices().toArray(new MacroVariation[0]));
        buildUi();
    }

    public static List<MacroVariation> show(Component parent,
                                            MacroVariationCatalog catalog) {
        if (GraphicsEnvironment.isHeadless()) {
            return Collections.<MacroVariation>emptyList();
        }
        Window owner = parent == null ? null : SwingUtilities.getWindowAncestor(parent);
        MacroVariationPickerDialog picker =
                new MacroVariationPickerDialog(owner, catalog);
        picker.dialog.pack();
        picker.dialog.setSize(new Dimension(
                Math.max(620, picker.dialog.getWidth()),
                Math.max(430, picker.dialog.getHeight())));
        picker.dialog.setLocationRelativeTo(owner);
        picker.dialog.setVisible(true);
        return picker.accepted;
    }

    private void buildUi() {
        dialog.setLayout(new BorderLayout(10, 10));
        JPanel body = new JPanel(new BorderLayout(8, 8));
        body.setBorder(BorderFactory.createEmptyBorder(12, 12, 4, 12));

        list.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        list.setCellRenderer(new MacroRenderer());
        list.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                refreshSummary();
            }
        });
        if (list.getModel().getSize() > 0) {
            list.setSelectedIndex(0);
        }
        JScrollPane libraryScroll = new JScrollPane(list);
        libraryScroll.setPreferredSize(new Dimension(360, 260));
        body.add(libraryScroll, BorderLayout.CENTER);

        summary.setEditable(false);
        summary.setLineWrap(true);
        summary.setWrapStyleWord(true);
        summary.setOpaque(false);
        summary.setFont(summary.getFont().deriveFont(Font.PLAIN, 11f));
        summary.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(FlashTheme.BORDER),
                FlashTheme.pad(6, 8, 6, 8)));
        body.add(summary, BorderLayout.SOUTH);

        body.add(actionPanel(), BorderLayout.EAST);
        dialog.add(body, BorderLayout.CENTER);
        dialog.add(footer(), BorderLayout.SOUTH);
        refreshSummary();
    }

    private JPanel actionPanel() {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setOpaque(false);
        panel.setBorder(BorderFactory.createEmptyBorder(0, 4, 0, 0));

        JButton paste = new JButton("Paste script...");
        paste.setAlignmentX(Component.LEFT_ALIGNMENT);
        paste.addActionListener(e -> acceptSingle(promptForPastedScript()));
        panel.add(paste);
        panel.add(Box.createVerticalStrut(6));

        JButton importFile = new JButton("Import .ijm...");
        importFile.setAlignmentX(Component.LEFT_ALIGNMENT);
        importFile.addActionListener(e -> acceptSingle(importScript()));
        panel.add(importFile);
        panel.add(Box.createVerticalStrut(6));

        JButton record = new JButton("Record macro...");
        record.setAlignmentX(Component.LEFT_ALIGNMENT);
        record.addActionListener(e -> acceptSingle(recordScript()));
        panel.add(record);
        panel.add(Box.createVerticalGlue());
        return panel;
    }

    private JPanel footer() {
        JPanel footer = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 8));
        JButton cancel = new JButton("Cancel");
        cancel.addActionListener(e -> {
            accepted = Collections.<MacroVariation>emptyList();
            dialog.dispose();
        });
        JButton add = new JButton("Add selected");
        add.addActionListener(e -> {
            List<MacroVariation> selected = list.getSelectedValuesList();
            accepted = selected == null
                    ? Collections.<MacroVariation>emptyList()
                    : new ArrayList<MacroVariation>(selected);
            dialog.dispose();
        });
        footer.add(cancel);
        footer.add(add);
        return footer;
    }

    private void refreshSummary() {
        List<MacroVariation> selected = list.getSelectedValuesList();
        if (selected == null || selected.isEmpty()) {
            summary.setText("Select one or more macros.");
            return;
        }
        if (selected.size() > 1) {
            summary.setText(selected.size() + " macros selected.");
            return;
        }
        MacroVariation variation = selected.get(0);
        summary.setText(MacroVariationCatalog.sourceLabel(variation) + ": "
                + displayName(variation) + "\n"
                + MacroVariationCatalog.summaryFor(variation.scriptText()));
    }

    private MacroVariation promptForPastedScript() {
        JTextField name = new JTextField("Pasted macro", 28);
        JTextArea script = new JTextArea(12, 52);
        script.setFont(FlashTheme.mono(12));
        JPanel panel = new JPanel(new BorderLayout(6, 6));
        JPanel top = new JPanel(new BorderLayout(6, 0));
        top.add(new JLabel("Name:"), BorderLayout.WEST);
        top.add(name, BorderLayout.CENTER);
        panel.add(top, BorderLayout.NORTH);
        panel.add(new JScrollPane(script), BorderLayout.CENTER);

        int result = JOptionPane.showConfirmDialog(dialog, panel,
                "Paste Macro Script", JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.PLAIN_MESSAGE);
        if (result != JOptionPane.OK_OPTION) {
            return null;
        }
        String text = script.getText();
        if (MacroToken.normalizeScriptText(text).isEmpty()) {
            JOptionPane.showMessageDialog(dialog, "Paste a non-empty macro script.",
                    "Paste Macro Script", JOptionPane.WARNING_MESSAGE);
            return null;
        }
        String display = name.getText() == null || name.getText().trim().isEmpty()
                ? "Pasted macro"
                : name.getText().trim();
        return MacroVariation.pasted(display, text);
    }

    private MacroVariation importScript() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Import Macro Script");
        chooser.setFileSelectionMode(JFileChooser.FILES_ONLY);
        chooser.addChoosableFileFilter(new FileNameExtensionFilter(
                "ImageJ macro (*.ijm)", "ijm"));
        chooser.addChoosableFileFilter(new FileNameExtensionFilter(
                "Text file (*.txt)", "txt"));
        if (chooser.showOpenDialog(dialog) != JFileChooser.APPROVE_OPTION) {
            return null;
        }
        File selected = chooser.getSelectedFile();
        if (selected == null) {
            return null;
        }
        try {
            String script = new String(Files.readAllBytes(selected.toPath()),
                    StandardCharsets.UTF_8);
            if (MacroToken.normalizeScriptText(script).isEmpty()) {
                JOptionPane.showMessageDialog(dialog, "The selected file is empty.",
                        "Import Macro Script", JOptionPane.WARNING_MESSAGE);
                return null;
            }
            return MacroVariation.pasted(stripExtension(selected.getName()), script);
        } catch (IOException e) {
            JOptionPane.showMessageDialog(dialog,
                    "Could not read the selected file:\n" + e.getMessage(),
                    "Import Macro Script", JOptionPane.WARNING_MESSAGE);
            return null;
        }
    }

    private MacroVariation recordScript() {
        RecorderDialog.Result result = RecorderDialog.show(owner,
                catalog.channelLabel(), null, null);
        if (result == null
                || MacroToken.normalizeScriptText(result.macroText).isEmpty()) {
            return null;
        }
        return MacroVariation.recorded("Recorded macro",
                catalog.channelLabel(), result.macroText);
    }

    private void acceptSingle(MacroVariation variation) {
        if (variation == null) {
            return;
        }
        accepted = Collections.singletonList(variation);
        dialog.dispose();
    }

    private static String displayName(MacroVariation variation) {
        if (variation == null) {
            return "";
        }
        if (MacroToken.NONE_VALUE.equals(variation.token())) {
            return "None";
        }
        return variation.displayName();
    }

    private static String stripExtension(String name) {
        String text = name == null ? "" : name.trim();
        int dot = text.lastIndexOf('.');
        return dot <= 0 ? text : text.substring(0, dot);
    }

    private static final class MacroRenderer extends DefaultListCellRenderer {
        @Override public Component getListCellRendererComponent(JList<?> list,
                                                                Object value,
                                                                int index,
                                                                boolean selected,
                                                                boolean focus) {
            JLabel label = (JLabel) super.getListCellRendererComponent(
                    list, value, index, selected, focus);
            if (value instanceof MacroVariation) {
                MacroVariation variation = (MacroVariation) value;
                label.setText(MacroVariationCatalog.sourceLabel(variation)
                        + " - " + displayName(variation));
                label.setToolTipText(MacroVariationCatalog.scriptPreview(
                        variation.scriptText()));
            }
            return label;
        }
    }
}
