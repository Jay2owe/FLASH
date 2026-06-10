package flash.pipeline.project;

import flash.pipeline.naming.ConditionAxis;
import org.junit.Test;

import java.io.File;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Stage 14: condition value rename / merge + fuzzy suggestions. */
public class ConditionMergeTest {

    private static ProjectManifestTableModel twoAxisModel() {
        ProjectManifestTableModel model = new ProjectManifestTableModel();
        model.setConditionAxes(Arrays.asList(ConditionAxis.of("Genotype"), ConditionAxis.of("Timepoint")));
        model.addFile(new File("a.tif"));
        model.addFile(new File("b.tif"));
        model.addFile(new File("c.tif"));
        // genotype drift: ctrl / Control / CTRL
        model.setConditionForRows(new int[]{0}, "Genotype", "ctrl");
        model.setConditionForRows(new int[]{1}, "Genotype", "Control");
        model.setConditionForRows(new int[]{2}, "Genotype", "CTRL");
        // timepoint identical across rows
        model.setConditionForRows(new int[]{0, 1, 2}, "Timepoint", "WeekFour");
        return model;
    }

    @Test
    public void merge_rewritesAllRowsForTargetAxisOnly() {
        ProjectManifestTableModel model = twoAxisModel();

        int changed = model.mergeConditionValues("Genotype",
                Arrays.asList("ctrl", "Control", "CTRL"), "Control");

        assertEquals(3, changed);
        assertEquals(1, model.distinctConditionValues("Genotype").size());
        assertTrue(model.distinctConditionValues("Genotype").contains("Control"));
        // timepoint axis untouched
        assertEquals(1, model.distinctConditionValues("Timepoint").size());
        assertTrue(model.distinctConditionValues("Timepoint").contains("WeekFour"));
    }

    @Test
    public void rename_updatesValueAndCounts() {
        ProjectManifestTableModel model = twoAxisModel();

        int changed = model.renameConditionValue("Genotype", "ctrl", "Control");

        assertEquals(1, changed);
        assertFalse(model.distinctConditionValues("Genotype").contains("ctrl"));
        // ctrl folded into Control -> two distinct genotypes remain (Control, CTRL)
        assertEquals(2, model.distinctConditionValues("Genotype").size());
        assertEquals("Control", model.getValueAt(0, ProjectManifestTableModel.COL_CONDITION));
    }

    @Test
    public void fuzzyGroups_findSimilarValues() {
        ProjectManifestTableModel model = twoAxisModel();

        List<List<String>> groups = model.fuzzyConditionGroups("Genotype", 3);
        // ctrl / Control / CTRL collapse into one fuzzy group
        assertEquals(1, groups.size());
        assertEquals(3, groups.get(0).size());
    }

    @Test
    public void merge_singleAxisProjectStillWorks() {
        ProjectManifestTableModel model = new ProjectManifestTableModel();   // implicit single Condition
        model.addFile(new File("a.tif"));
        model.addFile(new File("b.tif"));
        model.setConditionForRows(new int[]{0}, "wt");
        model.setConditionForRows(new int[]{1}, "WT");

        int changed = model.mergeConditionValues("Condition", Arrays.asList("wt", "WT"), "WT");
        assertEquals(2, changed);
        assertEquals(1, model.distinctConditionValues("Condition").size());
    }
}
