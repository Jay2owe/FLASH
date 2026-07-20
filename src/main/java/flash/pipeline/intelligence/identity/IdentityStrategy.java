package flash.pipeline.intelligence.identity;

import java.util.List;
import java.util.Map;

/**
 * A set-aware detection strategy. Given the whole batch of records it returns
 * the partial identity it can contribute for each. Records it has no opinion on
 * may be omitted from the returned map (or mapped to an empty
 * {@link PartialIdentity}). Seeing the whole batch is what enables cross-batch
 * frequency analysis and consistent grammar application.
 */
public interface IdentityStrategy {
    Map<SourceRecord, PartialIdentity> detect(List<SourceRecord> batch);
}
