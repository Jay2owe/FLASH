package flash.pipeline.runtime;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Planned multi-dependency repair summary for Auto-Fix All.
 */
public final class DependencyFixPlan {

    private final List<DependencySpec> dependenciesToFix;
    private final List<DependencySpec> alreadySatisfied;
    private final List<DependencySpec> blockedDependencies;
    private final long totalApproxDownloadBytes;
    private final boolean restartRequired;

    public DependencyFixPlan(List<DependencySpec> dependenciesToFix,
                             List<DependencySpec> alreadySatisfied,
                             List<DependencySpec> blockedDependencies,
                             long totalApproxDownloadBytes,
                             boolean restartRequired) {
        this.dependenciesToFix = unmodifiableCopy(dependenciesToFix);
        this.alreadySatisfied = unmodifiableCopy(alreadySatisfied);
        this.blockedDependencies = unmodifiableCopy(blockedDependencies);
        this.totalApproxDownloadBytes = totalApproxDownloadBytes;
        this.restartRequired = restartRequired;
    }

    public List<DependencySpec> getDependenciesToFix() {
        return dependenciesToFix;
    }

    public List<DependencySpec> getAlreadySatisfied() {
        return alreadySatisfied;
    }

    public List<DependencySpec> getBlockedDependencies() {
        return blockedDependencies;
    }

    public long getTotalApproxDownloadBytes() {
        return totalApproxDownloadBytes;
    }

    public boolean isRestartRequired() {
        return restartRequired;
    }

    private static List<DependencySpec> unmodifiableCopy(List<DependencySpec> specs) {
        if (specs == null || specs.isEmpty()) {
            return Collections.emptyList();
        }
        return Collections.unmodifiableList(new ArrayList<DependencySpec>(specs));
    }
}
