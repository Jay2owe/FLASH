package flash.pipeline.intensity.spatial;

import flash.pipeline.analyses.wizard.IntensitySpatialConfig;

import java.util.EnumSet;
import java.util.List;

/**
 * Contract for 2D source/partner channel intensity-spatial analysis families.
 */
public interface IntensitySpatialPairAnalysis {
    IntensitySpatialConfig.AnalysisKey key();

    EnumSet<IntensitySpatialOutputMode> outputModes();

    List<String> columns(IntensitySpatialConfig config,
                         String sourceChannel,
                         String partnerChannel,
                         boolean sourceBinarized,
                         boolean partnerBinarized);

    int estimatedCost();

    IntensitySpatialResult measure(IntensitySpatialPairContext context) throws Exception;
}
