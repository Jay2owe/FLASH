package flash.pipeline.project;

import java.util.Objects;

/**
 * One entry in the recent-projects list. Immutable.
 */
public final class RecentProject {
    public static final int MAX_ENTRIES = 10;

    public final String name;
    public final String path;
    public final long lastOpenedAt;

    public RecentProject(String name, String path, long lastOpenedAt) {
        this.name = name == null ? "" : name;
        this.path = path == null ? "" : path;
        this.lastOpenedAt = lastOpenedAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof RecentProject)) return false;
        RecentProject other = (RecentProject) o;
        return lastOpenedAt == other.lastOpenedAt
                && Objects.equals(name, other.name)
                && Objects.equals(path, other.path);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, path, Long.valueOf(lastOpenedAt));
    }
}
