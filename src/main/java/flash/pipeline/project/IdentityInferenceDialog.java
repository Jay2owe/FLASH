package flash.pipeline.project;

import flash.pipeline.intelligence.identity.AnimalPrefixConditionStrategy;
import flash.pipeline.intelligence.identity.FieldRule;
import flash.pipeline.intelligence.identity.GrammarStrategy;
import flash.pipeline.intelligence.identity.IdentityResolver;
import flash.pipeline.intelligence.identity.IdentityStrategy;
import flash.pipeline.intelligence.identity.NamingGrammar;
import flash.pipeline.intelligence.identity.NamingGrammarStore;
import flash.pipeline.intelligence.identity.NthTokenFieldStrategy;
import flash.pipeline.intelligence.identity.ParentFolderConditionStrategy;
import flash.pipeline.intelligence.identity.SourceRecord;
import flash.pipeline.ui.FlashTheme;
import flash.pipeline.ui.PipelineDialog;
import ij.IJ;

import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JTextField;
import java.awt.GridLayout;
import java.awt.Window;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * One-click rule-inference presets (Stage 13 / C6) with a mandatory preview.
 * Each preset is an {@link IdentityStrategy}; the dialog runs it against the
 * current rows <em>without mutating them</em> ({@link PatternPreviewPanel}) and
 * only applies (auto-fill, confirmed cells preserved) when the user clicks OK.
 */
public final class IdentityInferenceDialog {

    private static final String PRESET_PARENT = "Parent folder → condition";
    private static final String PRESET_ANIMAL = "Animal-name prefix → condition";
    private static final String PRESET_TOKEN = "Nth filename token → field";
    private static final String PRESET_REGEX = "Regex capture → field";
    private static final String[] PRESETS = {PRESET_PARENT, PRESET_ANIMAL, PRESET_TOKEN, PRESET_REGEX};
    private static final String[] FIELD_TYPES = {"Animal", "Hemisphere", "Region", "Condition"};

    private final Window owner;
    private final ProjectManifestTableModel model;
    private final String projectDir;

    private final JComboBox<String> presetCombo = new JComboBox<String>(PRESETS);
    private final JComboBox<String> fieldCombo = new JComboBox<String>(FIELD_TYPES);
    private final JTextField tokenField = new JTextField("2", 4);
    private final JTextField axisField = new JTextField("Condition", 12);
    private final JTextField regexField = new JTextField("(?<=_)M\\d+", 18);
    private final PatternPreviewPanel preview = new PatternPreviewPanel();

    private IdentityInferenceDialog(Window owner, ProjectManifestTableModel model, String projectDir) {
        this.owner = owner;
        this.model = model;
        this.projectDir = projectDir;
    }

    public static void show(Window owner, ProjectManifestTableModel model, String projectDir) {
        new IdentityInferenceDialog(owner, model, projectDir).open();
    }

    private void open() {
        PipelineDialog pd = new PipelineDialog(owner, "Infer Identity From a Rule", PipelineDialog.Phase.SETUP);
        pd.addComponent(buildForm());
        pd.addComponent(new JLabel("Preview (no changes applied until you click OK):"));
        pd.addComponent(preview);
        refreshPreview();
        if (pd.showDialog()) {
            applyStrategy(buildStrategy());
        }
    }

    private JComponent buildForm() {
        JPanel form = new JPanel(new GridLayout(0, 2, 6, 4));
        form.setOpaque(false);
        form.add(headed("Rule:"));
        form.add(presetCombo);
        form.add(headed("Target field (token/regex):"));
        form.add(fieldCombo);
        form.add(headed("Token number (Nth token):"));
        form.add(tokenField);
        form.add(headed("Condition axis label:"));
        form.add(axisField);
        form.add(headed("Capture regex:"));
        form.add(regexField);

        JButton update = new JButton("Update preview");
        update.addActionListener(new ActionListener() {
            @Override public void actionPerformed(ActionEvent e) { refreshPreview(); }
        });
        JButton promote = new JButton("Save as grammar rule…");
        promote.addActionListener(new ActionListener() {
            @Override public void actionPerformed(ActionEvent e) { promoteToGrammar(); }
        });
        form.add(update);
        form.add(promote);
        presetCombo.addActionListener(new ActionListener() {
            @Override public void actionPerformed(ActionEvent e) { refreshPreview(); }
        });
        return form;
    }

    private JLabel headed(String text) {
        JLabel label = new JLabel(text);
        label.setForeground(FlashTheme.TEXT_HEADER);
        return label;
    }

    private IdentityStrategy buildStrategy() {
        String preset = String.valueOf(presetCombo.getSelectedItem());
        if (PRESET_PARENT.equals(preset)) {
            return new ParentFolderConditionStrategy();
        }
        if (PRESET_ANIMAL.equals(preset)) {
            return new AnimalPrefixConditionStrategy();
        }
        if (PRESET_TOKEN.equals(preset)) {
            return new NthTokenFieldStrategy(parsedToken(), fieldType(), axisField.getText().trim());
        }
        // regex capture -> a single-rule grammar
        return new GrammarStrategy(regexGrammar());
    }

    private NamingGrammar regexGrammar() {
        FieldRule rule = FieldRule.capture(fieldType(), axisField.getText().trim(), regexField.getText().trim());
        return new NamingGrammar("inference", Collections.singletonList(rule));
    }

    private FieldRule.Type fieldType() {
        try {
            return FieldRule.Type.valueOf(String.valueOf(fieldCombo.getSelectedItem()).toUpperCase());
        } catch (RuntimeException e) {
            return FieldRule.Type.CONDITION;
        }
    }

    private int parsedToken() {
        try {
            return Integer.parseInt(tokenField.getText().trim());
        } catch (NumberFormatException e) {
            return 1;
        }
    }

    private void refreshPreview() {
        try {
            preview.render(PatternPreviewPanel.previewStrategy(model, buildStrategy()));
        } catch (RuntimeException ex) {
            preview.render(null);
            IJ.log("[FLASH] Inference preview error: " + ex.getMessage());
        }
    }

    private void applyStrategy(IdentityStrategy strategy) {
        try {
            List<SourceRecord> records = model.buildSourceRecords();
            Map<SourceRecord, ?> resolved =
                    new IdentityResolver(Collections.singletonList(strategy)).resolve(records);
            @SuppressWarnings("unchecked")
            Map<SourceRecord, flash.pipeline.intelligence.identity.IdentityCandidate> typed =
                    (Map<SourceRecord, flash.pipeline.intelligence.identity.IdentityCandidate>) resolved;
            model.applyResolvedBatch(records, typed);
        } catch (RuntimeException ex) {
            JOptionPane.showMessageDialog(owner, "Could not apply rule: " + ex.getMessage(),
                    "Infer identity", JOptionPane.WARNING_MESSAGE);
        }
    }

    /** Promote a regex/token rule into a saved grammar (the C6 on-ramp). */
    private void promoteToGrammar() {
        String preset = String.valueOf(presetCombo.getSelectedItem());
        NamingGrammar grammar;
        if (PRESET_REGEX.equals(preset)) {
            grammar = regexGrammar();
        } else if (PRESET_TOKEN.equals(preset)) {
            // express "Nth token" as an anchored capture over underscore tokens
            StringBuilder re = new StringBuilder("^");
            for (int i = 1; i < parsedToken(); i++) re.append("[^_]*_");
            re.append("([^_]+)");
            grammar = new NamingGrammar("inference",
                    Collections.singletonList(FieldRule.capture(fieldType(), axisField.getText().trim(), re.toString())));
        } else {
            JOptionPane.showMessageDialog(owner,
                    "Only token/regex rules can be promoted to a reusable grammar.",
                    "Infer identity", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        String name = JOptionPane.showInputDialog(owner, "Save grammar as:", "inference");
        if (name == null || name.trim().isEmpty()) return;
        try {
            NamingGrammarStore.save(projectDir, new NamingGrammar(name.trim(), grammar.rules));
            IJ.log("[FLASH] Promoted inference rule to grammar '" + name.trim() + "'.");
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(owner, "Could not save grammar: " + ex.getMessage(),
                    "Infer identity", JOptionPane.WARNING_MESSAGE);
        }
    }
}
