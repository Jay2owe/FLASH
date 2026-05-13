package flash.pipeline.ui.sandbox.variation;

import flash.pipeline.image.variation.VariantPlan;
import flash.pipeline.image.variation.VariantResult;

import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.table.AbstractTableModel;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.Window;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class VariationSessionLog {

    private final List<LogEntry> entries = new ArrayList<LogEntry>();

    public void recordGenerate(List<VariantResult> results) {
        int total = results == null ? 0 : results.size();
        int failed = 0;
        if (results != null) {
            for (int i = 0; i < results.size(); i++) {
                VariantResult result = results.get(i);
                if (result != null && !result.isSuccess()) failed++;
            }
        }
        Map<String, String> detail = new LinkedHashMap<String, String>();
        detail.put("total", Integer.toString(total));
        detail.put("failed", Integer.toString(failed));
        record(new LogEntry(Instant.now(),
                "GENERATE",
                "Generated " + total + " variation(s)",
                detail));
    }

    public void recordEliminate(VariantPlan plan, String label) {
        record(planEntry("ELIMINATE", plan, "Eliminated " + safeLabel(plan, label)));
    }

    public void recordPromote(VariantPlan plan) {
        record(planEntry("PROMOTE", plan, "Promoted " + safeLabel(plan, null)));
    }

    public void recordSavePreset(VariantPlan plan, String presetName) {
        Map<String, String> detail = planDetail(plan);
        detail.put("preset", presetName == null ? "" : presetName);
        record(new LogEntry(Instant.now(),
                "SAVE_PRESET",
                "Saved preset " + (presetName == null ? "" : presetName),
                detail));
    }

    public void record(LogEntry entry) {
        if (entry != null) entries.add(entry);
    }

    public void clear() {
        entries.clear();
    }

    public List<LogEntry> entries() {
        return Collections.unmodifiableList(entries);
    }

    public JDialog showViewer(Window owner) {
        final LogTableModel model = new LogTableModel();
        final JTable table = new JTable(model);
        table.setFillsViewportHeight(true);

        JDialog dialog = owner == null
                ? new JDialog((java.awt.Frame) null, "Variation log", false)
                : new JDialog(owner, "Variation log", java.awt.Dialog.ModalityType.MODELESS);
        dialog.setLayout(new BorderLayout(6, 6));
        dialog.add(new JScrollPane(table), BorderLayout.CENTER);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        JButton clear = new JButton("Clear log");
        clear.addActionListener(e -> {
            VariationSessionLog.this.clear();
            model.fireTableDataChanged();
        });
        JButton close = new JButton("Close");
        close.addActionListener(e -> dialog.dispose());
        buttons.add(clear);
        buttons.add(close);
        dialog.add(buttons, BorderLayout.SOUTH);
        dialog.setSize(680, 320);
        dialog.setLocationRelativeTo(owner);
        dialog.setVisible(true);
        return dialog;
    }

    private LogEntry planEntry(String action, VariantPlan plan, String summary) {
        return new LogEntry(Instant.now(), action, summary, planDetail(plan));
    }

    private static Map<String, String> planDetail(VariantPlan plan) {
        Map<String, String> detail = new LinkedHashMap<String, String>();
        if (plan == null) return detail;
        detail.put("label", plan.label);
        for (Map.Entry<String, String> entry : plan.paramDelta.entrySet()) {
            detail.put(entry.getKey(), entry.getValue());
        }
        return detail;
    }

    private static String safeLabel(VariantPlan plan, String fallback) {
        if (plan != null && plan.label != null && plan.label.length() > 0) return plan.label;
        return fallback == null ? "(unlabelled)" : fallback;
    }

    public static final class LogEntry {
        public final Instant timestamp;
        public final String action;
        public final String summary;
        public final Map<String, String> detail;

        public LogEntry(Instant timestamp,
                        String action,
                        String summary,
                        Map<String, String> detail) {
            this.timestamp = timestamp == null ? Instant.now() : timestamp;
            this.action = action == null ? "" : action;
            this.summary = summary == null ? "" : summary;
            this.detail = detail == null
                    ? Collections.<String, String>emptyMap()
                    : Collections.unmodifiableMap(new LinkedHashMap<String, String>(detail));
        }
    }

    private final class LogTableModel extends AbstractTableModel {
        private final String[] columns = { "Time", "Action", "Summary" };

        @Override public int getRowCount() {
            return entries.size();
        }

        @Override public int getColumnCount() {
            return columns.length;
        }

        @Override public String getColumnName(int column) {
            return columns[column];
        }

        @Override public Object getValueAt(int rowIndex, int columnIndex) {
            LogEntry entry = entries.get(rowIndex);
            switch (columnIndex) {
                case 0: return entry.timestamp.toString();
                case 1: return entry.action;
                default: return entry.summary;
            }
        }
    }
}
