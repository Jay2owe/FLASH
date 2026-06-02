package flash.pipeline.image;

import org.junit.Test;

import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class FilterBranchLabelsTest {

    @Test
    public void punctaResolveLinesMapToTheirBranches() {
        String macro = NamedFilterLoader.loadFilterContent("Puncta Resolve");
        assertNotNull(macro);

        assertTrue(FilterBranchLabels.isBranched(macro));

        Map<Integer, String> labels = FilterBranchLabels.labelByLine(macro);
        String[] lines = macro.split("\\r?\\n", -1);

        assertEquals("dens", labelForLineContaining(lines, labels, "Subtract Background"));
        assertEquals("edge", labelForLineContaining(lines, labels, "Variance"));
        // The trailing Gaussian/Minimum after imageCalculator are post-combine.
        assertEquals(FilterBranchLabels.AFTER_COMBINE,
                labelForLineContaining(lines, labels, "Minimum 3D"));
        assertEquals(FilterBranchLabels.COMBINE,
                labelForLineContaining(lines, labels, "imageCalculator"));
    }

    @Test
    public void diffuseObjectIsBranchedWithDoGBranches() {
        String macro = NamedFilterLoader.loadFilterContent("Diffuse Object");
        assertNotNull(macro);
        assertTrue(FilterBranchLabels.isBranched(macro));
        assertTrue(FilterBranchLabels.branches(macro).contains("DoG small"));
        assertTrue(FilterBranchLabels.branches(macro).contains("DoG big"));
    }

    @Test
    public void linearMacroIsAllSource() {
        String macro = "run(\"Gaussian Blur...\", \"sigma=2 stack\");\n"
                + "run(\"Median...\", \"radius=2 stack\");\n";
        assertFalse(FilterBranchLabels.isBranched(macro));
        for (String label : FilterBranchLabels.labelByLine(macro).values()) {
            assertEquals(FilterBranchLabels.SOURCE, label);
        }
    }

    @Test
    public void humanizeStripsLeadingUnderscore() {
        assertEquals("dens", FilterBranchLabels.humanize("_dens"));
        assertEquals("edge", FilterBranchLabels.humanize("_edge"));
        assertEquals("DoG small", FilterBranchLabels.humanize("DoG_small"));
    }

    private static String labelForLineContaining(String[] lines,
                                                 Map<Integer, String> labels,
                                                 String needle) {
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].contains(needle)) {
                return labels.get(i);
            }
        }
        throw new AssertionError("no line contains: " + needle);
    }
}
