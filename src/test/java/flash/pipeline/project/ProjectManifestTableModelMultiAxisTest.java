package flash.pipeline.project;

import flash.pipeline.naming.ConditionAxis;
import org.junit.Test;

import java.io.File;
import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
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

        assertEquals(9, model.getColumnCount());
        assertEquals("Genotype", model.getColumnName(ProjectManifestTableModel.COL_CONDITION));
        assertEquals("Timepoint", model.getColumnName(ProjectManifestTableModel.COL_CONDITION + 1));
        assertEquals("Notes", model.getColumnName(model.notesColumn()));

        assertEquals("hAPP", model.getValueAt(0, ProjectManifestTableModel.COL_CONDITION));
        assertEquals("WeekFour", model.getValueAt(0, ProjectManifestTableModel.COL_CONDITION + 1));
        assertTrue(model.distinctConditionValues("Genotype").contains("hAPP"));
        assertTrue(model.distinctConditionValues("Timepoint").contains("WeekFour"));

        model.setValueAt("WeekEight", 0, ProjectManifestTableModel.COL_CONDITION + 1);
        assertEquals("WeekEight", model.getValueAt(0, ProjectManifestTableModel.COL_CONDITION + 1));

        ProjectFile pf = model.toProjectFile("p", "/out", "w");
        assertEquals(2, pf.conditionAxes.size());
        ProjectFile.Item item = pf.items.get(0);
        assertEquals("hAPP", item.condition);
        assertEquals("hAPP", item.conditions.get("genotype"));
        assertEquals("WeekEight", item.conditions.get("timepoint"));

        ProjectManifestTableModel reloaded = new ProjectManifestTableModel();
        reloaded.loadFromProjectFile(pf);
        assertEquals(2, reloaded.conditionAxes().size());
        ProjectManifestTableModel.Row row = reloaded.getFile(0);
        assertEquals("hAPP", row.condition);
        assertEquals("WeekEight", row.conditions.get("timepoint"));
        assertEquals("WeekEight", reloaded.getValueAt(0, ProjectManifestTableModel.COL_CONDITION + 1));
    }

    @Test
    public void singleAxisStaysLegacy() {
        ProjectManifestTableModel model = new ProjectManifestTableModel();   // no axes defined
        model.addFile(new File("M1_LH_SCN.tif"));
        model.setConditionForRows(new int[]{0}, "WT");   // legacy single-arg -> primary "condition"

        assertEquals("WT", model.getValueAt(0, ProjectManifestTableModel.COL_CONDITION));
        assertEquals(8, model.getColumnCount());
        assertFalse(model.isConditionColumn(model.notesColumn()));

        ProjectFile pf = model.toProjectFile("p", "/out", "w");
        assertTrue(pf.conditionAxes.isEmpty());
        assertEquals("WT", pf.items.get(0).condition);
        assertTrue(pf.items.get(0).conditions.isEmpty());   // no multi-axis map written
    }

    @Test
    public void containerHeaderBlocksEveryConditionAxis() {
        ProjectManifestTableModel model = new ProjectManifestTableModel();
        model.setConditionAxes(Arrays.asList(ConditionAxis.of("Genotype"), ConditionAxis.of("Timepoint")));
        int idx = model.addFile(new File("slide.lif"));

        assertFalse(model.isCellEditable(idx, ProjectManifestTableModel.COL_CONDITION));
        assertFalse(model.isCellEditable(idx, ProjectManifestTableModel.COL_CONDITION + 1));

        model.setValueAt("WeekFour", idx, ProjectManifestTableModel.COL_CONDITION + 1);
        assertEquals("", model.getValueAt(idx, ProjectManifestTableModel.COL_CONDITION + 1));
    }

    // ── Stage 11: bulk assign for animal / hemisphere / region ──────────────

    @Test
    public void bulkIdentitySet_touchesOnlyTargetColumn() {
        ProjectManifestTableModel model = new ProjectManifestTableModel();
        model.addFile(new File("a.tif"));
        model.addFile(new File("b.tif"));
        // Seed a known baseline on every identity field for both rows.
        for (int r = 0; r < 2; r++) {
            model.setValueAt("animal" + r, r, ProjectManifestTableModel.COL_ANIMAL);
            model.setValueAt("LH", r, ProjectManifestTableModel.COL_HEMISPHERE);
            model.setValueAt("SCN", r, ProjectManifestTableModel.COL_REGION);
        }
        model.setConditionForRows(new int[]{0, 1}, "WT");

        model.setIdentityForRows(new int[]{0, 1}, ProjectManifestTableModel.COL_REGION, "Cortex");

        // region updated on both rows…
        assertEquals("Cortex", model.getValueAt(0, ProjectManifestTableModel.COL_REGION));
        assertEquals("Cortex", model.getValueAt(1, ProjectManifestTableModel.COL_REGION));
        // …while animal / hemisphere / condition are untouched.
        assertEquals("animal0", model.getValueAt(0, ProjectManifestTableModel.COL_ANIMAL));
        assertEquals("LH", model.getValueAt(1, ProjectManifestTableModel.COL_HEMISPHERE));
        assertEquals("WT", model.getValueAt(0, ProjectManifestTableModel.COL_CONDITION));
    }

    @Test
    public void bulkIdentitySet_animalAndHemisphere() {
        ProjectManifestTableModel model = new ProjectManifestTableModel();
        model.addFile(new File("a.tif"));
        model.addFile(new File("b.tif"));

        model.setIdentityForRows(new int[]{0, 1}, ProjectManifestTableModel.COL_ANIMAL, "M99");
        model.setIdentityForRows(new int[]{0, 1}, ProjectManifestTableModel.COL_HEMISPHERE, "RH");

        assertEquals("M99", model.getValueAt(0, ProjectManifestTableModel.COL_ANIMAL));
        assertEquals("M99", model.getValueAt(1, ProjectManifestTableModel.COL_ANIMAL));
        assertEquals("RH", model.getValueAt(0, ProjectManifestTableModel.COL_HEMISPHERE));
        assertEquals("RH", model.getValueAt(1, ProjectManifestTableModel.COL_HEMISPHERE));
    }

    @Test
    public void bulkIdentitySet_skipsContainerHeaderButSetsItsSeries() {
        ProjectManifestTableModel model = new ProjectManifestTableModel();
        int header = model.addFile(new File("slide.lif"));
        model.setSeriesEntries(header, Arrays.asList(
                new ProjectManifestTableModel.SeriesEntry(0, "S0"),
                new ProjectManifestTableModel.SeriesEntry(1, "S1")));
        model.setExpanded(header, true);
        // visible rows: 0 = container header, 1 = series0, 2 = series1.

        model.setIdentityForRows(new int[]{0, 1, 2}, ProjectManifestTableModel.COL_ANIMAL, "MX");

        // header (container source) is skipped; its own animal stays blank-ish/derived.
        assertFalse("MX".equals(model.getValueAt(0, ProjectManifestTableModel.COL_ANIMAL)));
        // the two series rows are set directly.
        assertEquals("MX", model.getValueAt(1, ProjectManifestTableModel.COL_ANIMAL));
        assertEquals("MX", model.getValueAt(2, ProjectManifestTableModel.COL_ANIMAL));
    }

    @Test
    public void bulkIdentitySet_fileLevelCascadesToSeries() {
        ProjectManifestTableModel model = new ProjectManifestTableModel();
        // A non-container source that still carries series rows: cascade applies.
        int header = model.addFile(new File("M1_LH_SCN.tif"));
        model.setSeriesEntries(header, Arrays.asList(
                new ProjectManifestTableModel.SeriesEntry(0, "S0"),
                new ProjectManifestTableModel.SeriesEntry(1, "S1")));
        model.setExpanded(header, true);

        model.setIdentityForRows(new int[]{0}, ProjectManifestTableModel.COL_REGION, "Hippocampus");

        assertEquals("Hippocampus", model.getValueAt(0, ProjectManifestTableModel.COL_REGION));
        assertEquals("Hippocampus", model.getValueAt(1, ProjectManifestTableModel.COL_REGION));
        assertEquals("Hippocampus", model.getValueAt(2, ProjectManifestTableModel.COL_REGION));
    }

    @Test
    public void bulkIdentitySet_ignoresNonIdentityColumns() {
        ProjectManifestTableModel model = new ProjectManifestTableModel();
        model.addFile(new File("a.tif"));
        model.setValueAt("SCN", 0, ProjectManifestTableModel.COL_REGION);
        boolean includeBefore = Boolean.TRUE.equals(model.getValueAt(0, ProjectManifestTableModel.COL_INCLUDE));

        // COL_INCLUDE and COL_CONDITION are not handled by setIdentityForRows.
        model.setIdentityForRows(new int[]{0}, ProjectManifestTableModel.COL_INCLUDE, "x");
        model.setIdentityForRows(new int[]{0}, ProjectManifestTableModel.COL_CONDITION, "shouldNotApply");

        assertEquals(includeBefore,
                Boolean.TRUE.equals(model.getValueAt(0, ProjectManifestTableModel.COL_INCLUDE)));
        assertEquals("", model.getValueAt(0, ProjectManifestTableModel.COL_CONDITION));
        assertEquals("SCN", model.getValueAt(0, ProjectManifestTableModel.COL_REGION));
    }
}
