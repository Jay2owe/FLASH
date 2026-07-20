package flash.pipeline.intelligence.identity;

import flash.pipeline.naming.ImageNameParser;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Inference preset: the Nth underscore-token of the filename core names a field.
 * Tokens are 1-based over the identity core (after a Bio-Formats {@code " - "}
 * separator and minus the extension), so {@code "APP_KO_m1_SCN"} token&nbsp;2 is
 * {@code "KO"}.
 */
public final class NthTokenFieldStrategy implements IdentityStrategy {

    private final int oneBasedToken;
    private final FieldRule.Type type;
    private final String axisLabel;

    public NthTokenFieldStrategy(int oneBasedToken, FieldRule.Type type, String axisLabel) {
        this.oneBasedToken = oneBasedToken;
        this.type = type;
        this.axisLabel = axisLabel == null ? "" : axisLabel;
    }

    @Override
    public Map<SourceRecord, PartialIdentity> detect(List<SourceRecord> batch) {
        Map<SourceRecord, PartialIdentity> out = new IdentityHashMap<SourceRecord, PartialIdentity>();
        if (batch == null || type == null || oneBasedToken < 1) return out;
        for (SourceRecord rec : batch) {
            String core = ImageNameParser.stripExtension(ImageNameParser.extractBioFormatsSeriesName(rec.seed));
            if (core == null) continue;
            String[] tokens = core.split("_");
            if (oneBasedToken > tokens.length) continue;
            String value = tokens[oneBasedToken - 1].trim();
            if (value.isEmpty()) continue;
            String provenance = "filename token " + oneBasedToken;
            PartialIdentity p = new PartialIdentity();
            switch (type) {
                case ANIMAL:     p.animal(value, Confidence.MEDIUM, provenance); break;
                case HEMISPHERE: p.hemisphere(value, Confidence.MEDIUM, provenance); break;
                case REGION:     p.region(value, Confidence.MEDIUM, provenance); break;
                case CONDITION:  p.condition(axisLabel.isEmpty() ? "Condition" : axisLabel,
                        value, Confidence.MEDIUM, provenance); break;
                default: continue;
            }
            out.put(rec, p);
        }
        return out;
    }
}
