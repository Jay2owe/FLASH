package flash.pipeline.ui.config;

import flash.pipeline.bin.ChannelConfig;
import flash.pipeline.ui.FlashTheme;
import flash.pipeline.zslice.ZSliceMode;
import flash.pipeline.zslice.ZSliceRange;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.ScrollPaneConstants;
import javax.swing.SwingConstants;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ConfigReviewPanel extends JPanel {

    public interface StepEditListener {
        void editStep(int stepIndex);
    }

    private static final int STEP_CHANNELS = 1;
    private static final int STEP_SCOPE = 2;
    private static final int STEP_SETTINGS = 3;
    private static final int STEP_Z_SLICES = 4;
    private static final int STEP_QC = 5;
    private static final int STEP_REVIEW = 6;

    private static final int SETTING_FILTER_PARAMETERS = 0;
    private static final int SETTING_MIN_MAX = 1;
    private static final int SETTING_ROI_INTENSITY_THRESHOLD = 2;
    private static final int SETTING_OBJECT_THRESHOLD = 3;
    private static final int SETTING_OBJECT_SIZE_FILTER = 4;
    private static final int SETTING_SEGMENTATION_METHOD = 5;

    private static final String MARK_DONE = "\u2713";
    private static final String MARK_CURRENT = "\u25B8";
    private static final String MARK_EDIT = "\u270E";

    private final ReviewModel model;
    private final JPanel railPanel;
    private final JPanel detailShell;
    private final Map<Integer, JButton> railButtons = new LinkedHashMap<Integer, JButton>();
    private StepEditListener editListener;
    private int selectedStepIndex = STEP_CHANNELS;

    public ConfigReviewPanel(ReviewModel model) {
        this.model = model == null ? ReviewModel.empty() : model;
        this.railPanel = new JPanel();
        this.detailShell = new JPanel(new BorderLayout());

        setLayout(new BorderLayout());
        setOpaque(false);
        setPreferredSize(new Dimension(820, 500));

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, buildRail(), detailShell);
        split.setBorder(BorderFactory.createLineBorder(FlashTheme.BORDER));
        split.setDividerSize(6);
        split.setResizeWeight(0.0d);
        split.setDividerLocation(215);
        add(split, BorderLayout.CENTER);

        selectStep(STEP_CHANNELS);
    }

    public void setStepEditListener(StepEditListener listener) {
        this.editListener = listener;
    }

    public void selectStep(int stepIndex) {
        ReviewStep step = model.step(stepIndex);
        if (step == null) {
            return;
        }
        selectedStepIndex = stepIndex;
        updateRailSelection();
        showDetail(step);
    }

    public int selectedStepIndexForTest() {
        return selectedStepIndex;
    }

    private JComponent buildRail() {
        railPanel.setLayout(new BoxLayout(railPanel, BoxLayout.Y_AXIS));
        railPanel.setBackground(FlashTheme.SURFACE_MUTED);
        railPanel.setBorder(FlashTheme.pad(10, 10, 10, 10));
        railPanel.setPreferredSize(new Dimension(215, 460));

        JLabel title = new JLabel("Steps");
        title.setFont(FlashTheme.h2());
        title.setForeground(FlashTheme.TEXT_HEADER);
        title.setAlignmentX(Component.LEFT_ALIGNMENT);
        railPanel.add(title);
        railPanel.add(Box.createVerticalStrut(6));

        for (ReviewStep step : model.steps) {
            JPanel row = buildRailRow(step);
            railPanel.add(row);
            railPanel.add(Box.createVerticalStrut(4));
        }

        railPanel.add(Box.createVerticalGlue());
        JLabel footer = new JLabel(model.footerText);
        footer.setFont(FlashTheme.bodyMedium());
        footer.setForeground(FlashTheme.SUCCESS_FG);
        footer.setAlignmentX(Component.LEFT_ALIGNMENT);
        footer.setBorder(FlashTheme.pad(8, 2, 0, 2));
        railPanel.add(footer);
        return railPanel;
    }

    private JPanel buildRailRow(final ReviewStep step) {
        JPanel row = new JPanel(new BorderLayout(4, 0));
        row.setOpaque(false);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 32));
        row.setAlignmentX(Component.LEFT_ALIGNMENT);

        String label = markerFor(step.index) + " " + step.index + " " + step.shortLabel;
        JButton select = new JButton(label);
        select.setHorizontalAlignment(SwingConstants.LEFT);
        select.setFocusPainted(false);
        select.setBorder(FlashTheme.pad(4, 6, 4, 6));
        select.setToolTipText("Review " + step.title);
        select.addActionListener(new ActionListener() {
            @Override public void actionPerformed(ActionEvent e) {
                selectStep(step.index);
            }
        });
        railButtons.put(Integer.valueOf(step.index), select);
        row.add(select, BorderLayout.CENTER);

        if (step.editable) {
            JButton edit = new JButton(MARK_EDIT);
            edit.setPreferredSize(new Dimension(30, 26));
            edit.setToolTipText("Edit " + step.title);
            edit.setFocusPainted(false);
            edit.addActionListener(new ActionListener() {
                @Override public void actionPerformed(ActionEvent e) {
                    requestEdit(step.index);
                }
            });
            row.add(edit, BorderLayout.EAST);
        }
        return row;
    }

    private String markerFor(int stepIndex) {
        if (stepIndex == STEP_REVIEW) return MARK_CURRENT;
        return MARK_DONE;
    }

    private void updateRailSelection() {
        for (Map.Entry<Integer, JButton> entry : railButtons.entrySet()) {
            boolean selected = entry.getKey().intValue() == selectedStepIndex;
            JButton button = entry.getValue();
            button.setBackground(selected ? FlashTheme.STAGE_ACTIVE_BG : FlashTheme.SURFACE_RAISED);
            button.setForeground(FlashTheme.TEXT_PRIMARY);
            button.setOpaque(true);
            button.setContentAreaFilled(true);
            button.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(selected ? FlashTheme.PRIMARY_BORDER : FlashTheme.BORDER),
                    FlashTheme.pad(4, 6, 4, 6)));
        }
    }

    private void showDetail(final ReviewStep step) {
        detailShell.removeAll();
        detailShell.setBackground(FlashTheme.SURFACE_RAISED);

        JPanel header = new JPanel(new BorderLayout(8, 0));
        header.setBackground(FlashTheme.SURFACE_RAISED);
        header.setBorder(FlashTheme.pad(10, 12, 8, 12));
        JLabel title = new JLabel(step.title);
        title.setFont(FlashTheme.h1());
        title.setForeground(FlashTheme.TEXT_HEADER);
        header.add(title, BorderLayout.WEST);
        if (step.editable) {
            JButton edit = new JButton("Edit");
            edit.setToolTipText("Edit " + step.title);
            styleEditButton(edit);
            edit.addActionListener(new ActionListener() {
                @Override public void actionPerformed(ActionEvent e) {
                    requestEdit(step.index);
                }
            });
            JPanel editWrap = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
            editWrap.setOpaque(false);
            editWrap.add(edit);
            header.add(editWrap, BorderLayout.EAST);
        }
        detailShell.add(header, BorderLayout.NORTH);

        ConfigQcScrollableBody body = new ConfigQcScrollableBody(new GridBagLayout());
        body.setBackground(FlashTheme.SURFACE_RAISED);
        body.setOpaque(true);
        body.setBorder(FlashTheme.pad(2, 12, 12, 12));
        renderRows(body, step.rows);

        JScrollPane scroll = new JScrollPane(body);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.getViewport().setBackground(FlashTheme.SURFACE_RAISED);
        scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED);
        detailShell.add(scroll, BorderLayout.CENTER);
        detailShell.revalidate();
        detailShell.repaint();
    }

    private void renderRows(JPanel body, List<ReviewRow> rows) {
        GridBagConstraints g = new GridBagConstraints();
        g.gridx = 0;
        g.gridy = 0;
        g.weightx = 1.0d;
        g.fill = GridBagConstraints.HORIZONTAL;
        g.insets = new Insets(0, 0, 6, 0);

        for (ReviewRow row : rows) {
            if (row.section) {
                body.add(sectionLabel(row.subject), g);
            } else {
                body.add(valueRow(row), g);
            }
            g.gridy++;
        }
        g.weighty = 1.0d;
        body.add(Box.createVerticalGlue(), g);
    }

    private JLabel sectionLabel(String text) {
        JLabel label = new JLabel(text);
        label.setFont(FlashTheme.h2());
        label.setForeground(FlashTheme.TEXT_SUBHEADER);
        label.setBorder(FlashTheme.pad(8, 0, 2, 0));
        return label;
    }

    private JPanel valueRow(ReviewRow row) {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBackground(FlashTheme.SURFACE_RAISED);
        panel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, FlashTheme.BORDER_MUTED),
                FlashTheme.pad(5, 0, 5, 0)));

        GridBagConstraints g = new GridBagConstraints();
        g.gridy = 0;
        g.insets = new Insets(0, 0, 0, 10);
        g.anchor = GridBagConstraints.WEST;

        g.gridx = 0;
        g.weightx = 0.30d;
        g.fill = GridBagConstraints.HORIZONTAL;
        panel.add(cell(row.subject, FlashTheme.bodyMedium(), FlashTheme.TEXT_PRIMARY), g);

        g.gridx = 1;
        g.weightx = 0.25d;
        panel.add(cell(row.setting, FlashTheme.body(), FlashTheme.TEXT_MUTED), g);

        g.gridx = 2;
        g.weightx = 0.45d;
        g.insets = new Insets(0, 0, 0, 0);
        panel.add(cell(row.value, FlashTheme.body(), FlashTheme.TEXT_PRIMARY), g);
        return panel;
    }

    private JLabel cell(String text, Font font, Color color) {
        JLabel label = new JLabel(text == null ? "" : text);
        label.setFont(font);
        label.setForeground(color);
        return label;
    }

    private void requestEdit(int stepIndex) {
        if (editListener != null) {
            editListener.editStep(stepIndex);
        }
    }

    private static void styleEditButton(JButton button) {
        button.setBackground(FlashTheme.INFO_BG);
        button.setForeground(FlashTheme.INFO_FG);
        button.setOpaque(true);
        button.setContentAreaFilled(true);
        button.setFocusPainted(false);
        button.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(FlashTheme.INFO_BORDER),
                FlashTheme.pad(3, 10, 3, 10)));
    }

    public static final class ReviewModel {
        public final List<ReviewStep> steps;
        public final String footerText;

        private ReviewModel(List<ReviewStep> steps, String footerText) {
            this.steps = Collections.unmodifiableList(new ArrayList<ReviewStep>(steps));
            this.footerText = footerText == null ? "" : footerText;
        }

        public static ReviewModel empty() {
            return from(null, null);
        }

        public static ReviewModel from(ChannelConfig cfg, boolean[][] customSettings) {
            ChannelConfig safe = cfg == null ? new ChannelConfig() : cfg;
            List<ReviewStep> steps = new ArrayList<ReviewStep>();
            steps.add(new ReviewStep(STEP_CHANNELS, "Channels", "Channel identity", true,
                    channelRows(safe)));
            steps.add(new ReviewStep(STEP_SCOPE, "Scope", "Analysis scope", true,
                    scopeRows(safe)));
            steps.add(new ReviewStep(STEP_SETTINGS, "Settings", "Settings mode", true,
                    settingsRows(safe, customSettings)));
            steps.add(new ReviewStep(STEP_Z_SLICES, "Z-slices", "Z-slice QC", true,
                    zSliceRows(safe)));
            steps.add(new ReviewStep(STEP_QC, "Quality QC", "Quality check", true,
                    qualityRows(safe)));
            steps.add(new ReviewStep(STEP_REVIEW, "Review", "Review configuration", false,
                    reviewRows(safe)));
            return new ReviewModel(steps, "All steps done " + MARK_DONE);
        }

        public ReviewStep step(int index) {
            for (ReviewStep step : steps) {
                if (step.index == index) return step;
            }
            return null;
        }
    }

    public static final class ReviewStep {
        public final int index;
        public final String shortLabel;
        public final String title;
        public final boolean editable;
        public final List<ReviewRow> rows;

        private ReviewStep(int index, String shortLabel, String title, boolean editable,
                           List<ReviewRow> rows) {
            this.index = index;
            this.shortLabel = shortLabel == null ? "" : shortLabel;
            this.title = title == null ? "" : title;
            this.editable = editable;
            this.rows = Collections.unmodifiableList(new ArrayList<ReviewRow>(rows));
        }
    }

    public static final class ReviewRow {
        public final String subject;
        public final String setting;
        public final String value;
        public final boolean section;

        private ReviewRow(String subject, String setting, String value, boolean section) {
            this.subject = subject == null ? "" : subject;
            this.setting = setting == null ? "" : setting;
            this.value = value == null ? "" : value;
            this.section = section;
        }

        public static ReviewRow section(String label) {
            return new ReviewRow(label, "", "", true);
        }

        public static ReviewRow value(String subject, String setting, String value) {
            return new ReviewRow(subject, setting, value, false);
        }
    }

    private static List<ReviewRow> channelRows(ChannelConfig cfg) {
        List<ReviewRow> rows = new ArrayList<ReviewRow>();
        rows.add(ReviewRow.section("Channels"));
        if (channelCount(cfg) == 0) {
            rows.add(ReviewRow.value("Channels", "Count", "0"));
            return rows;
        }
        for (int i = 0; i < cfg.channels.size(); i++) {
            ChannelConfig.Channel channel = cfg.channels.get(i);
            String subject = channelSubject(channel, i);
            rows.add(ReviewRow.value(subject, "LUT color", value(channel == null ? null : channel.color, "Grays")));
            rows.add(ReviewRow.value(subject, "Marker", markerSummary(channel)));
        }
        return rows;
    }

    private static List<ReviewRow> scopeRows(ChannelConfig cfg) {
        List<ReviewRow> rows = new ArrayList<ReviewRow>();
        rows.add(ReviewRow.section("Analysis scope"));
        ZSliceMode mode = cfg == null || cfg.zSliceMode == null ? ZSliceMode.FULL : cfg.zSliceMode;
        rows.add(ReviewRow.value("Z-stack", "Mode", mode.displayName));
        rows.add(ReviewRow.value("Z-stack", "Subset review",
                mode.usesSubset() ? "Enabled" : "Full stack"));
        return rows;
    }

    private static List<ReviewRow> settingsRows(ChannelConfig cfg, boolean[][] customSettings) {
        List<ReviewRow> rows = new ArrayList<ReviewRow>();
        rows.add(ReviewRow.section("Selected custom settings"));
        int count = channelCount(cfg);
        if (count == 0) {
            rows.add(ReviewRow.value("Channels", "Settings", "No channels"));
            return rows;
        }
        for (int i = 0; i < count; i++) {
            rows.add(ReviewRow.value(channelSubject(cfg.channels.get(i), i),
                    "QC fields", selectedSettingSummary(customSettings, i)));
        }
        return rows;
    }

    private static List<ReviewRow> zSliceRows(ChannelConfig cfg) {
        List<ReviewRow> rows = new ArrayList<ReviewRow>();
        rows.add(ReviewRow.section("Z-slice selections"));
        ZSliceMode mode = cfg == null || cfg.zSliceMode == null ? ZSliceMode.FULL : cfg.zSliceMode;
        rows.add(ReviewRow.value("Z-stack", "Mode", mode.displayName));
        if (!mode.usesSubset()) {
            rows.add(ReviewRow.value("Z-stack", "Range", "Full stack"));
            return rows;
        }
        if (cfg == null || cfg.zSliceSelections == null || cfg.zSliceSelections.isEmpty()) {
            rows.add(ReviewRow.value("Z-stack", "Ranges", "Not selected"));
            return rows;
        }
        for (Map.Entry<String, ZSliceRange> entry : cfg.zSliceSelections.entrySet()) {
            ZSliceRange range = entry.getValue();
            rows.add(ReviewRow.value("Series " + value(entry.getKey(), "?"),
                    "Range", range == null ? "Not selected" : range.toToken()));
        }
        return rows;
    }

    private static List<ReviewRow> qualityRows(ChannelConfig cfg) {
        List<ReviewRow> rows = new ArrayList<ReviewRow>();
        rows.add(ReviewRow.section("Per-channel QC values"));
        int count = channelCount(cfg);
        if (count == 0) {
            rows.add(ReviewRow.value("Channels", "QC", "No channels"));
            return rows;
        }
        for (int i = 0; i < count; i++) {
            ChannelConfig.Channel channel = cfg.channels.get(i);
            String subject = channelSubject(channel, i);
            rows.add(ReviewRow.value(subject, "Filter preset",
                    value(channel == null ? null : channel.filterPreset, "Default")));
            rows.add(ReviewRow.value(subject, "Display min-max",
                    value(channel == null ? null : channel.minmax, "None")));
            rows.add(ReviewRow.value(subject, "Object threshold",
                    value(channel == null ? null : channel.threshold, "default")));
            rows.add(ReviewRow.value(subject, "Intensity threshold",
                    value(channel == null ? null : channel.intensityThreshold, "default")));
            rows.add(ReviewRow.value(subject, "Particle size",
                    value(channel == null ? null : channel.size, "100-Infinity")));
            rows.add(ReviewRow.value(subject, "Segmentation",
                    segmentationSummary(channel == null ? null : channel.segmentationMethod)));
        }
        return rows;
    }

    private static List<ReviewRow> reviewRows(ChannelConfig cfg) {
        List<ReviewRow> rows = new ArrayList<ReviewRow>();
        rows.add(ReviewRow.section("Ready to save"));
        rows.add(ReviewRow.value("Configuration", "Channels", String.valueOf(channelCount(cfg))));
        ZSliceMode mode = cfg == null || cfg.zSliceMode == null ? ZSliceMode.FULL : cfg.zSliceMode;
        rows.add(ReviewRow.value("Configuration", "Z-slices", mode.displayName));
        rows.add(ReviewRow.value("Configuration", "Status", "Ready to save"));
        return rows;
    }

    private static int channelCount(ChannelConfig cfg) {
        return cfg == null || cfg.channels == null ? 0 : cfg.channels.size();
    }

    private static String channelSubject(ChannelConfig.Channel channel, int index) {
        return "C" + (index + 1) + " " + value(channel == null ? null : channel.name,
                "Channel" + (index + 1));
    }

    private static String markerSummary(ChannelConfig.Channel channel) {
        if (channel == null) return "Unspecified";
        List<String> parts = new ArrayList<String>();
        if (hasText(channel.markerId)) parts.add(channel.markerId.trim());
        if (hasText(channel.markerShape)) parts.add(channel.markerShape.trim());
        if (channel.markerCrowdingSensitive) parts.add("crowding-sensitive");
        return parts.isEmpty() ? "Unspecified" : join(parts, ", ");
    }

    private static String selectedSettingSummary(boolean[][] customSettings, int channelIndex) {
        List<String> selected = new ArrayList<String>();
        addSettingIfSelected(selected, customSettings, SETTING_FILTER_PARAMETERS, channelIndex,
                "Filter parameters");
        addSettingIfSelected(selected, customSettings, SETTING_MIN_MAX, channelIndex,
                "Display min-max");
        addSettingIfSelected(selected, customSettings, SETTING_ROI_INTENSITY_THRESHOLD, channelIndex,
                "ROI intensity threshold");
        addSettingIfSelected(selected, customSettings, SETTING_OBJECT_THRESHOLD, channelIndex,
                "Object threshold");
        addSettingIfSelected(selected, customSettings, SETTING_OBJECT_SIZE_FILTER, channelIndex,
                "Object size filter");
        addSettingIfSelected(selected, customSettings, SETTING_SEGMENTATION_METHOD, channelIndex,
                "Segmentation method");
        return selected.isEmpty() ? "Standard defaults" : join(selected, ", ");
    }

    private static void addSettingIfSelected(List<String> selected, boolean[][] customSettings,
                                             int row, int channelIndex, String label) {
        if (customSettings == null
                || row < 0
                || row >= customSettings.length
                || customSettings[row] == null
                || channelIndex < 0
                || channelIndex >= customSettings[row].length) {
            return;
        }
        if (customSettings[row][channelIndex]) selected.add(label);
    }

    private static String segmentationSummary(String token) {
        return SegmentationMethodStage.displayTextForChoice(value(token, "classical"), null);
    }

    private static String value(String raw, String fallback) {
        return hasText(raw) ? raw.trim() : fallback;
    }

    private static boolean hasText(String raw) {
        return raw != null && !raw.trim().isEmpty();
    }

    private static String join(List<String> values, String separator) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) sb.append(separator);
            sb.append(values.get(i));
        }
        return sb.toString();
    }
}
