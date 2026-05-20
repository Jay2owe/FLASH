package flash.pipeline.runtime;

/**
 * Side-effect-free dependency probe result.
 */
public final class DependencyStatus {

    public enum State {
        PRESENT,
        MISSING,
        ERROR
    }

    private final State state;
    private final String detailMessage;

    private DependencyStatus(State state, String detailMessage) {
        this.state = state;
        this.detailMessage = detailMessage == null ? "" : detailMessage.trim();
    }

    public static DependencyStatus present(String detailMessage) {
        return new DependencyStatus(State.PRESENT, detailMessage);
    }

    public static DependencyStatus missing(String detailMessage) {
        return new DependencyStatus(State.MISSING, detailMessage);
    }

    public static DependencyStatus error(String detailMessage) {
        return new DependencyStatus(State.ERROR, detailMessage);
    }

    public State getState() {
        return state;
    }

    public String getDetailMessage() {
        return detailMessage;
    }

    public boolean isPresent() {
        return state == State.PRESENT;
    }

    public boolean isMissing() {
        return state == State.MISSING;
    }

    public boolean isError() {
        return state == State.ERROR;
    }

    public boolean needsAttention() {
        return state != State.PRESENT;
    }
}
