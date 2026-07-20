package flash.pipeline.runtime;

/**
 * Result of one dependency fix or verify flow.
 */
public final class DependencyFixResult {

    private final DependencyId dependencyId;
    private final boolean attempted;
    private final boolean success;
    private final boolean restartRequired;
    private final String message;

    public DependencyFixResult(DependencyId dependencyId,
                               boolean attempted,
                               boolean success,
                               boolean restartRequired,
                               String message) {
        this.dependencyId = dependencyId;
        this.attempted = attempted;
        this.success = success;
        this.restartRequired = restartRequired;
        this.message = message == null ? "" : message;
    }

    public DependencyId getDependencyId() {
        return dependencyId;
    }

    public boolean wasAttempted() {
        return attempted;
    }

    public boolean isSuccess() {
        return success;
    }

    public boolean isRestartRequired() {
        return restartRequired;
    }

    public String getMessage() {
        return message;
    }
}
