package flash.pipeline.ui.config;

import flash.pipeline.help.AnalysisHelpCatalog;
import flash.pipeline.help.AnalysisHelpDialog;
import flash.pipeline.help.SetupHelpCatalog;
import flash.pipeline.help.SetupHelpTopic;
import flash.pipeline.objects.ObjectsCounter3DWrapper;
import flash.pipeline.runrecord.LoadedRunParameters;
import flash.pipeline.segmentation.EnhancedClassicalParameters;
import flash.pipeline.segmentation.EnhancedClassicalRunner;
import flash.pipeline.segmentation.MorphPredicate;
import flash.pipeline.segmentation.SegmentationMethod;
import flash.pipeline.segmentation.SegmentationTokenCodec;
import flash.pipeline.segmentation.SegmentationTokenParser;
import flash.pipeline.ui.FlashTheme;
import flash.pipeline.ui.HelpButton;
import flash.pipeline.ui.ToggleSwitch;
import ij.IJ;
import ij.ImagePlus;
import ij.measure.ResultsTable;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.SpinnerNumberModel;
import java.awt.Component;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class EnhancedClassicalSegmentationStage implements ConfigQcStage {
    public interface ParameterStore {
        String getMethodToken();
        void save(String methodToken);
    }

    public interface PreviewAdapter {
        ImagePlus createRawSource(ConfigQcContext context) throws Exception;
        ImagePlus createFilteredSource(ConfigQcContext context) throws Exception;
        void close(ImagePlus image);
    }

    private static final String[] FEATURE_OPTIONS = {
            "sphericity",
            "compactness",
            "elongation",
            "volume",
            "surface_area",
            "mean_intensity",
            "max_intensity",
            "feret_diameter_max"
    };
    private static final String[] OPERATOR_OPTIONS = {">=", "<=", ">", "<"};

    private final ParameterStore parameterStore;
    private final ClassicalSegmentationStage.ThresholdStore thresholdStore;
    private final ClassicalSegmentationStage.SizeStore sizeStore;
    private final PreviewAdapter previewAdapter;
    private final ClassicalSegmentationStage classicalDelegate;
    private final List<PredicateRow> rows = new ArrayList<PredicateRow>();

    private JPanel rowsPanel;
    private JLabel predicateSummary;
    private ConfigQcActions actions;
    private ConfigQcContext activeContext;
    private ImagePlus rawPreviewSource;

    public EnhancedClassicalSegmentationStage(ParameterStore parameterStore,
                                              ClassicalSegmentationStage.ThresholdStore thresholdStore,
                                              ClassicalSegmentationStage.SizeStore sizeStore,
                                              PreviewAdapter previewAdapter) {
        if (parameterStore == null) {
            throw new IllegalArgumentException("parameterStore must not be null");
        }
        if (thresholdStore == null) {
            throw new IllegalArgumentException("thresholdStore must not be null");
        }
        if (sizeStore == null) {
            throw new IllegalArgumentException("sizeStore must not be null");
        }
        if (previewAdapter == null) {
            throw new IllegalArgumentException("previewAdapter must not be null");
        }
        this.parameterStore = parameterStore;
        this.thresholdStore = thresholdStore;
        this.sizeStore = sizeStore;
        this.previewAdapter = previewAdapter;
        this.classicalDelegate = new ClassicalSegmentationStage(
                thresholdStore,
                sizeStore,
                new ClassicalSegmentationStage.PreviewAdapter() {
                    @Override public ImagePlus createRawSource(ConfigQcContext context) throws Exception {
                        rawPreviewSource = EnhancedClassicalSegmentationStage.this.previewAdapter
                                .createRawSource(context);
                        return rawPreviewSource;
                    }

                    @Override public ImagePlus createFilteredSource(ConfigQcContext context) throws Exception {
                        return EnhancedClassicalSegmentationStage.this.previewAdapter
                                .createFilteredSource(context);
                    }

                    @Override public ObjectsCounter3DWrapper.Result runPreview(ImagePlus filteredSource,
                                                                               int threshold,
                                                                               int minSize,
                                                                               int maxSize) throws Exception {
                        EnhancedClassicalParameters params = new EnhancedClassicalParameters(
                                threshold,
                                minSize,
                                maxSize,
                                collectEnabledPredicates(),
                                rawPreviewSource,
                                new EnhancedClassicalParameters.WarningSink() {
                                    @Override public void warn(String message) {
                                        IJ.log(message);
                                    }
                                });
                        ImagePlus labelImage = new EnhancedClassicalRunner().run(filteredSource, params);
                        updatePredicateSummary(labelImage);
                        ResultsTable stats = EnhancedClassicalRunner.statsProperty(labelImage);
                        return new ObjectsCounter3DWrapper.Result(
                                stats,
                                labelImage,
                                null,
                                stats != null && stats.size() > 0);
                    }

                    @Override public int countObjects(ObjectsCounter3DWrapper.Result result) {
                        if (result == null || result.getStatistics() == null) return 0;
                        return result.getStatistics().size();
                    }

                    @Override public void close(ImagePlus image) {
                        if (image == rawPreviewSource) rawPreviewSource = null;
                        EnhancedClassicalSegmentationStage.this.previewAdapter.close(image);
                    }
                });
    }

    @Override public String title() {
        return "Enhanced Classical Segmentation";
    }

    @Override public SetupHelpTopic helpTopic() {
        return SetupHelpCatalog.CLASSICAL_OBJECT_SEGMENTATION;
    }

    @Override public boolean controlsCanExpand() {
        return true;
    }

    @Override public JComponent buildControls(ConfigQcContext context, ConfigQcActions actions) {
        this.actions = actions;
        this.activeContext = context;
        rows.clear();

        JPanel panel = new JPanel();
        panel.setOpaque(false);
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));

        JComponent classicalControls = classicalDelegate.buildControls(context, actions);
        if (classicalControls != null) {
            classicalControls.setAlignmentX(Component.LEFT_ALIGNMENT);
            panel.add(classicalControls);
        }
        panel.add(Box.createVerticalStrut(6));
        panel.add(buildMorphSection());

        loadPredicatesFromStoredToken();
        refreshRows();
        return panel;
    }

    @Override public boolean supportsLoadedParameters() {
        return true;
    }

    @Override public LoadedRunParameters.Result applyLoadedParameters(Map<String, Object> parameters) {
        LoadedRunParameters.Result classical = classicalDelegate.applyLoadedParameters(parameters);
        int channel = activeContext == null ? 0 : activeContext.getChannelIndex();
        // The delegate already uses the active ConfigQcContext for channel-scoped
        // threshold/size values. Enhanced-only morphology lives in the method token.
        LoadedRunParameters.ValueLoad<String> method =
                LoadedRunParameters.segmentationMethod(parameters, channel);
        if (method.value != null
                && SegmentationTokenParser.parseLenient(method.value).isEnhancedClassical()) {
            parameterStore.save(method.value);
            rows.clear();
            loadPredicatesFromStoredToken();
            refreshRows();
            markPreviewStale();
        }
        return LoadedRunParameters.Result.merge(classical, method.result);
    }

    @Override public void onEnter(ConfigQcContext context, flash.pipeline.ui.preview.PreviewPairPanel preview) {
        this.activeContext = context;
        classicalDelegate.onEnter(context, preview);
    }

    @Override public boolean lockIn(ConfigQcContext context) {
        if (!classicalDelegate.lockIn(context)) {
            return false;
        }
        String threshold = thresholdStore.get();
        String sizeToken = sizeStore.get();
        parameterStore.save(buildMethodToken(threshold, sizeToken));
        setStatus("Locked Enhanced Classical segmentation.");
        return true;
    }

    @Override public void skipCurrentImage(ConfigQcContext context) {
        classicalDelegate.skipCurrentImage(context);
    }

    @Override public void restartStage(ConfigQcContext context) {
        classicalDelegate.restartStage(context);
    }

    @Override public void onLeave(ConfigQcContext context) {
        classicalDelegate.onLeave(context);
        rawPreviewSource = null;
        activeContext = null;
    }

    private JComponent buildMorphSection() {
        final JPanel panel = new JPanel();
        panel.setOpaque(false);
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setAlignmentX(Component.LEFT_ALIGNMENT);
        panel.setBorder(BorderFactory.createTitledBorder("Morphology filters"));

        rowsPanel = new JPanel();
        rowsPanel.setOpaque(false);
        rowsPanel.setLayout(new BoxLayout(rowsPanel, BoxLayout.Y_AXIS));

        JPanel actionRow = new JPanel(new GridBagLayout());
        actionRow.setOpaque(false);
        actionRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        actionRow.setBorder(FlashTheme.pad(2, 0, 2, 0));

        JButton add = new JButton("+ Add filter");
        add.addActionListener(e -> {
            rows.add(new PredicateRow("sphericity", ">=", 0.6, true));
            refreshRows();
            markPreviewStale();
        });

        JButton help = HelpButton.question("About Enhanced Classical segmentation.");
        help.addActionListener(e -> AnalysisHelpDialog.show(
                panel, AnalysisHelpCatalog.ENHANCED_CLASSICAL_SEGMENTATION));

        predicateSummary = new JLabel("No morphology filters enabled.");
        predicateSummary.setForeground(FlashTheme.TEXT_HELP);
        predicateSummary.setAlignmentX(Component.LEFT_ALIGNMENT);

        GridBagConstraints gbc = rowConstraints();
        actionRow.add(predicateSummary, gbc);
        gbc.gridx++;
        gbc.weightx = 1.0;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        actionRow.add(Box.createHorizontalGlue(), gbc);
        gbc.gridx++;
        gbc.weightx = 0.0;
        gbc.fill = GridBagConstraints.NONE;
        actionRow.add(add, gbc);
        gbc.gridx++;
        actionRow.add(help, gbc);

        panel.add(rowsPanel);
        panel.add(actionRow);
        return panel;
    }

    private void loadPredicatesFromStoredToken() {
        SegmentationMethod method = SegmentationTokenParser.parseLenient(parameterStore.getMethodToken());
        if (!method.isEnhancedClassical()) {
            return;
        }
        List<MorphPredicate> predicates = SegmentationMethod.morphPredicates(method);
        for (int i = 0; i < predicates.size(); i++) {
            MorphPredicate predicate = predicates.get(i);
            rows.add(new PredicateRow(
                    predicate.featureName,
                    predicate.op.symbol(),
                    predicate.value,
                    true));
        }
    }

    private void refreshRows() {
        if (rowsPanel == null) return;
        ensureVisibleMorphRow();
        rowsPanel.removeAll();
        for (int i = 0; i < rows.size(); i++) {
            rowsPanel.add(buildRow(rows.get(i)));
            rowsPanel.add(Box.createVerticalStrut(3));
        }
        updatePredicateSummary(null);
        rowsPanel.revalidate();
        rowsPanel.repaint();
    }

    private void ensureVisibleMorphRow() {
        if (!rows.isEmpty()) return;
        rows.add(new PredicateRow("sphericity", ">=", 0.6, false));
    }

    private static GridBagConstraints rowConstraints() {
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.gridy = 0;
        gbc.gridx = 0;
        gbc.insets = new Insets(0, 0, 0, 6);
        gbc.anchor = GridBagConstraints.WEST;
        gbc.fill = GridBagConstraints.NONE;
        gbc.weightx = 0.0;
        return gbc;
    }

    private JComponent buildRow(final PredicateRow row) {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setOpaque(false);
        panel.setAlignmentX(Component.LEFT_ALIGNMENT);
        panel.setBorder(FlashTheme.pad(2, 0, 2, 0));

        final JComboBox<String> feature = new JComboBox<String>(FEATURE_OPTIONS);
        feature.setSelectedItem(row.feature);
        feature.addActionListener(e -> {
            row.feature = String.valueOf(feature.getSelectedItem());
            markPreviewStale();
        });

        final JComboBox<String> operator = new JComboBox<String>(OPERATOR_OPTIONS);
        operator.setSelectedItem(row.operator);
        operator.addActionListener(e -> {
            row.operator = String.valueOf(operator.getSelectedItem());
            markPreviewStale();
        });

        final JSpinner value = new JSpinner(new SpinnerNumberModel(row.value, -Double.MAX_VALUE,
                Double.MAX_VALUE, 0.1));
        value.addChangeListener(e -> {
            Object v = value.getValue();
            if (v instanceof Number) row.value = ((Number) v).doubleValue();
            markPreviewStale();
        });

        final ToggleSwitch enabled = new ToggleSwitch(row.enabled);
        enabled.addChangeListener(new Runnable() {
            @Override public void run() {
                row.enabled = enabled.isSelected();
                markPreviewStale();
            }
        });
        JLabel enabledLabel = new JLabel("Use");
        enabledLabel.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
        enabledLabel.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override public void mouseClicked(java.awt.event.MouseEvent e) {
                if (enabled.isEnabled()) {
                    enabled.setSelected(!enabled.isSelected());
                }
            }
        });
        JPanel enabledPanel = new JPanel();
        enabledPanel.setOpaque(false);
        enabledPanel.setLayout(new BoxLayout(enabledPanel, BoxLayout.X_AXIS));
        enabledPanel.add(enabled);
        enabledPanel.add(Box.createHorizontalStrut(FlashTheme.SPACE_S));
        enabledPanel.add(enabledLabel);

        JButton remove = new JButton("X");
        remove.setToolTipText("Remove filter");
        remove.addActionListener(e -> {
            rows.remove(row);
            refreshRows();
            markPreviewStale();
        });

        GridBagConstraints gbc = rowConstraints();
        JLabel heading = new JLabel("Morphology:");
        heading.setFont(FlashTheme.bodyMedium());
        panel.add(heading, gbc);
        gbc.gridx++;
        panel.add(new JLabel("Feature"), gbc);
        gbc.gridx++;
        gbc.weightx = 0.35;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        panel.add(feature, gbc);
        gbc.gridx++;
        gbc.weightx = 0.0;
        gbc.fill = GridBagConstraints.NONE;
        panel.add(new JLabel("Rule"), gbc);
        gbc.gridx++;
        panel.add(operator, gbc);
        gbc.gridx++;
        panel.add(new JLabel("Value"), gbc);
        gbc.gridx++;
        panel.add(value, gbc);
        gbc.gridx++;
        panel.add(enabledPanel, gbc);
        gbc.gridx++;
        panel.add(remove, gbc);
        gbc.gridx++;
        gbc.weightx = 1.0;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        panel.add(Box.createHorizontalGlue(), gbc);
        return panel;
    }

    private List<MorphPredicate> collectEnabledPredicates() {
        List<MorphPredicate> predicates = new ArrayList<MorphPredicate>();
        for (int i = 0; i < rows.size(); i++) {
            PredicateRow row = rows.get(i);
            if (row == null || !row.enabled) continue;
            predicates.add(new MorphPredicate(row.feature, operator(row.operator), row.value));
        }
        return predicates;
    }

    private String buildMethodToken(String thresholdToken, String sizeToken) {
        int threshold = parseThreshold(thresholdToken);
        SizeValues size = parseSize(sizeToken);
        List<MorphPredicate> predicates = collectEnabledPredicates();

        Map<String, String> params = new LinkedHashMap<String, String>();
        params.put("thresh", String.valueOf(threshold));
        params.put("minSize", String.valueOf(size.min));
        params.put("maxSize", String.valueOf(size.max));
        if (!predicates.isEmpty()) {
            params.put("morph", SegmentationTokenCodec.percentEncodeToken(formatPredicates(predicates)));
        }
        return SegmentationTokenParser.format(new SegmentationMethod(
                SegmentationMethod.Engine.ENHANCED_CLASSICAL,
                params,
                ""));
    }

    private static String formatPredicates(List<MorphPredicate> predicates) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < predicates.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(predicates.get(i).format());
        }
        return sb.toString();
    }

    private void updatePredicateSummary(ImagePlus labelImage) {
        if (predicateSummary == null) return;
        List<MorphPredicate> predicates = collectEnabledPredicates();
        if (predicates.isEmpty()) {
            predicateSummary.setText("No morphology filters enabled.");
            return;
        }
        Object countsObj = labelImage == null ? null
                : labelImage.getProperty(EnhancedClassicalRunner.PREDICATE_COUNTS_PROPERTY);
        if (!(countsObj instanceof int[])) {
            predicateSummary.setText("Morphology filters enabled: " + predicates.size() + ".");
            return;
        }
        int[] counts = (int[]) countsObj;
        StringBuilder sb = new StringBuilder("<html>");
        for (int i = 0; i < predicates.size(); i++) {
            if (i > 0) sb.append("<br>");
            sb.append(predicates.get(i).format()).append(": ");
            sb.append(i < counts.length ? counts[i] : 0).append(" surviving");
        }
        sb.append("</html>");
        predicateSummary.setText(sb.toString());
    }

    private void markPreviewStale() {
        updatePredicateSummary(null);
        if (actions != null) {
            actions.markPreviewStale("Morphology filters changed. Run object preview again.");
            actions.setPreviewButtonStale(true);
        }
    }

    private void setStatus(String text) {
        if (actions != null) actions.setStatus(text);
    }

    private static MorphPredicate.Operator operator(String symbol) {
        if ("<=".equals(symbol)) return MorphPredicate.Operator.LE;
        if (">".equals(symbol)) return MorphPredicate.Operator.GT;
        if ("<".equals(symbol)) return MorphPredicate.Operator.LT;
        return MorphPredicate.Operator.GE;
    }

    private static int parseThreshold(String token) {
        try {
            return (int) Math.round(Double.parseDouble(token == null ? "0" : token.trim()));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static SizeValues parseSize(String token) {
        String safe = token == null || token.trim().isEmpty() ? "100-Infinity" : token.trim();
        String[] parts = safe.split("-", 2);
        int min = parseNonNegativeInt(parts.length > 0 ? parts[0] : "100", 100);
        int max = parseMax(parts.length > 1 ? parts[1] : "Infinity");
        return new SizeValues(min, Math.max(min, max));
    }

    private static int parseNonNegativeInt(String raw, int fallback) {
        try {
            double parsed = Double.parseDouble(raw == null ? "" : raw.trim());
            if (!Double.isFinite(parsed)) return fallback;
            if (parsed <= 0) return 0;
            if (parsed >= Integer.MAX_VALUE) return Integer.MAX_VALUE;
            return (int) Math.round(parsed);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static int parseMax(String raw) {
        if (raw == null || raw.trim().isEmpty()
                || "Infinity".equalsIgnoreCase(raw.trim())
                || "Inf".equalsIgnoreCase(raw.trim())) {
            return Integer.MAX_VALUE;
        }
        return parseNonNegativeInt(raw, Integer.MAX_VALUE);
    }

    private static final class PredicateRow {
        String feature;
        String operator;
        double value;
        boolean enabled;

        PredicateRow(String feature, String operator, double value, boolean enabled) {
            this.feature = feature == null ? "sphericity" : feature;
            this.operator = operator == null ? ">=" : operator;
            this.value = value;
            this.enabled = enabled;
        }
    }

    private static final class SizeValues {
        final int min;
        final int max;

        SizeValues(int min, int max) {
            this.min = min;
            this.max = max;
        }
    }
}
