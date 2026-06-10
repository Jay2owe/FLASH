package flash.pipeline.analyses;

import flash.pipeline.io.ConditionManifestIO;
import flash.pipeline.naming.ConditionAssignments;
import flash.pipeline.naming.ConditionAxis;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;

/**
 * Per-axis condition grouping for statistics (Stage 16). Exercises
 * {@link StatisticalAnalysis#remapToConditionAxis} directly so the per-axis
 * collapse logic is covered without booting the full analysis.
 */
public class StatisticalAnalysisConditionAxisTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    private File writeTwoAxisManifest() throws Exception {
        File dir = temp.newFolder("project");
        ConditionAssignments ca = new ConditionAssignments();
        ca.addAxis(ConditionAxis.of("Genotype"));
        ca.addAxis(ConditionAxis.of("Timepoint"));
        ca.put("M1", "genotype", "hAPP");
        ca.put("M1", "timepoint", "WeekFour");
        ca.put("M2", "genotype", "Syn");
        ca.put("M2", "timepoint", "WeekFour");
        ConditionManifestIO.saveAssignments(dir.getAbsolutePath(), ca);
        return dir;
    }

    private static Set<String> animals() {
        return new LinkedHashSet<String>(Arrays.asList("M1", "M2"));
    }

    private static Map<String, String> compositeMap() {
        Map<String, String> a2c = new LinkedHashMap<String, String>();
        a2c.put("M1", "hAPP_WeekFour");
        a2c.put("M2", "Syn_WeekFour");
        return a2c;
    }

    @Test
    public void remap_toGenotype_collapsesTimepoint() throws Exception {
        File dir = writeTwoAxisManifest();
        Map<String, String> a2c = compositeMap();

        StatisticalAnalysis.remapToConditionAxis(dir.getAbsolutePath(), a2c, animals(), "genotype");

        // Two animals that shared a composite-distinct label now group by genotype.
        assertEquals("hAPP", a2c.get("M1"));
        assertEquals("Syn", a2c.get("M2"));
    }

    @Test
    public void remap_toTimepoint_groupsBothTogether() throws Exception {
        File dir = writeTwoAxisManifest();
        Map<String, String> a2c = compositeMap();

        StatisticalAnalysis.remapToConditionAxis(dir.getAbsolutePath(), a2c, animals(), "timepoint");

        assertEquals("WeekFour", a2c.get("M1"));
        assertEquals("WeekFour", a2c.get("M2"));
    }

    @Test
    public void remap_blankOrNullAxis_isNoOp() throws Exception {
        File dir = writeTwoAxisManifest();

        Map<String, String> nullAxis = compositeMap();
        StatisticalAnalysis.remapToConditionAxis(dir.getAbsolutePath(), nullAxis, animals(), null);
        assertEquals("hAPP_WeekFour", nullAxis.get("M1"));

        Map<String, String> blankAxis = compositeMap();
        StatisticalAnalysis.remapToConditionAxis(dir.getAbsolutePath(), blankAxis, animals(), "  ");
        assertEquals("Syn_WeekFour", blankAxis.get("M2"));
    }

    @Test
    public void remap_unknownAxis_keepsComposite() throws Exception {
        File dir = writeTwoAxisManifest();
        Map<String, String> a2c = compositeMap();

        StatisticalAnalysis.remapToConditionAxis(dir.getAbsolutePath(), a2c, animals(), "sex");

        assertEquals("hAPP_WeekFour", a2c.get("M1"));
        assertEquals("Syn_WeekFour", a2c.get("M2"));
    }
}
