package flash.pipeline.intelligence.identity;

import flash.pipeline.naming.ImageNameParser;
import flash.pipeline.naming.NameParts;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Baseline (lowest-precedence) strategy wrapping the existing positional
 * {@link ImageNameParser}. A strict-convention match
 * ({@code Experiment-Animal_Hemisphere_Region}) is MEDIUM confidence; the
 * whole-title fallback animal is LOW. Single-condition parses populate the
 * default {@code Condition} axis. Higher-precedence strategies (grammar,
 * vocabulary, roster) override whatever they detect.
 */
public final class LegacyParserStrategy implements IdentityStrategy {

    private static final String PROV = "filename (legacy convention parser)";
    private static final String DEFAULT_CONDITION_AXIS = "Condition";

    @Override
    public Map<SourceRecord, PartialIdentity> detect(List<SourceRecord> batch) {
        Map<SourceRecord, PartialIdentity> out = new LinkedHashMap<SourceRecord, PartialIdentity>();
        if (batch == null) return out;
        for (SourceRecord r : batch) {
            NameParts np = ImageNameParser.parse(r.seed);
            Confidence conf = np.strictMatch ? Confidence.MEDIUM : Confidence.LOW;
            PartialIdentity p = new PartialIdentity()
                    .animal(np.animal, conf, PROV)
                    .region(np.region, conf, PROV)
                    .condition(DEFAULT_CONDITION_AXIS, np.condition, conf, PROV);
            if (np.hasKnownHemisphere()) {
                p.hemisphere(np.hemisphere, Confidence.MEDIUM, PROV);
            }
            out.put(r, p);
        }
        return out;
    }
}
