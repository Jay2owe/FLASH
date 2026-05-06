package flash.pipeline.runtime;

import org.junit.Test;

import java.util.EnumSet;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class DependencyRegistryTest {

    @Test
    public void everyDependencyIdHasExactlyOneSpec() {
        Map<DependencyId, DependencySpec> lookup = DependencyRegistry.lookup();
        assertEquals(DependencyId.values().length, lookup.size());

        Set<DependencyId> seen = EnumSet.noneOf(DependencyId.class);
        Set<DependencySpec> uniqueSpecs = new HashSet<DependencySpec>();
        for (DependencySpec spec : DependencyRegistry.all()) {
            assertSame(spec, lookup.get(spec.getId()));
            assertTrue("Duplicate spec for " + spec.getId(), seen.add(spec.getId()));
            assertTrue("Spec instance reused for " + spec.getId(), uniqueSpecs.add(spec));
        }

        assertEquals(EnumSet.allOf(DependencyId.class), seen);
    }

    @Test
    public void formatApproxSizeHandlesZeroBoundariesAndUnknownValues() {
        assertEquals("(~0 B)", DependencyRegistry.formatApproxSize(0));
        assertEquals("", DependencyRegistry.formatApproxSize(-1));
        assertEquals("(~1 KB)", DependencyRegistry.formatApproxSize(1));
        assertEquals("(~1 KB)", DependencyRegistry.formatApproxSize(1024));
        assertEquals("(~1 MB)", DependencyRegistry.formatApproxSize(1024 * 1024));
        assertEquals("(~1.5 MB)", DependencyRegistry.formatApproxSize(1536 * 1024));
        assertEquals("(~1 GB)", DependencyRegistry.formatApproxSize(1024L * 1024L * 1024L));
        assertEquals("(~2.5 GB)", DependencyRegistry.formatApproxSize(2684354560L));
    }

    @Test
    public void specFlagsMatchDependencyAuditTable() {
        assertFlags(DependencyId.PLUGIN_JAR_INTEGRITY, false, true, false);
        assertFlags(DependencyId.IMAGEJ_RUNTIME, false, true, false);
        assertFlags(DependencyId.BIO_FORMATS_RUNTIME, true, true, true);
        assertFlags(DependencyId.OBJECTS_COUNTER_3D, true, true, true);
        assertFlags(DependencyId.MCIB3D_CORE, false, true, true);
        assertFlags(DependencyId.NUCLEUS_COUNTER, true, true, true);
        assertFlags(DependencyId.STARDIST_RUNTIME, true, true, true);
        assertFlags(DependencyId.TENSORFLOW_NATIVE_RUNTIME, true, true, true);
        assertFlags(DependencyId.APACHE_POI_RUNTIME, true, true, true);
        assertFlags(DependencyId.CELLPOSE_RUNTIME, true, false, true);
        assertFlags(DependencyId.JTS_CORE, true, true, true);
    }

    private static void assertFlags(DependencyId id,
                                    boolean fixable,
                                    boolean restartRequired,
                                    boolean uiVisible) {
        DependencySpec spec = DependencyRegistry.get(id);
        assertEquals(id + " fixable", fixable, spec.isFixableInApp());
        assertEquals(id + " restart", restartRequired, spec.isRestartRequired());
        assertEquals(id + " visible", uiVisible, spec.isVisibleInDependenciesDialog());
    }
}
