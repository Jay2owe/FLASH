package flash.pipeline.runtime;

import java.util.EnumMap;
import java.util.List;

final class DependencyRuntimeTestSupport {

    private DependencyRuntimeTestSupport() {}

    static EnumMap<DependencyId, DependencyStatus> allPresent() {
        EnumMap<DependencyId, DependencyStatus> statuses =
                new EnumMap<DependencyId, DependencyStatus>(DependencyId.class);
        for (DependencyId id : DependencyId.values()) {
            statuses.put(id, DependencyStatus.present(id.name() + " present"));
        }
        return statuses;
    }

    static EnumMap<DependencyId, DependencyStatus> withStatuses(Object... idStatusPairs) {
        EnumMap<DependencyId, DependencyStatus> statuses = allPresent();
        for (int i = 0; i + 1 < idStatusPairs.length; i += 2) {
            statuses.put((DependencyId) idStatusPairs[i], (DependencyStatus) idStatusPairs[i + 1]);
        }
        return statuses;
    }

    static DependencyService serviceWith(final EnumMap<DependencyId, DependencyStatus> statuses) {
        return new DependencyService(new DependencyService.StatusSnapshotProvider() {
            @Override
            public EnumMap<DependencyId, DependencyStatus> snapshot(List<DependencySpec> specs) {
                return new EnumMap<DependencyId, DependencyStatus>(statuses);
            }
        });
    }
}
