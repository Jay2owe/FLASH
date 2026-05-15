package flash.pipeline.ui.preview;

import flash.pipeline.ui.variations.ParameterCombo;
import flash.pipeline.ui.variations.ParameterSweep;
import ij.ImagePlus;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Dialog;
import java.awt.FlowLayout;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.awt.Window;
import java.util.List;

public final class VariationMontageDialog extends JDialog {

    private static final double INITIAL_DESKTOP_WIDTH_FRACTION = 0.82;
    private static final double INITIAL_DESKTOP_HEIGHT_FRACTION = 0.80;

    public interface DisplayActionListener {
        void adjustBrightnessContrastRequested();
        void lutToggleRequested();
    }

    public static final class MontageTile {
        public final ParameterCombo combo;
        public final ImagePlus labelImage;
        public final int objectCount;
        public final double iouToNeighbours;

        public MontageTile(ParameterCombo combo,
                           ImagePlus labelImage,
                           int objectCount,
                           double iouToNeighbours) {
            this.combo = combo;
            this.labelImage = labelImage;
            this.objectCount = objectCount;
            this.iouToNeighbours = iouToNeighbours;
        }
    }

    private final VariationMontageGrid montageGrid = new VariationMontageGrid();
    private final JPanel sourceControls = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
    private final JComboBox<String> sourceChoice = new JComboBox<String>();
    private final JPanel overlayControls = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
    private final JCheckBox overlayCheck = new JCheckBox("Overlay objects");
    private final JComboBox<String> overlaySourceChoice = new JComboBox<String>();
    private final JButton displayControlsButton = new JButton("Adjust Brightness/Contrast");
    private final JButton lutToggleButton = new JButton("Grey LUT");

    private DisplayActionListener displayActionListener;
    private boolean updatingSourceChoice;
    private PreviewPairPanel.SourceMode sourceChoiceMode = PreviewPairPanel.SourceMode.RAW;
    private ImagePlus rawSourceImage;
    private ImagePlus filteredSourceImage;
    private PreviewDisplaySettings rawDisplaySettings = PreviewDisplaySettings.defaultFor("Grays");
    private PreviewDisplaySettings filteredDisplaySettings = PreviewDisplaySettings.defaultFor("Grays");

    public VariationMontageDialog(Window owner) {
        super(owner, "Variation montage", ModalityType.MODELESS);
        installModalExclusion();
        setDefaultCloseOperation(HIDE_ON_CLOSE);
        setLayout(new BorderLayout(8, 8));
        add(buildCentre(), BorderLayout.CENTER);
        add(buildFooter(), BorderLayout.SOUTH);
        wireSourceControls();
        wireOverlayControls();
        wireDisplayActionControls();
        pack();
        setMinimumSize(new Dimension(820, 520));
        sizeNearDesktop();
    }

    public void setTiles(List<MontageTile> tiles, ParameterSweep sweep, int initialZ) {
        montageGrid.setTiles(tiles, sweep, initialZ);
        int count = tiles == null ? 0 : tiles.size();
        setTitle(count > 0 ? "Variation montage (" + count + " tiles)" : "Variation montage");
        refreshGridImages();
        updateOverlayControls();
    }

    public void setSourceChoices(ImagePlus rawImage, ImagePlus filteredImage) {
        rawSourceImage = rawImage;
        filteredSourceImage = filteredImage;
        sourceChoiceMode = availableSourceChoiceMode(sourceChoiceMode);
        updateSourceControls();
        refreshGridImages();
        updateOverlayControls();
    }

    public void setDisplaySettings(PreviewDisplaySettings rawSettings,
                                   PreviewDisplaySettings filteredSettings) {
        rawDisplaySettings = safeDisplaySettings(rawSettings);
        filteredDisplaySettings = safeDisplaySettings(filteredSettings);
        refreshGridImages();
    }

    public void setDisplaySettings(PreviewDisplaySettings settings) {
        PreviewDisplaySettings safe = safeDisplaySettings(settings);
        setDisplaySettings(safe, safe);
    }

    public void setDisplayActionListener(DisplayActionListener listener) {
        displayActionListener = listener;
    }

    public void setDisplayActionState(String lutButtonText, String lutButtonTooltip) {
        displayControlsButton.setVisible(true);
        displayControlsButton.setEnabled(true);
        lutToggleButton.setVisible(true);
        lutToggleButton.setEnabled(true);
        lutToggleButton.setText(lutButtonText == null || lutButtonText.trim().isEmpty()
                ? "Grey LUT"
                : lutButtonText);
        lutToggleButton.setToolTipText(lutButtonTooltip);
    }

    public void setDisplayActionsEnabled(boolean enabled, String tooltip) {
        displayControlsButton.setEnabled(enabled);
        lutToggleButton.setEnabled(enabled);
        displayControlsButton.setToolTipText(tooltip);
        lutToggleButton.setToolTipText(tooltip);
    }

    public void raiseForUser() {
        setVisible(true);
        boolean previousAlwaysOnTop = isAlwaysOnTop();
        try {
            setAlwaysOnTop(true);
        } catch (SecurityException ignored) {
            // Best-effort only; toFront still handles normal desktop cases.
        }
        toFront();
        requestFocus();
        requestFocusInWindow();
        try {
            setAlwaysOnTop(previousAlwaysOnTop);
        } catch (SecurityException ignored) {
            // Leave focus behavior unchanged if the desktop rejects the change.
        }
    }

    int tileCountForTest() {
        return montageGrid.tileCount();
    }

    int currentZForTest() {
        return montageGrid.getCurrentZ();
    }

    boolean sourceControlsVisibleForTest() {
        return sourceControls.isVisible();
    }

    String selectedSourceChoiceForTest() {
        Object selected = sourceChoice.getSelectedItem();
        return selected == null ? "" : selected.toString();
    }

    void setSourceChoiceForTest(String label) {
        sourceChoice.setSelectedItem(label);
    }

    boolean overlayControlsVisibleForTest() {
        return overlayControls.isVisible();
    }

    void setOverlaySelectedForTest(boolean selected) {
        overlayCheck.setSelected(selected);
        refreshGridImages();
        updateOverlayControls();
    }

    void setOverlaySourceForTest(String sourceLabel) {
        overlaySourceChoice.setSelectedItem(sourceLabel);
        refreshGridImages();
    }

    JButton displayControlsButtonForTest() {
        return displayControlsButton;
    }

    JButton lutToggleButtonForTest() {
        return lutToggleButton;
    }

    private JPanel buildCentre() {
        JPanel centre = new JPanel(new BorderLayout());
        centre.setBorder(BorderFactory.createEmptyBorder(8, 8, 0, 8));
        JScrollPane scrollPane = new JScrollPane(montageGrid);
        scrollPane.setBorder(BorderFactory.createEmptyBorder());
        scrollPane.getViewport().setOpaque(false);
        centre.add(scrollPane, BorderLayout.CENTER);
        centre.setPreferredSize(new Dimension(1100, 620));
        return centre;
    }

    private JPanel buildFooter() {
        JPanel footer = new JPanel(new BorderLayout(8, 8));
        footer.setBorder(BorderFactory.createEmptyBorder(0, 8, 8, 8));

        JPanel leftControls = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        leftControls.setOpaque(false);

        sourceChoice.addItem("Original");
        sourceChoice.addItem("Filtered");
        sourceControls.setOpaque(false);
        sourceControls.add(new JLabel("Source:"));
        sourceControls.add(sourceChoice);
        sourceControls.setVisible(false);
        leftControls.add(sourceControls);

        overlaySourceChoice.addItem("Filtered image");
        overlaySourceChoice.addItem("Raw image");
        overlayControls.setOpaque(false);
        overlayCheck.setOpaque(false);
        overlayControls.add(overlayCheck);
        overlayControls.add(new JLabel("over"));
        overlayControls.add(overlaySourceChoice);
        overlayControls.setVisible(false);
        leftControls.add(overlayControls);
        footer.add(leftControls, BorderLayout.WEST);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        buttons.setOpaque(false);
        buttons.add(displayControlsButton);
        buttons.add(lutToggleButton);
        JButton close = new JButton("Close");
        close.addActionListener(e -> setVisible(false));
        buttons.add(close);
        footer.add(buttons, BorderLayout.EAST);
        return footer;
    }

    private void wireSourceControls() {
        sourceChoice.addActionListener(e -> {
            if (updatingSourceChoice) return;
            PreviewPairPanel.SourceMode selected = sourceChoice.getSelectedIndex() == 1
                    ? PreviewPairPanel.SourceMode.FILTERED
                    : PreviewPairPanel.SourceMode.RAW;
            sourceChoiceMode = availableSourceChoiceMode(selected);
            updateSourceControls();
            refreshGridImages();
            updateOverlayControls();
        });
    }

    private void wireOverlayControls() {
        overlayCheck.addActionListener(e -> {
            refreshGridImages();
            updateOverlayControls();
        });
        overlaySourceChoice.addActionListener(e -> refreshGridImages());
    }

    private void wireDisplayActionControls() {
        displayControlsButton.addActionListener(e -> {
            if (displayActionListener != null) {
                displayActionListener.adjustBrightnessContrastRequested();
            }
        });
        lutToggleButton.addActionListener(e -> {
            if (displayActionListener != null) {
                displayActionListener.lutToggleRequested();
            }
        });
    }

    private void refreshGridImages() {
        boolean overlaySelected = overlayCheck.isSelected() && overlayCheck.isEnabled();
        montageGrid.setDisplayImages(
                selectedSourceImage(),
                selectedSourceDisplaySettings(),
                overlaySelected,
                selectedOverlaySourceImage(),
                selectedOverlaySourceDisplaySettings());
    }

    private ImagePlus selectedSourceImage() {
        if (sourceChoiceMode == PreviewPairPanel.SourceMode.FILTERED) {
            return filteredSourceImage != null ? filteredSourceImage : rawSourceImage;
        }
        return rawSourceImage != null ? rawSourceImage : filteredSourceImage;
    }

    private PreviewDisplaySettings selectedSourceDisplaySettings() {
        if (sourceChoiceMode == PreviewPairPanel.SourceMode.FILTERED) {
            return filteredSourceImage != null ? filteredDisplaySettings : rawDisplaySettings;
        }
        return rawSourceImage != null ? rawDisplaySettings : filteredDisplaySettings;
    }

    private ImagePlus selectedOverlaySourceImage() {
        Object selected = overlaySourceChoice.getSelectedItem();
        boolean raw = selected != null && "Raw image".equals(selected.toString());
        if (raw) {
            return rawSourceImage != null ? rawSourceImage : filteredSourceImage;
        }
        return filteredSourceImage != null ? filteredSourceImage : rawSourceImage;
    }

    private PreviewDisplaySettings selectedOverlaySourceDisplaySettings() {
        Object selected = overlaySourceChoice.getSelectedItem();
        boolean raw = selected != null && "Raw image".equals(selected.toString());
        if (raw) {
            return rawSourceImage != null ? rawDisplaySettings : filteredDisplaySettings;
        }
        return filteredSourceImage != null ? filteredDisplaySettings : rawDisplaySettings;
    }

    private void updateSourceControls() {
        boolean visible = rawSourceImage != null && filteredSourceImage != null;
        sourceControls.setVisible(visible);
        sourceChoice.setEnabled(visible);
        updatingSourceChoice = true;
        try {
            sourceChoice.setSelectedIndex(sourceChoiceMode == PreviewPairPanel.SourceMode.FILTERED
                    ? 1
                    : 0);
        } finally {
            updatingSourceChoice = false;
        }
        sourceControls.revalidate();
        sourceControls.repaint();
    }

    private PreviewPairPanel.SourceMode availableSourceChoiceMode(
            PreviewPairPanel.SourceMode requestedMode) {
        PreviewPairPanel.SourceMode safeMode =
                requestedMode == PreviewPairPanel.SourceMode.FILTERED
                        ? PreviewPairPanel.SourceMode.FILTERED
                        : PreviewPairPanel.SourceMode.RAW;
        if (safeMode == PreviewPairPanel.SourceMode.FILTERED
                && filteredSourceImage != null) {
            return PreviewPairPanel.SourceMode.FILTERED;
        }
        if (safeMode == PreviewPairPanel.SourceMode.RAW
                && rawSourceImage != null) {
            return PreviewPairPanel.SourceMode.RAW;
        }
        if (rawSourceImage != null) return PreviewPairPanel.SourceMode.RAW;
        if (filteredSourceImage != null) return PreviewPairPanel.SourceMode.FILTERED;
        return safeMode;
    }

    private void updateOverlayControls() {
        boolean hasObjectMaps = montageGrid.hasTilesWithLabels();
        boolean hasSource = rawSourceImage != null || filteredSourceImage != null;
        overlayControls.setVisible(hasObjectMaps);
        overlayCheck.setEnabled(hasObjectMaps && hasSource);
        overlaySourceChoice.setEnabled(hasObjectMaps && hasSource && overlayCheck.isSelected());
        overlayControls.revalidate();
        overlayControls.repaint();
    }

    private static PreviewDisplaySettings safeDisplaySettings(PreviewDisplaySettings settings) {
        return settings == null ? PreviewDisplaySettings.defaultFor("Grays") : settings;
    }

    private void sizeNearDesktop() {
        if (GraphicsEnvironment.isHeadless()) return;
        Rectangle bounds = GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
        Dimension minimum = getMinimumSize();
        Dimension packed = getSize();
        int targetWidth = (int) Math.round(bounds.width * INITIAL_DESKTOP_WIDTH_FRACTION);
        int targetHeight = (int) Math.round(bounds.height * INITIAL_DESKTOP_HEIGHT_FRACTION);
        int width = Math.min(bounds.width, Math.max(Math.max(minimum.width, packed.width), targetWidth));
        int height = Math.min(bounds.height, Math.max(Math.max(minimum.height, packed.height), targetHeight));
        int x = bounds.x + Math.max(0, (bounds.width - width) / 2);
        int y = bounds.y + Math.max(0, (bounds.height - height) / 2);
        setBounds(x, y, width, height);
    }

    private void installModalExclusion() {
        try {
            setModalExclusionType(Dialog.ModalExclusionType.APPLICATION_EXCLUDE);
        } catch (SecurityException ignored) {
            // Some desktops disallow this; the dialog still remains modeless.
        }
    }

    @Override public void dispose() {
        montageGrid.disposeGeneratedImages();
        super.dispose();
    }
}
