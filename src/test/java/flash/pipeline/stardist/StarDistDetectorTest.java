package flash.pipeline.stardist;

import flash.pipeline.runtime.DependencyId;
import flash.pipeline.runtime.DependencyStatus;

import org.junit.After;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class StarDistDetectorTest {

    @After
    public void tearDown() {
        StarDistDetector.resetForTest();
    }

    @Test
    public void isAvailableBlocksWhenTensorFlowNativeRuntimeIsMissing() {
        StarDistDetector.setTrackMateApiProbeForTest(new StarDistDetector.TrackMateApiProbe() {
            @Override
            public void verify() {
                // TrackMate-StarDist API is present; only TensorFlow native is missing.
            }
        });
        StarDistDetector.setRuntimeDependencyProbeForTest(
                new StarDistDetector.RuntimeDependencyProbe() {
                    @Override
                    public DependencyStatus status(DependencyId id) {
                        if (id == DependencyId.TENSORFLOW_NATIVE_RUNTIME) {
                            return DependencyStatus.missing(
                                    "TensorFlow JNI bridge: MISSING");
                        }
                        return DependencyStatus.present("present");
                    }
                });

        assertFalse(StarDistDetector.isAvailable());
        assertTrue(StarDistDetector.getAvailabilityMessage()
                .contains("TensorFlow native runtime"));
        assertTrue(StarDistDetector.getAvailabilityMessage()
                .contains("Auto-Fix TensorFlow Native"));
    }

    @Test
    public void markRuntimeFailureLatchesTensorFlowFailureForThisSession() {
        StarDistDetector.setTrackMateApiProbeForTest(new StarDistDetector.TrackMateApiProbe() {
            @Override
            public void verify() {
                // Runtime starts as available in this test.
            }
        });
        StarDistDetector.setRuntimeDependencyProbeForTest(
                new StarDistDetector.RuntimeDependencyProbe() {
                    @Override
                    public DependencyStatus status(DependencyId id) {
                        return DependencyStatus.present("present");
                    }
                });
        assertTrue(StarDistDetector.isAvailable());

        StarDistDetector.markRuntimeFailure(
                new UnsatisfiedLinkError("no tensorflow_jni in java.library.path"));

        assertFalse(StarDistDetector.isAvailable());
        assertTrue(StarDistDetector.getAvailabilityMessage()
                .contains("tensorflow_jni"));
        assertTrue(StarDistDetector.getAvailabilityMessage()
                .contains("restart Fiji"));
    }
}
