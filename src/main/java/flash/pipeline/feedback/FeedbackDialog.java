package flash.pipeline.feedback;

import flash.pipeline.ui.FlashIcons;
import flash.pipeline.ui.FlashTheme;
import ij.IJ;
import ij.plugin.PlugIn;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
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
import javax.swing.WindowConstants;
import javax.swing.filechooser.FileNameExtensionFilter;

import java.awt.BorderLayout;
import java.awt.Desktop;
import java.awt.Dialog;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.datatransfer.StringSelection;
import java.io.File;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;

/** User-consented FLASH feedback and diagnostic bundle dialog. */
public final class FeedbackDialog implements PlugIn {

    private final Window owner;
    private final String projectDirectory;
    private final FeedbackDiagnostics.Snapshot initialDiagnostics;
    private final JDialog dialog;
    private final JComboBox<String> category;
    private final JTextField summary;
    private final JTextArea message;
    private final JCheckBox includeLog;
    private final JCheckBox includeExceptions;
    private final JCheckBox includeConsole;
    private final JCheckBox includeSystem;
    private final JCheckBox includeProject;
    private final DefaultListModel<File> attachments = new DefaultListModel<File>();
    private final JList<File> attachmentList = new JList<File>(attachments);

    @Override
    public void run(String arg) {
        SwingUtilities.invokeLater(new Runnable() {
            @Override public void run() {
                show(null, null);
            }
        });
    }

    public static void show(Window owner, String projectDirectory) {
        if (GraphicsEnvironment.isHeadless()) {
            IJ.log("[FLASH] Feedback dialog is unavailable in headless mode.");
            return;
        }
        new FeedbackDialog(owner, projectDirectory).open();
    }

    private FeedbackDialog(Window owner, String projectDirectory) {
        this.owner = owner;
        this.projectDirectory = projectDirectory == null ? "" : projectDirectory;
        this.initialDiagnostics = FeedbackDiagnostics.capture();
        this.dialog = owner == null
                ? new JDialog((java.awt.Frame) null, "Send FLASH Feedback", true)
                : new JDialog(owner, "Send FLASH Feedback", Dialog.ModalityType.APPLICATION_MODAL);
        dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        java.awt.image.BufferedImage brand = FlashIcons.brandImage(32);
        if (brand != null) dialog.setIconImage(brand);

        category = new JComboBox<String>(new String[] {
                "Bug / error", "Suggestion", "Question", "Other"
        });
        summary = new JTextField(42);
        message = new JTextArea(7, 54);
        message.setLineWrap(true);
        message.setWrapStyleWord(true);

        includeLog = diagnosticCheckBox("ImageJ Log", initialDiagnostics.log);
        includeExceptions = diagnosticCheckBox("Exceptions", initialDiagnostics.exceptions);
        includeConsole = diagnosticCheckBox("Console", initialDiagnostics.console);
        includeSystem = new JCheckBox("System and FLASH version details", true);
        includeProject = new JCheckBox("Current project folder", false);
        includeProject.setEnabled(!this.projectDirectory.trim().isEmpty());

        dialog.setContentPane(buildContent());
    }

    private void open() {
        dialog.pack();
        dialog.setMinimumSize(new Dimension(700, 620));
        dialog.setLocationRelativeTo(owner);
        dialog.setVisible(true);
    }

    private JPanel buildContent() {
        JPanel root = new JPanel(new BorderLayout(0, 10));
        root.setBackground(FlashTheme.SURFACE);
        root.setBorder(FlashTheme.pad(14, 18, 12, 18));

        JLabel intro = new JLabel("<html><body style='width:640px'>"
                + "Send feedback to <b>" + FeedbackReport.recipient() + "</b>. "
                + "FLASH prepares a local ZIP and opens an email draft; you review and attach the ZIP yourself. "
                + "Nothing is uploaded or sent automatically.</body></html>");
        intro.setForeground(FlashTheme.TEXT_PRIMARY);
        root.add(intro, BorderLayout.NORTH);

        JPanel body = new JPanel();
        body.setOpaque(false);
        body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
        body.add(buildMessagePanel());
        body.add(Box.createVerticalStrut(10));
        body.add(buildDiagnosticsPanel());
        body.add(Box.createVerticalStrut(10));
        body.add(buildAttachmentsPanel());

        JScrollPane bodyScroll = new JScrollPane(body);
        bodyScroll.setBorder(null);
        bodyScroll.getViewport().setBackground(FlashTheme.SURFACE);
        bodyScroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        root.add(bodyScroll, BorderLayout.CENTER);
        root.add(buildFooter(), BorderLayout.SOUTH);
        return root;
    }

    private JPanel buildMessagePanel() {
        JPanel panel = section("Feedback");
        JPanel form = new JPanel(new GridBagLayout());
        form.setOpaque(false);
        GridBagConstraints g = new GridBagConstraints();
        g.insets = new Insets(3, 3, 3, 6);
        g.anchor = GridBagConstraints.NORTHWEST;
        g.fill = GridBagConstraints.HORIZONTAL;

        g.gridx = 0; g.gridy = 0; g.weightx = 0;
        form.add(new JLabel("Category"), g);
        g.gridx = 1; g.weightx = 1;
        form.add(category, g);

        g.gridx = 0; g.gridy = 1; g.weightx = 0;
        form.add(new JLabel("Summary"), g);
        g.gridx = 1; g.weightx = 1;
        form.add(summary, g);

        g.gridx = 0; g.gridy = 2; g.weightx = 0;
        form.add(new JLabel("What happened?"), g);
        g.gridx = 1; g.weightx = 1; g.fill = GridBagConstraints.BOTH;
        JScrollPane messageScroll = new JScrollPane(message);
        messageScroll.setPreferredSize(new Dimension(560, 140));
        form.add(messageScroll, g);
        panel.add(form, BorderLayout.CENTER);
        return panel;
    }

    private JPanel buildDiagnosticsPanel() {
        JPanel panel = section("Optional diagnostics");
        JPanel choices = new JPanel();
        choices.setOpaque(false);
        choices.setLayout(new BoxLayout(choices, BoxLayout.Y_AXIS));
        choices.add(includeLog);
        choices.add(includeExceptions);
        choices.add(includeConsole);
        choices.add(includeSystem);
        choices.add(includeProject);
        choices.add(Box.createVerticalStrut(4));
        JLabel warning = new JLabel("<html><body style='width:610px'>"
                + "Diagnostic text can contain filenames and paths. FLASH redacts your home folder and email addresses, "
                + "caps each section, and never includes images or analysis outputs automatically. Use Preview to inspect the exact text."
                + "</body></html>");
        warning.setFont(warning.getFont().deriveFont(Font.PLAIN, 10f));
        warning.setForeground(FlashTheme.TEXT_MUTED);
        choices.add(warning);
        panel.add(choices, BorderLayout.CENTER);
        return panel;
    }

    private JPanel buildAttachmentsPanel() {
        JPanel panel = section("Saved diagnostic files");
        attachmentList.setVisibleRowCount(3);
        attachmentList.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        panel.add(new JScrollPane(attachmentList), BorderLayout.CENTER);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        buttons.setOpaque(false);
        JButton add = new JButton("Add Log/Exception files...");
        JButton remove = new JButton("Remove selected");
        add.addActionListener(e -> addAttachments());
        remove.addActionListener(e -> removeAttachments());
        buttons.add(add);
        buttons.add(remove);
        panel.add(buttons, BorderLayout.SOUTH);
        return panel;
    }

    private JPanel buildFooter() {
        JPanel footer = new JPanel(new BorderLayout());
        footer.setOpaque(false);
        JButton preview = new JButton("Preview exact bundle");
        JButton cancel = new JButton("Cancel");
        JButton prepare = new JButton("Prepare email");
        preview.addActionListener(e -> preview());
        cancel.addActionListener(e -> dialog.dispose());
        prepare.addActionListener(e -> prepareEmail());

        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        right.setOpaque(false);
        right.add(cancel);
        right.add(prepare);
        footer.add(preview, BorderLayout.WEST);
        footer.add(right, BorderLayout.EAST);
        return footer;
    }

    private static JPanel section(String title) {
        JPanel panel = new JPanel(new BorderLayout(0, 6));
        panel.setOpaque(false);
        panel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createTitledBorder(title), FlashTheme.pad(6)));
        panel.setAlignmentX(JPanel.LEFT_ALIGNMENT);
        return panel;
    }

    private static JCheckBox diagnosticCheckBox(String label, String text) {
        int chars = text == null ? 0 : text.length();
        JCheckBox box = new JCheckBox(label + (chars > 0 ? " (" + chars + " characters)" : " (not open)"),
                chars > 0);
        box.setEnabled(chars > 0);
        box.setOpaque(false);
        return box;
    }

    private void addAttachments() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Add saved FLASH/ImageJ diagnostic text");
        chooser.setMultiSelectionEnabled(true);
        chooser.setAcceptAllFileFilterUsed(false);
        chooser.setFileFilter(new FileNameExtensionFilter(
                "Text diagnostics (*.txt, *.log)", "txt", "log"));
        if (chooser.showOpenDialog(dialog) != JFileChooser.APPROVE_OPTION) return;
        File[] selected = chooser.getSelectedFiles();
        if (selected == null) return;
        for (File file : selected) {
            if (file != null && file.isFile() && !containsAttachment(file)) {
                attachments.addElement(file);
            }
        }
    }

    private boolean containsAttachment(File candidate) {
        String path = candidate.getAbsolutePath();
        for (int i = 0; i < attachments.size(); i++) {
            if (attachments.get(i).getAbsolutePath().equalsIgnoreCase(path)) return true;
        }
        return false;
    }

    private void removeAttachments() {
        int[] selected = attachmentList.getSelectedIndices();
        for (int i = selected.length - 1; i >= 0; i--) {
            attachments.remove(selected[i]);
        }
    }

    private FeedbackReport.Request request() {
        FeedbackReport.Request request = new FeedbackReport.Request();
        request.category = String.valueOf(category.getSelectedItem());
        request.summary = summary.getText().trim();
        request.message = message.getText().trim();
        request.includeLog = includeLog.isSelected();
        request.includeExceptions = includeExceptions.isSelected();
        request.includeConsole = includeConsole.isSelected();
        request.includeSystem = includeSystem.isSelected();
        request.includeProject = includeProject.isSelected();
        request.projectDirectory = projectDirectory;
        for (int i = 0; i < attachments.size(); i++) request.attachments.add(attachments.get(i));
        return request;
    }

    private LinkedHashMap<String, String> buildEntries(FeedbackReport.Request request) throws Exception {
        return FeedbackReport.build(request, FeedbackDiagnostics.capture());
    }

    private void preview() {
        try {
            LinkedHashMap<String, String> entries = buildEntries(request());
            JTextArea preview = new JTextArea(30, 90);
            preview.setEditable(false);
            preview.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
            preview.setText(FeedbackReport.preview(entries));
            preview.setCaretPosition(0);
            JOptionPane.showMessageDialog(dialog, new JScrollPane(preview),
                    "FLASH feedback bundle preview", JOptionPane.PLAIN_MESSAGE);
        } catch (Exception ex) {
            showError(ex);
        }
    }

    private void prepareEmail() {
        FeedbackReport.Request request = request();
        if (request.summary.isEmpty()) {
            JOptionPane.showMessageDialog(dialog, "Please add a short summary.",
                    "Summary required", JOptionPane.WARNING_MESSAGE);
            summary.requestFocusInWindow();
            return;
        }
        if (request.message.isEmpty()) {
            JOptionPane.showMessageDialog(dialog, "Please describe what happened or what you would like changed.",
                    "Description required", JOptionPane.WARNING_MESSAGE);
            message.requestFocusInWindow();
            return;
        }
        try {
            FeedbackDiagnostics.Snapshot diagnostics = FeedbackDiagnostics.capture();
            LinkedHashMap<String, String> entries = FeedbackReport.build(request, diagnostics);
            File bundle = FeedbackBundleWriter.writeDefault(entries);
            List<String> tags = FeedbackReport.tags(request, diagnostics);
            URI mailto = FeedbackMail.buildMailto(FeedbackReport.recipient(), request.category,
                    request.summary, join(tags), bundle.getAbsolutePath());
            copyToClipboard(bundle.getAbsolutePath());
            reveal(bundle);
            boolean opened = FeedbackMail.open(mailto);

            String result = "Diagnostic ZIP created:\n" + bundle.getAbsolutePath()
                    + "\n\nThe path has been copied to the clipboard. "
                    + (opened
                    ? "Attach the ZIP to the opened email draft, review it, and click Send."
                    : "No desktop mail application was available. Email the ZIP to "
                    + FeedbackReport.recipient() + ".");
            JOptionPane.showMessageDialog(dialog, result, "FLASH feedback prepared",
                    JOptionPane.INFORMATION_MESSAGE);
            dialog.dispose();
        } catch (Exception ex) {
            showError(ex);
        }
    }

    private void showError(Exception ex) {
        String detail = ex == null || ex.getMessage() == null ? "Unknown error" : ex.getMessage();
        IJ.log("[FLASH] Could not prepare feedback: " + detail);
        JOptionPane.showMessageDialog(dialog, "Could not prepare feedback:\n" + detail,
                "Feedback unavailable", JOptionPane.ERROR_MESSAGE);
    }

    private static void copyToClipboard(String value) {
        try {
            Toolkit.getDefaultToolkit().getSystemClipboard()
                    .setContents(new StringSelection(value == null ? "" : value), null);
        } catch (RuntimeException ignored) {
            // The path is also shown in the result dialog.
        }
    }

    private static void reveal(File bundle) {
        try {
            if (!Desktop.isDesktopSupported()) return;
            Desktop desktop = Desktop.getDesktop();
            if (!desktop.isSupported(Desktop.Action.OPEN)) return;
            File parent = bundle == null ? null : bundle.getAbsoluteFile().getParentFile();
            if (parent != null && parent.isDirectory()) desktop.open(parent);
        } catch (Exception ignored) {
            // The result dialog still exposes the bundle path.
        }
    }

    private static String join(List<String> values) {
        if (values == null || values.isEmpty()) return "none";
        StringBuilder out = new StringBuilder();
        for (String value : values) {
            if (out.length() > 0) out.append(", ");
            out.append(value);
        }
        return out.toString();
    }
}
