package flash.pipeline.deconv.qc;

import flash.pipeline.ui.FlashTheme;
import flash.pipeline.ui.preview.PreviewPairPanel;
import ij.IJ;
import ij.ImagePlus;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSlider;
import javax.swing.WindowConstants;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Frame;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Modal RAW-vs-DECONV preview shown before a full deconvolution batch starts.
 *
 * <p>The visual surface reuses the shared {@link PreviewPairPanel} (the same component used by Set
 * Up Configuration) so deconvolution gets the familiar raw/processed inspection experience. When
 * more than one channel is supplied each channel is rendered as its own raw/deconvolved row,
 * stacked vertically inside a scroll pane, and a single shared toolstrip plus a single shared Z
 * slider drive every row in lockstep. The decision contract is unchanged: run the full batch, go
 * back to reconfigure, or cancel.
 *
 * <p>Image ownership: the {@link PreviewContent} stacks belong to the caller. This dialog only
 * displays them and never closes them; the caller must close them after {@code show(...)} returns.
 */
public final class DeconvPreviewDialog {

    private static volatile HeadlessProbe headlessProbe = new HeadlessProbe() {
        @Override
        public boolean isHeadless() {
            return GraphicsEnvironment.isHeadless() || IJ.getInstance() == null;
        }
    };

    private DeconvPreviewDialog() {}

    public static Decision show(PreviewContent content, boolean skipPreview) {
        if (skipPreview || isHeadless() || content == null) {
            return Decision.RUN_FULL_BATCH;
        }
        return showPreviewPanel(content);
    }

    private static Decision showPreviewPanel(PreviewContent content) {
        final Decision[] decision = new Decision[]{Decision.CANCEL};
        final JDialog dialog = new JDialog((Frame) null, "3D Deconvolution Preview", true);
        dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        dialog.setLayout(new BorderLayout(0, 8));
        JPanel body = new JPanel(new BorderLayout(0, 8));
        body.add(workflowRow(), BorderLayout.NORTH);
        body.add(buildPreviewRoot(content), BorderLayout.CENTER);
        dialog.add(body, BorderLayout.CENTER);
        dialog.add(buildButtonRow(dialog, decision), BorderLayout.SOUTH);
        dialog.setMinimumSize(new Dimension(760, 600));
        dialog.pack();
        clampToScreen(dialog);
        dialog.setLocationRelativeTo(null);
        dialog.setVisible(true);
        return decision[0];
    }

    private static JPanel workflowRow() {
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        row.setBorder(BorderFactory.createEmptyBorder(8, 8, 0, 8));
        row.setOpaque(false);
        row.add(workflowChip("Setup", false));
        row.add(workflowSeparator());
        row.add(workflowChip("Preview", true));
        row.add(workflowSeparator());
        row.add(workflowChip("Run", false));
        return row;
    }

    private static JLabel workflowChip(String text, boolean active) {
        Color header = FlashTheme.TEXT_HEADER;
        JLabel chip = new JLabel(" " + text + " ");
        chip.setOpaque(true);
        chip.setFont(chip.getFont().deriveFont(active ? Font.BOLD : Font.PLAIN, 11f));
        chip.setBorder(BorderFactory.createLineBorder(header, 1, true));
        chip.setBackground(active ? header : FlashTheme.SURFACE);
        chip.setForeground(active ? FlashTheme.TEXT_ON_DARK : header);
        return chip;
    }

    private static JLabel workflowSeparator() {
        JLabel separator = new JLabel("\u25B8");
        separator.setForeground(FlashTheme.TEXT_MUTED);
        separator.setFont(separator.getFont().deriveFont(Font.PLAIN, 11f));
        return separator;
    }

    private static JPanel buildPreviewRoot(PreviewContent content) {
        final boolean multi = content.channels.size() > 1;
        final List<PreviewPairPanel> panels = new ArrayList<PreviewPairPanel>();

        JPanel column = new JPanel();
        column.setLayout(new BoxLayout(column, BoxLayout.Y_AXIS));
        column.setOpaque(false);

        // Shared Z slider spans only the range valid for EVERY row, so no channel is ever pinned
        // below the slider position (channels of one series share a depth, but stay honest if not).
        int sharedSlices = Integer.MAX_VALUE;
        for (ChannelPreview channel : content.channels) {
            PreviewPairPanel preview = new PreviewPairPanel(
                    null,
                    channel.rawLabel,
                    channel.deconvolvedLabel,
                    PreviewPairPanel.PreviewLayout.HORIZONTAL_SLIM);
            // Wire the real channel LUT so the shared Grey LUT toggle flips between the user's
            // selected colour and grey (instead of the old grey<->grays no-op).
            preview.setChannelLutName(channel.channelLutName);
            preview.setOriginal(channel.rawStack);
            preview.setAdjusted(channel.deconvolvedStack);
            preview.setAdjustedState(PreviewPairPanel.PreviewState.READY, "Preview complete.");
            preview.setDisplayControlsAvailable(true);
            // No raw/filtered source toggle: the two panes already are raw vs deconvolved.
            preview.setSourceToggleVisible(false);
            panels.add(preview);
            sharedSlices = Math.min(sharedSlices, stackSize(channel.rawStack));

            JPanel block = new JPanel(new BorderLayout(0, 4));
            block.setOpaque(false);
            block.setAlignmentX(Component.LEFT_ALIGNMENT);
            if (multi) {
                JLabel header = new JLabel(channel.channelName == null ? "Channel" : channel.channelName);
                header.setFont(header.getFont().deriveFont(Font.BOLD));
                header.setBorder(BorderFactory.createEmptyBorder(4, 2, 0, 2));
                block.add(header, BorderLayout.NORTH);
            }
            block.add(preview, BorderLayout.CENTER);
            column.add(block);
            column.add(Box.createVerticalStrut(10));
        }

        JComponent center;
        if (multi) {
            JScrollPane scroll = new JScrollPane(column,
                    JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
                    JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
            scroll.setBorder(null);
            scroll.getViewport().setOpaque(false);
            scroll.setOpaque(false);
            scroll.getVerticalScrollBar().setUnitIncrement(16);
            scroll.setPreferredSize(new Dimension(740, 620));
            center = scroll;
        } else {
            center = column;
        }

        JPanel root = new JPanel(new BorderLayout(0, 8));
        root.add(buildSharedToolstrip(panels, content), BorderLayout.NORTH);
        root.add(center, BorderLayout.CENTER);
        root.add(buildSharedZRow(panels, sharedSlices), BorderLayout.SOUTH);
        root.setBorder(BorderFactory.createEmptyBorder(8, 8, 0, 8));
        if (!multi) {
            root.setPreferredSize(new Dimension(720, 520));
        }
        return root;
    }

    private static JComponent buildSharedToolstrip(final List<PreviewPairPanel> panels,
                                                   PreviewContent content) {
        JButton largeViewButton = new JButton("Large view");
        JButton brightnessButton = new JButton("Adjust Brightness/Contrast");
        final JButton lutToggleButton = new JButton("Grey LUT");

        largeViewButton.setToolTipText("Open a larger raw vs deconvolved view for each channel.");
        largeViewButton.addActionListener(e -> {
            for (PreviewPairPanel panel : panels) {
                panel.largeViewButton().doClick();
            }
        });

        brightnessButton.setToolTipText("Adjust preview brightness/contrast.");
        brightnessButton.addActionListener(e -> {
            for (PreviewPairPanel panel : panels) {
                panel.requestBrightnessContrastControls();
            }
        });

        final String colourLabel = colourToggleLabel(content);
        final boolean toggleEnabled = !allGrayLuts(content);
        final boolean[] grey = new boolean[]{false};
        lutToggleButton.setEnabled(toggleEnabled);
        lutToggleButton.setToolTipText(toggleEnabled
                ? "Show previews in grey."
                : "All preview channels already use a grey LUT.");
        lutToggleButton.addActionListener(e -> {
            for (PreviewPairPanel panel : panels) {
                panel.requestGreyLutToggle();
            }
            grey[0] = !grey[0];
            lutToggleButton.setText(grey[0] ? colourLabel : "Grey LUT");
            lutToggleButton.setToolTipText(grey[0]
                    ? "Show previews with the channel LUT."
                    : "Show previews in grey.");
        });

        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        right.setOpaque(false);
        right.add(largeViewButton);
        right.add(brightnessButton);
        right.add(lutToggleButton);

        JPanel bar = new JPanel(new BorderLayout(8, 0));
        bar.setOpaque(false);
        bar.add(right, BorderLayout.EAST);
        return bar;
    }

    private static JComponent buildSharedZRow(final List<PreviewPairPanel> panels, int maxZ) {
        final int sliceCount = Math.max(1, maxZ);
        final JSlider slider = new JSlider(1, sliceCount, 1);
        final JLabel countLabel = new JLabel("1 / " + sliceCount);
        countLabel.setHorizontalAlignment(JLabel.RIGHT);
        slider.setEnabled(sliceCount > 1);
        // Re-entrancy guard: driving a panel's Z fires its shared-Z listener, which would
        // otherwise drive the slider and the other panels right back.
        final boolean[] syncing = new boolean[]{false};

        slider.addChangeListener(e -> {
            if (syncing[0]) return;
            syncing[0] = true;
            try {
                int z = slider.getValue();
                for (PreviewPairPanel panel : panels) {
                    panel.setCurrentZ(z);
                }
                countLabel.setText(z + " / " + sliceCount);
            } finally {
                syncing[0] = false;
            }
        });

        for (final PreviewPairPanel source : panels) {
            source.setSharedZChangeListener(new PreviewPairPanel.SharedZChangeListener() {
                @Override public void zSliceChanged(int zSlice) {
                    if (syncing[0]) return;
                    syncing[0] = true;
                    try {
                        slider.setValue(zSlice);
                        countLabel.setText(zSlice + " / " + sliceCount);
                        for (PreviewPairPanel panel : panels) {
                            if (panel != source) {
                                panel.setCurrentZ(zSlice);
                            }
                        }
                    } finally {
                        syncing[0] = false;
                    }
                }
            });
        }

        JPanel row = new JPanel(new BorderLayout(6, 0));
        row.setOpaque(false);
        row.add(new JLabel("Z:"), BorderLayout.WEST);
        row.add(slider, BorderLayout.CENTER);
        row.add(countLabel, BorderLayout.EAST);
        return row;
    }

    private static JPanel buildButtonRow(final JDialog dialog, final Decision[] decision) {
        JButton runButton = new JButton("Use settings & run batch");
        JButton backButton = new JButton("Back to settings");
        JButton cancelButton = new JButton("Cancel");

        runButton.addActionListener(e -> {
            decision[0] = Decision.RUN_FULL_BATCH;
            dialog.dispose();
        });
        backButton.addActionListener(e -> {
            decision[0] = Decision.RECONFIGURE;
            dialog.dispose();
        });
        cancelButton.addActionListener(e -> {
            decision[0] = Decision.CANCEL;
            dialog.dispose();
        });

        JPanel row = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 8));
        row.add(runButton);
        row.add(backButton);
        row.add(cancelButton);
        dialog.getRootPane().setDefaultButton(runButton);
        return row;
    }

    private static void clampToScreen(JDialog dialog) {
        Rectangle screen = GraphicsEnvironment
                .getLocalGraphicsEnvironment()
                .getMaximumWindowBounds();
        Dimension size = dialog.getSize();
        int width = Math.min(size.width, screen.width);
        int height = Math.min(size.height, screen.height);
        if (width != size.width || height != size.height) {
            dialog.setSize(width, height);
        }
    }

    private static int stackSize(ImagePlus image) {
        if (image == null) return 1;
        try {
            return Math.max(1, image.getStackSize());
        } catch (RuntimeException e) {
            return 1;
        }
    }

    private static boolean isGrayLut(String lut) {
        if (lut == null) return true;
        String normalized = lut.trim().toLowerCase(Locale.ROOT);
        return normalized.isEmpty()
                || normalized.equals("gray")
                || normalized.equals("grey")
                || normalized.equals("grays")
                || normalized.equals("greys");
    }

    static boolean allGrayLuts(PreviewContent content) {
        for (ChannelPreview channel : content.channels) {
            if (!isGrayLut(channel.channelLutName)) {
                return false;
            }
        }
        return true;
    }

    /** Label for the toggle when previews are currently grey (i.e. the action restores colour). */
    static String colourToggleLabel(PreviewContent content) {
        if (content.channels.size() == 1) {
            String lut = content.channels.get(0).channelLutName;
            if (!isGrayLut(lut)) {
                return lut.trim() + " LUT";
            }
            return "Channel LUT";
        }
        return "Channel LUTs";
    }

    static void setHeadlessProbeForTest(HeadlessProbe probe) {
        if (probe != null) {
            headlessProbe = probe;
        }
    }

    static void resetForTest() {
        headlessProbe = new HeadlessProbe() {
            @Override
            public boolean isHeadless() {
                return GraphicsEnvironment.isHeadless() || IJ.getInstance() == null;
            }
        };
    }

    private static boolean isHeadless() {
        return headlessProbe.isHeadless();
    }

    interface HeadlessProbe {
        boolean isHeadless();
    }

    public enum Decision {
        RUN_FULL_BATCH,
        RECONFIGURE,
        CANCEL
    }

    /** A single channel's raw/deconvolved preview pair plus its display metadata. */
    public static final class ChannelPreview {
        public final ImagePlus rawStack;
        public final ImagePlus deconvolvedStack;
        public final String rawLabel;
        public final String deconvolvedLabel;
        public final String channelName;
        public final String channelLutName;

        public ChannelPreview(ImagePlus rawStack,
                              ImagePlus deconvolvedStack,
                              String rawLabel,
                              String deconvolvedLabel,
                              String channelName,
                              String channelLutName) {
            this.rawStack = rawStack;
            this.deconvolvedStack = deconvolvedStack;
            this.rawLabel = rawLabel == null ? "Raw" : rawLabel;
            this.deconvolvedLabel = deconvolvedLabel == null ? "Deconvolved" : deconvolvedLabel;
            this.channelName = channelName;
            this.channelLutName = channelLutName;
        }
    }

    /**
     * Preview payload: one or more {@link ChannelPreview} rows. The single-channel constructor is
     * preserved for backwards compatibility; the convenience fields ({@link #rawStack} etc.) always
     * mirror the first channel so existing single-channel readers keep working.
     */
    public static final class PreviewContent {
        public final List<ChannelPreview> channels;
        public final ImagePlus rawStack;
        public final ImagePlus deconvolvedStack;
        public final String rawLabel;
        public final String deconvolvedLabel;

        public PreviewContent(ImagePlus rawStack,
                              ImagePlus deconvolvedStack,
                              String rawLabel,
                              String deconvolvedLabel) {
            this(Collections.singletonList(new ChannelPreview(
                    rawStack, deconvolvedStack, rawLabel, deconvolvedLabel, null, null)));
        }

        public PreviewContent(List<ChannelPreview> channels) {
            if (channels == null || channels.isEmpty()) {
                throw new IllegalArgumentException("PreviewContent requires at least one channel.");
            }
            this.channels = Collections.unmodifiableList(new ArrayList<ChannelPreview>(channels));
            ChannelPreview first = this.channels.get(0);
            this.rawStack = first.rawStack;
            this.deconvolvedStack = first.deconvolvedStack;
            this.rawLabel = first.rawLabel;
            this.deconvolvedLabel = first.deconvolvedLabel;
        }
    }
}
