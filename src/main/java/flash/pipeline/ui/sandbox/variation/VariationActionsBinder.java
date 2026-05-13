package flash.pipeline.ui.sandbox.variation;

import flash.pipeline.image.dag.DagIR;
import flash.pipeline.image.dag.DagToIjmEmitter;
import flash.pipeline.image.variation.VariantPlan;
import ij.IJ;
import ij.plugin.frame.Recorder;

import javax.swing.JOptionPane;
import java.awt.Component;
import java.awt.GraphicsEnvironment;
import java.text.SimpleDateFormat;
import java.util.Date;

public final class VariationActionsBinder implements TileActionListener {

    public interface Target {
        void promote(DagIR dag, String label);
    }

    public interface StatusSink {
        void setStatus(String text);
    }

    private final Target target;
    private final Component owner;
    private final VariationSessionLog sessionLog;
    private final VariationPresetWriter presetWriter;
    private final String sourceTitle;
    private final StatusSink statusSink;

    public VariationActionsBinder(Target target,
                                  Component owner,
                                  VariationSessionLog sessionLog,
                                  VariationPresetWriter presetWriter,
                                  String sourceTitle,
                                  StatusSink statusSink) {
        if (target == null) throw new IllegalArgumentException("target must not be null");
        this.target = target;
        this.owner = owner;
        this.sessionLog = sessionLog;
        this.presetWriter = presetWriter;
        this.sourceTitle = sourceTitle == null || sourceTitle.trim().isEmpty()
                ? "source"
                : sourceTitle;
        this.statusSink = statusSink;
    }

    @Override
    public void onPromote(VariantPlan plan) {
        if (plan == null || plan.dag == null) return;
        target.promote(plan.dag, plan.label);
        if (sessionLog != null) sessionLog.recordPromote(plan);
        if (Recorder.record) {
            Recorder.recordString("// flash variation: promoted " + safeLabel(plan.label) + "\n");
            Recorder.recordString(DagToIjmEmitter.emit(plan.dag));
        }
        showStatus("Applied variation: " + safeLabel(plan.label));
    }

    @Override
    public void onSavePreset(VariantPlan plan) {
        if (plan == null || plan.dag == null) return;
        if (presetWriter == null) {
            showStatus("Variation preset saving is not available here.");
            return;
        }
        if (GraphicsEnvironment.isHeadless()) {
            showStatus("Variation preset saving needs a visible dialog.");
            return;
        }
        String defaultName = defaultPresetName(sourceTitle, plan.label, new Date());
        String name = (String) JOptionPane.showInputDialog(owner,
                "Preset name:",
                "Save variation preset",
                JOptionPane.PLAIN_MESSAGE,
                null,
                null,
                defaultName);
        if (name == null || name.trim().isEmpty()) return;
        try {
            savePreset(plan, name);
        } catch (Exception ex) {
            IJ.showMessage("Variations", "Could not save preset:\n" + ex.getMessage());
        }
    }

    public String savePreset(VariantPlan plan, String name) throws Exception {
        if (plan == null || plan.dag == null) throw new IllegalArgumentException("plan must not be null");
        if (presetWriter == null) throw new IllegalStateException("presetWriter must not be null");
        if (name == null || name.trim().isEmpty()) {
            throw new IllegalArgumentException("name must not be empty");
        }
        String safeName = sanitize(name);
        presetWriter.savePreset(safeName, plan);
        if (sessionLog != null) sessionLog.recordSavePreset(plan, safeName);
        if (Recorder.record) {
            Recorder.recordString("// flash variation: saved preset " + safeName + "\n");
        }
        showStatus("Saved variation preset: " + safeName);
        return safeName;
    }

    static String defaultPresetName(String sourceTitle, String label, Date date) {
        String time = new SimpleDateFormat("HHmm").format(date == null ? new Date() : date);
        return sanitize(sourceTitle) + "_" + sanitize(label) + "_" + time;
    }

    static String sanitize(String value) {
        String v = value == null ? "" : value.trim();
        if (v.isEmpty()) return "variation";
        v = v.replaceAll("[^A-Za-z0-9._=-]+", "_");
        v = v.replaceAll("_+", "_");
        while (v.startsWith("_")) v = v.substring(1);
        while (v.endsWith("_")) v = v.substring(0, v.length() - 1);
        return v.length() == 0 ? "variation" : v;
    }

    private void showStatus(String text) {
        if (statusSink != null) statusSink.setStatus(text);
    }

    private static String safeLabel(String label) {
        return label == null || label.trim().isEmpty() ? "(unlabelled)" : label;
    }
}
