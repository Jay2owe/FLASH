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

    @Test
    public void remap_nestedRowKeys_useParentAxisValue() throws Exception {
        // Paired/nested granularity: row keys like "M1-LH" are absent from the
        // manifest, so per-axis grouping must resolve via the parent animal.
        File dir = writeTwoAxisManifest();
        Map<String, String> a2c = new LinkedHashMap<String, String>();
        a2c.put("M1-LH", "");
        a2c.put("M1-RH", "");
        a2c.put("M2-LH", "");
        Set<String> animals = new LinkedHashSet<String>(Arrays.asList("M1-LH", "M1-RH", "M2-LH"));
        Map<String, String> keyToParent = new LinkedHashMap<String, String>();
        keyToParent.put("M1-LH", "M1");
        keyToParent.put("M1-RH", "M1");
        keyToParent.put("M2-LH", "M2");

        StatisticalAnalysis.remapToConditionAxis(
                dir.getAbsolutePath(), a2c, animals, "timepoint", keyToParent);
        assertEquals("WeekFour", a2c.get("M1-LH"));
        assertEquals("WeekFour", a2c.get("M1-RH"));
        assertEquals("WeekFour", a2c.get("M2-LH"));

        Map<String, String> byGeno = new LinkedHashMap<String, String>(a2c);
        StatisticalAnalysis.remapToConditionAxis(
                dir.getAbsolutePath(), byGeno, animals, "genotype", keyToParent);
        assertEquals("hAPP", byGeno.get("M1-LH"));
        assertEquals("Syn", byGeno.get("M2-LH"));
    }

    @Test
    public void remap_nestedRowKey_withoutParentMap_blanksSecondaryAxis() throws Exception {
        // Documents the bug the parent map fixes: a nested key absent from the
        // manifest resolves blank on a secondary axis when no parent is supplied.
        File dir = writeTwoAxisManifest();
        Map<String, String> a2c = new LinkedHashMap<String, String>();
        a2c.put("M1-LH", "hAPP_WeekFour");
        Set<String> animals = new LinkedHashSet<String>(Arrays.asList("M1-LH"));

        StatisticalAnalysis.remapToConditionAxis(dir.getAbsolutePath(), a2c, animals, "timepoint");
        assertEquals("", a2c.get("M1-LH"));
    }

    @Test
    public void previewText_combined_listsCompositeGroups() {
        ConditionAssignments model = new ConditionAssignments();
        model.addAxis(ConditionAxis.of("Genotype"));
        model.addAxis(ConditionAxis.of("Timepoint"));
        model.put("M1", "genotype", "hAPP");
        model.put("M1", "timepoint", "WeekFour");
        model.put("M2", "genotype", "Syn");
        model.put("M2", "timepoint", "WeekTwo");

        String preview = StatisticalAnalysis.previewText(model, null);
        assertEquals("Groups (2): hAPP_WeekFour, Syn_WeekTwo", preview);
    }

    @Test
    public void previewText_perAxis_listsAxisValuesOnly() {
        ConditionAssignments model = new ConditionAssignments();
        model.addAxis(ConditionAxis.of("Genotype"));
        model.addAxis(ConditionAxis.of("Timepoint"));
        model.put("M1", "genotype", "hAPP");
        model.put("M1", "timepoint", "WeekFour");
        model.put("M2", "genotype", "hAPP");
        model.put("M2", "timepoint", "WeekTwo");

        // Both animals share genotype hAPP -> one group on the genotype axis.
        assertEquals("Groups (1): hAPP", StatisticalAnalysis.previewText(model, "genotype"));
        assertEquals("Groups (2): WeekFour, WeekTwo",
                StatisticalAnalysis.previewText(model, "timepoint"));
    }
}
