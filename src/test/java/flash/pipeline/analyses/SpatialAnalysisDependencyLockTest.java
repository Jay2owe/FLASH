package flash.pipeline.analyses;

import flash.pipeline.runtime.DependencyId;
import flash.pipeline.runtime.DependencyStatus;
import flash.pipeline.ui.ToggleSwitch;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SpatialAnalysisDependencyLockTest {

    @Test
    public void missingDependencyLockNamesRequirementDependencyAndDetail() {
        SpatialAnalysis.SpatialDependencyLock lock = SpatialAnalysis.dependencyLockFromStatus(
                DependencyId.JTS_CORE,
                DependencyStatus.missing("jts-core jar missing"),
                "Voronoi territory analysis");

        assertTrue(lock.isLocked());
        assertTrue(lock.message().contains("Voronoi territory analysis"));
        assertTrue(lock.message().contains("JTS / Voronoi geometry"));
        assertTrue(lock.message().contains("jts-core jar missing"));
    }

    @Test
    public void presentDependencyDoesNotLockToggle() {
        SpatialAnalysis.SpatialDependencyLock lock = SpatialAnalysis.dependencyLockFromStatus(
                DependencyId.JTS_CORE,
                DependencyStatus.present("available"),
                "Voronoi territory analysis");
        ToggleSwitch toggle = new ToggleSwitch(true);

        SpatialAnalysis.applyDependencyLockToToggle(toggle, lock);

        assertFalse(lock.isLocked());
        assertTrue(toggle.isSelected());
        assertTrue(toggle.isEnabled());
    }

    @Test
    public void missingDependencyClearsDisablesAndExplainsToggle() {
        SpatialAnalysis.SpatialDependencyLock lock = SpatialAnalysis.dependencyLockFromStatus(
                DependencyId.MCIB3D_CORE,
                DependencyStatus.missing("mcib3d-core classes missing"),
                "3D shape features");
        ToggleSwitch toggle = new ToggleSwitch(true);

        SpatialAnalysis.applyDependencyLockToToggle(toggle, lock);

        assertFalse(toggle.isSelected());
        assertFalse(toggle.isEnabled());
        assertEquals(lock.message(), toggle.getToolTipText());
    }
}
