package flash.pipeline.image;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * Regression coverage for the executor backstop documented in
 * docs/filter-branch-robustness: a bare {@code run("Duplicate", ...)} (no
 * ellipsis) dispatches to the Image5D plugin, so the internal filter execution
 * path normalises it to {@code run("Duplicate...", ...)} before running.
 */
public class FilterExecutorDuplicateGuardTest {

    @Test
    public void normalisesBareDuplicateWithArgs() {
        assertEquals("run(\"Duplicate...\", \"x=2 y=2 z=1\");",
                FilterExecutor.normalizeDuplicateCommand("run(\"Duplicate\", \"x=2 y=2 z=1\");"));
    }

    @Test
    public void normalisesBareDuplicateWithoutArgs() {
        assertEquals("run(\"Duplicate...\");",
                FilterExecutor.normalizeDuplicateCommand("run(\"Duplicate\");"));
    }

    @Test
    public void leavesProperDuplicateUnchanged() {
        String good = "run(\"Duplicate...\", \"title=copy duplicate\");";
        assertEquals(good, FilterExecutor.normalizeDuplicateCommand(good));
    }

    @Test
    public void leavesUnrelatedMacroUnchanged() {
        String macro = "run(\"Gaussian Blur...\", \"sigma=2 stack\");";
        assertEquals(macro, FilterExecutor.normalizeDuplicateCommand(macro));
    }

    @Test
    public void normalisesEveryOccurrence() {
        String in = "run(\"Duplicate\", \"a\");\nrun(\"Duplicate\", \"b\");";
        String out = "run(\"Duplicate...\", \"a\");\nrun(\"Duplicate...\", \"b\");";
        assertEquals(out, FilterExecutor.normalizeDuplicateCommand(in));
    }
}
