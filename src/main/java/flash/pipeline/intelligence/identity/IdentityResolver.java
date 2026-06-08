package flash.pipeline.intelligence.identity;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Runs ordered {@link IdentityStrategy strategies} over a batch and merges their
 * contributions per record, first-wins: strategies earlier in the list have
 * higher precedence, and a field they fill is never overridden by a later one.
 *
 * <p>Deterministic and idempotent for a fixed input batch — resolving the same
 * records twice yields equal results. This is the single entry point shared by
 * the Project Builder (first folder load) and Set Up Configuration, so identity
 * never differs between the two screens.
 */
public final class IdentityResolver {

    private final List<IdentityStrategy> strategies;

    public IdentityResolver(List<IdentityStrategy> strategies) {
        this.strategies = new ArrayList<IdentityStrategy>(
                strategies == null ? new ArrayList<IdentityStrategy>() : strategies);
    }

    /**
     * Resolve one identity per record. The returned map preserves batch order
     * and is keyed by the same {@link SourceRecord} instances supplied.
     */
    public Map<SourceRecord, IdentityCandidate> resolve(List<SourceRecord> batch) {
        Map<SourceRecord, IdentityCandidate> out =
                new LinkedHashMap<SourceRecord, IdentityCandidate>();
        if (batch == null) return out;
        for (SourceRecord r : batch) {
            out.put(r, new IdentityCandidate());
        }
        for (IdentityStrategy strategy : strategies) {
            Map<SourceRecord, PartialIdentity> contributions = strategy.detect(batch);
            if (contributions == null) continue;
            for (Map.Entry<SourceRecord, IdentityCandidate> e : out.entrySet()) {
                e.getValue().fillFrom(contributions.get(e.getKey()));
            }
        }
        return out;
    }
}
