package flash.pipeline.project;

import flash.pipeline.intelligence.identity.FieldRule;
import flash.pipeline.intelligence.identity.IdentityResolvers;
import flash.pipeline.intelligence.identity.NamingGrammar;
import flash.pipeline.intelligence.identity.NamingGrammarStore;
import flash.pipeline.intelligence.identity.SourceRecord;
import flash.pipeline.intelligence.identity.ValuePattern;
import flash.pipeline.ui.FlashTheme;
import flash.pipeline.ui.PipelineDialog;
import ij.IJ;

import javax.swing.DefaultCellEditor;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.table.DefaultTableModel;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Window;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Per-field naming-grammar editor (Stage 10): a rule table (capture regex or
 * alias value lists) with a live, non-mutating preview ({@link PatternPreviewPanel})
 * run against the current Project Builder rows. Grammars save/load to
 * {@code naming_grammars/}; OK applies the resolved values to the table
 * (auto-fill only — confirmed cells are preserved).
 */
public final class IdentityGrammarDialog {

    private static final String[] FIELD_TYPES = {"Animal", "Hemisphere", "Region", "Condition"};
    private static final String[] MODES = {"capture", "alias"};
    private static final String[] COLUMNS = {"Field", "Axis label", "Mode", "Pattern / Values"};

    private final Window owner;
    private final ProjectManifestTableModel model;
    private final String projectDir;
    private final JTextField nameField = new JTextField("My grammar", 18);
    private final DefaultTableModel rulesModel = new DefaultTableModel(COLUMNS, 0);
    private final JTable rulesTable = new JTable(rulesModel);
    private final PatternPreviewPanel preview = new PatternPreviewPanel();

    private IdentityGrammarDialog(Window owner, ProjectManifestTableModel model, String projectDir) {
        this.owner = owner;
        this.model = model;
        this.projectDir = projectDir;
        installRuleEditors();
    }

    /** Open the editor; on OK, the grammar's resolved values are applied to the model. */
    public static void show(Window owner, ProjectManifestTableModel model, String projectDir) {
        new IdentityGrammarDialog(owner, model, projectDir).open();
    }

    private void open() {
        PipelineDialog pd = new PipelineDialog(owner, "Identity Naming Grammar", PipelineDialog.Phase.SETUP);
        pd.addComponent(buildNameRow());
        pd.addComponent(buildRulesPanel());
        pd.addComponent(buildButtonRow());
        pd.addComponent(new JLabel("Live preview (no changes applied until you click OK):"));
        pd.addComponent(preview);
        refreshPreview();
        if (pd.showDialog()) {
            applyGrammarToModel();
        }
    }

    private JComponent buildNameRow() {
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        row.setOpaque(false);
        JLabel label = new JLabel("Grammar name:");
        label.setForeground(FlashTheme.TEXT_HEADER);
        row.add(label);
        row.add(nameField);
        return row;
    }

    private JComponent buildRulesPanel() {
        rulesTable.setRowHeight(22);
        JScrollPane scroll = new JScrollPane(rulesTable);
        scroll.setPreferredSize(new Dimension(620, 160));
        JPanel panel = new JPanel(new BorderLayout());
        panel.setOpaque(false);
        panel.add(scroll, BorderLayout.CENTER);
        return panel;
    }

    private JComponent buildButtonRow() {
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        row.setOpaque(false);

        JButton addRule = new JButton("Add rule");
        addRule.addActionListener(new ActionListener() {
            @Override public void actionPerformed(ActionEvent e) {
                rulesModel.addRow(new Object[]{"Animal", "", "capture", ""});
            }
        });
        JButton addAxis = new JButton("+ add condition axis");
        addAxis.addActionListener(new ActionListener() {
            @Override public void actionPerformed(ActionEvent e) {
                String axis = JOptionPane.showInputDialog(owner, "New condition axis label:", "Genotype");
                if (axis == null || axis.trim().isEmpty()) return;
                rulesModel.addRow(new Object[]{"Condition", axis.trim(), "alias", ""});
            }
        });
        JButton removeRule = new JButton("Remove rule");
        removeRule.addActionListener(new ActionListener() {
            @Override public void actionPerformed(ActionEvent e) {
                int r = rulesTable.getSelectedRow();
                if (r >= 0) rulesModel.removeRow(r);
            }
        });
        JButton update = new JButton("Update preview");
        update.addActionListener(new ActionListener() {
            @Override public void actionPerformed(ActionEvent e) {
                if (rulesTable.isEditing()) rulesTable.getCellEditor().stopCellEditing();
                refreshPreview();
            }
        });
        JButton save = new JButton("Save…");
        save.addActionListener(new ActionListener() {
            @Override public void actionPerformed(ActionEvent e) { saveGrammar(); }
        });
        JButton load = new JButton("Load…");
        load.addActionListener(new ActionListener() {
            @Override public void actionPerformed(ActionEvent e) { loadGrammar(); }
        });

        row.add(addRule);
        row.add(addAxis);
        row.add(removeRule);
        row.add(update);
        row.add(save);
        row.add(load);
        return row;
    }

    private void installRuleEditors() {
        rulesTable.getColumnModel().getColumn(0)
                .setCellEditor(new DefaultCellEditor(new JComboBox<String>(FIELD_TYPES)));
        rulesTable.getColumnModel().getColumn(2)
                .setCellEditor(new DefaultCellEditor(new JComboBox<String>(MODES)));
    }

    // ── Grammar build / parse ────────────────────────────────────────────────

    private NamingGrammar buildGrammar() {
        List<FieldRule> rules = new ArrayList<FieldRule>();
        for (int r = 0; r < rulesModel.getRowCount(); r++) {
            FieldRule.Type type = parseType(str(rulesModel.getValueAt(r, 0)));
            if (type == null) continue;
            String axisLabel = type == FieldRule.Type.CONDITION ? str(rulesModel.getValueAt(r, 1)) : "";
            String mode = str(rulesModel.getValueAt(r, 2));
            String spec = str(rulesModel.getValueAt(r, 3)).trim();
            if (spec.isEmpty()) continue;
            if ("alias".equalsIgnoreCase(mode)) {
                List<ValuePattern> vps = parseValuePatterns(spec);
                if (!vps.isEmpty()) rules.add(FieldRule.alias(type, axisLabel, vps));
            } else {
                rules.add(FieldRule.capture(type, axisLabel, spec));
            }
        }
        return new NamingGrammar(nameField.getText().trim(), rules);
    }

    private void populateFrom(NamingGrammar grammar) {
        rulesModel.setRowCount(0);
        if (grammar == null) return;
        nameField.setText(grammar.name);
        for (FieldRule rule : grammar.rules) {
            String mode = rule.isCapture() ? "capture" : "alias";
            String spec = rule.isCapture()
                    ? (rule.capture == null ? "" : rule.capture.pattern())
                    : encodeValuePatterns(rule.values);
            rulesModel.addRow(new Object[]{
                    capitalise(rule.type.name()), rule.axisLabel == null ? "" : rule.axisLabel, mode, spec});
        }
    }

    /** Parse {@code "LH=re1|re2; RH=re3"} into value patterns. */
    static List<ValuePattern> parseValuePatterns(String spec) {
        List<ValuePattern> out = new ArrayList<ValuePattern>();
        if (spec == null) return out;
        for (String part : spec.split(";")) {
            int eq = part.indexOf('=');
            if (eq < 0) continue;
            String canonical = part.substring(0, eq).trim();
            if (canonical.isEmpty()) continue;
            List<String> regexes = new ArrayList<String>();
            for (String re : part.substring(eq + 1).split("\\|")) {
                String t = re.trim();
                if (!t.isEmpty()) regexes.add(t);
            }
            if (regexes.isEmpty()) regexes.add(java.util.regex.Pattern.quote(canonical));
            out.add(new ValuePattern(canonical, regexes));
        }
        return out;
    }

    private static String encodeValuePatterns(List<ValuePattern> values) {
        StringBuilder sb = new StringBuilder();
        if (values == null) return "";
        for (ValuePattern vp : values) {
            if (sb.length() > 0) sb.append("; ");
            sb.append(vp.canonical).append('=');
            for (int i = 0; i < vp.patterns.size(); i++) {
                if (i > 0) sb.append('|');
                sb.append(vp.patterns.get(i).pattern());
            }
        }
        return sb.toString();
    }

    private static FieldRule.Type parseType(String s) {
        if (s == null) return null;
        try {
            return FieldRule.Type.valueOf(s.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    // ── Preview / apply / persistence ────────────────────────────────────────

    private void refreshPreview() {
        try {
            preview.render(PatternPreviewPanel.previewGrammar(model, buildGrammar()));
        } catch (RuntimeException ex) {
            preview.render(null);
            IJ.log("[FLASH] Grammar preview error: " + ex.getMessage());
        }
    }

    private void applyGrammarToModel() {
        try {
            NamingGrammar grammar = buildGrammar();
            List<SourceRecord> records = model.buildSourceRecords();
            model.applyResolvedBatch(records, IdentityResolvers.standard(grammar).resolve(records));
        } catch (RuntimeException ex) {
            JOptionPane.showMessageDialog(owner, "Could not apply grammar: " + ex.getMessage(),
                    "Identity grammar", JOptionPane.WARNING_MESSAGE);
        }
    }

    private void saveGrammar() {
        if (rulesTable.isEditing()) rulesTable.getCellEditor().stopCellEditing();
        try {
            NamingGrammarStore.save(projectDir, buildGrammar());
            IJ.log("[FLASH] Grammar saved: " + nameField.getText().trim());
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(owner, "Could not save grammar: " + ex.getMessage(),
                    "Identity grammar", JOptionPane.WARNING_MESSAGE);
        }
    }

    private void loadGrammar() {
        List<String> names = NamingGrammarStore.listNames(projectDir);
        if (names.isEmpty()) {
            JOptionPane.showMessageDialog(owner, "No saved grammars yet.",
                    "Identity grammar", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        String chosen = (String) JOptionPane.showInputDialog(owner, "Load grammar:", "Identity grammar",
                JOptionPane.QUESTION_MESSAGE, null, names.toArray(new String[names.size()]), names.get(0));
        if (chosen == null) return;
        NamingGrammar grammar = NamingGrammarStore.loadIfExists(projectDir, chosen);
        if (grammar != null) {
            populateFrom(grammar);
            refreshPreview();
        }
    }

    private static String str(Object o) {
        return o == null ? "" : o.toString();
    }

    private static String capitalise(String s) {
        if (s == null || s.isEmpty()) return "";
        return s.substring(0, 1).toUpperCase() + s.substring(1).toLowerCase();
    }
}
