package flash.pipeline.intelligence.identity;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Top-precedence strategy that applies the project's active {@link NamingGrammar}
 * to each record. When the grammar is defined it is authoritative; fields it
 * does not match fall through to the lower-precedence vocabulary / frequency /
 * legacy strategies.
 */
public final class GrammarStrategy implements IdentityStrategy {

    private final NamingGrammar grammar;
    private final GrammarInterpreter interpreter = new GrammarInterpreter();

    public GrammarStrategy(NamingGrammar grammar) {
        this.grammar = grammar;
    }

    @Override
    public Map<SourceRecord, PartialIdentity> detect(List<SourceRecord> batch) {
        Map<SourceRecord, PartialIdentity> out = new LinkedHashMap<SourceRecord, PartialIdentity>();
        if (batch == null) return out;
        for (SourceRecord r : batch) {
            out.put(r, interpreter.apply(grammar, r.seed));
        }
        return out;
    }
}
