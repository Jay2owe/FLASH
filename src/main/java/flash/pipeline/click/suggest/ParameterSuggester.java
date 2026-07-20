package flash.pipeline.click.suggest;

public interface ParameterSuggester<S> {
    S suggest(SuggestionContext ctx);
}
