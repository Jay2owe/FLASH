package flash.pipeline.intensity.spatial;

import flash.pipeline.analyses.wizard.IntensitySpatialConfig;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.PriorityQueue;

/**
 * Mean source-channel intensity in Euclidean shells around the partner user-threshold mask.
 */
public final class DistanceShell2DAnalysis implements IntensitySpatialPairAnalysis {
    @Override
    public IntensitySpatialConfig.AnalysisKey key() {
        return IntensitySpatialConfig.AnalysisKey.DISTANCE_SHELL;
    }

    @Override
    public EnumSet<IntensitySpatialOutputMode> outputModes() {
        return EnumSet.of(IntensitySpatialOutputMode.BASE, IntensitySpatialOutputMode.MIP);
    }

    @Override
    public List<String> columns(IntensitySpatialConfig config,
                                String sourceChannel,
                                String partnerChannel,
                                boolean sourceBinarized,
                                boolean partnerBinarized) {
        ArrayList<String> columns = new ArrayList<String>();
        if (!partnerBinarized) return columns;
        double shellWidth = shellWidth(config);
        int shellCount = shellCount(config);
        for (int i = 0; i < shellCount; i++) {
            double start = i * shellWidth;
            double end = (i + 1) * shellWidth;
            columns.add(sourceChannel + "_DistShell"
                    + PairPlane2D.scaleToken(start) + "to"
                    + PairPlane2D.scaleToken(end) + "_" + partnerChannel);
        }
        columns.add(sourceChannel + "_DistShellSlope_" + partnerChannel);
        columns.add(sourceChannel + "_DistShellAUC_" + partnerChannel);
        return columns;
    }

    @Override
    public int estimatedCost() {
        return 7;
    }

    @Override
    public IntensitySpatialResult measure(IntensitySpatialPairContext context) {
        LinkedHashMap<String, Double> values = new LinkedHashMap<String, Double>();
        if (!context.hasPartnerMaskImage()) {
            return new IntensitySpatialResult(values);
        }

        PairPlane2D plane = PairPlane2D.raw(context);
        double shellWidth = shellWidth(context.config());
        int shellCount = shellCount(context.config());
        List<String> columns = columns(context.config(), context.sourceChannelName(),
                context.partnerChannelName(), context.hasSourceMaskImage(), true);
        if (plane.count == 0 || !plane.hasPartnerMask()) {
            context.warn("distance shells have no valid partner mask pixels; returning NaN.");
            return IntensitySpatialResult.nanFor(columns);
        }

        double[] distances = distanceToPartnerMask(plane);
        double[] sums = new double[shellCount];
        int[] counts = new int[shellCount];
        for (int i = 0; i < distances.length; i++) {
            if (!plane.valid[i]) continue;
            double distance = distances[i];
            if (!PairPlane2D.isFinite(distance)) continue;
            int shell = (int) Math.floor(distance / shellWidth);
            if (shell < 0 || shell >= shellCount) continue;
            sums[shell] += plane.source[i];
            counts[shell]++;
        }

        double[] xs = new double[shellCount];
        double[] ys = new double[shellCount];
        int n = 0;
        double auc = 0.0;
        for (int i = 0; i < shellCount; i++) {
            double mean = counts[i] == 0 ? Double.NaN : sums[i] / counts[i];
            double start = i * shellWidth;
            double end = (i + 1) * shellWidth;
            values.put(context.sourceChannelName() + "_DistShell"
                    + PairPlane2D.scaleToken(start) + "to"
                    + PairPlane2D.scaleToken(end) + "_" + context.partnerChannelName(),
                    Double.valueOf(mean));
            if (PairPlane2D.isFinite(mean)) {
                xs[n] = start + shellWidth / 2.0;
                ys[n] = mean;
                n++;
                auc += mean * shellWidth;
            }
        }
        values.put(context.sourceChannelName() + "_DistShellSlope_" + context.partnerChannelName(),
                Double.valueOf(PairPlane2D.slope(xs, ys, n)));
        values.put(context.sourceChannelName() + "_DistShellAUC_" + context.partnerChannelName(),
                Double.valueOf(n == 0 ? Double.NaN : auc));
        return new IntensitySpatialResult(values);
    }

    private static double[] distanceToPartnerMask(PairPlane2D plane) {
        final double[] distances = new double[plane.width * plane.height];
        java.util.Arrays.fill(distances, Double.POSITIVE_INFINITY);
        PriorityQueue<Node> queue = new PriorityQueue<Node>(Math.max(1, plane.count),
                new Comparator<Node>() {
                    @Override
                    public int compare(Node a, Node b) {
                        return Double.compare(a.distance, b.distance);
                    }
                });

        for (int i = 0; i < plane.partnerMask.length; i++) {
            if (plane.valid[i] && plane.partnerMask[i]) {
                distances[i] = 0.0;
                queue.add(new Node(i, 0.0));
            }
        }

        int[] dx = {-1, 0, 1, -1, 1, -1, 0, 1};
        int[] dy = {-1, -1, -1, 0, 0, 1, 1, 1};
        while (!queue.isEmpty()) {
            Node node = queue.poll();
            if (node.distance > distances[node.index]) continue;
            int x = node.index % plane.width;
            int y = node.index / plane.width;
            for (int i = 0; i < dx.length; i++) {
                int xx = x + dx[i];
                int yy = y + dy[i];
                if (xx < 0 || xx >= plane.width || yy < 0 || yy >= plane.height) continue;
                int next = yy * plane.width + xx;
                if (!plane.valid[next]) continue;
                double stepX = dx[i] * plane.pixelWidthUm;
                double stepY = dy[i] * plane.pixelHeightUm;
                double candidate = node.distance + Math.sqrt(stepX * stepX + stepY * stepY);
                if (candidate < distances[next]) {
                    distances[next] = candidate;
                    queue.add(new Node(next, candidate));
                }
            }
        }
        return distances;
    }

    private static double shellWidth(IntensitySpatialConfig config) {
        double value = config == null
                ? IntensitySpatialConfig.DEFAULT_SHELL_WIDTH_UM
                : config.getShellWidthUm();
        return value > 0.0 && PairPlane2D.isFinite(value)
                ? value
                : IntensitySpatialConfig.DEFAULT_SHELL_WIDTH_UM;
    }

    private static int shellCount(IntensitySpatialConfig config) {
        int value = config == null
                ? IntensitySpatialConfig.DEFAULT_SHELL_COUNT
                : config.getShellCount();
        return Math.max(1, value);
    }

    private static final class Node {
        final int index;
        final double distance;

        private Node(int index, double distance) {
            this.index = index;
            this.distance = distance;
        }
    }
}
