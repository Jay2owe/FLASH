package flash.pipeline.ui.variations;

/** Test-source bridge for coordinator isolation from strategy subpackages. */
public final class VariationCleanupCoordinatorTestAccess {

    private VariationCleanupCoordinatorTestAccess() {
    }

    public static void reset() {
        VariationCleanupCoordinator.resetForTest();
    }

    public static Throwable drain() {
        return VariationCleanupCoordinator.drainNowForTest();
    }

    public static int pendingCount() {
        return VariationCleanupCoordinator.pendingCountForTest();
    }
}
