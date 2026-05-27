package flash.pipeline.testutil;

import org.junit.Assume;

import java.awt.GraphicsEnvironment;

public final class UiTestAssumptions {
    public static final String INTERACTIVE_UI_PROPERTY =
            "flash.tests.interactiveUi";

    private UiTestAssumptions() {
    }

    public static void assumeDisplayAvailable() {
        Assume.assumeFalse("Swing UI tests require a display.",
                GraphicsEnvironment.isHeadless());
    }

    public static void assumeInteractiveUiTestsEnabled() {
        Assume.assumeTrue("Interactive Swing tests are disabled by default; "
                        + "set -D" + INTERACTIVE_UI_PROPERTY + "=true to run them.",
                Boolean.getBoolean(INTERACTIVE_UI_PROPERTY));
        assumeDisplayAvailable();
    }
}
