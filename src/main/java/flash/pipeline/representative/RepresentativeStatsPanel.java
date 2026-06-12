package flash.pipeline.representative;

import flash.pipeline.ui.FlashTheme;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import java.awt.AlphaComposite;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * Per-channel quantification charts shown beside the representative selection grid.
 */
public final class RepresentativeStatsPanel extends JPanel {

    private static final int PANEL_WIDTH = 360;
    private static final int CHART_WIDTH = 326;
    private static final int CHART_HEIGHT = 176;
    private static final Color CHART_BACKGROUND = new Color(250, 250, 250);
    private static final Color AXIS = new Color(145, 145, 145);
    private static final Color GRID = new Color(224, 228, 232);
    private static final Color BAR_FILL = new Color(71, 145, 196, 70);
    private static final Color BAR_BORDER = new Color(71, 145, 196, 165);
    private static final Color POINT = new Color(72, 72, 72, 190);
    private static final Color HIGHLIGHT = new Color(204, 70, 42);

    private final List<ChannelChartData> chartData;
    private final List<ChannelChartPanel> chartPanels =
            new ArrayList<ChannelChartPanel>();
    private final LinkedHashSet<String> highlightedSeriesIds =
            new LinkedHashSet<String>();
    private String highlightedSeriesId = "";

    public RepresentativeStatsPanel(RepresentativeStatTable table) {
        this.chartData = buildChartData(table);
        buildUi();
    }

    public void setHighlightedSeries(RepresentativeSeries series) {
        setHighlightedSeriesId(series == null ? "" : series.id());
    }

    public void setHighlightedSeries(Collection<RepresentativeSeries> series,
                                     RepresentativeSeries primarySeries) {
        LinkedHashSet<String> ids = new LinkedHashSet<String>();
        if (series != null) {
            for (RepresentativeSeries item : series) {
                String id = item == null ? "" : clean(item.id());
                if (!id.isEmpty()) {
                    ids.add(id);
                }
            }
        }
        setHighlightedSeriesIds(ids,
                primarySeries == null ? "" : primarySeries.id());
    }

    public void setHighlightedSeriesId(String seriesId) {
        String normalized = clean(seriesId);
        LinkedHashSet<String> ids = new LinkedHashSet<String>();
        if (!normalized.isEmpty()) {
            ids.add(normalized);
        }
        setHighlightedSeriesIds(ids, normalized);
    }

    private void setHighlightedSeriesIds(LinkedHashSet<String> seriesIds,
                                         String primarySeriesId) {
        String normalizedPrimary = clean(primarySeriesId);
        if (normalizedPrimary.isEmpty() && !seriesIds.isEmpty()) {
            normalizedPrimary = seriesIds.iterator().next();
        }
        if (highlightedSeriesId.equals(normalizedPrimary)
                && highlightedSeriesIds.equals(seriesIds)) {
            return;
        }
        highlightedSeriesId = normalizedPrimary;
        highlightedSeriesIds.clear();
        highlightedSeriesIds.addAll(seriesIds);
        for (ChannelChartPanel chartPanel : chartPanels) {
            chartPanel.setHighlightedSeriesIds(highlightedSeriesIds,
                    highlightedSeriesId);
        }
    }

    boolean hasChartsForTest() {
        return !chartData.isEmpty();
    }

    int chartCountForTest() {
        return chartData.size();
    }

    String highlightedSeriesIdForTest() {
        return highlightedSeriesId;
    }

    List<String> highlightedSeriesIdsForTest() {
        return Collections.unmodifiableList(
                new ArrayList<String>(highlightedSeriesIds));
    }

    List<ChannelChartData> chartDataForTest() {
        return chartData;
    }

    private void buildUi() {
        setLayout(new BorderLayout(0, FlashTheme.SPACE_S));
        setBackground(FlashTheme.SURFACE_RAISED);
        setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(FlashTheme.BORDER_STRONG),
                FlashTheme.pad(FlashTheme.SPACE_S)));
        setPreferredSize(new Dimension(PANEL_WIDTH, 620));
        setMinimumSize(new Dimension(300, 320));

        JLabel header = new JLabel("Quantification");
        header.setFont(FlashTheme.h2());
        header.setForeground(FlashTheme.TEXT_HEADER);
        add(header, BorderLayout.NORTH);

        if (chartData.isEmpty()) {
            JLabel empty = new JLabel("No statistic values");
            empty.setFont(FlashTheme.caption());
            empty.setForeground(FlashTheme.TEXT_MUTED);
            empty.setBorder(FlashTheme.pad(FlashTheme.SPACE_L));
            add(empty, BorderLayout.CENTER);
            return;
        }

        JPanel chartColumn = new JPanel();
        chartColumn.setLayout(new BoxLayout(chartColumn, BoxLayout.Y_AXIS));
        chartColumn.setBackground(FlashTheme.SURFACE_RAISED);

        for (ChannelChartData data : chartData) {
            ChannelChartPanel chartPanel = new ChannelChartPanel(data);
            chartPanel.setAlignmentX(LEFT_ALIGNMENT);
            chartPanels.add(chartPanel);
            chartColumn.add(chartPanel);
            chartColumn.add(Box.createVerticalStrut(FlashTheme.SPACE_S));
        }

        JScrollPane scroll = new JScrollPane(chartColumn,
                JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
                JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.getViewport().setBackground(FlashTheme.SURFACE_RAISED);
        scroll.getVerticalScrollBar().setUnitIncrement(18);
        add(scroll, BorderLayout.CENTER);
    }

    static List<ChannelChartData> buildChartData(RepresentativeStatTable table) {
        if (table == null || table.isEmpty()) {
            return Collections.emptyList();
        }

        List<RepresentativeStatTable.Row> rows = table.rows();
        List<ChannelChartData> charts = new ArrayList<ChannelChartData>();
        for (String channelName : table.channelNames()) {
            LinkedHashMap<String, ConditionBuilder> byCondition =
                    new LinkedHashMap<String, ConditionBuilder>();
            for (RepresentativeStatTable.Row row : rows) {
                Double value = row.value(channelName);
                if (value == null || !Double.isFinite(value.doubleValue())) continue;

                String condition = RepresentativeSelection.conditionLabel(row.conditionName);
                ConditionBuilder builder = byCondition.get(condition);
                if (builder == null) {
                    builder = new ConditionBuilder(condition);
                    byCondition.put(condition, builder);
                }
                builder.points.add(new PointBuilder(row.seriesId, pointLabel(row),
                        condition, value.doubleValue()));
            }
            ChannelChartData data = new ChannelChartData(channelName, byCondition);
            if (!data.isEmpty()) {
                charts.add(data);
            }
        }
        return Collections.unmodifiableList(charts);
    }

    private static String pointLabel(RepresentativeStatTable.Row row) {
        if (!clean(row.animalName).isEmpty()) return clean(row.animalName);
        if (!clean(row.seriesName).isEmpty()) return clean(row.seriesName);
        if (row.seriesNumber > 0) return "Series " + row.seriesNumber;
        return clean(row.seriesId);
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }

    private static String formatNumber(double value) {
        double abs = Math.abs(value);
        String pattern = abs >= 1000.0 || (abs > 0.0 && abs < 0.01)
                ? "0.###E0" : "0.###";
        return new DecimalFormat(pattern).format(value);
    }

    static final class ChannelChartData {
        final String channelName;
        final List<ConditionSummary> conditions;
        final Map<String, SeriesPoint> pointsBySeriesId;
        final double minimum;
        final double maximum;

        ChannelChartData(String channelName,
                         LinkedHashMap<String, ConditionBuilder> byCondition) {
            this.channelName = clean(channelName).isEmpty()
                    ? "Statistic" : clean(channelName);

            List<ConditionSummary> conditionList =
                    new ArrayList<ConditionSummary>();
            LinkedHashMap<String, SeriesPoint> pointMap =
                    new LinkedHashMap<String, SeriesPoint>();
            double min = Double.POSITIVE_INFINITY;
            double max = Double.NEGATIVE_INFINITY;
            int index = 0;

            for (ConditionBuilder builder : byCondition.values()) {
                double sum = 0.0;
                List<SeriesPoint> points = new ArrayList<SeriesPoint>();
                for (PointBuilder pointBuilder : builder.points) {
                    SeriesPoint point = new SeriesPoint(pointBuilder.seriesId,
                            pointBuilder.label, pointBuilder.conditionName, index,
                            pointBuilder.value);
                    points.add(point);
                    pointMap.put(point.seriesId, point);
                    sum += point.value;
                    if (point.value < min) min = point.value;
                    if (point.value > max) max = point.value;
                }
                if (!points.isEmpty()) {
                    double mean = sum / (double) points.size();
                    if (mean < min) min = mean;
                    if (mean > max) max = mean;
                    conditionList.add(new ConditionSummary(builder.conditionName,
                            index, mean, points));
                    index++;
                }
            }

            if (conditionList.isEmpty()) {
                min = 0.0;
                max = 1.0;
            } else if (!(max > min)) {
                double pad = Math.max(1.0, Math.abs(min) * 0.08);
                min -= pad;
                max += pad;
            } else {
                double pad = (max - min) * 0.08;
                min -= pad;
                max += pad;
            }

            this.conditions = Collections.unmodifiableList(conditionList);
            this.pointsBySeriesId = Collections.unmodifiableMap(pointMap);
            this.minimum = min;
            this.maximum = max;
        }

        boolean isEmpty() {
            return conditions.isEmpty();
        }

        ConditionSummary condition(String conditionName) {
            String normalized = RepresentativeSelection.conditionLabel(conditionName);
            for (ConditionSummary condition : conditions) {
                if (condition.name.equals(normalized)) return condition;
            }
            return null;
        }

        SeriesPoint point(String seriesId) {
            return pointsBySeriesId.get(clean(seriesId));
        }
    }

    static final class ConditionSummary {
        final String name;
        final int index;
        final double mean;
        final List<SeriesPoint> points;

        ConditionSummary(String name,
                         int index,
                         double mean,
                         List<SeriesPoint> points) {
            this.name = RepresentativeSelection.conditionLabel(name);
            this.index = index;
            this.mean = mean;
            this.points = Collections.unmodifiableList(
                    new ArrayList<SeriesPoint>(points));
        }
    }

    static final class SeriesPoint {
        final String seriesId;
        final String label;
        final String conditionName;
        final int conditionIndex;
        final double value;

        SeriesPoint(String seriesId,
                    String label,
                    String conditionName,
                    int conditionIndex,
                    double value) {
            this.seriesId = clean(seriesId);
            this.label = clean(label);
            this.conditionName = RepresentativeSelection.conditionLabel(conditionName);
            this.conditionIndex = conditionIndex;
            this.value = value;
        }
    }

    private static final class ConditionBuilder {
        final String conditionName;
        final List<PointBuilder> points = new ArrayList<PointBuilder>();

        ConditionBuilder(String conditionName) {
            this.conditionName = RepresentativeSelection.conditionLabel(conditionName);
        }
    }

    private static final class PointBuilder {
        final String seriesId;
        final String label;
        final String conditionName;
        final double value;

        PointBuilder(String seriesId,
                     String label,
                     String conditionName,
                     double value) {
            this.seriesId = clean(seriesId);
            this.label = clean(label);
            this.conditionName = RepresentativeSelection.conditionLabel(conditionName);
            this.value = value;
        }
    }

    private static final class ChannelChartPanel extends JPanel {
        private final ChannelChartData data;
        private final LinkedHashSet<String> highlightedSeriesIds =
                new LinkedHashSet<String>();
        private String highlightedSeriesId = "";

        ChannelChartPanel(ChannelChartData data) {
            this.data = data;
            setBackground(CHART_BACKGROUND);
            setOpaque(true);
            setBorder(BorderFactory.createLineBorder(FlashTheme.BORDER));
            setMinimumSize(new Dimension(260, 144));
            setPreferredSize(new Dimension(CHART_WIDTH, CHART_HEIGHT));
            setMaximumSize(new Dimension(Integer.MAX_VALUE, CHART_HEIGHT));
        }

        void setHighlightedSeriesId(String highlightedSeriesId) {
            LinkedHashSet<String> ids = new LinkedHashSet<String>();
            String normalized = clean(highlightedSeriesId);
            if (!normalized.isEmpty()) {
                ids.add(normalized);
            }
            setHighlightedSeriesIds(ids, normalized);
        }

        void setHighlightedSeriesIds(Collection<String> seriesIds,
                                     String primarySeriesId) {
            highlightedSeriesIds.clear();
            if (seriesIds != null) {
                for (String seriesId : seriesIds) {
                    String normalized = clean(seriesId);
                    if (!normalized.isEmpty()) {
                        highlightedSeriesIds.add(normalized);
                    }
                }
            }
            highlightedSeriesId = clean(primarySeriesId);
            if (highlightedSeriesId.isEmpty() && !highlightedSeriesIds.isEmpty()) {
                highlightedSeriesId = highlightedSeriesIds.iterator().next();
            }
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                        RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setFont(FlashTheme.caption());

                int width = getWidth();
                int height = getHeight();
                int left = 50;
                int right = 12;
                int top = 30;
                int bottom = 36;
                int plotWidth = Math.max(1, width - left - right);
                int plotHeight = Math.max(1, height - top - bottom);

                g2.setColor(CHART_BACKGROUND);
                g2.fillRect(0, 0, width, height);
                drawTitle(g2, width);

                if (data.isEmpty()) {
                    drawCenteredText(g2, "No values", width, height);
                    return;
                }

                drawGrid(g2, left, top, plotWidth, plotHeight);
                drawMeanBars(g2, left, top, plotWidth, plotHeight);
                drawPoints(g2, left, top, plotWidth, plotHeight, false);
                drawHighlightedPoint(g2, left, top, plotWidth, plotHeight);
                drawAxes(g2, left, top, plotWidth, plotHeight);
                drawLabels(g2, left, top, plotWidth, plotHeight);
            } finally {
                g2.dispose();
            }
        }

        private void drawTitle(Graphics2D g2, int width) {
            FontMetrics metrics = g2.getFontMetrics();
            g2.setColor(FlashTheme.TEXT_HEADER);
            String title = fitString(g2, data.channelName, Math.max(40, width - 16));
            g2.drawString(title, 8, 18);

            String summary = selectedSummary();
            if (!summary.isEmpty()) {
                g2.setColor(HIGHLIGHT.darker());
                String fitted = fitString(g2, summary, Math.max(40, width - 90));
                g2.drawString(fitted, width - 8 - metrics.stringWidth(fitted), 18);
            }
        }

        private String selectedSummary() {
            SeriesPoint point = data.point(highlightedSeriesId);
            if (point == null) return "";
            ConditionSummary condition = data.condition(point.conditionName);
            if (condition == null) return point.label + " " + formatNumber(point.value);
            double delta = point.value - condition.mean;
            String sign = delta >= 0.0 ? "+" : "";
            return point.label + " " + formatNumber(point.value)
                    + " (" + sign + formatNumber(delta) + ")";
        }

        private void drawGrid(Graphics2D g2,
                              int left,
                              int top,
                              int plotWidth,
                              int plotHeight) {
            g2.setColor(GRID);
            for (int i = 0; i <= 2; i++) {
                int y = top + (int) Math.round((i / 2.0) * plotHeight);
                g2.drawLine(left, y, left + plotWidth, y);
            }
        }

        private void drawMeanBars(Graphics2D g2,
                                  int left,
                                  int top,
                                  int plotWidth,
                                  int plotHeight) {
            int count = Math.max(1, data.conditions.size());
            int slotWidth = Math.max(1, plotWidth / count);
            int barWidth = Math.max(8, Math.min(34,
                    (int) Math.round(slotWidth * 0.38)));
            int baseline = top + plotHeight;

            for (ConditionSummary condition : data.conditions) {
                int center = conditionCenter(condition.index, left, plotWidth);
                int x = center - barWidth / 2;
                int y = valueToY(condition.mean, top, plotHeight);
                int height = Math.max(1, baseline - y);

                g2.setColor(BAR_FILL);
                g2.fillRect(x, y, barWidth, height);
                g2.setColor(BAR_BORDER);
                g2.drawRect(x, y, barWidth, height);
                g2.drawLine(x - 2, y, x + barWidth + 2, y);
            }
        }

        private void drawPoints(Graphics2D g2,
                                int left,
                                int top,
                                int plotWidth,
                                int plotHeight,
                                boolean highlightedOnly) {
            for (ConditionSummary condition : data.conditions) {
                for (SeriesPoint point : condition.points) {
                    boolean highlighted =
                            highlightedSeriesIds.contains(point.seriesId);
                    if (highlightedOnly != highlighted) continue;
                    drawPoint(g2, point, left, top, plotWidth, plotHeight,
                            highlighted);
                }
            }
        }

        private void drawHighlightedPoint(Graphics2D g2,
                                          int left,
                                          int top,
                                          int plotWidth,
                                          int plotHeight) {
            for (ConditionSummary condition : data.conditions) {
                for (SeriesPoint point : condition.points) {
                    if (!highlightedSeriesIds.contains(point.seriesId)) {
                        continue;
                    }
                    int x = pointX(point, left, plotWidth);
                    int yPoint = valueToY(point.value, top, plotHeight);
                    int yMean = valueToY(condition.mean, top, plotHeight);
                    g2.setColor(new Color(HIGHLIGHT.getRed(),
                            HIGHLIGHT.getGreen(), HIGHLIGHT.getBlue(), 130));
                    g2.drawLine(x, yPoint, x, yMean);
                }
            }
            drawPoints(g2, left, top, plotWidth, plotHeight, true);
        }

        private void drawPoint(Graphics2D g2,
                               SeriesPoint point,
                               int left,
                               int top,
                               int plotWidth,
                               int plotHeight,
                               boolean highlighted) {
            int x = pointX(point, left, plotWidth);
            int y = valueToY(point.value, top, plotHeight);
            int radius = highlighted ? 6 : 3;

            if (highlighted) {
                g2.setColor(Color.WHITE);
                g2.fillOval(x - radius - 1, y - radius - 1,
                        (radius + 1) * 2, (radius + 1) * 2);
                g2.setColor(HIGHLIGHT);
                g2.fillOval(x - radius, y - radius, radius * 2, radius * 2);
                g2.setColor(HIGHLIGHT.darker());
                g2.drawOval(x - radius, y - radius, radius * 2, radius * 2);
            } else {
                g2.setComposite(AlphaComposite.getInstance(
                        AlphaComposite.SRC_OVER, 0.78f));
                g2.setColor(POINT);
                g2.fillOval(x - radius, y - radius, radius * 2, radius * 2);
                g2.setComposite(AlphaComposite.SrcOver);
            }
        }

        private void drawAxes(Graphics2D g2,
                              int left,
                              int top,
                              int plotWidth,
                              int plotHeight) {
            g2.setColor(AXIS);
            g2.drawLine(left, top, left, top + plotHeight);
            g2.drawLine(left, top + plotHeight,
                    left + plotWidth, top + plotHeight);
        }

        private void drawLabels(Graphics2D g2,
                                int left,
                                int top,
                                int plotWidth,
                                int plotHeight) {
            FontMetrics metrics = g2.getFontMetrics();
            g2.setColor(FlashTheme.TEXT_HELP);
            String max = formatNumber(data.maximum);
            String min = formatNumber(data.minimum);
            g2.drawString(max, left - 8 - metrics.stringWidth(max),
                    top + metrics.getAscent());
            g2.drawString(min, left - 8 - metrics.stringWidth(min),
                    top + plotHeight);

            int conditionCount = Math.max(1, data.conditions.size());
            int slotWidth = Math.max(1, plotWidth / conditionCount);
            int baseline = top + plotHeight + 16;
            for (ConditionSummary condition : data.conditions) {
                int center = conditionCenter(condition.index, left, plotWidth);
                String label = fitString(g2, condition.name,
                        Math.max(16, slotWidth - 6));
                g2.drawString(label, center - metrics.stringWidth(label) / 2,
                        baseline);
            }
        }

        private int valueToY(double value, int top, int plotHeight) {
            if (!(data.maximum > data.minimum)) return top + plotHeight;
            double normalized = (value - data.minimum) / (data.maximum - data.minimum);
            if (normalized < 0.0) normalized = 0.0;
            if (normalized > 1.0) normalized = 1.0;
            return top + (int) Math.round((1.0 - normalized) * plotHeight);
        }

        private int pointX(SeriesPoint point, int left, int plotWidth) {
            int center = conditionCenter(point.conditionIndex, left, plotWidth);
            int count = Math.max(1, data.conditions.size());
            int slotWidth = Math.max(1, plotWidth / count);
            int maxJitter = Math.max(4, Math.min(22,
                    (int) Math.round(slotWidth * 0.28)));
            return center + (int) Math.round(jitterFraction(point.seriesId) * maxJitter);
        }

        private int conditionCenter(int index, int left, int plotWidth) {
            int count = Math.max(1, data.conditions.size());
            return left + (int) Math.round(((index + 0.5) * plotWidth) / count);
        }

        private static double jitterFraction(String seriesId) {
            int hash = clean(seriesId).hashCode() & 0x7fffffff;
            return (hash % 1001) / 1000.0 - 0.5;
        }

        private void drawCenteredText(Graphics2D g2, String text, int width, int height) {
            FontMetrics metrics = g2.getFontMetrics();
            g2.setColor(FlashTheme.TEXT_MUTED);
            int x = Math.max(0, (width - metrics.stringWidth(text)) / 2);
            int y = Math.max(metrics.getAscent(), (height + metrics.getAscent()) / 2);
            g2.drawString(text, x, y);
        }

        private static String fitString(Graphics2D g2, String text, int maxWidth) {
            String trimmed = clean(text);
            if (maxWidth <= 0 || trimmed.isEmpty()) return "";
            FontMetrics metrics = g2.getFontMetrics();
            if (metrics.stringWidth(trimmed) <= maxWidth) return trimmed;
            String suffix = "...";
            int limit = trimmed.length();
            while (limit > 1) {
                String candidate = trimmed.substring(0, limit) + suffix;
                if (metrics.stringWidth(candidate) <= maxWidth) return candidate;
                limit--;
            }
            return metrics.stringWidth(suffix) <= maxWidth ? suffix : "";
        }
    }
}
