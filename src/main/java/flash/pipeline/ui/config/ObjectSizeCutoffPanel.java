package flash.pipeline.ui.config;

import flash.pipeline.ui.FlashTheme;
import flash.pipeline.ui.preview.ObjectSizeFilterPreview;

import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;

final class ObjectSizeCutoffPanel extends JPanel {

    private final CutoffLine minLine = new CutoffLine(ObjectSizeFilterPreview.belowMinColor());
    private final CutoffLine maxLine = new CutoffLine(ObjectSizeFilterPreview.aboveMaxColor());
    private final JLabel minLabel = new JLabel("Min cutoff: not previewed");
    private final JLabel maxLabel = new JLabel("Max cutoff: Infinity");
    private final JLabel summaryLabel = new JLabel("Run object preview to label removed objects.");

    ObjectSizeCutoffPanel() {
        setOpaque(false);
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        setAlignmentX(JComponent.LEFT_ALIGNMENT);
        setBorder(FlashTheme.pad(2, 0, 2, 0));

        minLabel.setForeground(FlashTheme.TEXT_SUBHEADER);
        maxLabel.setForeground(FlashTheme.TEXT_SUBHEADER);
        summaryLabel.setForeground(FlashTheme.TEXT_SUBHEADER);

        add(lineRow(minLine, minLabel));
        add(Box.createVerticalStrut(2));
        add(lineRow(maxLine, maxLabel));
        add(Box.createVerticalStrut(2));
        add(summaryLabel);
    }

    void setSummary(ObjectSizeFilterPreview.Summary summary) {
        if (summary == null) {
            minLine.setLinePixels(14);
            minLine.setVisible(true);
            maxLine.setVisible(false);
            minLabel.setText("Min cutoff: not previewed");
            maxLabel.setVisible(true);
            maxLabel.setText("Max cutoff: Infinity");
            summaryLabel.setText("Run object preview to label removed objects.");
            return;
        }
        minLine.setLinePixels(summary.minLinePixels);
        minLine.setVisible(true);
        minLabel.setText("Min removes < " + summary.minVoxels + " voxels ("
                + summary.minDiameterText + "), removes " + summary.belowMinCount);

        maxLine.setVisible(summary.maxFinite);
        maxLabel.setVisible(summary.maxFinite);
        if (summary.maxFinite) {
            maxLine.setLinePixels(summary.maxLinePixels);
            maxLabel.setText("Max removes > " + summary.maxVoxels + " voxels ("
                    + summary.maxDiameterText + "), removes " + summary.aboveMaxCount);
        }
        summaryLabel.setText(summary.statusText());
        revalidate();
        repaint();
    }

    String summaryTextForTest() {
        return summaryLabel.getText();
    }

    private static JPanel lineRow(CutoffLine line, JLabel label) {
        JPanel row = new JPanel();
        row.setOpaque(false);
        row.setLayout(new BoxLayout(row, BoxLayout.X_AXIS));
        row.setAlignmentX(JComponent.LEFT_ALIGNMENT);
        row.add(line);
        row.add(Box.createHorizontalStrut(8));
        row.add(label);
        row.add(Box.createHorizontalGlue());
        return row;
    }

    private static final class CutoffLine extends JComponent {
        private final Color color;
        private int linePixels = 14;

        CutoffLine(Color color) {
            this.color = color == null ? FlashTheme.TEXT_MUTED : color;
            setPreferredSize(new Dimension(170, 14));
            setMinimumSize(new Dimension(170, 14));
            setMaximumSize(new Dimension(170, 14));
        }

        void setLinePixels(int linePixels) {
            this.linePixels = Math.max(10, Math.min(160, linePixels));
            repaint();
        }

        @Override protected void paintComponent(Graphics graphics) {
            super.paintComponent(graphics);
            Graphics2D g = (Graphics2D) graphics.create();
            try {
                g.setColor(color);
                g.setStroke(new BasicStroke(3f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                int y = getHeight() / 2;
                g.drawLine(2, y, Math.min(getWidth() - 2, 2 + linePixels), y);
            } finally {
                g.dispose();
            }
        }
    }
}
