package flash.pipeline.ui;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class RecorderDialogTest {

    @Test
    public void summariseDiff_describesOnlyCapturedDiff() {
        String summary = RecorderDialog.summariseDiff(
                "run(\"Gaussian Blur...\", \"sigma=2 stack\");\n"
                        + "run(\"Median...\", \"radius=3 stack\");");

        assertEquals("Gaussian Blur (sigma=2) \u2192 Median (radius=3).", summary);
    }

    @Test
    public void summariseDiff_emptyDiffDoesNotMentionSeed() {
        assertEquals("nothing yet.", RecorderDialog.summariseDiff(""));
        assertEquals("nothing yet.", RecorderDialog.summariseDiff(null));
    }

    @Test
    public void combineSeedAndDiff_trimsSeedAndAppendsDiffExactly() {
        String combined = RecorderDialog.combineSeedAndDiff(
                "  run(\"Invert\");\n\n",
                "run(\"Gaussian Blur...\", \"sigma=2 stack\");");

        assertEquals("run(\"Invert\");\nrun(\"Gaussian Blur...\", \"sigma=2 stack\");", combined);
    }

    @Test
    public void combineSeedAndDiff_withoutSeedReturnsDiffOnly() {
        assertEquals("run(\"Invert\");",
                RecorderDialog.combineSeedAndDiff("   ", "run(\"Invert\");"));
    }
}
