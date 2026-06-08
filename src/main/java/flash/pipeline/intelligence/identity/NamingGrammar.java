package flash.pipeline.intelligence.identity;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A user-defined, saveable identity grammar: an ordered list of
 * {@link FieldRule} that teach FLASH a lab's naming once and reuse it across
 * studies. Persisted (Stage 07b) to
 * {@code FLASH/Config/.settings/naming_grammars/<name>.json}. Applied at top
 * precedence by {@link GrammarStrategy}.
 */
public final class NamingGrammar {

    public final String name;
    public final List<FieldRule> rules;

    public NamingGrammar(String name, List<FieldRule> rules) {
        this.name = name == null ? "" : name;
        this.rules = rules == null ? Collections.<FieldRule>emptyList()
                : Collections.unmodifiableList(new ArrayList<FieldRule>(rules));
    }
}
