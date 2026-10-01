package flash.pipeline.intensity.spatial;

import flash.pipeline.analyses.wizard.IntensitySpatialConfig;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * Mean source-channel intensity in native-3D Euclidean shells around the partner mask.
 */
public final class DistanceShell3DAnalysis implements IntensitySpatialPairAnalysis {
    @Override
    public IntensitySpatialConfig.AnalysisKey key() {
        return IntensitySpatialConfig.AnalysisKey.DISTANCE_SHELL_3D;
    }

    @Override
    public EnumSet<IntensitySpatialOutputMode> outputModes() {
        return EnumSet.of(IntensitySpatialOutputMode.NATIVE_3D);
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
            columns.add(sourceChannel + "_DistShell3D"
                    + PairPlane2D.scaleToken(start) + "to"
                    + PairPlane2D.scaleToken(end) + "_" + partnerChannel);
        }
        columns.add(sourceChannel + "_DistShell3DSlope_" + partnerChannel);
        columns.add(sourceChannel + "_DistShell3DAUC_" + partnerChannel);
        return columns;
    }

    @Override
    public int estimatedCost() {
        return 10;
    }

    @Override
    public IntensitySpatialResult measure(IntensitySpatialPairContext context) {
        LinkedHashMap<String, Double> values = new LinkedHashMap<String, Double>();
        if (!context.hasPartnerMaskImage()) {
            return new IntensitySpatialResult(values);
        }

        PairVolume3D volume = PairVolume3D.raw(context);
        double shellWidth = shellWidth(context.config());
        int shellCount = shellCount(context.config());
        List<String> columns = columns(context.config(), context.sourceChannelName(),
                context.partnerChannelName(), context.hasSourceMaskImage(), true);
        if (volume.count == 0 || !volume.hasPartnerMask()) {
            context.warn("native-3D distance shells have no valid partner mask voxels; returning NaN.");
            return IntensitySpatialResult.nanFor(columns);
        }

        double[] distances = distanceToPartnerMask(volume);
        double[] sums = new double[shellCount];
        int[] counts = new int[shellCount];
        for (int i = 0; i < distances.length; i++) {
            if (!volume.valid[i]) continue;
            double distance = distances[i];
            if (!PairVolume3D.isFinite(distance)) continue;
            int shell = (int) Math.floor(distance / shellWidth);
            if (shell < 0 || shell >= shellCount) continue;
            sums[shell] += volume.source[i];
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
            values.put(context.sourceChannelName() + "_DistShell3D"
                    + PairPlane2D.scaleToken(start) + "to"
                    + PairPlane2D.scaleToken(end) + "_" + context.partnerChannelName(),
                    Double.valueOf(mean));
            if (PairVolume3D.isFinite(mean)) {
                xs[n] = start + shellWidth / 2.0;
                ys[n] = mean;
                n++;
                auc += mean * shellWidth;
            }
        }
        values.put(context.sourceChannelName() + "_DistShell3DSlope_" + context.partnerChannelName(),
                Double.valueOf(PairPlane2D.slope(xs, ys, n)));
        values.put(context.sourceChannelName() + "_DistShell3DAUC_" + context.partnerChannelName(),
                Double.valueOf(n == 0 ? Double.NaN : auc));
        return new IntensitySpatialResult(values);
    }

    private static double[] distanceToPartnerMask(PairVolume3D volume) {
        return ExactEuclideanDistance.toMask(volume.partnerMask, volume.valid,
                volume.width, volume.height, volume.depth,
                volume.pixelWidthUm, volume.pixelHeightUm, volume.pixelDepthUm);
    }

    private static double shellWidth(IntensitySpatialConfig config) {
        double value = config == null
                ? IntensitySpatialConfig.DEFAULT_SHELL_WIDTH_UM
                : config.getShellWidthUm();
        return value > 0.0 && PairVolume3D.isFinite(value)
                ? value
                : IntensitySpatialConfig.DEFAULT_SHELL_WIDTH_UM;
    }

    private static int shellCount(IntensitySpatialConfig config) {
        int value = config == null
                ? IntensitySpatialConfig.DEFAULT_SHELL_COUNT
                : config.getShellCount();
        return Math.max(1, value);
    }

}
