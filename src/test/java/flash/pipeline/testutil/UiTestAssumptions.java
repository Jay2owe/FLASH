package flash.pipeline.testutil;

import org.junit.Assume;

import java.awt.GraphicsEnvironment;
import java.awt.Window;

public final class UiTestAssumptions {
    public static final String INTERACTIVE_UI_PROPERTY =
            "flash.tests.interactiveUi";

    private UiTestAssumptions() {
    }

    public static boolean isDisplayAvailable() {
        return !GraphicsEnvironment.isHeadless();
    }

    public static boolean areInteractiveUiTestsEnabled() {
        return Boolean.getBoolean(INTERACTIVE_UI_PROPERTY) && isDisplayAvailable();
    }

    public static void assumeDisplayAvailable() {
        Assume.assumeTrue("Swing UI tests require a display.", isDisplayAvailable());
    }

    public static void assumeInteractiveUiTestsEnabled() {
        Assume.assumeTrue("Interactive Swing tests are disabled by default; "
                        + "set -D" + INTERACTIVE_UI_PROPERTY + "=true to run them.",
                Boolean.getBoolean(INTERACTIVE_UI_PROPERTY));
        assumeDisplayAvailable();
    }

    public static TestWait.ResourceSnapshot snapshotOwnedResources() {
        return TestWait.snapshotResources();
    }

    public static void dispose(Window window) {
        if (window != null) {
            window.dispose();
        }
    }
}
