package flash.pipeline.intelligence.identity;

import flash.pipeline.naming.ConditionNameParser;
import flash.pipeline.naming.ImageNameParser;
import flash.pipeline.naming.NameParts;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Inference preset: derive the condition from the animal-name prefix using the
 * existing {@link ConditionNameParser} ({@code hAPP3Week2 -> hAPPWeek2}). The
 * animal is taken from the legacy filename parse of each record's seed.
 */
public final class AnimalPrefixConditionStrategy implements IdentityStrategy {

    public static final String AXIS_CONDITION = "Condition";

    @Override
    public Map<SourceRecord, PartialIdentity> detect(List<SourceRecord> batch) {
        Map<SourceRecord, PartialIdentity> out = new IdentityHashMap<SourceRecord, PartialIdentity>();
        if (batch == null) return out;
        for (SourceRecord rec : batch) {
            NameParts parts = ImageNameParser.parse(rec.seed);
            String animal = parts == null ? "" : parts.animal;
            if (animal == null || animal.trim().isEmpty()) continue;
            String condition = ConditionNameParser.detectCondition(animal.trim());
            if (condition == null || condition.trim().isEmpty()) continue;
            out.put(rec, new PartialIdentity().condition(
                    AXIS_CONDITION, condition.trim(), Confidence.MEDIUM, "animal-name prefix"));
        }
        return out;
    }
}
