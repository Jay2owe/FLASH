package flash.pipeline.ui.wizard;

import java.util.Map;

/**
 * Generic wizard preset contract.
 */
public interface Preset<T> {

    String getName();

    String getDescription();

    T getPayload();

    String getLibraryVersion();

    Map<String, Object> toJsonObject();

    default String name() {
        return getName();
    }

    default String description() {
        return getDescription();
    }

    default T payload() {
        return getPayload();
    }

    default String libraryVersion() {
        return getLibraryVersion();
    }
}
