package flash.pipeline.intelligence.identity;

import java.util.ArrayList;
import java.util.List;

/**
 * Assembles the standard ordered strategy stack for identity detection. This is
 * the single entry point the Project Builder (first folder load) and Set Up
 * Configuration both use, so detection is identical on both screens.
 *
 * <p>Precedence (highest first): the project's saved {@link NamingGrammar} (if
 * any) → {@link VocabularyStrategy} (ZT/CT + lexicons) → {@link FrequencyStrategy}
 * (cross-batch varying/repeated tokens) → {@link LegacyParserStrategy}
 * (positional convention fallback).
 */
public final class IdentityResolvers {

    private IdentityResolvers() {
    }

    public static IdentityResolver standard(NamingGrammar grammar) {
        List<IdentityStrategy> strategies = new ArrayList<IdentityStrategy>();
        if (grammar != null && !grammar.rules.isEmpty()) {
            strategies.add(new GrammarStrategy(grammar));
        }
        strategies.add(new VocabularyStrategy());
        strategies.add(new FrequencyStrategy());
        strategies.add(new LegacyParserStrategy());
        return new IdentityResolver(strategies);
    }

    public static IdentityResolver standard() {
        return standard(null);
    }
}
