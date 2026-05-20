package flash.pipeline.decontamination;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ImageIcon;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import javax.swing.border.EmptyBorder;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Swing panel that lays out grouped Spectral Decontamination previews by condition.
 */
public class SpectralPreviewPanel extends JPanel {

    private static final Color BACKGROUND = new Color(248, 248, 248);
    private static final Color CONDITION_BACKGROUND = new Color(233, 238, 242);
    private static final Color HEADER_BACKGROUND = new Color(238, 238, 238);
    private static final Color CELL_BACKGROUND = Color.WHITE;
    private static final Color BORDER_COLOR = new Color(214, 214, 214);
    private static final Font HEADER_FONT = new Font(Font.SANS_SERIF, Font.BOLD, 12);
    private static final Font BODY_FONT = new Font(Font.SANS_SERIF, Font.PLAIN, 11);
    private static final Font META_FONT = new Font(Font.SANS_SERIF, Font.PLAIN, 12);

    public SpectralPreviewPanel(List<SpectralPreviewRenderer.RenderedPreview> previews) {
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        setBackground(BACKGROUND);
        setBorder(new EmptyBorder(4, 4, 4, 4));

        add(buildIntro());
        add(Box.createVerticalStrut(8));
        add(buildHeaderRow());
        add(Box.createVerticalStrut(6));

        Map<String, List<SpectralPreviewRenderer.RenderedPreview>> grouped = groupByCondition(previews);
        if (grouped.isEmpty()) {
            add(buildEmptyState());
            return;
        }

        for (Map.Entry<String, List<SpectralPreviewRenderer.RenderedPreview>> entry : grouped.entrySet()) {
            add(buildConditionHeader(entry.getKey(), entry.getValue().size()));
            add(Box.createVerticalStrut(6));
            for (SpectralPreviewRenderer.RenderedPreview preview : entry.getValue()) {
                add(buildPreviewRow(preview));
                add(Box.createVerticalStrut(8));
            }
            add(Box.createVerticalStrut(8));
        }
    }

    private JPanel buildIntro() {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBackground(BACKGROUND);
        panel.setAlignmentX(Component.LEFT_ALIGNMENT);

        JLabel title = new JLabel("Preview the same correction stack that batch processing will run.");
        title.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 13));
        title.setAlignmentX(Component.LEFT_ALIGNMENT);
        panel.add(title);

        JLabel body = new JLabel("<html><body style='width:1100px;'>"
                + "Rows are grouped by condition so controls and experimental images can be compared directly. "
                + "Use Back if the corrected target or final mask looks wrong for any image in this subset."
                + "</body></html>");
        body.setFont(BODY_FONT);
        body.setAlignmentX(Component.LEFT_ALIGNMENT);
        panel.add(Box.createVerticalStrut(4));
        panel.add(body);
        return panel;
    }

    private JPanel buildHeaderRow() {
        JPanel row = new JPanel(new GridBagLayout());
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        row.setBackground(HEADER_BACKGROUND);
        row.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(BORDER_COLOR),
                new EmptyBorder(6, 8, 6, 8)));

        addHeaderCell(row, 0, "Image");
        addHeaderCell(row, 1, "Raw target");
        addHeaderCell(row, 2, "Bleed-through");
        addHeaderCell(row, 3, "Autofluorescence");
        addHeaderCell(row, 4, "Corrected target");
        addHeaderCell(row, 5, "Final overlay");
        addHeaderCell(row, 6, "Metrics");
        return row;
    }

    private void addHeaderCell(JPanel row, int column, String text) {
        GridBagConstraints gbc = baseConstraints(column);
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.weightx = column == 6 ? 1.0 : 0.0;
        JLabel label = new JLabel(text);
        label.setFont(HEADER_FONT);
        row.add(label, gbc);
    }

    private JPanel buildConditionHeader(String condition, int rowCount) {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.X_AXIS));
        panel.setBackground(CONDITION_BACKGROUND);
        panel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(BORDER_COLOR),
                new EmptyBorder(8, 10, 8, 10)));
        panel.setAlignmentX(Component.LEFT_ALIGNMENT);

        JLabel label = new JLabel(condition + " (" + rowCount + " image" + (rowCount == 1 ? "" : "s") + ")");
        label.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 13));
        panel.add(label);
        panel.add(Box.createHorizontalGlue());
        return panel;
    }

    private JPanel buildPreviewRow(SpectralPreviewRenderer.RenderedPreview preview) {
        JPanel row = new JPanel(new GridBagLayout());
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        row.setBackground(CELL_BACKGROUND);
        row.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(BORDER_COLOR),
                new EmptyBorder(8, 8, 8, 8)));

        GridBagConstraints meta = baseConstraints(0);
        meta.anchor = GridBagConstraints.NORTHWEST;
        meta.fill = GridBagConstraints.BOTH;
        row.add(buildMetaCell(preview), meta);

        row.add(buildSingleImageCell(preview.rawTarget), baseConstraints(1));
        row.add(buildGroupCell(preview.bleedThroughChannels), baseConstraints(2));
        row.add(buildGroupCell(preview.autofluorescenceChannels), baseConstraints(3));
        row.add(buildSingleImageCell(preview.correctedTarget), baseConstraints(4));
        row.add(buildSingleImageCell(preview.finalOverlay), baseConstraints(5));

        GridBagConstraints metrics = baseConstraints(6);
        metrics.weightx = 1.0;
        metrics.fill = GridBagConstraints.BOTH;
        row.add(buildMetricsCell(preview), metrics);
        return row;
    }

    private GridBagConstraints baseConstraints(int column) {
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.gridx = column;
        gbc.gridy = 0;
        gbc.insets = new Insets(2, 4, 2, 4);
        gbc.anchor = GridBagConstraints.NORTHWEST;
        gbc.fill = GridBagConstraints.VERTICAL;
        gbc.weighty = 1.0;
        return gbc;
    }

    private JPanel buildMetaCell(SpectralPreviewRenderer.RenderedPreview preview) {
        JPanel panel = verticalCell();
        SpectralPreviewSelector.PreviewSelection selection = preview.selection;

        panel.add(metaLine(bold(selection.candidate.seriesName)));
        panel.add(metaLine("Series " + (selection.candidate.seriesIndex + 1)
                + " | " + selection.candidate.animalName));
        panel.add(metaLine("Condition role: " + selection.conditionRole));
        panel.add(metaLine("Preview role: " + selection.selectionRole.replace(';', ',')));
        panel.add(metaLine("Source size: " + preview.sourceWidth + " x " + preview.sourceHeight));
        return panel;
    }

    private JPanel buildSingleImageCell(SpectralPreviewRenderer.RenderedImage image) {
        JPanel cell = verticalCell();
        cell.add(imageTitle(image.label));
        cell.add(Box.createVerticalStrut(4));
        JLabel label = new JLabel(new ImageIcon(image.image));
        label.setAlignmentX(Component.LEFT_ALIGNMENT);
        label.setBorder(BorderFactory.createLineBorder(BORDER_COLOR));
        cell.add(label);
        return cell;
    }

    private JPanel buildGroupCell(List<SpectralPreviewRenderer.RenderedImage> images) {
        JPanel cell = verticalCell();
        List<SpectralPreviewRenderer.RenderedImage> safeImages = images == null
                ? new ArrayList<SpectralPreviewRenderer.RenderedImage>()
                : images;
        for (int i = 0; i < safeImages.size(); i++) {
            SpectralPreviewRenderer.RenderedImage image = safeImages.get(i);
            cell.add(imageTitle(image.label));
            cell.add(Box.createVerticalStrut(4));
            JLabel label = new JLabel(new ImageIcon(image.image));
            label.setAlignmentX(Component.LEFT_ALIGNMENT);
            label.setBorder(BorderFactory.createLineBorder(BORDER_COLOR));
            cell.add(label);
            if (i < safeImages.size() - 1) {
                cell.add(Box.createVerticalStrut(8));
            }
        }
        return cell;
    }

    private JPanel buildMetricsCell(SpectralPreviewRenderer.RenderedPreview preview) {
        JPanel panel = verticalCell();
        panel.setPreferredSize(new Dimension(260, 220));

        SpectralPreviewRenderer.PreviewMetrics metrics = preview.metrics;
        panel.add(metaLine(metrics.targetPositiveLabel));
        panel.add(metaLine("Saturated voxel fraction: " + percent(metrics.saturatedFraction)));
        if (metrics.objectsKept != null) {
            panel.add(metaLine("Objects kept: " + metrics.objectsKept));
        }
        if (metrics.objectsRemoved != null) {
            panel.add(metaLine("Objects removed: " + metrics.objectsRemoved));
        }

        if (!metrics.warningLines.isEmpty()) {
            panel.add(Box.createVerticalStrut(6));
            panel.add(metaLine(bold("Warnings")));
            for (String warningLine : metrics.warningLines) {
                panel.add(metaLine(warningLine));
            }
        }

        if (!metrics.detailLines.isEmpty()) {
            panel.add(Box.createVerticalStrut(6));
            panel.add(metaLine(bold("Thresholds")));
            for (String detailLine : metrics.detailLines) {
                panel.add(metaLine(detailLine));
            }
        }

        panel.add(Box.createVerticalStrut(6));
        panel.add(metaLine(bold("Correction coefficients")));
        if (metrics.coefficientLines.isEmpty()) {
            panel.add(metaLine("Not estimated by the current stack."));
        } else {
            for (String line : metrics.coefficientLines) {
                panel.add(metaLine(line));
            }
        }
        return panel;
    }

    private JPanel buildEmptyState() {
        JPanel panel = verticalCell();
        panel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(BORDER_COLOR),
                new EmptyBorder(12, 12, 12, 12)));
        panel.add(metaLine("No preview images were rendered."));
        return panel;
    }

    private JPanel verticalCell() {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBackground(CELL_BACKGROUND);
        panel.setAlignmentY(Component.TOP_ALIGNMENT);
        return panel;
    }

    private JLabel imageTitle(String text) {
        JLabel label = new JLabel(text == null ? "" : text);
        label.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 11));
        label.setAlignmentX(Component.LEFT_ALIGNMENT);
        return label;
    }

    private JLabel metaLine(String text) {
        JLabel label = new JLabel("<html><body style='width:240px;'>" + text + "</body></html>", SwingConstants.LEFT);
        label.setFont(META_FONT);
        label.setAlignmentX(Component.LEFT_ALIGNMENT);
        return label;
    }

    private String bold(String text) {
        return "<b>" + (text == null ? "" : text) + "</b>";
    }

    private String percent(double value) {
        return String.format(java.util.Locale.US, "%.3f%%", value * 100.0);
    }

    private Map<String, List<SpectralPreviewRenderer.RenderedPreview>> groupByCondition(
            List<SpectralPreviewRenderer.RenderedPreview> previews) {
        LinkedHashMap<String, List<SpectralPreviewRenderer.RenderedPreview>> grouped =
                new LinkedHashMap<String, List<SpectralPreviewRenderer.RenderedPreview>>();
        if (previews == null) {
            return grouped;
        }
        for (SpectralPreviewRenderer.RenderedPreview preview : previews) {
            if (preview == null || preview.selection == null || preview.selection.candidate == null) {
                continue;
            }
            String condition = preview.selection.candidate.conditionName;
            if (condition == null || condition.trim().isEmpty()) {
                condition = "Unassigned";
            }
            List<SpectralPreviewRenderer.RenderedPreview> group = grouped.get(condition);
            if (group == null) {
                group = new ArrayList<SpectralPreviewRenderer.RenderedPreview>();
                grouped.put(condition, group);
            }
            group.add(preview);
        }
        return grouped;
    }
}
