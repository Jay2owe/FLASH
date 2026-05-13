package flash.pipeline.ui.config;

import flash.pipeline.help.SetupHelpCatalog;
import flash.pipeline.help.SetupHelpTopic;
import flash.pipeline.intelligence.EmptySliceSuggester;
import flash.pipeline.io.SeriesMeta;
import flash.pipeline.ui.preview.PreviewPairPanel;
import flash.pipeline.zslice.ZSliceRange;
import flash.pipeline.zslice.ZSliceSelection;
import ij.ImagePlus;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class ZSliceSelectionStage implements ConfigQcStage {

    static final String ACTION_NEXT_IMAGE = "Next image";
    static final String ACTION_ACCEPT_SELECTION = "Accept selection";
    static final String ACTION_RESTART = "Restart from first image";
    static final String ACTION_APPLY_TO_COMPATIBLE = "Apply current range to all remaining images";

    public enum PartialApplyChoice {
        APPLY_TO_COMPATIBLE,
        CONTINUE_MANUAL,
        CANCEL
    }

    public interface SelectionStore {
        ZSliceSelection get(int seriesIndex);
        void put(ZSliceSelection selection);
    }

    public interface ImageOpener {
        ImagePlus open(SeriesMeta meta) throws Exception;
        void close(ImagePlus image);
    }

    public interface PartialApplyHandler {
        PartialApplyChoice choose(ZSliceRange range,
                                  List<SeriesMeta> compatibleMetas,
                                  List<SeriesMeta> incompatibleMetas);
    }

    private final List<SeriesMeta> metas;
    private final SelectionStore selectionStore;
    private final ImageOpener imageOpener;
    private final PartialApplyHandler partialApplyHandler;

    private ConfigQcActions actions;
    private PreviewPairPanel preview;
    private ImagePlus activeImage;
    private SeriesMeta activeMeta;
    private ZSliceRange lastAcceptedRange;
    private ZSliceRange restartRange;

    private JLabel totalSlicesLabel;
    private JLabel suggestionLabel;
    private JLabel feedbackLabel;
    private JLabel errorLabel;
    private JTextField startField;
    private JTextField endField;
    private JComboBox<String> actionChoice;
    private boolean updatingFields;

    public ZSliceSelectionStage(List<SeriesMeta> metas,
                                SelectionStore selectionStore,
                                ImageOpener imageOpener,
                                PartialApplyHandler partialApplyHandler) {
        this.metas = Collections.unmodifiableList(copyMetas(metas));
        if (selectionStore == null) {
            throw new IllegalArgumentException("selectionStore must not be null");
        }
        if (imageOpener == null) {
            throw new IllegalArgumentException("imageOpener must not be null");
        }
        this.selectionStore = selectionStore;
        this.imageOpener = imageOpener;
        this.partialApplyHandler = partialApplyHandler == null
                ? new PartialApplyHandler() {
                    @Override public PartialApplyChoice choose(ZSliceRange range,
                                                               List<SeriesMeta> compatibleMetas,
                                                               List<SeriesMeta> incompatibleMetas) {
                        return PartialApplyChoice.CANCEL;
                    }
                }
                : partialApplyHandler;
    }

    @Override
    public String title() {
        return "Z-Slice Subset";
    }

    @Override
    public SetupHelpTopic helpTopic() {
        return SetupHelpCatalog.Z_SLICE_SUBSET;
    }

    @Override
    public boolean controlsCanExpand() {
        return true;
    }

    @Override
    public boolean isApplicable(ConfigQcContext context) {
        return !metas.isEmpty();
    }

    @Override
    public JComponent buildControls(final ConfigQcContext context, ConfigQcActions actions) {
        this.actions = actions;

        JPanel panel = new JPanel(new BorderLayout(0, 6));
        panel.setOpaque(false);
        panel.add(buildInfoPanel(), BorderLayout.NORTH);
        panel.add(buildRangePanel(), BorderLayout.CENTER);
        panel.add(buildFeedbackPanel(), BorderLayout.SOUTH);
        installRangeListeners();
        updateForContext(context);
        return panel;
    }

    @Override
    public void onEnter(final ConfigQcContext context, PreviewPairPanel preview) {
        closeActiveImage();
        this.preview = preview;
        this.activeMeta = currentMeta(context);
        if (preview != null) {
            preview.setSharedZChangeListener(new PreviewPairPanel.SharedZChangeListener() {
                @Override public void zSliceChanged(int zSlice) {
                    updateFeedback();
                }
            });
            preview.setAdjusted(null);
        }

        updateForContext(context);
        if (activeMeta == null) {
            setError("No image series is available for z-slice selection.");
            return;
        }

        try {
            activeImage = imageOpener.open(activeMeta);
            if (activeImage == null) {
                throw new IllegalStateException("Bio-Formats returned no image.");
            }
            String displayName = context == null ? "" : context.getCurrentImageDisplayName();
            if (displayName != null && !displayName.trim().isEmpty()) {
                activeImage.setTitle(displayName);
            }
            if (preview != null) {
                preview.setOriginal(activeImage);
            }
            DefaultSelection defaults = defaultSelection(activeMeta, lastAcceptedRange, activeImage);
            setRangeFields(defaults.range);
            updateSuggestion(defaults);
            clearError();
            updateFeedback();
        } catch (Exception e) {
            setError("Failed to open image series: " + e.getMessage());
            if (actions != null) {
                actions.cancel();
            }
        }
    }

    @Override
    public boolean lockIn(ConfigQcContext context) {
        SeriesMeta meta = currentMeta(context);
        if (meta == null) {
            setError("No image series is available.");
            return false;
        }

        ZSliceRange range = currentRange();
        if (range == null || !range.isValidFor(totalSlices(meta))) {
            setError("Enter a valid contiguous z-slice range within 1-" + totalSlices(meta) + ".");
            return false;
        }

        selectionStore.put(new ZSliceSelection(
                meta.index, meta.name, totalSlices(meta), range));
        lastAcceptedRange = range;

        String action = selectedAction();
        if (ACTION_RESTART.equals(action)) {
            context.requestNextImageIndex(0);
            setStatus("Restarting z-slice selection from the first image.");
            return true;
        }

        if (ACTION_APPLY_TO_COMPATIBLE.equals(action)) {
            return applyToCompatibleRemaining(context, range);
        }

        setStatus("Keeping slices " + range.toToken() + " for this image.");
        return true;
    }

    @Override
    public void skipCurrentImage(ConfigQcContext context) {
        setStatus("Skipped this image.");
    }

    @Override
    public void restartStage(ConfigQcContext context) {
        ZSliceRange range = currentRange();
        if (range != null) {
            restartRange = range;
            lastAcceptedRange = range;
        }
        setStatus("Restarting z-slice selection from the first image.");
    }

    @Override
    public void onLeave(ConfigQcContext context) {
        if (preview != null) {
            preview.setSharedZChangeListener(null);
        }
        closeActiveImage();
        preview = null;
        activeMeta = null;
        restartRange = null;
    }

    ZSliceRange currentRangeForTest() {
        return currentRange();
    }

    void setRangeForTest(String start, String end) {
        if (startField != null) startField.setText(start);
        if (endField != null) endField.setText(end);
    }

    void setActionForTest(String action) {
        if (actionChoice != null) actionChoice.setSelectedItem(action);
    }

    String feedbackTextForTest() {
        return feedbackLabel == null ? "" : feedbackLabel.getText();
    }

    String errorTextForTest() {
        return errorLabel == null ? "" : errorLabel.getText();
    }

    static RangeCompatibility computeRangeCompatibility(
            List<SeriesMeta> metas, int startIndex, ZSliceRange range) {
        List<Integer> compatiblePositions = new ArrayList<Integer>();
        List<SeriesMeta> compatibleMetas = new ArrayList<SeriesMeta>();
        List<SeriesMeta> incompatibleMetas = new ArrayList<SeriesMeta>();
        int firstIncompatible = -1;
        if (metas != null && range != null) {
            for (int i = Math.max(0, startIndex); i < metas.size(); i++) {
                SeriesMeta meta = metas.get(i);
                if (meta == null) continue;
                if (range.isValidFor(totalSlices(meta))) {
                    compatiblePositions.add(Integer.valueOf(i));
                    compatibleMetas.add(meta);
                } else {
                    incompatibleMetas.add(meta);
                    if (firstIncompatible < 0) firstIncompatible = i;
                }
            }
        }
        return new RangeCompatibility(
                compatiblePositions, compatibleMetas, incompatibleMetas, firstIncompatible);
    }

    private JComponent buildInfoPanel() {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        panel.setOpaque(false);
        totalSlicesLabel = new JLabel("Total z-slices: ");
        JLabel help = new JLabel("Choose a contiguous inclusive range (e.g. 11-30).");
        help.setForeground(new Color(90, 90, 90));
        panel.add(totalSlicesLabel);
        panel.add(help);
        return panel;
    }

    private JComponent buildRangePanel() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setOpaque(false);
        panel.setBorder(BorderFactory.createEmptyBorder(2, 0, 2, 0));

        GridBagConstraints gbc = new GridBagConstraints();
        gbc.gridy = 0;
        gbc.gridx = 0;
        gbc.insets = new Insets(2, 0, 2, 6);
        gbc.anchor = GridBagConstraints.WEST;

        startField = new JTextField(6);
        JButton useStart = new JButton("Use current Z as start");
        useStart.addActionListener(e -> useCurrentZ(startField));
        addRow(panel, gbc, "Start", startField, useStart);

        endField = new JTextField(6);
        JButton useEnd = new JButton("Use current Z as end");
        useEnd.addActionListener(e -> useCurrentZ(endField));
        addRow(panel, gbc, "End", endField, useEnd);

        actionChoice = new JComboBox<String>();
        gbc.weightx = 0.0;
        gbc.fill = GridBagConstraints.NONE;
        panel.add(new JLabel("Action"), gbc);
        gbc.gridx++;
        gbc.gridwidth = GridBagConstraints.REMAINDER;
        gbc.weightx = 1.0;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        panel.add(actionChoice, gbc);
        gbc.gridwidth = 1;
        return panel;
    }

    private void addRow(JPanel panel, GridBagConstraints gbc, String label, JTextField field, JButton button) {
        gbc.gridx = 0;
        gbc.weightx = 0.0;
        gbc.fill = GridBagConstraints.NONE;
        panel.add(new JLabel(label), gbc);

        gbc.gridx++;
        gbc.weightx = 0.0;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        panel.add(field, gbc);

        gbc.gridx++;
        gbc.weightx = 0.0;
        gbc.fill = GridBagConstraints.NONE;
        panel.add(button, gbc);
        gbc.gridx++;
    }

    private JComponent buildFeedbackPanel() {
        JPanel panel = new JPanel();
        panel.setOpaque(false);
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        suggestionLabel = new JLabel(" ");
        feedbackLabel = new JLabel(" ");
        errorLabel = new JLabel(" ");
        suggestionLabel.setForeground(new Color(90, 90, 90));
        feedbackLabel.setForeground(new Color(55, 71, 79));
        errorLabel.setForeground(new Color(160, 45, 45));
        suggestionLabel.setAlignmentX(JComponent.LEFT_ALIGNMENT);
        feedbackLabel.setAlignmentX(JComponent.LEFT_ALIGNMENT);
        errorLabel.setAlignmentX(JComponent.LEFT_ALIGNMENT);
        panel.add(suggestionLabel);
        panel.add(Box.createVerticalStrut(4));
        panel.add(feedbackLabel);
        panel.add(Box.createVerticalStrut(4));
        panel.add(errorLabel);
        return panel;
    }

    private void installRangeListeners() {
        DocumentListener listener = new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) {
                fieldChanged();
            }

            @Override public void removeUpdate(DocumentEvent e) {
                fieldChanged();
            }

            @Override public void changedUpdate(DocumentEvent e) {
                fieldChanged();
            }
        };
        startField.getDocument().addDocumentListener(listener);
        endField.getDocument().addDocumentListener(listener);
    }

    private void fieldChanged() {
        if (updatingFields) return;
        clearError();
        updateFeedback();
    }

    private void updateForContext(ConfigQcContext context) {
        SeriesMeta meta = currentMeta(context);
        if (totalSlicesLabel != null) {
            totalSlicesLabel.setText("Total z-slices: " + totalSlices(meta));
        }
        updateActionChoices(context);
    }

    private void updateActionChoices(ConfigQcContext context) {
        if (actionChoice == null) return;
        String previous = (String) actionChoice.getSelectedItem();
        actionChoice.removeAllItems();
        int currentIndex = context == null ? 0 : context.getCurrentImageIndex();
        int count = context == null ? metas.size() : context.getImageCount();
        if (currentIndex >= count - 1) {
            actionChoice.addItem(ACTION_ACCEPT_SELECTION);
            actionChoice.addItem(ACTION_RESTART);
        } else {
            actionChoice.addItem(ACTION_NEXT_IMAGE);
            actionChoice.addItem(ACTION_RESTART);
            actionChoice.addItem(ACTION_APPLY_TO_COMPATIBLE);
        }
        if (previous != null) {
            if (hasActionChoice(previous)) {
                actionChoice.setSelectedItem(previous);
            } else if (actionChoice.getItemCount() > 0) {
                actionChoice.setSelectedIndex(0);
            }
        }
    }

    private boolean hasActionChoice(String value) {
        if (actionChoice == null || value == null) return false;
        for (int i = 0; i < actionChoice.getItemCount(); i++) {
            if (value.equals(actionChoice.getItemAt(i))) return true;
        }
        return false;
    }

    private DefaultSelection defaultSelection(SeriesMeta meta, ZSliceRange lastAcceptedRange, ImagePlus image) {
        int total = totalSlices(meta);
        if (restartRange != null && restartRange.isValidFor(total)) {
            return new DefaultSelection(restartRange, "Restart range", "");
        }
        ZSliceSelection existing = selectionStore.get(meta.index);
        if (existing != null && existing.range != null && !existing.isFullStack()) {
            return new DefaultSelection(existing.range, "Saved range", "");
        }

        EmptySliceSuggester.Suggestion suggestion = EmptySliceSuggester.suggest(image);
        if (suggestion != null && suggestion.range != null && suggestion.range.isValidFor(total)) {
            return new DefaultSelection(
                    suggestion.range,
                    "Suggested",
                    suggestion.trimsSlices() ? suggestion.tooltip() : "");
        }

        if (existing != null && existing.range != null) {
            return new DefaultSelection(existing.range, "Saved range", "");
        }
        if (lastAcceptedRange != null && lastAcceptedRange.isValidFor(total)) {
            return new DefaultSelection(lastAcceptedRange, "Previous accepted range", "");
        }
        return new DefaultSelection(ZSliceRange.fullStack(total), "Full stack", "");
    }

    private void updateSuggestion(DefaultSelection selection) {
        if (suggestionLabel == null || selection == null || selection.range == null) return;
        String text = selection.label + ": " + selection.range.toToken();
        if (selection.hint != null && !selection.hint.trim().isEmpty()) {
            text += " - " + selection.hint;
        }
        suggestionLabel.setText(text);
        suggestionLabel.setToolTipText(selection.hint);
    }

    private boolean applyToCompatibleRemaining(ConfigQcContext context, ZSliceRange range) {
        int startIndex = context.getCurrentImageIndex() + 1;
        RangeCompatibility compat = computeRangeCompatibility(metas, startIndex, range);
        if (compat.isEmptyRemaining() || compat.isAllCompatible()) {
            applyRangeToPositions(compat.compatiblePositions, range);
            context.requestNextImageIndex(context.getImageCount());
            setStatus("Applied " + range.toToken() + " to all remaining compatible images.");
            return true;
        }

        if (compat.isAllIncompatible()) {
            setError("The range " + range.toToken()
                    + " does not fit any remaining image series. "
                    + incompatibleSummary(compat.incompatibleMetas));
            return false;
        }

        PartialApplyChoice choice = partialApplyHandler.choose(
                range, compat.compatibleMetas, compat.incompatibleMetas);
        if (choice == PartialApplyChoice.CANCEL) {
            setStatus("Choose a smaller range or continue image-by-image.");
            return false;
        }
        if (choice == PartialApplyChoice.CONTINUE_MANUAL) {
            setStatus("Keeping this image's range; continuing image-by-image.");
            return true;
        }

        applyRangeToPositions(compat.compatiblePositions, range);
        context.requestNextImageIndex(compat.firstIncompatiblePosition);
        setStatus("Applied " + range.toToken()
                + " to compatible images; next image needs a separate range.");
        return true;
    }

    private void applyRangeToPositions(List<Integer> positions, ZSliceRange range) {
        if (positions == null || range == null) return;
        for (Integer position : positions) {
            if (position == null || position.intValue() < 0 || position.intValue() >= metas.size()) continue;
            SeriesMeta remaining = metas.get(position.intValue());
            if (remaining == null) continue;
            selectionStore.put(new ZSliceSelection(
                    remaining.index, remaining.name, totalSlices(remaining), range));
        }
    }

    private void useCurrentZ(JTextField field) {
        if (field == null) return;
        int z = preview == null ? 1 : preview.getCurrentZ();
        field.setText(String.valueOf(z));
        updateFeedback();
    }

    private void setRangeFields(ZSliceRange range) {
        if (range == null || startField == null || endField == null) return;
        updatingFields = true;
        try {
            startField.setText(String.valueOf(range.startSlice));
            endField.setText(String.valueOf(range.endSlice));
        } finally {
            updatingFields = false;
        }
    }

    private ZSliceRange currentRange() {
        if (startField == null || endField == null) return null;
        try {
            int start = Integer.parseInt(startField.getText().trim());
            int end = Integer.parseInt(endField.getText().trim());
            return new ZSliceRange(start, end);
        } catch (Exception e) {
            return null;
        }
    }

    private void updateFeedback() {
        if (feedbackLabel == null) return;
        SeriesMeta meta = activeMeta;
        if (meta == null && !metas.isEmpty()) {
            meta = metas.get(0);
        }
        int total = totalSlices(meta);
        ZSliceRange range = currentRange();
        String text;
        if (range == null || !range.isValidFor(total)) {
            text = "Enter start and end slices within 1-" + total + ".";
        } else {
            int currentZ = preview == null ? 1 : preview.getCurrentZ();
            boolean inside = currentZ >= range.startSlice && currentZ <= range.endSlice;
            text = "Keeping slices " + range.toToken() + " of " + total
                    + ". Current Z is " + (inside ? "inside" : "outside")
                    + " the selected range.";
        }
        feedbackLabel.setText(text);
        if (preview != null) {
            preview.setAdjustedState(PreviewPairPanel.PreviewState.READY, text);
        }
    }

    private String selectedAction() {
        Object item = actionChoice == null ? null : actionChoice.getSelectedItem();
        return item == null ? ACTION_NEXT_IMAGE : item.toString();
    }

    private SeriesMeta currentMeta(ConfigQcContext context) {
        if (metas.isEmpty()) return null;
        int index = context == null ? 0 : context.getCurrentImageIndex();
        if (index < 0) index = 0;
        if (index >= metas.size()) index = metas.size() - 1;
        return metas.get(index);
    }

    private void closeActiveImage() {
        ImagePlus image = activeImage;
        activeImage = null;
        if (image != null) {
            imageOpener.close(image);
        }
    }

    private void setStatus(String message) {
        if (actions != null) {
            actions.setStatus(message);
        }
    }

    private void setError(String message) {
        if (errorLabel != null) {
            errorLabel.setText(message == null ? "" : message);
        }
        if (actions != null) {
            actions.setStatus(message);
        }
    }

    private void clearError() {
        if (errorLabel != null) {
            errorLabel.setText(" ");
        }
    }

    private static int totalSlices(SeriesMeta meta) {
        return Math.max(1, meta == null ? 1 : meta.nSlices);
    }

    private static String displayName(SeriesMeta meta) {
        if (meta == null) return "Image";
        if (meta.name != null && !meta.name.trim().isEmpty()) {
            return meta.name;
        }
        return "Series " + (meta.index + 1);
    }

    private static String incompatibleSummary(List<SeriesMeta> incompatibleMetas) {
        if (incompatibleMetas == null || incompatibleMetas.isEmpty()) return "";
        StringBuilder sb = new StringBuilder("Outliers: ");
        int limit = Math.min(3, incompatibleMetas.size());
        for (int i = 0; i < limit; i++) {
            if (i > 0) sb.append("; ");
            SeriesMeta meta = incompatibleMetas.get(i);
            sb.append(displayName(meta)).append(" has ").append(totalSlices(meta)).append(" slices");
        }
        if (incompatibleMetas.size() > limit) {
            sb.append("; and ").append(incompatibleMetas.size() - limit).append(" more");
        }
        return sb.toString();
    }

    private static List<SeriesMeta> copyMetas(List<SeriesMeta> source) {
        List<SeriesMeta> copy = new ArrayList<SeriesMeta>();
        if (source != null) {
            for (int i = 0; i < source.size(); i++) {
                SeriesMeta meta = source.get(i);
                if (meta != null) copy.add(meta);
            }
        }
        return copy;
    }

    static final class RangeCompatibility {
        final List<Integer> compatiblePositions;
        final List<SeriesMeta> compatibleMetas;
        final List<SeriesMeta> incompatibleMetas;
        final int firstIncompatiblePosition;

        RangeCompatibility(List<Integer> compatiblePositions,
                           List<SeriesMeta> compatibleMetas,
                           List<SeriesMeta> incompatibleMetas,
                           int firstIncompatiblePosition) {
            this.compatiblePositions = compatiblePositions == null
                    ? Collections.<Integer>emptyList()
                    : Collections.unmodifiableList(new ArrayList<Integer>(compatiblePositions));
            this.compatibleMetas = compatibleMetas == null
                    ? Collections.<SeriesMeta>emptyList()
                    : Collections.unmodifiableList(new ArrayList<SeriesMeta>(compatibleMetas));
            this.incompatibleMetas = incompatibleMetas == null
                    ? Collections.<SeriesMeta>emptyList()
                    : Collections.unmodifiableList(new ArrayList<SeriesMeta>(incompatibleMetas));
            this.firstIncompatiblePosition = firstIncompatiblePosition;
        }

        boolean isEmptyRemaining() {
            return compatiblePositions.isEmpty() && incompatibleMetas.isEmpty();
        }

        boolean isAllCompatible() {
            return !compatiblePositions.isEmpty() && incompatibleMetas.isEmpty();
        }

        boolean isAllIncompatible() {
            return compatiblePositions.isEmpty() && !incompatibleMetas.isEmpty();
        }
    }

    private static final class DefaultSelection {
        final ZSliceRange range;
        final String label;
        final String hint;

        DefaultSelection(ZSliceRange range, String label, String hint) {
            this.range = range;
            this.label = label == null || label.trim().isEmpty() ? "Default" : label;
            this.hint = hint == null ? "" : hint;
        }
    }
}
