package flash.pipeline.ui.variations;

import ij.ImagePlus;
import ij.ImageStack;
import ij.process.ImageProcessor;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Dialog.ModalityType;
import java.awt.FlowLayout;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Window;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;

public final class CustomCropPicker extends JDialog {

    private final PreviewPanel previewPanel;
    private final JLabel boundsLabel = new JLabel();
    private Rectangle selectedBounds;

    private CustomCropPicker(Window owner, ImagePlus source, Rectangle initialBounds) {
        super(owner, "Custom crop", ModalityType.APPLICATION_MODAL);
        if (source == null) {
            throw new IllegalArgumentException("source must not be null");
        }
        this.previewPanel = new PreviewPanel(source, initialBounds);
        this.selectedBounds = previewPanel.selection();
        buildUi();
        pack();
        setLocationRelativeTo(owner);
    }

    public static Rectangle choose(Window owner, ImagePlus source, Rectangle initialBounds) {
        if (source == null || GraphicsEnvironment.isHeadless()) {
            return null;
        }
        CustomCropPicker picker = new CustomCropPicker(owner, source, initialBounds);
        picker.setVisible(true);
        return picker.selectedBounds == null ? null : new Rectangle(picker.selectedBounds);
    }

    private void buildUi() {
        JPanel root = new JPanel(new BorderLayout(8, 8));
        root.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        root.add(previewPanel, BorderLayout.CENTER);

        previewPanel.setSelectionListener(new Runnable() {
            @Override public void run() {
                updateBoundsLabel();
            }
        });

        JPanel footer = new JPanel(new BorderLayout(8, 0));
        updateBoundsLabel();
        footer.add(boundsLabel, BorderLayout.WEST);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        JButton cancel = new JButton("Cancel");
        cancel.addActionListener(e -> {
            selectedBounds = null;
            dispose();
        });
        JButton ok = new JButton("OK");
        ok.addActionListener(e -> {
            selectedBounds = previewPanel.selection();
            dispose();
        });
        buttons.add(cancel);
        buttons.add(ok);
        footer.add(buttons, BorderLayout.EAST);
        root.add(footer, BorderLayout.SOUTH);
        setContentPane(root);
    }

    private void updateBoundsLabel() {
        Rectangle bounds = previewPanel.selection();
        boundsLabel.setText("x " + bounds.x + ", y " + bounds.y
                + ", w " + bounds.width + ", h " + bounds.height);
    }

    private static BufferedImage projectionImage(ImagePlus source) {
        int width = source.getWidth();
        int height = source.getHeight();
        double[] maxValues = new double[width * height];
        java.util.Arrays.fill(maxValues, -Double.MAX_VALUE);
        ImageStack stack = source.getStack();
        int planes = Math.max(1, stack.getSize());
        for (int z = 1; z <= planes; z++) {
            ImageProcessor processor = stack.getProcessor(z);
            for (int y = 0; y < height; y++) {
                int offset = y * width;
                for (int x = 0; x < width; x++) {
                    double value = processor.getPixelValue(x, y);
                    if (Double.isNaN(value)) {
                        value = 0.0d;
                    }
                    int index = offset + x;
                    if (value > maxValues[index]) {
                        maxValues[index] = value;
                    }
                }
            }
        }
        double min = Double.POSITIVE_INFINITY;
        double max = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < maxValues.length; i++) {
            double value = maxValues[i] == -Double.MAX_VALUE ? 0.0d : maxValues[i];
            maxValues[i] = value;
            if (value < min) min = value;
            if (value > max) max = value;
        }
        if (!Double.isFinite(min) || !Double.isFinite(max) || max <= min) {
            min = 0.0d;
            max = 1.0d;
        }
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_BYTE_GRAY);
        for (int y = 0; y < height; y++) {
            int offset = y * width;
            for (int x = 0; x < width; x++) {
                int gray = (int) Math.round(((maxValues[offset + x] - min) / (max - min)) * 255.0d);
                if (gray < 0) gray = 0;
                if (gray > 255) gray = 255;
                int rgb = (gray << 16) | (gray << 8) | gray;
                image.setRGB(x, y, rgb);
            }
        }
        return image;
    }

    private static final class PreviewPanel extends JPanel {
        private final BufferedImage image;
        private final double scale;
        private Rectangle selection;
        private Point anchor;
        private Runnable selectionListener;

        PreviewPanel(ImagePlus source, Rectangle initialBounds) {
            this.image = projectionImage(source);
            this.scale = scaleFor(image.getWidth(), image.getHeight());
            this.selection = initialSelection(image.getWidth(), image.getHeight(),
                    initialBounds);
            setBackground(Color.DARK_GRAY);
            setBorder(BorderFactory.createLineBorder(new Color(85, 91, 96)));
            setPreferredSize(new Dimension(
                    Math.max(1, (int) Math.ceil(image.getWidth() * scale)),
                    Math.max(1, (int) Math.ceil(image.getHeight() * scale))));
            MouseAdapter mouse = new MouseAdapter() {
                @Override public void mousePressed(MouseEvent e) {
                    anchor = imagePoint(e.getPoint());
                    selection = new Rectangle(anchor.x, anchor.y, 1, 1);
                    notifySelection();
                }

                @Override public void mouseDragged(MouseEvent e) {
                    if (anchor == null) {
                        return;
                    }
                    Point current = imagePoint(e.getPoint());
                    selection = normalized(anchor, current, image.getWidth(), image.getHeight());
                    notifySelection();
                }

                @Override public void mouseReleased(MouseEvent e) {
                    if (anchor != null) {
                        Point current = imagePoint(e.getPoint());
                        selection = normalized(anchor, current, image.getWidth(), image.getHeight());
                        anchor = null;
                        notifySelection();
                    }
                }
            };
            addMouseListener(mouse);
            addMouseMotionListener(mouse);
        }

        void setSelectionListener(Runnable selectionListener) {
            this.selectionListener = selectionListener;
        }

        Rectangle selection() {
            return new Rectangle(selection);
        }

        @Override protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            int drawWidth = (int) Math.round(image.getWidth() * scale);
            int drawHeight = (int) Math.round(image.getHeight() * scale);
            int drawX = (getWidth() - drawWidth) / 2;
            int drawY = (getHeight() - drawHeight) / 2;
            g2.drawImage(image, drawX, drawY, drawWidth, drawHeight, null);
            g2.setColor(new Color(255, 214, 64));
            g2.setStroke(new BasicStroke(2.0f));
            Rectangle scaled = new Rectangle(
                    drawX + (int) Math.round(selection.x * scale),
                    drawY + (int) Math.round(selection.y * scale),
                    Math.max(1, (int) Math.round(selection.width * scale)),
                    Math.max(1, (int) Math.round(selection.height * scale)));
            g2.draw(scaled);
            g2.setColor(new Color(255, 214, 64, 48));
            g2.fill(scaled);
            g2.dispose();
        }

        private void notifySelection() {
            if (selectionListener != null) {
                selectionListener.run();
            }
            repaint();
        }

        private Point imagePoint(Point point) {
            int drawWidth = (int) Math.round(image.getWidth() * scale);
            int drawHeight = (int) Math.round(image.getHeight() * scale);
            int drawX = (getWidth() - drawWidth) / 2;
            int drawY = (getHeight() - drawHeight) / 2;
            int x = (int) Math.floor((point.x - drawX) / scale);
            int y = (int) Math.floor((point.y - drawY) / scale);
            if (x < 0) x = 0;
            if (y < 0) y = 0;
            if (x >= image.getWidth()) x = image.getWidth() - 1;
            if (y >= image.getHeight()) y = image.getHeight() - 1;
            return new Point(x, y);
        }

        private static double scaleFor(int width, int height) {
            double scale = Math.min(720.0d / Math.max(1, width),
                    520.0d / Math.max(1, height));
            if (scale > 4.0d) {
                scale = 4.0d;
            }
            if (scale < 0.05d) {
                scale = 0.05d;
            }
            return scale;
        }

        private static Rectangle initialSelection(int width, int height,
                                                  Rectangle requested) {
            Rectangle imageBounds = new Rectangle(0, 0, width, height);
            Rectangle initial;
            if (requested == null) {
                int cropWidth = Math.min(256, width);
                int cropHeight = Math.min(256, height);
                initial = new Rectangle((width - cropWidth) / 2,
                        (height - cropHeight) / 2,
                        cropWidth,
                        cropHeight);
            } else {
                initial = new Rectangle(requested);
            }
            Rectangle clipped = initial.intersection(imageBounds);
            if (clipped.width <= 0 || clipped.height <= 0) {
                return imageBounds;
            }
            return clipped;
        }

        private static Rectangle normalized(Point a, Point b, int width, int height) {
            int x1 = Math.min(a.x, b.x);
            int y1 = Math.min(a.y, b.y);
            int x2 = Math.max(a.x, b.x);
            int y2 = Math.max(a.y, b.y);
            x1 = Math.max(0, Math.min(width - 1, x1));
            y1 = Math.max(0, Math.min(height - 1, y1));
            x2 = Math.max(0, Math.min(width - 1, x2));
            y2 = Math.max(0, Math.min(height - 1, y2));
            return new Rectangle(x1, y1, Math.max(1, x2 - x1 + 1),
                    Math.max(1, y2 - y1 + 1));
        }
    }
}
