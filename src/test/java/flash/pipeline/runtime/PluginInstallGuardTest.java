package flash.pipeline.runtime;

import org.junit.BeforeClass;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class PluginInstallGuardTest {

    @BeforeClass
    public static void forceHeadlessProperty() {
        System.setProperty("java.awt.headless", "true");
    }

    @Test
    public void missingClassNameConvertsSlashesToDots() {
        NoClassDefFoundError error = new NoClassDefFoundError("flash/pipeline/roi/RoiIO");
        assertEquals("flash.pipeline.roi.RoiIO", PluginInstallGuard.missingClassName(error));
    }

    @Test
    public void missingClassNameStripsCouldNotInitializePrefix() {
        NoClassDefFoundError error =
                new NoClassDefFoundError("Could not initialize class flash.pipeline.roi.RoiIO");
        assertEquals("flash.pipeline.roi.RoiIO", PluginInstallGuard.missingClassName(error));
    }

    @Test
    public void missingClassNameReturnsNullForEmptyMessages() {
        NoClassDefFoundError error = new NoClassDefFoundError((String) null);
        assertNull(PluginInstallGuard.missingClassName(error));
    }

    @Test
    public void internalPluginIntegrityGuardStillTripsIndependentlyOfOptionalDependencies() {
        FeatureDependencyGate.configure(
                DependencyRuntimeTestSupport.serviceWith(DependencyRuntimeTestSupport.allPresent()),
                null);

        assertTrue(PluginInstallGuard.reportMissingInternalClass(
                "3D Object Analysis",
                new NoClassDefFoundError("flash/pipeline/roi/RoiIO")));
    }

    @Test
    public void internalPluginIntegrityGuardIgnoresExternalOptionalDependencyClasses() {
        assertFalse(PluginInstallGuard.reportMissingInternalClass(
                "Excel Summary Export",
                new NoClassDefFoundError("org/apache/poi/ss/usermodel/Workbook")));
    }
}
