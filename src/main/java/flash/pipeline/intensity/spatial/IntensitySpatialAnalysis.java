package flash.pipeline.intensity.spatial;

import flash.pipeline.analyses.wizard.IntensitySpatialConfig;
import flash.pipeline.runtime.DependencyId;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Contract for same-channel intensity-spatial analysis families.
 */
public interface IntensitySpatialAnalysis {
    enum AnalysisValidity {
        RAW_ONLY,
        EITHER_VALID,
        REQUIRES_PARTNER_BINARIZATION,
        NATIVE_3D
    }

    IntensitySpatialConfig.AnalysisKey key();

    AnalysisValidity validity();

    EnumSet<IntensitySpatialOutputMode> outputModes();

    Set<DependencyId> dependencyIds();

    List<String> columns(IntensitySpatialConfig config, boolean binarizedPartner);

    int estimatedCost();

    IntensitySpatialResult measure(IntensitySpatialContext context) throws Exception;
}
