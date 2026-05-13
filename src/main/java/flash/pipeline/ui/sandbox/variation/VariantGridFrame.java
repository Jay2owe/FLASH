package flash.pipeline.ui.sandbox.variation;

import flash.pipeline.image.variation.VariantPlan;
import flash.pipeline.image.variation.VariantResult;
import ij.IJ;
import ij.ImagePlus;
import ij.plugin.frame.Recorder;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollBar;
import javax.swing.JToggleButton;
import javax.swing.JToolBar;
import javax.swing.event.ChangeListener;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridLayout;
import java.awt.KeyboardFocusManager;
import java.awt.event.MouseWheelEvent;
import java.awt.event.MouseWheelListener;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class VariantGridFrame extends JFrame {

    private static final int MAX_TILES = 16;

    interface CompareLauncher {
        void open(VariantGridFrame owner,
                  TilePanel left,
                  TilePanel right,
                  TileActionListener actionListener);
    }

    private final SharedSliceDriver driver = new SharedSliceDriver();
    private final List<TilePanel> tiles = new ArrayList<TilePanel>();
    private final JPanel grid;
    private final JScrollBar zBar;
    private final JLabel statusLabel;
    private final JToggleButton mipToggle;
    private final int initialVariantTileCount;
    private final int initialErrorTileCount;
    private TilePanel rawTile;
    private TilePanel promotedTile;
    private TileActionListener actionListener;
    private VariationSessionLog sessionLog;
    private MontageExporter montageExporter;
    private IjmClipboardExporter ijmClipboardExporter;
    private CompareLauncher compareLauncher = new CompareLauncher() {
        @Override
        public void open(VariantGridFrame owner,
                         TilePanel left,
                         TilePanel right,
                         TileActionListener listener) {
            CompareFrame compare = new CompareFrame(
                    "Compare: " + left.label() + " vs " + right.label(),
                    left.getScrubImp(),
                    right.getScrubImp(),
                    left.label(),
                    right.label(),
                    left.plan(),
                    right.plan(),
                    listener);
            compare.setLocationRelativeTo(owner);
            compare.setVisible(true);
        }
    };

    public VariantGridFrame(String title, ImagePlus rawSource, List<VariantResult> results) {
        super(title == null ? "Variant Grid" : title);
        if (rawSource == null) throw new IllegalArgumentException("rawSource must not be null");
        if (results == null) throw new IllegalArgumentException("results must not be null");

        int requested = 1 + results.size();
        int totalTiles = Math.min(requested, MAX_TILES);
        int variantCap = Math.max(0, totalTiles - 1);
        int[] dims = computeGridDims(totalTiles);

        grid = new JPanel(new GridLayout(dims[0], dims[1], 4, 4));
        grid.setBackground(new Color(0x1a, 0x1a, 0x1a));
        grid.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));

        rawTile = addImageTile(rawSource, "RAW", true, null);

        int errors = 0;
        int kept = 0;
        for (int i = 0; i < results.size() && kept < variantCap; i++) {
            VariantResult result = results.get(i);
            if (result == null) continue;
            VariantPlan plan = result.plan;
            String caption = plan == null || plan.label == null || plan.label.length() == 0
                    ? "(unlabelled)"
                    : plan.label;
            if (result.error != null || result.output == null) {
                TilePanel tile = TilePanel.forError(caption, result.error, plan);
                tiles.add(tile);
                grid.add(tile);
                errors++;
            } else {
                ImagePlus styled = null;
                try {
                    styled = DisplaySettingsCloner.cloneFrom(rawSource, result.output);
                    CaptionBaker.bakeAll(styled, caption);
                    addImageTile(styled, caption, false, plan);
                    styled = null;
                } finally {
                    if (styled != null) styled.flush();
                    if (result.output != rawSource) result.output.flush();
                }
            }
            kept++;
        }
        initialVariantTileCount = kept;
        initialErrorTileCount = errors;
        padGrid();

        int maxSlice = Math.max(1, driver.maxSlice());
        zBar = new JScrollBar(JScrollBar.VERTICAL, 1, 1, 1, maxSlice + 1);
        zBar.setEnabled(maxSlice > 1);
        zBar.addAdjustmentListener(e -> {
            driver.setSlice(e.getValue());
            refreshStatus();
        });

        MouseWheelListener wheel = new MouseWheelListener() {
            @Override public void mouseWheelMoved(MouseWheelEvent e) {
                if (!zBar.isVisible() || !zBar.isEnabled()) return;
                int next = zBar.getValue() + e.getWheelRotation();
                next = Math.max(zBar.getMinimum(), Math.min(zBar.getMaximum() - 1, next));
                zBar.setValue(next);
            }
        };
        grid.addMouseWheelListener(wheel);

        mipToggle = new JToggleButton("MIP");
        mipToggle.setToolTipText("Show maximum-intensity Z projection on every tile");
        mipToggle.addChangeListener(new ChangeListener() {
            @Override public void stateChanged(javax.swing.event.ChangeEvent e) {
                boolean on = mipToggle.isSelected();
                for (int i = 0; i < tiles.size(); i++) {
                    TilePanel tile = tiles.get(i);
                    if (tile.isVisible()) tile.setMipMode(on);
                }
                zBar.setVisible(!on);
                revalidate();
                repaint();
            }
        });

        JToolBar toolbar = new JToolBar();
        toolbar.setFloatable(false);
        toolbar.add(mipToggle);
        toolbar.addSeparator();
        JButton saveMontage = new JButton("Save montage");
        saveMontage.setToolTipText("Save a labelled PNG montage of the visible tiles");
        saveMontage.addActionListener(e -> saveMontage());
        JButton copyIjm = new JButton("Copy .ijm");
        copyIjm.setToolTipText("Copy ImageJ macro code for visible variants");
        copyIjm.addActionListener(e -> copyIjm());
        JButton saveFocusedPreset = new JButton("Save preset");
        saveFocusedPreset.setToolTipText("Save the focused visible variant as a preset");
        saveFocusedPreset.addActionListener(e -> saveFocusedPreset());
        toolbar.add(saveMontage);
        toolbar.add(copyIjm);
        toolbar.add(saveFocusedPreset);

        statusLabel = new JLabel(" ");
        statusLabel.setBorder(BorderFactory.createEmptyBorder(2, 8, 2, 8));
        statusLabel.setFont(statusLabel.getFont().deriveFont(Font.PLAIN, 11f));
        JPanel south = new JPanel(new BorderLayout());
        south.add(statusLabel, BorderLayout.WEST);
        south.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0,
                new Color(0xc0, 0xc0, 0xc0)));

        setLayout(new BorderLayout());
        add(toolbar, BorderLayout.NORTH);
        add(grid, BorderLayout.CENTER);
        add(zBar, BorderLayout.EAST);
        add(south, BorderLayout.SOUTH);
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);

        refreshAllTileActions();
        refreshStatus();
        pack();
        Dimension preferred = getPreferredSize();
        setSize(Math.min(preferred.width, 1400), Math.min(preferred.height, 1000));
    }

    public void setActionListener(TileActionListener actionListener) {
        this.actionListener = actionListener;
        refreshAllTileActions();
    }

    public void setSessionLog(VariationSessionLog sessionLog) {
        this.sessionLog = sessionLog;
    }

    public void attachExporters(MontageExporter montageExporter,
                                IjmClipboardExporter ijmClipboardExporter) {
        this.montageExporter = montageExporter;
        this.ijmClipboardExporter = ijmClipboardExporter;
    }

    public List<TilePanel> visibleTilesInDisplayOrder() {
        List<TilePanel> out = new ArrayList<TilePanel>();
        for (int i = 0; i < tiles.size(); i++) {
            TilePanel tile = tiles.get(i);
            if (tile.isVisible()) out.add(tile);
        }
        return out;
    }

    public List<VariantPlan> visibleVariantPlansInDisplayOrder() {
        List<VariantPlan> out = new ArrayList<VariantPlan>();
        List<TilePanel> visible = visibleVariantTilesInDisplayOrder();
        for (int i = 0; i < visible.size(); i++) {
            if (visible.get(i).plan() != null) out.add(visible.get(i).plan());
        }
        return out;
    }

    public void eliminateTile(TilePanel tile) {
        if (tile == null || tile.isRawTile() || !tile.isVisible()) return;
        if (tile == promotedTile) promotedTile = null;
        tile.setVisible(false);
        driver.unregister(tile.getScrubImp());
        if (sessionLog != null) sessionLog.recordEliminate(tile.plan(), tile.label());
        if (Recorder.record) {
            Recorder.recordString("// flash variation: eliminated " + tile.label() + "\n");
        }

        List<TilePanel> remainingVariants = visibleVariantTilesInDisplayOrder();
        if (remainingVariants.size() == 1) {
            TilePanel survivor = remainingVariants.get(0);
            if (survivor.hasImage()) {
                openCompare(rawTile, survivor);
                dispose();
            } else {
                relayoutGrid();
                refreshStatus();
            }
        } else if (remainingVariants.isEmpty()) {
            relayoutGrid();
            statusLabel.setText("Only raw remains. Re-open Variations to try again.");
        } else {
            relayoutGrid();
            refreshStatus();
        }
    }

    static int[] computeGridDims(int n) {
        int capped = Math.max(1, Math.min(n, MAX_TILES));
        int cols = (int) Math.ceil(Math.sqrt(capped));
        int rows = (int) Math.ceil(capped / (double) cols);
        return new int[] { rows, cols };
    }

    @Override
    public Dimension getPreferredSize() {
        Dimension base = super.getPreferredSize();
        return new Dimension(Math.max(640, base.width), Math.max(480, base.height));
    }

    private TilePanel addImageTile(ImagePlus imp, String caption, boolean isRaw,
                                   VariantPlan plan) {
        final TilePanel tile = new TilePanel(imp, caption, isRaw, plan);
        tiles.add(tile);
        grid.add(tile);
        driver.register(imp, new Runnable() {
            @Override public void run() {
                if (tile.getActiveCanvas() != null) tile.getActiveCanvas().repaint();
            }
        });
        return tile;
    }

    private void refreshAllTileActions() {
        for (int i = 0; i < tiles.size(); i++) {
            final TilePanel tile = tiles.get(i);
            TileActionListener tileListener = new TileActionListener() {
                @Override public void onPromote(VariantPlan plan) {
                    if (actionListener != null) actionListener.onPromote(plan);
                    markPromotedTile(tile);
                }

                @Override public void onSavePreset(VariantPlan plan) {
                    if (actionListener != null) actionListener.onSavePreset(plan);
                }
            };
            tile.setActions(tileListener, new Runnable() {
                @Override public void run() {
                    eliminateTile(tile);
                }
            });
        }
    }

    private void markPromotedTile(TilePanel tile) {
        if (promotedTile != null && promotedTile != tile) {
            promotedTile.setAppliedToBuilder(false);
        }
        promotedTile = tile;
        if (tile != null) {
            tile.setAppliedToBuilder(true);
            statusLabel.setText("Applied to active filter: " + tile.label());
        }
    }

    private List<TilePanel> visibleVariantTilesInDisplayOrder() {
        List<TilePanel> out = new ArrayList<TilePanel>();
        for (int i = 0; i < tiles.size(); i++) {
            TilePanel tile = tiles.get(i);
            if (tile.isVisible() && !tile.isRawTile()) out.add(tile);
        }
        return out;
    }

    private void relayoutGrid() {
        List<TilePanel> visible = visibleTilesInDisplayOrder();
        int[] dims = computeGridDims(visible.size());
        grid.removeAll();
        grid.setLayout(new GridLayout(dims[0], dims[1], 4, 4));
        for (int i = 0; i < visible.size(); i++) {
            grid.add(visible.get(i));
        }
        padGrid();
        refreshZBar();
        grid.revalidate();
        grid.repaint();
    }

    private void padGrid() {
        GridLayout layout = (GridLayout) grid.getLayout();
        int totalCells = layout.getRows() * layout.getColumns();
        int filled = grid.getComponentCount();
        for (int i = filled; i < totalCells; i++) {
            JPanel empty = new JPanel();
            empty.setBackground(new Color(0x1a, 0x1a, 0x1a));
            grid.add(empty);
        }
    }

    private void refreshZBar() {
        int maxSlice = Math.max(1, driver.maxSlice());
        int value = Math.max(1, Math.min(zBar.getValue(), maxSlice));
        zBar.setMaximum(maxSlice + 1);
        zBar.setValue(value);
        zBar.setEnabled(maxSlice > 1);
    }

    private void refreshStatus() {
        int slice = driver.currentSlice();
        int max = driver.maxSlice();
        int visibleVariants = visibleVariantTilesInDisplayOrder().size();
        StringBuilder text = new StringBuilder();
        text.append("Slice ").append(slice).append(" of ").append(max);
        text.append("  |  Variants: ").append(visibleVariants).append(" visible");
        if (initialVariantTileCount != visibleVariants) {
            text.append(" of ").append(initialVariantTileCount);
        }
        if (initialErrorTileCount > 0) {
            text.append(" (").append(initialErrorTileCount).append(" failed)");
        }
        statusLabel.setText(text.toString());
    }

    private void openCompare(TilePanel left, TilePanel right) {
        compareLauncher.open(this, left, right, actionListener);
    }

    private void saveMontage() {
        if (montageExporter == null) montageExporter = new MontageExporter(this);
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Save labelled montage");
        chooser.setSelectedFile(new File(defaultExportBaseName() + "_variations.png"));
        chooser.addChoosableFileFilter(
                new FileNameExtensionFilter("PNG image (*.png)", "png"));
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
        File file = ensureExtension(chooser.getSelectedFile(), ".png");
        montageExporter.exportTo(file);
        if (Recorder.record && file != null) {
            Recorder.recordString("// flash variation: saved montage " + file.getName() + "\n");
        }
    }

    private void copyIjm() {
        if (ijmClipboardExporter == null) ijmClipboardExporter = new IjmClipboardExporter(this);
        try {
            ijmClipboardExporter.copyVisibleVariantsToClipboard();
            if (Recorder.record) Recorder.recordString("// flash variation: copied visible .ijm\n");
        } catch (RuntimeException ex) {
            IJ.showMessage("Variations", "Could not copy .ijm to clipboard:\n" + ex.getMessage());
        }
    }

    private void saveFocusedPreset() {
        TilePanel tile = focusedVariantTile();
        if (tile == null || tile.plan() == null) {
            IJ.showMessage("Variations", "Click a visible variant tile first.");
            return;
        }
        if (actionListener == null) return;
        actionListener.onSavePreset(tile.plan());
    }

    private TilePanel focusedVariantTile() {
        Component focusOwner = KeyboardFocusManager
                .getCurrentKeyboardFocusManager()
                .getFocusOwner();
        List<TilePanel> variants = visibleVariantTilesInDisplayOrder();
        for (int i = 0; i < variants.size(); i++) {
            TilePanel tile = variants.get(i);
            if (focusOwner != null
                    && (focusOwner == tile
                    || javax.swing.SwingUtilities.isDescendingFrom(focusOwner, tile))) {
                return tile;
            }
        }
        return variants.isEmpty() ? null : variants.get(0);
    }

    TilePanel rawTileForTest() {
        return rawTile;
    }

    List<TilePanel> tilesForTest() {
        return Collections.unmodifiableList(tiles);
    }

    int visibleVariantTileCountForTest() {
        return visibleVariantTilesInDisplayOrder().size();
    }

    SharedSliceDriver driverForTest() {
        return driver;
    }

    JScrollBar zBarForTest() {
        return zBar;
    }

    JLabel statusLabelForTest() {
        return statusLabel;
    }

    JToggleButton mipToggleForTest() {
        return mipToggle;
    }

    void setCompareLauncherForTest(CompareLauncher launcher) {
        compareLauncher = launcher == null ? compareLauncher : launcher;
    }

    private String defaultExportBaseName() {
        if (rawTile == null || rawTile.getScrubImp() == null) return "variations";
        String title = rawTile.getScrubImp().getTitle();
        if (title == null || title.trim().isEmpty()) return "variations";
        return title.replaceAll("[^A-Za-z0-9._-]+", "_");
    }

    private static File ensureExtension(File file, String extension) {
        if (file == null) return null;
        String name = file.getName().toLowerCase();
        if (name.endsWith(extension)) return file;
        return new File(file.getParentFile(), file.getName() + extension);
    }
}
