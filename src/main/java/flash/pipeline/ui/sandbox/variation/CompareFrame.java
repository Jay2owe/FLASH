package flash.pipeline.ui.sandbox.variation;

import flash.pipeline.image.variation.VariantPlan;
import ij.ImagePlus;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.JScrollBar;
import javax.swing.JSpinner;
import javax.swing.JToggleButton;
import javax.swing.JToolBar;
import javax.swing.KeyStroke;
import javax.swing.SpinnerNumberModel;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.GridLayout;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;

public final class CompareFrame extends JFrame {

    private final SharedSliceDriver driver = new SharedSliceDriver();
    private final CardLayout rootCards = new CardLayout();
    private final JPanel rootPanel = new JPanel(rootCards);
    private final CardLayout flickerCards = new CardLayout();
    private final JPanel flickerPanel = new JPanel(flickerCards);
    private final JToggleButton flickerToggle = new JToggleButton("Flicker");
    private final JSpinner rateSpinner =
            new JSpinner(new SpinnerNumberModel(2.0, 0.5, 5.0, 0.5));
    private final FlickerController flicker;

    public CompareFrame(String title, ImagePlus left, ImagePlus right,
                        String leftLabel, String rightLabel,
                        TileActionListener listener) {
        this(title, left, right, leftLabel, rightLabel, null, null, listener);
    }

    public CompareFrame(String title, ImagePlus left, ImagePlus right,
                        String leftLabel, String rightLabel,
                        VariantPlan leftPlan, VariantPlan rightPlan,
                        TileActionListener listener) {
        super(title == null ? "Compare variations" : title);
        if (left == null) throw new IllegalArgumentException("left image must not be null");
        if (right == null) throw new IllegalArgumentException("right image must not be null");

        TilePanel leftSide = tile(left, leftLabel, leftPlan, listener);
        TilePanel rightSide = tile(right, rightLabel, rightPlan, listener);
        TilePanel leftFlicker = tile(left, leftLabel, leftPlan, listener);
        TilePanel rightFlicker = tile(right, rightLabel, rightPlan, listener);

        JPanel sideBySide = new JPanel(new GridLayout(1, 2, 4, 4));
        sideBySide.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
        sideBySide.setBackground(new Color(0x1a, 0x1a, 0x1a));
        sideBySide.add(leftSide);
        sideBySide.add(rightSide);

        flickerPanel.add(leftFlicker, "left");
        flickerPanel.add(rightFlicker, "right");

        rootPanel.add(sideBySide, "side");
        rootPanel.add(flickerPanel, "flicker");

        flicker = new FlickerController(2.0, new Runnable() {
            @Override public void run() {
                flickerCards.show(flickerPanel, flicker.leftVisible() ? "left" : "right");
            }
        });

        int maxSlice = Math.max(1, driver.maxSlice());
        JScrollBar zBar = new JScrollBar(JScrollBar.VERTICAL, 1, 1, 1, maxSlice + 1);
        zBar.setEnabled(maxSlice > 1);
        zBar.addAdjustmentListener(e -> driver.setSlice(e.getValue()));

        JToolBar toolbar = new JToolBar();
        toolbar.setFloatable(false);
        flickerToggle.setFocusable(false);
        toolbar.add(flickerToggle);
        toolbar.add(rateSpinner);
        JButton close = new JButton("Close");
        close.setFocusable(false);
        close.addActionListener(e -> dispose());
        toolbar.addSeparator();
        toolbar.add(close);

        flickerToggle.addActionListener(e -> setFlickerOn(flickerToggle.isSelected()));
        rateSpinner.addChangeListener(e -> {
            Object value = rateSpinner.getValue();
            if (value instanceof Number) {
                flicker.setRate(((Number) value).doubleValue());
            }
        });
        getRootPane().getInputMap(javax.swing.JComponent.WHEN_IN_FOCUSED_WINDOW)
                .put(KeyStroke.getKeyStroke("SPACE"), "toggleFlicker");
        getRootPane().getActionMap().put("toggleFlicker", new javax.swing.AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent e) {
                flickerToggle.setSelected(!flickerToggle.isSelected());
                setFlickerOn(flickerToggle.isSelected());
            }
        });

        setLayout(new BorderLayout());
        add(toolbar, BorderLayout.NORTH);
        add(rootPanel, BorderLayout.CENTER);
        add(zBar, BorderLayout.EAST);
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override public void windowClosed(WindowEvent e) {
                flicker.stop();
            }
        });
        pack();
        Dimension pref = getPreferredSize();
        setSize(Math.min(Math.max(pref.width, 720), 1400),
                Math.min(Math.max(pref.height, 480), 1000));
    }

    private TilePanel tile(ImagePlus imp, String label, VariantPlan plan,
                           TileActionListener listener) {
        final TilePanel panel = new TilePanel(imp, label, plan == null, plan);
        panel.setCompareActions(listener);
        driver.register(imp, new Runnable() {
            @Override public void run() {
                if (panel.getActiveCanvas() != null) panel.getActiveCanvas().repaint();
            }
        });
        return panel;
    }

    private void setFlickerOn(boolean on) {
        if (on) {
            rootCards.show(rootPanel, "flicker");
            flickerCards.show(flickerPanel, "left");
            flicker.start();
        } else {
            flicker.stop();
            rootCards.show(rootPanel, "side");
        }
    }
}
