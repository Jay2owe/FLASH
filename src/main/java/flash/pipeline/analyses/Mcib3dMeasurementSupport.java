package flash.pipeline.analyses;

/**
 * Lazy linkage boundary for the optional mcib3d measurement API.
 *
 * <p>This class is first needed only when 3D morphometry actually runs. Do not
 * expose mcib3d types in its public/package-visible method signatures: doing so
 * would make callers resolve those optional classes during plugin startup.</p>
 */
final class Mcib3dMeasurementSupport {

    private Mcib3dMeasurementSupport() {
    }

    static double value(Object measure, String name) {
        try {
            mcib3d.geom2.measurements.MeasureAbstract typed =
                    (mcib3d.geom2.measurements.MeasureAbstract) measure;
            Double value = typed.getValueMeasurement(name);
            return value != null && Double.isFinite(value.doubleValue())
                    ? value.doubleValue()
                    : Double.NaN;
        } catch (Exception e) {
            return Double.NaN;
        }
    }
}
