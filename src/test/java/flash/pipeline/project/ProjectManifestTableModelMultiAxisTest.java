package flash.pipeline.project;

import flash.pipeline.naming.ConditionAxis;
import org.junit.Test;

import java.io.File;
import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** Headless tests for the N-axis condition plumbing in {@link ProjectManifestTableModel}. */
public class ProjectManifestTableModelMultiAxisTest {

    @Test
    public void multiAxisAssignPersistAndReload() {
        ProjectManifestTableModel model = new ProjectManifestTableModel();
        model.setConditionAxes(Arrays.asList(ConditionAxis.of("Genotype"), ConditionAxis.of("Timepoint")));
        model.addFile(new File("M14_LH_SCN.tif"));   // non-container; parses animal/hemi/region

        model.setConditionForRows(new int[]{0}, "Genotype", "hAPP");   // primary axis
        model.setConditionForRows(new int[]{0}, "Timepoint", "WeekFour");

        // Primary axis is shown in the (single) Condition column.
        assertEquals("hAPP", model.getValueAt(0, ProjectManifestTableModel.COL_CONDITION));
        assertTrue(model.distinctConditionValues("Genotype").contains("hAPP"));
        assertTrue(model.distinctConditionValues("Timepoint").contains("WeekFour"));

        ProjectFile pf = model.toProjectFile("p", "/out", "w");
        assertEquals(2, pf.conditionAxes.size());
        ProjectFile.Item item = pf.items.get(0);
        assertEquals("hAPP", item.condition);
        assertEquals("hAPP", item.conditions.get("genotype"));
        assertEquals("WeekFour", item.conditions.get("timepoint"));

        ProjectManifestTableModel reloaded = new ProjectManifestTableModel();
        reloaded.loadFromProjectFile(pf);
        assertEquals(2, reloaded.conditionAxes().size());
        ProjectManifestTableModel.Row row = reloaded.getFile(0);
        assertEquals("hAPP", row.condition);
        assertEquals("WeekFour", row.conditions.get("timepoint"));
    }

    @Test
    public void singleAxisStaysLegacy() {
        ProjectManifestTableModel model = new ProjectManifestTableModel();   // no axes defined
        model.addFile(new File("M1_LH_SCN.tif"));
        model.setConditionForRows(new int[]{0}, "WT");   // legacy single-arg -> primary "condition"

        assertEquals("WT", model.getValueAt(0, ProjectManifestTableModel.COL_CONDITION));

        ProjectFile pf = model.toProjectFile("p", "/out", "w");
        assertTrue(pf.conditionAxes.isEmpty());
        assertEquals("WT", pf.items.get(0).condition);
        assertTrue(pf.items.get(0).conditions.isEmpty());   // no multi-axis map written
    }
}
