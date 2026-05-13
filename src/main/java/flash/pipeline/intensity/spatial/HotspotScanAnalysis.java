package flash.pipeline.intensity.spatial;

import flash.pipeline.analyses.wizard.IntensitySpatialConfig;
import flash.pipeline.runtime.DependencyId;
import ij.ImagePlus;
import ij.gui.Roi;
import ij.process.ImageProcessor;

import java.awt.Rectangle;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * Local Moran/LISA hotspot scan on a bounded downsampled grid.
 */
public final class HotspotScanAnalysis implements IntensitySpatialAnalysis {
    public static final String COLUMN_FRACTION = "Intensity_HotspotFraction";
    public static final String COLUMN_MORANS_I = "Intensity_HotspotMoransI";
    public static final String COLUMN_P = "Intensity_HotspotP";

    private static final int TARGET_MAX_GRID_SIDE = 64;
    private static final double LOCAL_ALPHA = 0.05;

    @Override
    public IntensitySpatialConfig.AnalysisKey key() {
        return IntensitySpatialConfig.AnalysisKey.HOTSPOTSCAN;
    }

    @Override
    public AnalysisValidity validity() {
        return AnalysisValidity.EITHER_VALID;
    }

    @Override
    public EnumSet<IntensitySpatialOutputMode> outputModes() {
        return EnumSet.of(IntensitySpatialOutputMode.BASE, IntensitySpatialOutputMode.MIP);
    }

    @Override
    public Set<DependencyId> dependencyIds() {
        return Collections.emptySet();
    }

    @Override
    public List<String> columns(IntensitySpatialConfig config, boolean binarizedPartner) {
        java.util.ArrayList<String> columns = new java.util.ArrayList<String>();
        addColumns(columns, "");
        if (binarizedPartner) {
            addColumns(columns, "_binarized");
        }
        return columns;
    }

    @Override
    public int estimatedCost() {
        return 5;
    }

    @Override
    public IntensitySpatialResult measure(IntensitySpatialContext context) {
        LinkedHashMap<String, Double> values = new LinkedHashMap<String, Double>();
        measureInto(values, context, context.image(), "");
        if (context.hasBinarizedImage()) {
            measureInto(values, context, context.binarizedImage(), "_binarized");
        }
        return new IntensitySpatialResult(values);
    }

    private static void measureInto(LinkedHashMap<String, Double> values,
                                    IntensitySpatialContext context,
                                    ImagePlus image,
                                    String suffix) {
        Grid grid = Grid.from(image, context.sliceIndex(), context.roi());
        if (grid.validCount < 4) {
            context.warn("hotspot scan has insufficient valid ROI grid cells; returning NaN.");
            putNan(values, suffix);
            return;
        }

        Stats stats = Stats.from(grid);
        if (stats.varianceSum <= 0.0 || Double.isNaN(stats.varianceSum)) {
            values.put(COLUMN_FRACTION + suffix, Double.valueOf(0.0));
            values.put(COLUMN_MORANS_I + suffix, Double.valueOf(0.0));
            values.put(COLUMN_P + suffix, Double.valueOf(1.0));
            return;
        }

        double observedGlobal = globalMoransI(grid, stats.values, stats.mean, stats.varianceSum);
        LocalStats localStats = localHotspotStats(grid, stats, context.config().getPermutations(),
                context.config().getSeed());
        double p = globalPermutationP(grid, stats, observedGlobal,
                context.config().getPermutations(), context.config().getSeed());

        values.put(COLUMN_FRACTION + suffix, Double.valueOf(localStats.hotspotFraction));
        values.put(COLUMN_MORANS_I + suffix, Double.valueOf(observedGlobal));
        values.put(COLUMN_P + suffix, Double.valueOf(p));
    }

    private static void addColumns(List<String> columns, String suffix) {
        columns.add(COLUMN_FRACTION + suffix);
        columns.add(COLUMN_MORANS_I + suffix);
        columns.add(COLUMN_P + suffix);
    }

    private static void putNan(LinkedHashMap<String, Double> values, String suffix) {
        values.put(COLUMN_FRACTION + suffix, Double.valueOf(Double.NaN));
        values.put(COLUMN_MORANS_I + suffix, Double.valueOf(Double.NaN));
        values.put(COLUMN_P + suffix, Double.valueOf(Double.NaN));
    }

    private static double globalPermutationP(Grid grid,
                                             Stats stats,
                                             double observed,
                                             int permutations,
                                             long seed) {
        if (permutations <= 0 || Double.isNaN(observed)) {
            return Double.NaN;
        }
        double[] shuffled = stats.values.clone();
        Random random = new Random(seed);
        int greaterOrEqual = 0;
        for (int i = 0; i < permutations; i++) {
            shuffle(shuffled, random);
            double permuted = globalMoransI(grid, shuffled, stats.mean, stats.varianceSum);
            if (!Double.isNaN(permuted) && permuted >= observed) {
                greaterOrEqual++;
            }
        }
        return (greaterOrEqual + 1.0) / (permutations + 1.0);
    }

    private static LocalStats localHotspotStats(Grid grid,
                                                Stats stats,
                                                int permutations,
                                                long seed) {
        double[] z = zScores(stats.values, stats.mean, stats.varianceSum);
        double[] observed = localScores(grid, z);
        int[] exceedances = new int[observed.length];
        if (permutations > 0) {
            double[] shuffled = z.clone();
            Random random = new Random(seed ^ 0x5deece66dL);
            for (int p = 0; p < permutations; p++) {
                shuffle(shuffled, random);
                double[] permuted = localScores(grid, shuffled);
                for (int i = 0; i < observed.length; i++) {
                    if (!Double.isNaN(permuted[i]) && permuted[i] >= observed[i]) {
                        exceedances[i]++;
                    }
                }
            }
        }

        int hotspotPixels = 0;
        int totalPixels = 0;
        for (int i = 0; i < grid.cells.length; i++) {
            Cell cell = grid.cells[i];
            if (!cell.valid) continue;
            totalPixels += cell.pixelCount;
            double localP = permutations <= 0
                    ? Double.NaN
                    : (exceedances[cell.valueIndex] + 1.0) / (permutations + 1.0);
            if (z[cell.valueIndex] > 0.0
                    && observed[cell.valueIndex] > 0.0
                    && !Double.isNaN(localP)
                    && localP <= LOCAL_ALPHA) {
                hotspotPixels += cell.pixelCount;
            }
        }
        double fraction = totalPixels == 0 ? Double.NaN : hotspotPixels / (double) totalPixels;
        return new LocalStats(fraction);
    }

    private static double[] zScores(double[] values, double mean, double varianceSum) {
        double sd = Math.sqrt(varianceSum / values.length);
        double[] z = new double[values.length];
        for (int i = 0; i < values.length; i++) {
            z[i] = (values[i] - mean) / sd;
        }
        return z;
    }

    private static double[] localScores(Grid grid, double[] values) {
        double[] scores = new double[values.length];
        for (int i = 0; i < scores.length; i++) {
            scores[i] = Double.NaN;
        }
        for (int y = 0; y < grid.height; y++) {
            for (int x = 0; x < grid.width; x++) {
                Cell cell = grid.cell(x, y);
                if (!cell.valid) continue;
                double neighborSum = 0.0;
                int neighborCount = 0;
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dx = -1; dx <= 1; dx++) {
                        if (dx == 0 && dy == 0) continue;
                        Cell neighbor = grid.cell(x + dx, y + dy);
                        if (neighbor == null || !neighbor.valid) continue;
                        neighborSum += values[neighbor.valueIndex];
                        neighborCount++;
                    }
                }
                if (neighborCount > 0) {
                    scores[cell.valueIndex] = values[cell.valueIndex] * neighborSum / neighborCount;
                }
            }
        }
        return scores;
    }

    private static double globalMoransI(Grid grid, double[] values, double mean, double varianceSum) {
        if (varianceSum <= 0.0) return 0.0;
        double weighted = 0.0;
        int weightCount = 0;
        for (int y = 0; y < grid.height; y++) {
            for (int x = 0; x < grid.width; x++) {
                Cell cell = grid.cell(x, y);
                if (!cell.valid) continue;
                double zi = values[cell.valueIndex] - mean;
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dx = -1; dx <= 1; dx++) {
                        if (dx == 0 && dy == 0) continue;
                        Cell neighbor = grid.cell(x + dx, y + dy);
                        if (neighbor == null || !neighbor.valid) continue;
                        weighted += zi * (values[neighbor.valueIndex] - mean);
                        weightCount++;
                    }
                }
            }
        }
        if (weightCount == 0) return Double.NaN;
        return grid.validCount * weighted / (weightCount * varianceSum);
    }

    private static void shuffle(double[] values, Random random) {
        for (int i = values.length - 1; i > 0; i--) {
            int j = random.nextInt(i + 1);
            double tmp = values[i];
            values[i] = values[j];
            values[j] = tmp;
        }
    }

    private static double pixelSize(ImagePlus image, boolean xAxis) {
        return CalibrationUtil.pixelSizeUm(image,
                xAxis ? CalibrationUtil.Axis.X : CalibrationUtil.Axis.Y);
    }

    private static Rectangle clippedBounds(ImagePlus image, Roi roi) {
        int width = image == null ? 0 : image.getWidth();
        int height = image == null ? 0 : image.getHeight();
        Rectangle raw = roi == null ? new Rectangle(0, 0, width, height) : roi.getBounds();
        int x0 = Math.max(0, raw.x);
        int y0 = Math.max(0, raw.y);
        int x1 = Math.min(width, raw.x + raw.width);
        int y1 = Math.min(height, raw.y + raw.height);
        return new Rectangle(x0, y0, Math.max(0, x1 - x0), Math.max(0, y1 - y0));
    }

    private static final class LocalStats {
        final double hotspotFraction;

        LocalStats(double hotspotFraction) {
            this.hotspotFraction = hotspotFraction;
        }
    }

    private static final class Stats {
        final double[] values;
        final double mean;
        final double varianceSum;

        private Stats(double[] values, double mean, double varianceSum) {
            this.values = values;
            this.mean = mean;
            this.varianceSum = varianceSum;
        }

        static Stats from(Grid grid) {
            double sum = 0.0;
            double[] values = new double[grid.validCount];
            for (Cell cell : grid.cells) {
                if (!cell.valid) continue;
                values[cell.valueIndex] = cell.mean;
                sum += cell.mean;
            }
            double mean = sum / values.length;
            double varianceSum = 0.0;
            for (double value : values) {
                double delta = value - mean;
                varianceSum += delta * delta;
            }
            return new Stats(values, mean, varianceSum);
        }
    }

    private static final class Grid {
        final int width;
        final int height;
        final Cell[] cells;
        final int validCount;

        private Grid(int width, int height, Cell[] cells, int validCount) {
            this.width = width;
            this.height = height;
            this.cells = cells;
            this.validCount = validCount;
        }

        Cell cell(int x, int y) {
            if (x < 0 || y < 0 || x >= width || y >= height) return null;
            return cells[y * width + x];
        }

        static Grid from(ImagePlus image, int slice, Roi roi) {
            if (image == null || image.getStackSize() < slice) {
                return new Grid(0, 0, new Cell[0], 0);
            }
            ImageProcessor ip = image.getStack().getProcessor(slice);
            Rectangle bounds = clippedBounds(image, roi);
            if (bounds.width <= 0 || bounds.height <= 0) {
                return new Grid(0, 0, new Cell[0], 0);
            }

            int blockWidth = blockPixels(bounds.width, pixelSize(image, true));
            int blockHeight = blockPixels(bounds.height, pixelSize(image, false));
            int gridWidth = Math.max(1, (bounds.width + blockWidth - 1) / blockWidth);
            int gridHeight = Math.max(1, (bounds.height + blockHeight - 1) / blockHeight);
            double[] sums = new double[gridWidth * gridHeight];
            int[] counts = new int[gridWidth * gridHeight];

            for (int y = bounds.y; y < bounds.y + bounds.height; y++) {
                for (int x = bounds.x; x < bounds.x + bounds.width; x++) {
                    if (roi != null && !roi.contains(x, y)) continue;
                    double value = ip.getf(x, y);
                    if (Double.isNaN(value) || Double.isInfinite(value)) continue;
                    int gx = Math.min(gridWidth - 1, (x - bounds.x) / blockWidth);
                    int gy = Math.min(gridHeight - 1, (y - bounds.y) / blockHeight);
                    int index = gy * gridWidth + gx;
                    sums[index] += value;
                    counts[index]++;
                }
            }

            Cell[] cells = new Cell[gridWidth * gridHeight];
            int valid = 0;
            for (int i = 0; i < cells.length; i++) {
                if (counts[i] > 0) {
                    cells[i] = new Cell(true, sums[i] / counts[i], counts[i], valid++);
                } else {
                    cells[i] = new Cell(false, Double.NaN, 0, -1);
                }
            }
            return new Grid(gridWidth, gridHeight, cells, valid);
        }

        private static int blockPixels(int spanPixels, double pixelSizeUm) {
            int bySide = Math.max(1, (spanPixels + TARGET_MAX_GRID_SIDE - 1) / TARGET_MAX_GRID_SIDE);
            double physicalFloor = pixelSizeUm > 0.0 ? Math.round(2.0 / pixelSizeUm) : 1.0;
            return Math.max(bySide, Math.max(1, (int) physicalFloor));
        }
    }

    private static final class Cell {
        final boolean valid;
        final double mean;
        final int pixelCount;
        final int valueIndex;

        Cell(boolean valid, double mean, int pixelCount, int valueIndex) {
            this.valid = valid;
            this.mean = mean;
            this.pixelCount = pixelCount;
            this.valueIndex = valueIndex;
        }
    }
}
