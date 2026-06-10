package flash.pipeline.intensity.spatial;

import flash.pipeline.analyses.wizard.IntensitySpatialConfig;

import java.util.EnumSet;
import java.util.List;

/**
 * Lightweight 2D cross-channel intensity correlation without Coloc2/Costes/Manders.
 */
public final class FastCrossCorrelation2DAnalysis implements IntensitySpatialPairAnalysis {
    @Override
    public IntensitySpatialConfig.AnalysisKey key() {
        return IntensitySpatialConfig.AnalysisKey.CROSSCORR_FAST;
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
        return FastCrossCorrelation2DCore.columns(sourceChannel, partnerChannel);
    }

    @Override
    public int estimatedCost() {
        return 3;
    }

    @Override
    public IntensitySpatialResult measure(IntensitySpatialPairContext context) {
        List<String> columns = columns(context.config(), context.sourceChannelName(),
                context.partnerChannelName(), context.hasSourceBinarizedImage(),
                context.hasPartnerBinarizedImage());
        PairPlane2D plane;
        try {
            plane = PairPlane2D.raw(context);
        } catch (IllegalArgumentException ex) {
            context.warn("fast cross-correlation skipped before allocation: " + safeMessage(ex));
            return IntensitySpatialResult.nanFor(columns);
        }
        if (plane.count < 3) {
            context.warn("fast cross-correlation has insufficient valid ROI pixels; returning NaN.");
            return IntensitySpatialResult.nanFor(columns);
        }
        return new IntensitySpatialResult(FastCrossCorrelation2DCore.values(
                plane, context.sourceChannelName(), context.partnerChannelName()));
    }

    private static String safeMessage(Throwable throwable) {
        String message = throwable == null ? null : throwable.getMessage();
        return message == null || message.trim().isEmpty()
                ? throwable.getClass().getSimpleName()
                : message.trim();
    }
}
