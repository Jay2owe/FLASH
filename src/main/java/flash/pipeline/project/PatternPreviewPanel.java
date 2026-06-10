package flash.pipeline.project;

import flash.pipeline.intelligence.identity.Confidence;
import flash.pipeline.intelligence.identity.FieldValue;
import flash.pipeline.intelligence.identity.IdentityCandidate;
import flash.pipeline.intelligence.identity.IdentityResolver;
import flash.pipeline.intelligence.identity.IdentityResolvers;
import flash.pipeline.intelligence.identity.IdentityStrategy;
import flash.pipeline.intelligence.identity.NamingGrammar;
import flash.pipeline.intelligence.identity.SourceRecord;
import flash.pipeline.naming.ConditionAxis;
import flash.pipeline.ui.FlashTheme;

import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.table.DefaultTableModel;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reusable live-preview / before-after diff widget for identity detection
 * (Stages 10 and 13). Runs a grammar or strategy against the current
 * {@link ProjectManifestTableModel} rows <em>without mutating them</em> and shows
 * the resolved values, their confidence, and how many cells would change.
 */
public final class PatternPreviewPanel extends JPanel {

    private static final String[] FIXED_FIELDS = {"animal", "hemisphere", "region"};
    private static final String[] FIXED_LABELS = {"Animal", "Hemisphere", "Region"};

    /** One detected field value in a preview. */
    public static final class FieldCell {
        public final String value;
        public final Confidence confidence;
        public final String provenance;

        FieldCell(String value, Confidence confidence, String provenance) {
            this.value = value == null ? "" : value;
            this.confidence = confidence == null ? Confidence.NONE : confidence;
            this.provenance = provenance == null ? "" : provenance;
        }
    }

    /** Immutable result of a preview: per-row before/after values + change/conflict tallies. */
    public static final class Outcome {
        public final List<String> fieldIds;
        public final List<String> fieldLabels;
        public final List<String> rowLabels;
        public final List<Map<String, String>> before;
        public final List<Map<String, FieldCell>> after;
        public final int changes;
        public final int conflicts;

        Outcome(List<String> fieldIds, List<String> fieldLabels, List<String> rowLabels,
                List<Map<String, String>> before, List<Map<String, FieldCell>> after,
                int changes, int conflicts) {
            this.fieldIds = fieldIds;
            this.fieldLabels = fieldLabels;
            this.rowLabels = rowLabels;
            this.before = before;
            this.after = after;
            this.changes = changes;
            this.conflicts = conflicts;
        }
    }

    private final JLabel summary = new JLabel(" ");
    private final DefaultTableModel previewTableModel = new DefaultTableModel();
    private final JTable previewTable = new JTable(previewTableModel);

    public PatternPreviewPanel() {
        super(new BorderLayout(0, 4));
        setOpaque(false);
        summary.setForeground(FlashTheme.TEXT_MUTED);
        previewTable.setRowHeight(20);
        previewTable.setEnabled(false);
        JScrollPane scroll = new JScrollPane(previewTable);
        scroll.setPreferredSize(new Dimension(620, 200));
        scroll.setBorder(BorderFactory.createLineBorder(FlashTheme.BORDER));
        add(summary, BorderLayout.NORTH);
        add(scroll, BorderLayout.CENTER);
    }

    // ── No-mutate compute ───────────────────────────────────────────────────

    /** Preview the model resolved by the standard stack with {@code grammar} on top. Does not mutate the model. */
    public static Outcome previewGrammar(ProjectManifestTableModel model, NamingGrammar grammar) {
        return previewResolved(model, IdentityResolvers.standard(grammar));
    }

    /** Preview the model resolved by a single {@code strategy} (Stage 13 presets). Does not mutate the model. */
    public static Outcome previewStrategy(ProjectManifestTableModel model, IdentityStrategy strategy) {
        return previewResolved(model, new IdentityResolver(Collections.singletonList(strategy)));
    }

    private static Outcome previewResolved(ProjectManifestTableModel model, IdentityResolver resolver) {
        List<String> fieldIds = new ArrayList<String>(Arrays.asList(FIXED_FIELDS));
        List<String> fieldLabels = new ArrayList<String>(Arrays.asList(FIXED_LABELS));
        for (ConditionAxis axis : model.conditionAxes()) {
            fieldIds.add(axis.id);
            fieldLabels.add(axis.label == null || axis.label.trim().isEmpty() ? axis.id : axis.label);
        }

        List<String> rowLabels = new ArrayList<String>();
        List<Map<String, String>> before = new ArrayList<Map<String, String>>();
        List<Map<String, FieldCell>> after = new ArrayList<Map<String, FieldCell>>();
        int changes = 0;
        int conflicts = 0;

        List<SourceRecord> records = model.buildSourceRecords();
        Map<SourceRecord, IdentityCandidate> resolved = resolver.resolve(records);

        for (int idx = 0; idx < records.size(); idx++) {
            SourceRecord rec = records.get(idx);
            IdentityCandidate candidate = resolved.get(rec);
            rowLabels.add(rec.seed == null || rec.seed.isEmpty() ? "(row " + (idx + 1) + ")" : rec.seed);

            Map<String, String> beforeRow = new LinkedHashMap<String, String>();
            Map<String, FieldCell> afterRow = new LinkedHashMap<String, FieldCell>();
            for (String field : fieldIds) {
                int col = columnForField(model, field);
                String current = col < 0 ? "" : stringValue(model.getValueAt(idx, col));
                beforeRow.put(field, current);

                FieldValue fv = candidate == null ? null : fieldValue(candidate, field);
                if (fv != null && !fv.isBlank()) {
                    afterRow.put(field, new FieldCell(fv.value, fv.confidence, fv.provenance));
                    if (!fv.value.equals(current)) changes++;
                    if (!current.trim().isEmpty() && !fv.value.equals(current)) conflicts++;
                }
            }
            before.add(beforeRow);
            after.add(afterRow);
        }
        return new Outcome(fieldIds, fieldLabels, rowLabels, before, after, changes, conflicts);
    }

    private static int columnForField(ProjectManifestTableModel model, String field) {
        if ("animal".equals(field)) return ProjectManifestTableModel.COL_ANIMAL;
        if ("hemisphere".equals(field)) return ProjectManifestTableModel.COL_HEMISPHERE;
        if ("region".equals(field)) return ProjectManifestTableModel.COL_REGION;
        return model.conditionColumnForAxis(field);
    }

    private static FieldValue fieldValue(IdentityCandidate c, String field) {
        if ("animal".equals(field)) return c.getAnimal();
        if ("hemisphere".equals(field)) return c.getHemisphere();
        if ("region".equals(field)) return c.getRegion();
        return c.getCondition(field);
    }

    private static String stringValue(Object value) {
        return value == null ? "" : value.toString();
    }

    // ── Render ───────────────────────────────────────────────────────────────

    /** Render an outcome into the widget (current → detected per field, with a change summary). */
    public void render(Outcome outcome) {
        if (outcome == null) {
            summary.setText("No preview.");
            previewTableModel.setDataVector(new Object[0][0], new Object[]{"Row"});
            return;
        }
        List<String> columns = new ArrayList<String>();
        columns.add("Row");
        columns.addAll(outcome.fieldLabels);
        Object[][] data = new Object[outcome.rowLabels.size()][columns.size()];
        for (int r = 0; r < outcome.rowLabels.size(); r++) {
            data[r][0] = outcome.rowLabels.get(r);
            for (int f = 0; f < outcome.fieldIds.size(); f++) {
                String id = outcome.fieldIds.get(f);
                String cur = outcome.before.get(r).get(id);
                FieldCell cell = outcome.after.get(r).get(id);
                data[r][f + 1] = cellText(cur, cell);
            }
        }
        previewTableModel.setDataVector(data, columns.toArray());
        summary.setText("Preview: would change " + outcome.changes + " value(s)"
                + (outcome.conflicts > 0 ? "; " + outcome.conflicts + " conflict(s) with existing values" : "")
                + ".");
    }

    private static String cellText(String current, FieldCell detected) {
        String cur = current == null ? "" : current;
        if (detected == null || detected.value.isEmpty()) {
            return cur;
        }
        if (detected.value.equals(cur)) {
            return detected.value + " (" + detected.confidence.name().toLowerCase() + ")";
        }
        String left = cur.isEmpty() ? "—" : cur;
        return left + " → " + detected.value + " (" + detected.confidence.name().toLowerCase() + ")";
    }
}
