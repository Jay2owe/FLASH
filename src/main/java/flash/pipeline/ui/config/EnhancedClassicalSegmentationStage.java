package flash.pipeline.ui.config;

import flash.pipeline.click.suggest.EnhancedClassicalMorphSuggester;
import flash.pipeline.click.suggest.SuggestionContext;
import flash.pipeline.help.AnalysisHelpCatalog;
import flash.pipeline.help.AnalysisHelpDialog;
import flash.pipeline.help.SetupHelpCatalog;
import flash.pipeline.help.SetupHelpTopic;
import flash.pipeline.objects.ObjectsCounter3DWrapper;
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
    private CollapsibleSection filterSection;
    private JLabel predicateSummary;
    private ConfigQcActions actions;
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
                },
                new ClassicalSegmentationStage.SuggestionDecorator() {
                    @Override public ClickSuggestPanel.Suggestion decorate(
                            SuggestionContext context,
                            ClickSuggestPanel.Suggestion parameterSuggestion) {
                        return decorateEnhancedSuggestion(context, parameterSuggestion);
                    }
                },
                1);
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

    @Override public void onEnter(ConfigQcContext context, flash.pipeline.ui.preview.PreviewPairPanel preview) {
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
    }

    private JComponent buildMorphSection() {
        filterSection = new CollapsibleSection("Filter by morphology", false);
        filterSection.setAlignmentX(Component.LEFT_ALIGNMENT);
        JButton help = HelpButton.question("About Enhanced Classical segmentation.");
        help.addActionListener(e -> AnalysisHelpDialog.show(
                filterSection, AnalysisHelpCatalog.ENHANCED_CLASSICAL_SEGMENTATION));
        filterSection.getHeaderControls().add(help);
        rowsPanel = new JPanel();
        rowsPanel.setOpaque(false);
        rowsPanel.setLayout(new BoxLayout(rowsPanel, BoxLayout.Y_AXIS));

        JButton add = new JButton("+ Add filter");
        add.addActionListener(e -> {
            rows.add(new PredicateRow("sphericity", ">=", 0.6, true));
            if (filterSection != null) filterSection.setExpanded(true);
            refreshRows();
            markPreviewStale();
        });

        predicateSummary = new JLabel("No morphology filters enabled.");
        predicateSummary.setForeground(FlashTheme.TEXT_HELP);
        predicateSummary.setAlignmentX(Component.LEFT_ALIGNMENT);

        filterSection.getBody().add(rowsPanel);
        filterSection.getBody().add(Box.createVerticalStrut(4));
        filterSection.getBody().add(add);
        filterSection.getBody().add(Box.createVerticalStrut(4));
        filterSection.getBody().add(predicateSummary);
        return filterSection;
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
        if (!rows.isEmpty() && filterSection != null) {
            filterSection.setExpanded(true);
        }
    }

    private void refreshRows() {
        if (rowsPanel == null) return;
        rowsPanel.removeAll();
        if (rows.isEmpty()) {
            JLabel empty = new JLabel("No morphology filters enabled.");
            empty.setForeground(FlashTheme.TEXT_HELP);
            empty.setAlignmentX(Component.LEFT_ALIGNMENT);
            rowsPanel.add(empty);
        } else {
            for (int i = 0; i < rows.size(); i++) {
                rowsPanel.add(buildRow(rows.get(i)));
                rowsPanel.add(Box.createVerticalStrut(3));
            }
        }
        updatePredicateSummary(null);
        rowsPanel.revalidate();
        rowsPanel.repaint();
    }

    private JComponent buildRow(final PredicateRow row) {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setOpaque(false);
        panel.setAlignmentX(Component.LEFT_ALIGNMENT);
        panel.setBorder(BorderFactory.createEmptyBorder(1, 0, 1, 0));

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

        JButton remove = new JButton("X");
        remove.setToolTipText("Remove filter");
        remove.addActionListener(e -> {
            rows.remove(row);
            refreshRows();
            markPreviewStale();
        });

        GridBagConstraints gbc = new GridBagConstraints();
        gbc.gridy = 0;
        gbc.insets = new Insets(0, 0, 0, 5);
        gbc.anchor = GridBagConstraints.WEST;
        gbc.fill = GridBagConstraints.NONE;
        gbc.gridx = 0;
        panel.add(feature, gbc);
        gbc.gridx++;
        panel.add(operator, gbc);
        gbc.gridx++;
        panel.add(value, gbc);
        gbc.gridx++;
        panel.add(enabled, gbc);
        gbc.gridx++;
        panel.add(remove, gbc);
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

    private ClickSuggestPanel.Suggestion decorateEnhancedSuggestion(
            SuggestionContext context,
            ClickSuggestPanel.Suggestion parameterSuggestion) {
        SuggestionContext morphContext = rawPreviewSource == null || context == null
                ? context
                : new SuggestionContext(
                        rawPreviewSource,
                        context.labelImage,
                        context.auxImage,
                        context.negativeClicks,
                        context.positiveClicks,
                        context.currentParams);
        final EnhancedClassicalMorphSuggester.MorphSuggestion morphSuggestion =
                new EnhancedClassicalMorphSuggester().suggestMorph(morphContext);
        if (morphSuggestion == null) {
            return parameterSuggestion;
        }

        List<ClickSuggestPanel.ActionSuggestion> actions =
                new ArrayList<ClickSuggestPanel.ActionSuggestion>();
        if (parameterSuggestion != null) {
            actions.addAll(parameterSuggestion.actions);
        }
        actions.add(new ClickSuggestPanel.ActionSuggestion(
                "Add to morph filters",
                new Runnable() {
                    @Override public void run() {
                        addSuggestedMorphFilter(morphSuggestion);
                    }
                }));

        if (parameterSuggestion == null) {
            return new ClickSuggestPanel.Suggestion(
                    new ArrayList<ClickSuggestPanel.FieldSuggestion>(),
                    morphSuggestion.message(),
                    "",
                    null,
                    null,
                    actions);
        }
        String message = parameterSuggestion.message == null
                || parameterSuggestion.message.trim().isEmpty()
                ? morphSuggestion.message()
                : parameterSuggestion.message + " " + morphSuggestion.message();
        return new ClickSuggestPanel.Suggestion(
                parameterSuggestion.fields,
                message,
                parameterSuggestion.hint,
                parameterSuggestion.applyAction,
                parameterSuggestion.revertAction,
                actions);
    }

    private void addSuggestedMorphFilter(EnhancedClassicalMorphSuggester.MorphSuggestion suggestion) {
        if (suggestion == null) return;
        MorphPredicate predicate = suggestion.toPredicate();
        rows.add(new PredicateRow(predicate.featureName, predicate.op.symbol(),
                predicate.value, true));
        if (filterSection != null) filterSection.setExpanded(true);
        refreshRows();
        markPreviewStale();
        setStatus("Added morph filter: " + predicate.format()
                + ". Run object preview again.");
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
