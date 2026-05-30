package flash.pipeline.runrecord;

import java.util.Map;
import java.util.function.Consumer;

/**
 * Consumer variant used by load-from-run UI adapters when they can report
 * which run-record parameter keys were actually understood.
 */
public interface LoadedRunParameterApplier extends Consumer<Map<String, Object>> {

    LoadedRunParameters.Result applyLoadedParameters(Map<String, Object> parameters);

    @Override
    default void accept(Map<String, Object> parameters) {
        LoadedRunParameters.rememberLastResult(applyLoadedParameters(parameters));
    }
}
