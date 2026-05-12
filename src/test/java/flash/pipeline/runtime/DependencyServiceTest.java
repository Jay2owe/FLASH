package flash.pipeline.runtime;

import org.junit.Test;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class DependencyServiceTest {

    @Test
    public void statusSnapshotsDoNotInvokeFixers() {
        CountingStatusProvider provider = new CountingStatusProvider(DependencyRuntimeTestSupport.allPresent());
        Map<DependencyId, DependencyFixer> fixers = fakeFixers();
        DependencyService service = new DependencyService(provider, fixers);

        service.refreshStatuses();
        service.refreshStatuses();

        assertEquals(2, provider.calls);
        assertEquals(DependencyId.values().length, provider.lastSpecCount);
        for (DependencyFixer fixer : fixers.values()) {
            assertEquals(0, ((CountingFixer) fixer).applyCalls);
        }
    }

    @Test
    public void getFixableDependenciesReturnsEveryRegistryFixableAndOnlyFixableSpecs() {
        DependencyService service = new DependencyService();

        Set<DependencyId> expected = EnumSet.noneOf(DependencyId.class);
        for (DependencySpec spec : DependencyRegistry.all()) {
            if (spec.isFixableInApp()) {
                expected.add(spec.getId());
            }
        }

        List<DependencySpec> fixable = service.getFixableDependencies();
        assertEquals(expected, ids(fixable));
        for (DependencySpec spec : fixable) {
            assertTrue(spec.getId() + " should be marked fixable", spec.isFixableInApp());
        }
    }

    @Test
    public void getMissingFixableDependenciesIncludesMissingFijiPluginRows() {
        DependencyService service = DependencyRuntimeTestSupport.serviceWith(
                DependencyRuntimeTestSupport.withStatuses(
                        DependencyId.APACHE_POI_RUNTIME,
                        DependencyStatus.missing("Excel jars missing"),
                        DependencyId.TENSORFLOW_NATIVE_RUNTIME,
                        DependencyStatus.error("TensorFlow jars unreadable"),
                        DependencyId.BIO_FORMATS_RUNTIME,
                        DependencyStatus.missing("Bio-Formats missing")));

        Set<DependencyId> missing = ids(service.getMissingFixableDependencies());

        assertTrue(missing.contains(DependencyId.APACHE_POI_RUNTIME));
        assertTrue(missing.contains(DependencyId.TENSORFLOW_NATIVE_RUNTIME));
        assertTrue(missing.contains(DependencyId.BIO_FORMATS_RUNTIME));
        assertFalse(missing.contains(DependencyId.STARDIST_RUNTIME));
    }

    @Test
    public void planFixAllAggregatesMissingFixableDependencies() {
        DependencyService service = DependencyRuntimeTestSupport.serviceWith(
                DependencyRuntimeTestSupport.withStatuses(
                        DependencyId.APACHE_POI_RUNTIME,
                        DependencyStatus.missing("Excel jars missing"),
                        DependencyId.CELLPOSE_RUNTIME,
                        DependencyStatus.missing("Cellpose missing"),
                        DependencyId.BIO_FORMATS_RUNTIME,
                        DependencyStatus.missing("Bio-Formats missing")));

        DependencyFixPlan plan = service.planFixAll();

        assertEquals(linkedIds(DependencyId.BIO_FORMATS_RUNTIME,
                        DependencyId.APACHE_POI_RUNTIME,
                        DependencyId.CELLPOSE_RUNTIME),
                ids(plan.getDependenciesToFix()));
        assertEquals(DependencyRegistry.get(DependencyId.BIO_FORMATS_RUNTIME).getApproxDownloadSizeBytes()
                        + DependencyRegistry.get(DependencyId.APACHE_POI_RUNTIME).getApproxDownloadSizeBytes()
                        + DependencyRegistry.get(DependencyId.CELLPOSE_RUNTIME).getApproxDownloadSizeBytes(),
                plan.getTotalApproxDownloadBytes());
        assertTrue(plan.isRestartRequired());
        assertFalse(ids(plan.getBlockedDependencies()).contains(DependencyId.BIO_FORMATS_RUNTIME));
        assertFalse(ids(plan.getAlreadySatisfied()).contains(DependencyId.APACHE_POI_RUNTIME));
    }

    @Test
    public void planFixAllIsEmptyWhenEverythingHealthy() {
        DependencyService service = DependencyRuntimeTestSupport.serviceWith(
                DependencyRuntimeTestSupport.allPresent());

        DependencyFixPlan plan = service.planFixAll();

        assertTrue(plan.getDependenciesToFix().isEmpty());
        assertTrue(plan.getBlockedDependencies().isEmpty());
        assertEquals(0L, plan.getTotalApproxDownloadBytes());
        assertFalse(plan.isRestartRequired());
        assertEquals(ids(new DependencyService().getFixableDependencies()),
                ids(plan.getAlreadySatisfied()));
    }

    @Test
    public void planFixAllOrdersJarFixersBeforeCellpose() {
        EnumMap<DependencyId, DependencyStatus> statuses = DependencyRuntimeTestSupport.allPresent();
        for (DependencySpec spec : DependencyRegistry.all()) {
            if (spec.isFixableInApp()) {
                statuses.put(spec.getId(), DependencyStatus.missing(spec.getDisplayName() + " missing"));
            }
        }
        DependencyService service = DependencyRuntimeTestSupport.serviceWith(statuses);

        List<DependencyId> ordered = new ArrayList<DependencyId>(ids(service.planFixAll().getDependenciesToFix()));

        assertEquals(DependencyId.BIO_FORMATS_RUNTIME, ordered.get(0));
        assertEquals(DependencyId.OBJECTS_COUNTER_3D, ordered.get(1));
        assertEquals(DependencyId.STARDIST_RUNTIME, ordered.get(2));
        assertEquals(DependencyId.TENSORFLOW_NATIVE_RUNTIME, ordered.get(3));
        assertEquals(DependencyId.APACHE_POI_RUNTIME, ordered.get(4));
        assertEquals(DependencyId.JTS_CORE, ordered.get(5));
        assertEquals(DependencyId.COLOC2_RUNTIME, ordered.get(6));
        assertEquals(DependencyId.IMGLIB2_ALGORITHM_RUNTIME, ordered.get(7));
        assertEquals(DependencyId.IMGLIB2_FFT_RUNTIME, ordered.get(8));
        assertEquals(DependencyId.JTRANSFORMS_RUNTIME, ordered.get(9));
        assertEquals(DependencyId.ORIENTATIONJ_RUNTIME, ordered.get(10));
        assertEquals(DependencyId.CELLPOSE_RUNTIME, ordered.get(ordered.size() - 1));
    }

    @Test
    public void missingFijiPluginRowsOfferAutoFixButtons() {
        DependencyService service = DependencyRuntimeTestSupport.serviceWith(
                DependencyRuntimeTestSupport.withStatuses(
                        DependencyId.BIO_FORMATS_RUNTIME,
                        DependencyStatus.missing("Bio-Formats missing"),
                        DependencyId.OBJECTS_COUNTER_3D,
                        DependencyStatus.missing("3D Objects Counter missing")));

        Map<DependencyId, String> labels = firstActionLabels(service.getDialogRows());

        assertEquals("Auto-Fix Bio-Formats (~245 KB)", labels.get(DependencyId.BIO_FORMATS_RUNTIME));
        assertEquals("Auto-Fix 3D Objects Counter (~22 KB)", labels.get(DependencyId.OBJECTS_COUNTER_3D));
    }

    @Test
    public void missingIntensitySpatialRowsOfferInstallButtons() {
        DependencyService service = DependencyRuntimeTestSupport.serviceWith(
                DependencyRuntimeTestSupport.withStatuses(
                        DependencyId.COLOC2_RUNTIME,
                        DependencyStatus.missing("Coloc 2 missing"),
                        DependencyId.IMGLIB2_ALGORITHM_RUNTIME,
                        DependencyStatus.missing("ImgLib2 Algorithm missing"),
                        DependencyId.IMGLIB2_FFT_RUNTIME,
                        DependencyStatus.missing("ImgLib2 FFT missing"),
                        DependencyId.JTRANSFORMS_RUNTIME,
                        DependencyStatus.missing("JTransforms missing"),
                        DependencyId.ORIENTATIONJ_RUNTIME,
                        DependencyStatus.missing("OrientationJ missing")));

        Map<DependencyId, String> labels = firstActionLabels(service.getDialogRows());

        assertEquals("Install Coloc 2 (~150 KB)", labels.get(DependencyId.COLOC2_RUNTIME));
        assertEquals("Install ImgLib2 Algorithm (~1.1 MB)", labels.get(DependencyId.IMGLIB2_ALGORITHM_RUNTIME));
        assertEquals("Install ImgLib2 FFT (~20 KB)", labels.get(DependencyId.IMGLIB2_FFT_RUNTIME));
        assertEquals("Install JTransforms (~487 KB)", labels.get(DependencyId.JTRANSFORMS_RUNTIME));
        assertEquals("Install OrientationJ (~438 KB)", labels.get(DependencyId.ORIENTATIONJ_RUNTIME));
    }

    @Test
    public void dialogRowsNeedingAttentionIncludeOnlyVisibleMissingRows() {
        DependencyService service = DependencyRuntimeTestSupport.serviceWith(
                DependencyRuntimeTestSupport.withStatuses(
                        DependencyId.PLUGIN_JAR_INTEGRITY,
                        DependencyStatus.missing("FLASH jar missing"),
                        DependencyId.APACHE_POI_RUNTIME,
                        DependencyStatus.missing("Excel jars missing"),
                        DependencyId.STARDIST_RUNTIME,
                        DependencyStatus.error("StarDist jar conflict")));

        List<DependencyService.DialogRow> rows = service.getDialogRowsNeedingAttention();
        Set<DependencyId> ids = rowIds(rows);

        assertTrue(service.hasVisibleDependenciesNeedingAttention());
        assertTrue(ids.contains(DependencyId.APACHE_POI_RUNTIME));
        assertTrue(ids.contains(DependencyId.STARDIST_RUNTIME));
        assertFalse(ids.contains(DependencyId.PLUGIN_JAR_INTEGRITY));
    }

    @Test
    public void dialogRowsNeedingAttentionAreEmptyWhenOnlyHiddenRowsAreMissing() {
        DependencyService service = DependencyRuntimeTestSupport.serviceWith(
                DependencyRuntimeTestSupport.withStatuses(
                        DependencyId.PLUGIN_JAR_INTEGRITY,
                        DependencyStatus.missing("FLASH jar missing")));

        assertTrue(service.getDialogRowsNeedingAttention().isEmpty());
        assertFalse(service.hasVisibleDependenciesNeedingAttention());
    }

    private static Map<DependencyId, DependencyFixer> fakeFixers() {
        Map<DependencyId, DependencyFixer> fixers = new LinkedHashMap<DependencyId, DependencyFixer>();
        int order = 0;
        for (DependencySpec spec : DependencyRegistry.all()) {
            if (spec.isFixableInApp()) {
                fixers.put(spec.getId(), new CountingFixer(order++));
            }
        }
        return fixers;
    }

    private static Set<DependencyId> ids(List<DependencySpec> specs) {
        Set<DependencyId> ids = new LinkedHashSet<DependencyId>();
        for (DependencySpec spec : specs) {
            ids.add(spec.getId());
        }
        return ids;
    }

    private static Set<DependencyId> linkedIds(DependencyId... ids) {
        Set<DependencyId> set = new LinkedHashSet<DependencyId>();
        for (DependencyId id : ids) {
            set.add(id);
        }
        return set;
    }

    private static Set<DependencyId> rowIds(List<DependencyService.DialogRow> rows) {
        Set<DependencyId> ids = new LinkedHashSet<DependencyId>();
        for (DependencyService.DialogRow row : rows) {
            ids.add(row.getSpec().getId());
        }
        return ids;
    }

    private static Map<DependencyId, String> firstActionLabels(List<DependencyService.DialogRow> rows) {
        Map<DependencyId, String> labels = new LinkedHashMap<DependencyId, String>();
        for (DependencyService.DialogRow row : rows) {
            if (!row.getActions().isEmpty()) {
                labels.put(row.getSpec().getId(), row.getActions().get(0).getLabel());
            }
        }
        return labels;
    }

    private static final class CountingStatusProvider implements DependencyService.StatusSnapshotProvider {
        private final EnumMap<DependencyId, DependencyStatus> statuses;
        private int calls;
        private int lastSpecCount;

        CountingStatusProvider(EnumMap<DependencyId, DependencyStatus> statuses) {
            this.statuses = statuses;
        }

        @Override
        public EnumMap<DependencyId, DependencyStatus> snapshot(List<DependencySpec> specs) {
            calls++;
            lastSpecCount = specs == null ? 0 : specs.size();
            return new EnumMap<DependencyId, DependencyStatus>(statuses);
        }
    }

    private static final class CountingFixer implements DependencyFixer {
        private final int order;
        private int applyCalls;

        CountingFixer(int order) {
            this.order = order;
        }

        @Override
        public DependencyFixResult apply(DependencySpec spec, String actionId, ProgressCallback callback) {
            applyCalls++;
            return new DependencyFixResult(spec.getId(), true, true, false, "fake");
        }

        @Override
        public int getExecutionOrder() {
            return order;
        }
    }
}
