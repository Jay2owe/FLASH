package flash.pipeline.representative;

import flash.pipeline.ui.FlashTheme;

import javax.imageio.ImageIO;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ImageIcon;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.border.Border;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Scrollable Step-2 grid for choosing one representative series per condition.
 */
public final class RepresentativeSelectionPanel extends JPanel {

    /**
     * Listener notified whenever the representative row selection changes.
     */
    public interface SelectionListener {
        void selectionChanged(SelectionEvent event);
    }

    /**
     * Snapshot of selection state after a representative row click.
     */
    public static final class SelectionEvent {
        private final RepresentativeSeries series;
        private final boolean complete;
        private final int selectedConditionCount;
        private final int conditionCount;

        SelectionEvent(RepresentativeSeries series,
                       boolean complete,
                       int selectedConditionCount,
                       int conditionCount) {
            this.series = series;
            this.complete = complete;
            this.selectedConditionCount = selectedConditionCount;
            this.conditionCount = conditionCount;
        }

        public RepresentativeSeries series() {
            return series;
        }

        public boolean isComplete() {
            return complete;
        }

        public int selectedConditionCount() {
            return selectedConditionCount;
        }

        public int conditionCount() {
            return conditionCount;
        }
    }

    private static final int THUMB_WIDTH = 132;
    private static final int THUMB_HEIGHT = 84;
    private static final int META_WIDTH = 148;
    private static final int ROW_GAP = 8;
    private static final int MAX_THUMB_THREADS = 4;

    private final LinkedHashMap<String, List<RepresentativeSeries>> seriesByCondition;
    private final LinkedHashMap<String, RepresentativeSeries> selectedByCondition =
            new LinkedHashMap<String, RepresentativeSeries>();
    private final LinkedHashMap<String, SeriesRowPanel> rowBySeriesId =
            new LinkedHashMap<String, SeriesRowPanel>();
    private final LinkedHashMap<String, List<SeriesRowPanel>> rowsByCondition =
            new LinkedHashMap<String, List<SeriesRowPanel>>();
    private final List<SelectionListener> listeners = new ArrayList<SelectionListener>();
    private final List<Future<?>> thumbnailTasks = new ArrayList<Future<?>>();
    private final ExecutorService thumbnailExecutor;
    private final JLabel statusLabel = new JLabel();
    private final RepresentativeStatsPanel statsPanel;

    private volatile boolean disposed = false;

    public RepresentativeSelectionPanel(List<RepresentativeSeries> series) {
        this(series, RepresentativeStatistic.NONE, null, null);
    }

    public RepresentativeSelectionPanel(List<RepresentativeSeries> series,
                                        RepresentativeStatistic statistic,
                                        RepresentativeStatTable statTable) {
        this(series, statistic, statTable, null);
    }

    public RepresentativeSelectionPanel(List<RepresentativeSeries> series,
                                        RepresentativeStatistic statistic,
                                        RepresentativeStatTable statTable,
                                        RepresentativeSelection rememberedSelection) {
        this.seriesByCondition = groupByCondition(series);
        this.statsPanel = shouldShowStatsPanel(statistic)
                ? new RepresentativeStatsPanel(statTable) : null;
        this.thumbnailExecutor = Executors.newFixedThreadPool(thumbnailThreadCount(),
                new ThumbnailThreadFactory());
        buildUi();
        applyRememberedSelection(rememberedSelection);
        updateStatus();
    }

    public void addSelectionListener(SelectionListener listener) {
        if (listener != null) listeners.add(listener);
    }

    public void removeSelectionListener(SelectionListener listener) {
        listeners.remove(listener);
    }

    public List<String> conditionNames() {
        return Collections.unmodifiableList(
                new ArrayList<String>(seriesByCondition.keySet()));
    }

    public int seriesCount(String conditionName) {
        List<RepresentativeSeries> series =
                seriesByCondition.get(RepresentativeSelection.conditionLabel(conditionName));
        return series == null ? 0 : series.size();
    }

    public RepresentativeSeries selectedSeries(String conditionName) {
        return selectedByCondition.get(RepresentativeSelection.conditionLabel(conditionName));
    }

    public boolean hasCompleteSelection() {
        return !seriesByCondition.isEmpty()
                && selectedByCondition.size() == seriesByCondition.size();
    }

    public RepresentativeSelection createSelection() {
        if (!hasCompleteSelection()) {
            throw new IllegalStateException("Representative selection is incomplete.");
        }
        return new RepresentativeSelection(conditionNames(), selectedByCondition);
    }

    public boolean selectSeries(RepresentativeSeries series) {
        if (series == null) return false;
        String id = series.id();
        SeriesRowPanel selectedRow = id == null ? null : rowBySeriesId.get(id);
        if (selectedRow == null) return false;

        RepresentativeSeries selectedSeries = selectedRow.series;
        String condition = RepresentativeSelection.conditionLabel(selectedSeries.condition());
        selectedByCondition.put(condition, selectedSeries);
        List<SeriesRowPanel> rows = rowsByCondition.get(condition);
        if (rows != null) {
            for (SeriesRowPanel row : rows) {
                row.setSelected(row.series == selectedSeries);
            }
        }
        if (statsPanel != null) {
            statsPanel.setHighlightedSeries(selectedSeries);
        }
        updateStatus();
        fireSelectionChanged(selectedSeries);
        return true;
    }

    boolean selectSeriesForTests(String seriesId) {
        SeriesRowPanel row = rowBySeriesId.get(seriesId);
        return row != null && selectSeries(row.series);
    }

    RepresentativeStatsPanel statsPanelForTests() {
        return statsPanel;
    }

    public void dispose() {
        disposed = true;
        for (Future<?> task : thumbnailTasks) {
            task.cancel(true);
        }
        thumbnailExecutor.shutdownNow();
    }

    private void buildUi() {
        setLayout(new BorderLayout(0, FlashTheme.SPACE_S));
        setBackground(FlashTheme.SURFACE_RAISED);
        setAlignmentX(Component.LEFT_ALIGNMENT);

        statusLabel.setFont(FlashTheme.caption());
        statusLabel.setForeground(FlashTheme.TEXT_MUTED);
        statusLabel.setBorder(FlashTheme.pad(0, 2, 0, 2));
        add(statusLabel, BorderLayout.NORTH);

        JPanel columns = new JPanel();
        columns.setLayout(new BoxLayout(columns, BoxLayout.X_AXIS));
        columns.setBackground(FlashTheme.SURFACE);
        columns.setBorder(FlashTheme.pad(FlashTheme.SPACE_S));

        for (Map.Entry<String, List<RepresentativeSeries>> entry : seriesByCondition.entrySet()) {
            JPanel column = buildConditionColumn(entry.getKey(), entry.getValue());
            columns.add(column);
            columns.add(Box.createHorizontalStrut(FlashTheme.SPACE_M));
        }

        JScrollPane scroll = new JScrollPane(columns,
                JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
                JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);
        scroll.setBorder(BorderFactory.createLineBorder(FlashTheme.BORDER));
        scroll.getVerticalScrollBar().setUnitIncrement(24);
        scroll.getHorizontalScrollBar().setUnitIncrement(24);
        scroll.setPreferredSize(new Dimension(statsPanel == null ? 1080 : 760, 620));
        scroll.setMinimumSize(new Dimension(640, 360));

        if (statsPanel == null) {
            add(scroll, BorderLayout.CENTER);
        } else {
            JPanel body = new JPanel(new BorderLayout(FlashTheme.SPACE_M, 0));
            body.setBackground(FlashTheme.SURFACE_RAISED);
            body.add(scroll, BorderLayout.CENTER);
            body.add(statsPanel, BorderLayout.EAST);
            add(body, BorderLayout.CENTER);
        }
    }

    private void applyRememberedSelection(RepresentativeSelection rememberedSelection) {
        if (rememberedSelection == null || seriesByCondition.isEmpty()) {
            return;
        }
        for (String condition : rememberedSelection.conditionNames()) {
            RepresentativeSeries remembered =
                    rememberedSelection.seriesForCondition(condition);
            RepresentativeSeries current = matchingCurrentSeries(condition, remembered);
            if (current != null) {
                selectSeries(current);
            }
        }
    }

    private RepresentativeSeries matchingCurrentSeries(String condition,
                                                       RepresentativeSeries remembered) {
        if (remembered == null) {
            return null;
        }
        String normalizedCondition =
                RepresentativeSelection.conditionLabel(condition);
        SeriesRowPanel byId = rowBySeriesId.get(remembered.id());
        if (byId != null && sameCondition(byId.series, normalizedCondition)) {
            return byId.series;
        }
        List<SeriesRowPanel> rows = rowsByCondition.get(normalizedCondition);
        if (rows == null) {
            return null;
        }
        for (SeriesRowPanel row : rows) {
            if (sameSeries(row.series, remembered)) {
                return row.series;
            }
        }
        return null;
    }

    private static boolean sameSeries(RepresentativeSeries current,
                                      RepresentativeSeries remembered) {
        if (current == null || remembered == null) {
            return false;
        }
        if (current.seriesIndex() == remembered.seriesIndex()
                && samePath(current.sourcePath(), remembered.sourcePath())) {
            return true;
        }
        return clean(current.seriesName()).equals(clean(remembered.seriesName()))
                && clean(current.animal()).equals(clean(remembered.animal()))
                && clean(current.hemisphere()).equals(clean(remembered.hemisphere()))
                && clean(current.region()).equals(clean(remembered.region()));
    }

    private static boolean sameCondition(RepresentativeSeries series, String condition) {
        return series != null
                && RepresentativeSelection.conditionLabel(series.condition())
                .equals(RepresentativeSelection.conditionLabel(condition));
    }

    private static boolean samePath(File left, File right) {
        if (left == null || right == null) {
            return left == right;
        }
        return left.getAbsoluteFile().equals(right.getAbsoluteFile());
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }

    private JPanel buildConditionColumn(String condition,
                                        List<RepresentativeSeries> seriesForCondition) {
        JPanel column = new JPanel();
        column.setLayout(new BoxLayout(column, BoxLayout.Y_AXIS));
        column.setBackground(FlashTheme.SURFACE_RAISED);
        column.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(FlashTheme.BORDER_STRONG),
                FlashTheme.pad(FlashTheme.SPACE_S)));
        column.setAlignmentY(Component.TOP_ALIGNMENT);

        int width = preferredColumnWidth(seriesForCondition);
        column.setPreferredSize(new Dimension(width, 120));
        column.setMaximumSize(new Dimension(width, Integer.MAX_VALUE));

        JLabel header = new JLabel(condition);
        header.setFont(FlashTheme.h2());
        header.setForeground(FlashTheme.TEXT_HEADER);
        header.setAlignmentX(Component.LEFT_ALIGNMENT);
        column.add(header);
        column.add(Box.createVerticalStrut(FlashTheme.SPACE_S));

        List<SeriesRowPanel> rows = new ArrayList<SeriesRowPanel>();
        for (RepresentativeSeries series : seriesForCondition) {
            SeriesRowPanel row = new SeriesRowPanel(series);
            row.setAlignmentX(Component.LEFT_ALIGNMENT);
            rows.add(row);
            rowBySeriesId.put(series.id(), row);
            column.add(row);
            column.add(Box.createVerticalStrut(ROW_GAP));
        }
        rowsByCondition.put(condition, rows);
        return column;
    }

    private int preferredColumnWidth(List<RepresentativeSeries> seriesForCondition) {
        int maxTileCount = 1;
        for (RepresentativeSeries series : seriesForCondition) {
            int count = series.channelThumbnails().size() + 1;
            if (count > maxTileCount) maxTileCount = count;
        }
        return META_WIDTH + FlashTheme.SPACE_M
                + maxTileCount * (THUMB_WIDTH + FlashTheme.SPACE_S)
                + FlashTheme.SPACE_L;
    }

    private void updateStatus() {
        statusLabel.setText("Selected " + selectedByCondition.size()
                + " / " + seriesByCondition.size() + " conditions");
    }

    private void fireSelectionChanged(RepresentativeSeries series) {
        SelectionEvent event = new SelectionEvent(series, hasCompleteSelection(),
                selectedByCondition.size(), seriesByCondition.size());
        List<SelectionListener> copy = new ArrayList<SelectionListener>(listeners);
        for (SelectionListener listener : copy) {
            listener.selectionChanged(event);
        }
    }

    private JComponent buildMetaPanel(RepresentativeSeries series) {
        JPanel meta = new JPanel();
        meta.setLayout(new BoxLayout(meta, BoxLayout.Y_AXIS));
        meta.setOpaque(false);
        meta.setPreferredSize(new Dimension(META_WIDTH, THUMB_HEIGHT + 24));
        meta.setMaximumSize(new Dimension(META_WIDTH, THUMB_HEIGHT + 24));

        JLabel title = metadataLabel(series.seriesName().isEmpty()
                ? "Series " + series.seriesNumber()
                : series.seriesName(), FlashTheme.bodyMedium(), FlashTheme.TEXT_PRIMARY);
        JLabel animal = metadataLabel(series.animal(), FlashTheme.caption(),
                FlashTheme.TEXT_SUBHEADER);
        JLabel region = metadataLabel(regionText(series), FlashTheme.caption(),
                FlashTheme.TEXT_MUTED);
        JLabel source = metadataLabel(sourceText(series), FlashTheme.caption(),
                FlashTheme.TEXT_MUTED);

        meta.add(title);
        meta.add(Box.createVerticalStrut(3));
        meta.add(animal);
        meta.add(region);
        meta.add(Box.createVerticalGlue());
        meta.add(source);
        return meta;
    }

    private JLabel metadataLabel(String text, java.awt.Font font, Color color) {
        JLabel label = new JLabel(text == null ? "" : text);
        label.setFont(font);
        label.setForeground(color);
        label.setToolTipText(text);
        label.setAlignmentX(Component.LEFT_ALIGNMENT);
        label.setPreferredSize(new Dimension(META_WIDTH, 18));
        label.setMaximumSize(new Dimension(META_WIDTH, 18));
        return label;
    }

    private String regionText(RepresentativeSeries series) {
        String hemi = series.hemisphere();
        String region = series.region();
        if (hemi.isEmpty()) return region;
        if (region.isEmpty()) return hemi;
        return hemi + " " + region;
    }

    private String sourceText(RepresentativeSeries series) {
        String source = series.previewSource().name().toLowerCase();
        return series.cacheHit() ? source + " cache" : source;
    }

    private JComponent thumbnailTile(String label,
                                     BufferedImage image,
                                     File cacheFile) {
        JPanel tile = new JPanel(new BorderLayout(0, 3));
        tile.setOpaque(false);
        tile.setPreferredSize(new Dimension(THUMB_WIDTH, THUMB_HEIGHT + 20));
        tile.setMaximumSize(new Dimension(THUMB_WIDTH, THUMB_HEIGHT + 20));

        JLabel imageLabel = new JLabel("Loading", SwingConstants.CENTER);
        imageLabel.setFont(FlashTheme.caption());
        imageLabel.setForeground(FlashTheme.TEXT_MUTED);
        imageLabel.setOpaque(true);
        imageLabel.setBackground(FlashTheme.SURFACE_MUTED);
        imageLabel.setBorder(BorderFactory.createLineBorder(FlashTheme.BORDER));
        imageLabel.setPreferredSize(new Dimension(THUMB_WIDTH, THUMB_HEIGHT));
        imageLabel.setMinimumSize(new Dimension(THUMB_WIDTH, THUMB_HEIGHT));

        JLabel caption = new JLabel(label == null ? "" : label, SwingConstants.CENTER);
        caption.setFont(FlashTheme.caption());
        caption.setForeground(FlashTheme.TEXT_SUBHEADER);
        caption.setToolTipText(label);

        tile.add(imageLabel, BorderLayout.CENTER);
        tile.add(caption, BorderLayout.SOUTH);
        loadThumbnailAsync(imageLabel, image, cacheFile);
        return tile;
    }

    private void loadThumbnailAsync(final JLabel label,
                                    final BufferedImage image,
                                    final File cacheFile) {
        Future<?> task = thumbnailExecutor.submit(new Runnable() {
            @Override
            public void run() {
                final ImageIcon icon = createIcon(image, cacheFile);
                SwingUtilities.invokeLater(new Runnable() {
                    @Override
                    public void run() {
                        if (disposed) return;
                        if (icon == null) {
                            label.setText("Missing");
                        } else {
                            label.setText("");
                            label.setIcon(icon);
                        }
                    }
                });
            }
        });
        thumbnailTasks.add(task);
    }

    private ImageIcon createIcon(BufferedImage image, File cacheFile) {
        BufferedImage source = image;
        if (source == null && cacheFile != null && cacheFile.isFile()) {
            try {
                source = ImageIO.read(cacheFile);
            } catch (IOException e) {
                source = null;
            }
        }
        if (source == null) return null;
        return new ImageIcon(scaleToFit(source, THUMB_WIDTH, THUMB_HEIGHT));
    }

    private BufferedImage scaleToFit(BufferedImage source, int maxWidth, int maxHeight) {
        int width = Math.max(1, source.getWidth());
        int height = Math.max(1, source.getHeight());
        double scale = Math.min((double) maxWidth / (double) width,
                (double) maxHeight / (double) height);
        int targetWidth = Math.max(1, (int) Math.round(width * scale));
        int targetHeight = Math.max(1, (int) Math.round(height * scale));
        BufferedImage scaled = new BufferedImage(targetWidth, targetHeight,
                BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = scaled.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(RenderingHints.KEY_RENDERING,
                    RenderingHints.VALUE_RENDER_QUALITY);
            g.drawImage(source, 0, 0, targetWidth, targetHeight, null);
        } finally {
            g.dispose();
        }
        return scaled;
    }

    private void installSelectionHandler(Component component,
                                         final RepresentativeSeries series) {
        component.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                selectSeries(series);
            }
        });
        if (component instanceof Container) {
            Component[] children = ((Container) component).getComponents();
            for (int i = 0; i < children.length; i++) {
                installSelectionHandler(children[i], series);
            }
        }
    }

    private static LinkedHashMap<String, List<RepresentativeSeries>> groupByCondition(
            List<RepresentativeSeries> seriesList) {
        LinkedHashMap<String, List<RepresentativeSeries>> grouped =
                new LinkedHashMap<String, List<RepresentativeSeries>>();
        if (seriesList == null) return grouped;
        for (RepresentativeSeries series : seriesList) {
            if (series == null) continue;
            String condition = RepresentativeSelection.conditionLabel(series.condition());
            List<RepresentativeSeries> bucket = grouped.get(condition);
            if (bucket == null) {
                bucket = new ArrayList<RepresentativeSeries>();
                grouped.put(condition, bucket);
            }
            bucket.add(series);
        }
        return grouped;
    }

    private static int thumbnailThreadCount() {
        int processors = Runtime.getRuntime().availableProcessors();
        return Math.max(1, Math.min(MAX_THUMB_THREADS, processors));
    }

    private static boolean shouldShowStatsPanel(RepresentativeStatistic statistic) {
        return statistic != null && statistic != RepresentativeStatistic.NONE;
    }

    private static Border rowBorder(boolean selected) {
        Color border = selected ? FlashTheme.SELECTION_BORDER : FlashTheme.BORDER;
        int thickness = selected ? 2 : 1;
        return BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(border, thickness),
                FlashTheme.pad(FlashTheme.SPACE_S));
    }

    private final class SeriesRowPanel extends JPanel {
        final RepresentativeSeries series;

        SeriesRowPanel(RepresentativeSeries series) {
            this.series = series;
            setLayout(new BorderLayout(FlashTheme.SPACE_M, 0));
            setBackground(FlashTheme.TILE_BG);
            setBorder(rowBorder(false));
            setPreferredSize(new Dimension(preferredColumnWidth(
                    Collections.singletonList(series)) - FlashTheme.SPACE_L,
                    THUMB_HEIGHT + 42));
            setMaximumSize(new Dimension(Integer.MAX_VALUE, THUMB_HEIGHT + 42));

            JPanel strip = new JPanel();
            strip.setLayout(new BoxLayout(strip, BoxLayout.X_AXIS));
            strip.setOpaque(false);
            for (RepresentativeSeries.ChannelThumbnail thumbnail
                    : series.channelThumbnails()) {
                strip.add(thumbnailTile(thumbnail.channelName(),
                        thumbnail.image(), thumbnail.cacheFile()));
                strip.add(Box.createHorizontalStrut(FlashTheme.SPACE_S));
            }
            strip.add(thumbnailTile("Merge", series.mergeThumbnail(),
                    series.mergeCacheFile()));

            add(buildMetaPanel(series), BorderLayout.WEST);
            add(strip, BorderLayout.CENTER);
            installSelectionHandler(this, series);
        }

        void setSelected(boolean selected) {
            setBackground(selected ? FlashTheme.PRIMARY_BG : FlashTheme.TILE_BG);
            setBorder(rowBorder(selected));
            repaint();
        }
    }

    private static final class ThumbnailThreadFactory implements ThreadFactory {
        private final AtomicInteger count = new AtomicInteger(1);

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable,
                    "representative-selection-thumb-" + count.getAndIncrement());
            thread.setDaemon(true);
            return thread;
        }
    }
}
