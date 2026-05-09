package flash.pipeline.ui.preview;

import ij.ImagePlus;
import ij.ImageStack;
import ij.process.ColorProcessor;
import ij.process.ImageProcessor;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSlider;
import javax.swing.SwingConstants;
import javax.swing.event.ChangeEvent;
import javax.swing.event.ChangeListener;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.RenderingHints;
import java.awt.image.ColorModel;
import java.awt.image.IndexColorModel;

public final class ImagePreviewPanel extends JPanel {

    public interface ZSliceChangeListener {
        void zSliceChanged(ImagePreviewPanel source, int zSlice);
    }

    private final JLabel titleLabel = new JLabel("No image selected.");
    private final JLabel detailLabel = new JLabel(" ");
    private final JLabel statusLabel = new JLabel(" ");
    private final JLabel sliceLabel = new JLabel(" ");
    private final JSlider zSlider = new JSlider(1, 1, 1);
    private final CanvasPanel canvas = new CanvasPanel();

    private ImagePlus image;
    private int currentC = 1;
    private int currentZ = 1;
    private int currentT = 1;
    private boolean updatingSlider;
    private ZSliceChangeListener zSliceChangeListener;
    private PreviewDisplaySettings displaySettings = PreviewDisplaySettings.defaultFor("Grays");
    private boolean displaySettingsEnabled = true;

    public ImagePreviewPanel(String title) {
        super(new BorderLayout(6, 6));
        setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createTitledBorder(title == null ? "Image preview" : title),
                BorderFactory.createEmptyBorder(6, 6, 6, 6)));

        JPanel labels = new JPanel();
        labels.setLayout(new BoxLayout(labels, BoxLayout.Y_AXIS));
        labels.setOpaque(false);
        titleLabel.setAlignmentX(LEFT_ALIGNMENT);
        detailLabel.setAlignmentX(LEFT_ALIGNMENT);
        statusLabel.setAlignmentX(LEFT_ALIGNMENT);
        detailLabel.setForeground(new Color(90, 90, 90));
        statusLabel.setForeground(new Color(90, 90, 90));
        labels.add(titleLabel);
        labels.add(Box.createVerticalStrut(2));
        labels.add(detailLabel);
        labels.add(Box.createVerticalStrut(2));
        labels.add(statusLabel);
        add(labels, BorderLayout.NORTH);

        canvas.setPreferredSize(new Dimension(260, 220));
        add(canvas, BorderLayout.CENTER);

        JPanel zRow = new JPanel(new BorderLayout(6, 0));
        zRow.setOpaque(false);
        sliceLabel.setHorizontalAlignment(SwingConstants.RIGHT);
        zRow.add(new JLabel("Z:"), BorderLayout.WEST);
        zRow.add(zSlider, BorderLayout.CENTER);
        zRow.add(sliceLabel, BorderLayout.EAST);
        add(zRow, BorderLayout.SOUTH);

        zSlider.addChangeListener(new ChangeListener() {
            @Override public void stateChanged(ChangeEvent e) {
                if (updatingSlider) return;
                currentZ = zSlider.getValue();
                updateSliceLabel();
                canvas.repaint();
                if (zSliceChangeListener != null) {
                    zSliceChangeListener.zSliceChanged(ImagePreviewPanel.this, currentZ);
                }
            }
        });
        refresh();
    }

    public void setImage(ImagePlus image) {
        int previousZ = currentZ;
        this.image = image;
        if (hasUsableImage()) {
            currentC = clamp(image.getC(), 1, Math.max(1, image.getNChannels()));
            currentZ = clamp(previousZ, 1, Math.max(1, image.getNSlices()));
            currentT = clamp(image.getT(), 1, Math.max(1, image.getNFrames()));
        } else {
            currentC = 1;
            currentZ = 1;
            currentT = 1;
        }
        refresh();
    }

    public void setCurrentZ(int zSlice) {
        if (!hasUsableImage()) {
            currentZ = 1;
            setSliderState(1, 1, 1);
            zSlider.setEnabled(false);
            updateSliceLabel();
            canvas.repaint();
            return;
        }

        int slices = Math.max(1, image.getNSlices());
        currentZ = clamp(zSlice, 1, slices);
        setSliderState(1, slices, currentZ);
        zSlider.setEnabled(slices > 1);
        updateSliceLabel();
        canvas.repaint();
    }

    public int getCurrentZ() {
        return currentZ;
    }

    public int getSliceCount() {
        if (!hasUsableImage()) return 1;
        return Math.max(1, image.getNSlices());
    }

    public void setStatusText(String text) {
        statusLabel.setText(text == null || text.trim().isEmpty() ? " " : text);
        canvas.repaint();
    }

    public void setDisplaySettings(PreviewDisplaySettings settings) {
        displaySettings = settings == null ? PreviewDisplaySettings.defaultFor("Grays") : settings;
        canvas.repaint();
    }

    void setDisplaySettingsEnabled(boolean enabled) {
        displaySettingsEnabled = enabled;
        canvas.repaint();
    }

    public void setZSliceChangeListener(ZSliceChangeListener listener) {
        this.zSliceChangeListener = listener;
    }

    public void refresh() {
        if (!hasUsableImage()) {
            titleLabel.setText("No image selected.");
            detailLabel.setText(" ");
            setSliderState(1, 1, 1);
            zSlider.setEnabled(false);
            sliceLabel.setText(" ");
            canvas.repaint();
            return;
        }

        currentC = clamp(currentC, 1, Math.max(1, image.getNChannels()));
        currentZ = clamp(currentZ, 1, Math.max(1, image.getNSlices()));
        currentT = clamp(currentT, 1, Math.max(1, image.getNFrames()));

        String title = image.getTitle() == null || image.getTitle().trim().isEmpty()
                ? "Untitled"
                : image.getTitle();
        titleLabel.setText(title);
        detailLabel.setText(image.getWidth() + " x " + image.getHeight()
                + ", C=" + Math.max(1, image.getNChannels())
                + ", Z=" + Math.max(1, image.getNSlices())
                + ", T=" + Math.max(1, image.getNFrames()));

        int slices = Math.max(1, image.getNSlices());
        setSliderState(1, slices, currentZ);
        zSlider.setEnabled(slices > 1);
        updateSliceLabel();
        canvas.repaint();
    }

    boolean hasImageForTest() {
        return hasUsableImage();
    }

    boolean isZSliderEnabledForTest() {
        return zSlider.isEnabled();
    }

    String statusTextForTest() {
        return statusLabel.getText();
    }

    String titleTextForTest() {
        return titleLabel.getText();
    }

    ImageProcessor renderedProcessorForTest() {
        return currentProcessor();
    }

    private boolean hasUsableImage() {
        return image != null && image.getStack() != null && image.getStackSize() > 0;
    }

    private void setSliderState(int minimum, int maximum, int value) {
        updatingSlider = true;
        try {
            zSlider.setMinimum(minimum);
            zSlider.setMaximum(maximum);
            zSlider.setValue(clamp(value, minimum, maximum));
        } finally {
            updatingSlider = false;
        }
    }

    private void updateSliceLabel() {
        if (!hasUsableImage()) {
            sliceLabel.setText(" ");
            return;
        }
        int slices = Math.max(1, image.getNSlices());
        sliceLabel.setText(currentZ + "/" + slices);
    }

    private ImageProcessor currentProcessor() {
        ImagePlus imp = image;
        if (!hasUsableImage()) return null;
        ImageStack stack = imp.getStack();
        int stackSize = stack.getSize();
        int stackIndex = imp.getStackIndex(
                clamp(currentC, 1, Math.max(1, imp.getNChannels())),
                clamp(currentZ, 1, Math.max(1, imp.getNSlices())),
                clamp(currentT, 1, Math.max(1, imp.getNFrames())));
        if (stackIndex < 1 || stackIndex > stackSize) {
            stackIndex = clamp(currentZ, 1, stackSize);
        }
        ImageProcessor processor = stack.getProcessor(stackIndex);
        if (processor == null) return null;
        ImageProcessor copy = processor.duplicate();
        boolean colorProcessor = copy instanceof ColorProcessor;
        if (!displaySettingsEnabled) {
            if (!colorProcessor) {
                double min = imp.getDisplayRangeMin();
                double max = imp.getDisplayRangeMax();
                if (max > min) {
                    copy.setMinAndMax(min, max);
                }
            }
            return copy;
        }
        double min = displaySettings.hasDisplayRange()
                ? displaySettings.getDisplayMin()
                : imp.getDisplayRangeMin();
        double max = displaySettings.hasDisplayRange()
                ? displaySettings.getDisplayMax()
                : imp.getDisplayRangeMax();
        if (!colorProcessor && max > min) {
            copy.setMinAndMax(min, max);
        }
        ColorModel colorModel = colorProcessor
                ? null
                : colorModelFor(displaySettings.effectiveLutName());
        if (colorModel != null) {
            copy.setColorModel(colorModel);
        }
        return copy;
    }

    private static ColorModel colorModelFor(String lutName) {
        String normalized = PreviewDisplaySettings.normalizeLutName(lutName);
        byte[] red = new byte[256];
        byte[] green = new byte[256];
        byte[] blue = new byte[256];
        for (int i = 0; i < 256; i++) {
            int r = i;
            int g = i;
            int b = i;
            if ("Red".equals(normalized)) {
                g = 0;
                b = 0;
            } else if ("Green".equals(normalized)) {
                r = 0;
                b = 0;
            } else if ("Blue".equals(normalized)) {
                r = 0;
                g = 0;
            } else if ("Cyan".equals(normalized)) {
                r = 0;
            } else if ("Magenta".equals(normalized)) {
                g = 0;
            } else if ("Yellow".equals(normalized)) {
                b = 0;
            }
            red[i] = (byte) r;
            green[i] = (byte) g;
            blue[i] = (byte) b;
        }
        return new IndexColorModel(8, 256, red, green, blue);
    }

    static int clamp(int value, int minimum, int maximum) {
        if (maximum < minimum) return minimum;
        if (value < minimum) return minimum;
        if (value > maximum) return maximum;
        return value;
    }

    private final class CanvasPanel extends JPanel {
        CanvasPanel() {
            setBackground(Color.BLACK);
            setOpaque(true);
            setMinimumSize(new Dimension(180, 160));
        }

        @Override protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                ImageProcessor processor = currentProcessor();
                if (processor == null) {
                    drawCenteredText(g2, "No image selected");
                    return;
                }
                Image awtImage = processor.createImage();
                if (awtImage == null) {
                    drawCenteredText(g2, "Preview unavailable");
                    return;
                }

                int imageWidth = processor.getWidth();
                int imageHeight = processor.getHeight();
                int availableWidth = Math.max(1, getWidth() - 12);
                int availableHeight = Math.max(1, getHeight() - 12);
                double scale = Math.min(
                        availableWidth / (double) imageWidth,
                        availableHeight / (double) imageHeight);
                int drawWidth = Math.max(1, (int) Math.round(imageWidth * scale));
                int drawHeight = Math.max(1, (int) Math.round(imageHeight * scale));
                int x = (getWidth() - drawWidth) / 2;
                int y = (getHeight() - drawHeight) / 2;

                g2.setRenderingHint(
                        RenderingHints.KEY_INTERPOLATION,
                        RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                g2.drawImage(awtImage, x, y, drawWidth, drawHeight, null);
                g2.setColor(new Color(120, 120, 120));
                g2.drawRect(x, y, drawWidth - 1, drawHeight - 1);
            } finally {
                g2.dispose();
            }
        }

        private void drawCenteredText(Graphics2D g2, String text) {
            g2.setColor(new Color(150, 150, 150));
            int width = g2.getFontMetrics().stringWidth(text);
            int x = Math.max(0, (getWidth() - width) / 2);
            int y = Math.max(g2.getFontMetrics().getAscent(),
                    (getHeight() + g2.getFontMetrics().getAscent()) / 2);
            g2.drawString(text, x, y);
        }
    }
}
