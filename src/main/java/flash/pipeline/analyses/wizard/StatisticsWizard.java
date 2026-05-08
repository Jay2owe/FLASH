package flash.pipeline.analyses.wizard;

import flash.pipeline.analyses.StatisticsConfig;
import flash.pipeline.ui.PipelineDialog;
import flash.pipeline.ui.ToggleSwitch;
import flash.pipeline.ui.wizard.WizardFlow;

import javax.swing.JComboBox;
import javax.swing.JTextField;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Intent-led setup helper for Statistical Analysis.
 * <p>
 * The wizard collects experimental-design intent (paired vs independent),
 * distribution caution, and post-hoc preference, then writes a
 * {@link StatisticsConfig} into the calling main panel via the shared
 * {@link MainPanelBinding}. The same answers can be derived from a static
 * answer map for headless / CLI use, keeping the wizard unit-testable.
 */
public class StatisticsWizard extends WizardFlow {

    public static final String DESIGN_INDEPENDENT = "Independent";
    public static final String DESIGN_PAIRED_LH_RH = "Paired LH vs RH";
    public static final String DESIGN_PAIRED_REGIONS = "Paired across regions";
    public static final String DESIGN_PAIRED_REPEATED = "Paired repeated measures (disabled)";

    public static final String DIST_AUTO = "Automatic (D'Agostino-Pearson per metric, default)";
    public static final String DIST_NORMAL = "Assume normal";
    public static final String DIST_SKEWED = "Assume skewed";

    public static final String POSTHOC_BONFERRONI = "Bonferroni (default)";
    public static final String POSTHOC_TUKEY = "Tukey HSD";
    public static final String POSTHOC_DUNNS = "Dunn's test";
    public static final String POSTHOC_NONE = "None - raw p-values";

    public static final String METRIC_ALL = "All metrics (default)";
    public static final String METRIC_SELECTED = "Only selected metrics";

    public static final String CONFIG_FIELD_ID = "statistics.config";

    private final AggregationConfig aggCfg;
    private final List<String> availableMetrics;
    private final int conditionCount;
    private final List<Integer> groupSizes;

    public StatisticsWizard(MainPanelBinding panel,
                            AggregationConfig aggCfg,
                            List<String> availableMetrics,
                            int conditionCount,
                            List<Integer> groupSizes,
                            boolean headless) {
        super("Statistics Helper", panel, headless);
        this.aggCfg = aggCfg == null ? new AggregationConfig() : aggCfg;
        this.availableMetrics = availableMetrics == null
                ? Collections.<String>emptyList()
                : new ArrayList<String>(availableMetrics);
        this.conditionCount = Math.max(0, conditionCount);
        this.groupSizes = groupSizes == null
                ? Collections.<Integer>emptyList()
                : new ArrayList<Integer>(groupSizes);
        register(new DesignScreen());
        register(new GroupSummaryScreen());
        register(new DistributionScreen());
        register(new PostHocScreen());
        register(new MetricScopeScreen());
    }

    public StatisticsWizard(MainPanelBinding panel,
                            AggregationConfig aggCfg,
                            List<String> availableMetrics,
                            int conditionCount,
                            boolean headless) {
        this(panel, aggCfg, availableMetrics, conditionCount,
                Collections.<Integer>emptyList(), headless);
    }

    public StatisticsConfig deriveCurrentConfig() {
        return deriveConfig(currentAnswers(), aggCfg, availableMetrics);
    }

    @Override
    protected PipelineDialog createDialog(Screen screen, int screenIndex, boolean finish) {
        PipelineDialog dialog = super.createDialog(screen, screenIndex, finish);
        dialog.setBreadcrumb(PipelineDialog.Phase.EXPORT, null);
        return dialog;
    }

    /**
     * Pure-function answer-map &rarr; {@link StatisticsConfig} derivation.
     * <p>
     * Empty or missing answers yield the engine's default behaviour:
     * unpaired, automatic K&sup2; normality gate, Bonferroni-corrected
     * pairwise post-hoc, every numeric metric column tested.
     */
    public static StatisticsConfig deriveConfig(Map<String, Object> answers,
                                                AggregationConfig aggCfg,
                                                List<String> availableMetrics) {
        Map<String, Object> safeAnswers = answers == null
                ? new LinkedHashMap<String, Object>()
                : answers;
        StatisticsConfig cfg = new StatisticsConfig();
        cfg.pairedMode = parsePairedAnswer(safeAnswers);
        cfg.distributionMode = parseDistributionAnswer(safeAnswers);
        cfg.postHocMethod = parsePostHocAnswer(safeAnswers, cfg.distributionMode);
        cfg.metricFilter = parseMetricFilterAnswer(safeAnswers, availableMetrics);
        return cfg;
    }

    /** Builds a {@link StatisticsConfig} from a saved {@link StatisticsPreset}. */
    public static StatisticsConfig fromPreset(StatisticsPreset preset) {
        if (preset == null) {
            return new StatisticsConfig();
        }
        return preset.toConfig();
    }

    public static String[] designOptions() {
        return new String[]{
                DESIGN_INDEPENDENT,
                DESIGN_PAIRED_LH_RH,
                DESIGN_PAIRED_REGIONS
        };
    }

    public static String[] distributionOptions() {
        return new String[]{DIST_AUTO, DIST_NORMAL, DIST_SKEWED};
    }

    public static String[] postHocOptions(StatisticsConfig.DistributionMode distribution) {
        List<String> out = new ArrayList<String>();
        out.add(POSTHOC_BONFERRONI);
        if (isTukeyEnabled(distribution)) {
            out.add(POSTHOC_TUKEY);
        }
        if (isDunnsEnabled(distribution)) {
            out.add(POSTHOC_DUNNS);
        }
        out.add(POSTHOC_NONE);
        return out.toArray(new String[out.size()]);
    }

    public static boolean isTukeyEnabled(StatisticsConfig.DistributionMode distribution) {
        return distribution != StatisticsConfig.DistributionMode.ASSUME_SKEWED;
    }

    public static boolean isDunnsEnabled(StatisticsConfig.DistributionMode distribution) {
        return distribution != StatisticsConfig.DistributionMode.ASSUME_NORMAL;
    }

    /**
     * @return hint text describing why a paired option may not work with the
     *         supplied aggregation granularity, or empty string when no hint
     *         is needed.
     */
    public static String pairedDesignHint(String designOption, AggregationConfig aggCfg) {
        if (aggCfg == null) return "";
        if (DESIGN_PAIRED_LH_RH.equals(designOption)
                && aggCfg.getGranularity() != AggregationConfig.Granularity.HEMISPHERE) {
            return "(requires per-hemisphere aggregation)";
        }
        if (DESIGN_PAIRED_REGIONS.equals(designOption)
                && aggCfg.getGranularity() != AggregationConfig.Granularity.REGION) {
            return "(requires per-region aggregation)";
        }
        return "";
    }

    private static StatisticsConfig.PairedMode parsePairedAnswer(Map<String, Object> answers) {
        String choice = answerString(answers, "design.choice", DESIGN_INDEPENDENT);
        if (DESIGN_PAIRED_LH_RH.equals(choice)) {
            return StatisticsConfig.PairedMode.HEMISPHERE;
        }
        if (DESIGN_PAIRED_REGIONS.equals(choice)) {
            return StatisticsConfig.PairedMode.REGION;
        }
        if (DESIGN_PAIRED_REPEATED.equals(choice)) {
            return StatisticsConfig.PairedMode.SESSION;
        }
        return StatisticsConfig.PairedMode.OFF;
    }

    private static StatisticsConfig.DistributionMode parseDistributionAnswer(Map<String, Object> answers) {
        String choice = answerString(answers, "distribution.choice", DIST_AUTO);
        if (DIST_NORMAL.equals(choice)) {
            return StatisticsConfig.DistributionMode.ASSUME_NORMAL;
        }
        if (DIST_SKEWED.equals(choice)) {
            return StatisticsConfig.DistributionMode.ASSUME_SKEWED;
        }
        return StatisticsConfig.DistributionMode.AUTO;
    }

    private static StatisticsConfig.PostHocMethod parsePostHocAnswer(Map<String, Object> answers,
                                                                     StatisticsConfig.DistributionMode dist) {
        String choice = answerString(answers, "posthoc.choice", POSTHOC_BONFERRONI);
        if (POSTHOC_TUKEY.equals(choice) && isTukeyEnabled(dist)) {
            return StatisticsConfig.PostHocMethod.TUKEY;
        }
        if (POSTHOC_DUNNS.equals(choice) && isDunnsEnabled(dist)) {
            return StatisticsConfig.PostHocMethod.DUNNS;
        }
        if (POSTHOC_NONE.equals(choice)) {
            return StatisticsConfig.PostHocMethod.NONE;
        }
        return StatisticsConfig.PostHocMethod.BONFERRONI;
    }

    private static List<String> parseMetricFilterAnswer(Map<String, Object> answers,
                                                        List<String> availableMetrics) {
        String scope = answerString(answers, "metric.scope", METRIC_ALL);
        if (!METRIC_SELECTED.equals(scope)) {
            return null;
        }

        List<String> selected = new ArrayList<String>();
        if (availableMetrics != null) {
            for (int i = 0; i < availableMetrics.size(); i++) {
                String metric = availableMetrics.get(i);
                if (metric == null) continue;
                if (booleanAnswer(answers, "metric." + i, false)) {
                    selected.add(metric);
                }
            }
        }
        if (!selected.isEmpty()) {
            return selected;
        }

        String free = answerString(answers, "metric.freeform", "");
        if (free.trim().isEmpty()) {
            return null;
        }
        List<String> out = new ArrayList<String>();
        for (String token : free.split(",")) {
            String trimmed = token.trim();
            if (!trimmed.isEmpty()) {
                out.add(trimmed);
            }
        }
        return out.isEmpty() ? null : out;
    }

    private static String answerString(Map<String, Object> answers, String key, String fallback) {
        if (answers == null) return fallback;
        Object value = answers.get(key);
        if (value == null) return fallback;
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? fallback : text;
    }

    private static boolean booleanAnswer(Map<String, Object> answers, String key, boolean fallback) {
        if (answers == null || !answers.containsKey(key)) return fallback;
        Object value = answers.get(key);
        if (value instanceof Boolean) return ((Boolean) value).booleanValue();
        return Boolean.parseBoolean(String.valueOf(value));
    }

    private final class DesignScreen extends Screen {
        private DesignScreen() {
            super("What kind of comparison are you running?");
            defaultAnswer("design.choice", DESIGN_INDEPENDENT);
        }

        @Override
        public void build(PipelineDialog dialog, AnswerMap answers) {
            dialog.addHeader("What kind of comparison are you running?");
            String selected = answers.getString("design.choice", DESIGN_INDEPENDENT);
            String[] options = designOptions();
            if (Arrays.asList(options).indexOf(selected) < 0) {
                selected = DESIGN_INDEPENDENT;
            }
            dialog.addChoice("Experimental design", options, selected);
            String hemiHint = pairedDesignHint(DESIGN_PAIRED_LH_RH, aggCfg);
            if (!hemiHint.isEmpty()) {
                dialog.addHelpText("Paired LH vs RH " + hemiHint);
            }
            String regionHint = pairedDesignHint(DESIGN_PAIRED_REGIONS, aggCfg);
            if (!regionHint.isEmpty()) {
                dialog.addHelpText("Paired across regions " + regionHint);
            }
            dialog.addHelpText("Repeated measures (per-session) is not yet supported by aggregation.");
        }

        @Override
        public void read(PipelineDialog dialog, AnswerMap answers) {
            answers.put("design.choice", dialog.getNextChoice());
        }

        @Override
        public void writeTo(MainPanelBinding panel, AnswerMap answers) {
            panel.setValue(CONFIG_FIELD_ID, deriveCurrentConfig());
            writebackField(CONFIG_FIELD_ID);
        }
    }

    private final class GroupSummaryScreen extends Screen {
        private GroupSummaryScreen() {
            super("Group counts");
        }

        @Override
        public void build(PipelineDialog dialog, AnswerMap answers) {
            dialog.addHeader("Group counts");
            dialog.addMessage(buildGroupSummary());
            if (hasUnderpoweredGroup()) {
                dialog.addMessage("Warning: at least one condition has fewer than 3 animals — "
                        + "tests may have low power. Edit conditions in the main panel before running.");
            }
        }

        @Override
        public void read(PipelineDialog dialog, AnswerMap answers) {
            // Read-only screen; no inputs to capture.
        }

        @Override
        public void writeTo(MainPanelBinding panel, AnswerMap answers) {
            // Summary screen does not write back.
        }

        private String buildGroupSummary() {
            if (conditionCount <= 0) {
                return "No conditions detected. Assign conditions in the main panel before running tests.";
            }
            StringBuilder sb = new StringBuilder();
            sb.append("Your dataset has ").append(conditionCount)
                    .append(conditionCount == 1 ? " condition" : " conditions");
            if (!groupSizes.isEmpty()) {
                sb.append(" with ");
                for (int i = 0; i < groupSizes.size(); i++) {
                    if (i > 0) sb.append(", ");
                    sb.append("n=").append(groupSizes.get(i));
                }
            }
            sb.append('.');
            return sb.toString();
        }

        private boolean hasUnderpoweredGroup() {
            for (Integer size : groupSizes) {
                if (size != null && size.intValue() < 3) {
                    return true;
                }
            }
            return false;
        }
    }

    private final class DistributionScreen extends Screen {
        private DistributionScreen() {
            super("How should the engine pick parametric vs non-parametric?");
            defaultAnswer("distribution.choice", DIST_AUTO);
        }

        @Override
        public void build(PipelineDialog dialog, AnswerMap answers) {
            dialog.addHeader("How should the engine pick parametric vs non-parametric?");
            String selected = answers.getString("distribution.choice", DIST_AUTO);
            if (Arrays.asList(distributionOptions()).indexOf(selected) < 0) {
                selected = DIST_AUTO;
            }
            dialog.addChoice("Distribution caution", distributionOptions(), selected);
            dialog.addHelpText("Automatic runs the D'Agostino-Pearson normality gate per metric. "
                    + "Choose 'Assume normal' or 'Assume skewed' to override.");
        }

        @Override
        public void read(PipelineDialog dialog, AnswerMap answers) {
            answers.put("distribution.choice", dialog.getNextChoice());
        }

        @Override
        public void writeTo(MainPanelBinding panel, AnswerMap answers) {
        }
    }

    private final class PostHocScreen extends Screen {
        private PostHocScreen() {
            super("Multi-group post-hoc correction");
            defaultAnswer("posthoc.choice", POSTHOC_BONFERRONI);
        }

        @Override
        public boolean isApplicable(AnswerMap prior) {
            return conditionCount >= 3;
        }

        @Override
        public void build(PipelineDialog dialog, AnswerMap answers) {
            dialog.addHeader("Multi-group post-hoc correction");
            StatisticsConfig.DistributionMode dist = parseDistributionAnswer(answers);
            String[] options = postHocOptions(dist);
            String selected = answers.getString("posthoc.choice", POSTHOC_BONFERRONI);
            if (Arrays.asList(options).indexOf(selected) < 0) {
                selected = POSTHOC_BONFERRONI;
            }
            dialog.addChoice("Post-hoc method", options, selected);
            if (!isTukeyEnabled(dist)) {
                dialog.addHelpText("Tukey HSD is parametric and is hidden when distribution is set to 'Assume skewed'.");
            }
            if (!isDunnsEnabled(dist)) {
                dialog.addHelpText("Dunn's test is non-parametric and is hidden when distribution is set to 'Assume normal'.");
            }
        }

        @Override
        public void read(PipelineDialog dialog, AnswerMap answers) {
            answers.put("posthoc.choice", dialog.getNextChoice());
        }

        @Override
        public void writeTo(MainPanelBinding panel, AnswerMap answers) {
        }
    }

    private final class MetricScopeScreen extends Screen {
        private MetricScopeScreen() {
            super("Which metrics should be tested?");
            defaultAnswer("metric.scope", METRIC_ALL);
            for (int i = 0; i < availableMetrics.size(); i++) {
                defaultAnswer("metric." + i, Boolean.FALSE);
            }
            defaultAnswer("metric.freeform", "");
        }

        @Override
        public void build(PipelineDialog dialog, AnswerMap answers) {
            dialog.addHeader("Which metrics should be tested?");
            dialog.beginAdvancedSection("statistics");
            String[] options = new String[]{METRIC_ALL, METRIC_SELECTED};
            String selected = answers.getString("metric.scope", METRIC_ALL);
            if (Arrays.asList(options).indexOf(selected) < 0) {
                selected = METRIC_ALL;
            }
            final JComboBox<String> scopeCombo = dialog.addChoice("Metric scope", options, selected);
            final boolean restricting = METRIC_SELECTED.equals(selected);

            if (availableMetrics.isEmpty()) {
                dialog.addHelpText("3D Objects.csv was not found. "
                        + "Enter metric column names below as a comma-separated list.");
                final JTextField freeform = dialog.addStringField("Metrics (comma-separated)",
                        answers.getString("metric.freeform", ""), 32);
                freeform.setEnabled(restricting);
                scopeCombo.addActionListener(new ActionListener() {
                    @Override
                    public void actionPerformed(ActionEvent e) {
                        boolean active = METRIC_SELECTED.equals(scopeCombo.getSelectedItem());
                        freeform.setEnabled(active);
                    }
                });
            } else {
                dialog.addHelpText("Toggle 'Only selected metrics' to restrict testing to a subset of "
                        + availableMetrics.size() + " detected metric columns.");
                final List<ToggleSwitch> toggles = new ArrayList<ToggleSwitch>();
                for (int i = 0; i < availableMetrics.size(); i++) {
                    ToggleSwitch toggle = dialog.addToggle(availableMetrics.get(i),
                            answers.getBoolean("metric." + i, false));
                    toggle.setEnabled(restricting);
                    toggles.add(toggle);
                }
                scopeCombo.addActionListener(new ActionListener() {
                    @Override
                    public void actionPerformed(ActionEvent e) {
                        boolean active = METRIC_SELECTED.equals(scopeCombo.getSelectedItem());
                        for (ToggleSwitch toggle : toggles) {
                            toggle.setEnabled(active);
                        }
                    }
                });
            }
            dialog.endAdvancedSection();
        }

        @Override
        public void read(PipelineDialog dialog, AnswerMap answers) {
            answers.put("metric.scope", dialog.getNextChoice());
            if (availableMetrics.isEmpty()) {
                answers.put("metric.freeform", dialog.getNextString());
            } else {
                for (int i = 0; i < availableMetrics.size(); i++) {
                    answers.put("metric." + i, Boolean.valueOf(dialog.getNextBoolean()));
                }
            }
        }

        @Override
        public void writeTo(MainPanelBinding panel, AnswerMap answers) {
            panel.setValue(CONFIG_FIELD_ID, deriveCurrentConfig());
            writebackField(CONFIG_FIELD_ID);
        }
    }
}
