package flash.pipeline.project;

import flash.pipeline.intelligence.identity.Confidence;
import flash.pipeline.io.ConditionManifestIO;
import flash.pipeline.naming.ConditionAssignments;
import flash.pipeline.naming.ConditionAxis;
import flash.pipeline.naming.OrientationManifestRow;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Stage 17 end-to-end: the batch resolver fills the manifest, is idempotent,
 * never overwrites user edits, and confirmed identity round-trips through
 * Conditions.csv and Image Orientation.csv.
 */
public class IdentityResolutionEndToEndTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void batchResolveIsIdempotentAndPreservesUserEdits() {
        ProjectManifestTableModel model = new ProjectManifestTableModel();
        model.addFile(new File("M14_LH_SCN.tif"));
        model.addFile(new File("M15_RH_PVN.tif"));

        model.resolveIdentities();
        assertEquals("LH", model.getValueAt(0, ProjectManifestTableModel.COL_HEMISPHERE));
        assertEquals(Confidence.HIGH, model.confidenceAt(0, ProjectManifestTableModel.COL_HEMISPHERE));

        // a user override
        model.setValueAt("Hippocampus", 0, ProjectManifestTableModel.COL_REGION);
        assertTrue(model.isUserSet(0, ProjectManifestTableModel.COL_REGION));

        // re-resolving is idempotent for auto cells and never clobbers the edit
        model.resolveIdentities();
        assertEquals("Hippocampus", model.getValueAt(0, ProjectManifestTableModel.COL_REGION));
        assertTrue(model.isUserSet(0, ProjectManifestTableModel.COL_REGION));
        assertEquals("LH", model.getValueAt(0, ProjectManifestTableModel.COL_HEMISPHERE));
        assertEquals("M15", model.getValueAt(1, ProjectManifestTableModel.COL_ANIMAL));
    }

    @Test
    public void multiAxisConditionsRoundTripThroughCsv() throws Exception {
        File dir = temp.newFolder("project");
        ProjectManifestTableModel model = new ProjectManifestTableModel();
        model.setConditionAxes(Arrays.asList(ConditionAxis.of("Genotype"), ConditionAxis.of("Timepoint")));
        model.addFile(new File("M14_LH_SCN.tif"));
        model.setValueAt("M14", 0, ProjectManifestTableModel.COL_ANIMAL);
        model.setValueAt("hAPP", 0, ProjectManifestTableModel.COL_CONDITION);          // Genotype
        model.setValueAt("WeekFour", 0, ProjectManifestTableModel.COL_CONDITION + 1);  // Timepoint

        ConditionManifestIO.saveAssignments(dir.getAbsolutePath(), model.toConditionAssignments());
        ConditionAssignments back = ConditionManifestIO.readAssignmentsModel(dir.getAbsolutePath());

        assertEquals("hAPP", back.get("M14", "genotype"));
        assertEquals("WeekFour", back.get("M14", "timepoint"));
    }

    @Test
    public void resolveAddsDetectedConditionAxis() {
        // Fresh project, no axes declared. A genotype token in the name must
        // surface as a new Genotype axis rather than being silently dropped.
        ProjectManifestTableModel model = new ProjectManifestTableModel();
        model.addFile(new File("hAPP_M14_LH_SCN.tif"));
        assertTrue(model.conditionAxes().isEmpty());

        model.resolveIdentities();

        int genotypeCol = model.conditionColumnForAxis("genotype");
        assertTrue("a Genotype axis column should have been created", genotypeCol >= 0);
        assertEquals("hAPP", model.getValueAt(0, genotypeCol));
    }

    @Test
    public void legacyPrimaryConditionSurvivesAxisPromotionAndRoundTrip() {
        // A legacy single-Condition value must stay under the Condition column when
        // auto-detection promotes the schema to add a Genotype axis, and survive save/reload.
        ProjectManifestTableModel model = new ProjectManifestTableModel();
        model.addFile(new File("hAPP_M14_LH_SCN.tif"));
        model.setConditionForRows(new int[]{0}, "WT");   // legacy primary Condition value

        model.resolveIdentities();                        // detects genotype hAPP -> promotes schema

        assertEquals("WT", model.getValueAt(0, ProjectManifestTableModel.COL_CONDITION));
        int genotypeCol = model.conditionColumnForAxis("genotype");
        assertTrue(genotypeCol > ProjectManifestTableModel.COL_CONDITION);
        assertEquals("hAPP", model.getValueAt(0, genotypeCol));

        ProjectFile pf = model.toProjectFile("p", "/out", "w");
        ProjectManifestTableModel reloaded = new ProjectManifestTableModel();
        reloaded.loadFromProjectFile(pf);
        assertEquals("WT", reloaded.getValueAt(0, ProjectManifestTableModel.COL_CONDITION));
        assertEquals("hAPP", reloaded.getValueAt(0, reloaded.conditionColumnForAxis("genotype")));
    }

    @Test
    public void rosterImportPromotesImplicitConditionInsteadOfCorrupting() {
        // Legacy implicit single-Condition project with a primary value already set.
        ProjectManifestTableModel model = new ProjectManifestTableModel();
        model.addFile(new File("M1.tif"));
        model.setValueAt("M1", 0, ProjectManifestTableModel.COL_ANIMAL);
        model.setConditionForRows(new int[]{0}, "Control");
        assertTrue(model.conditionAxes().isEmpty());

        // Roster introduces a NEW Genotype axis for the same animal.
        java.util.Map<String, java.util.Map<String, String>> byAnimal =
                new java.util.LinkedHashMap<String, java.util.Map<String, String>>();
        java.util.Map<String, String> vals = new java.util.LinkedHashMap<String, String>();
        vals.put("genotype", "WT");
        byAnimal.put("M1", vals);
        model.importRoster(Arrays.asList(ConditionAxis.of("Genotype")), byAnimal, false);

        // The legacy Control value stays under the primary Condition column...
        assertEquals("Control", model.getValueAt(0, ProjectManifestTableModel.COL_CONDITION));
        // ...and WT lands under a NEW Genotype column, not on top of Control.
        int genotypeCol = model.conditionColumnForAxis("genotype");
        assertTrue(genotypeCol > ProjectManifestTableModel.COL_CONDITION);
        assertEquals("WT", model.getValueAt(0, genotypeCol));
    }

    @Test
    public void userSetCellMetaSurvivesSaveReloadAndBlocksReResolve() {
        ProjectManifestTableModel model = new ProjectManifestTableModel();
        model.addFile(new File("M14_LH_SCN.tif"));
        model.resolveIdentities();

        // User overrides the auto-detected region; this marks the cell user-set.
        model.setValueAt("Hippocampus", 0, ProjectManifestTableModel.COL_REGION);
        assertTrue(model.isUserSet(0, ProjectManifestTableModel.COL_REGION));

        // Save + reopen.
        ProjectFile pf = model.toProjectFile("p", "/out", "w");
        ProjectManifestTableModel reloaded = new ProjectManifestTableModel();
        reloaded.loadFromProjectFile(pf);

        // The user-set flag and value survive the round trip...
        assertTrue("user-set flag must persist across save/reopen",
                reloaded.isUserSet(0, ProjectManifestTableModel.COL_REGION));
        assertEquals("Hippocampus", reloaded.getValueAt(0, ProjectManifestTableModel.COL_REGION));

        // ...so a later auto-resolve (e.g. after adding more files) does NOT clobber it.
        reloaded.resolveIdentities();
        assertEquals("Hippocampus", reloaded.getValueAt(0, ProjectManifestTableModel.COL_REGION));
        assertTrue(reloaded.isUserSet(0, ProjectManifestTableModel.COL_REGION));
    }

    @Test
    public void removingAxisPurgesMetaSoReimportWorksAfterReload() {
        // Condition + Genotype; Genotype is non-primary so it is removable.
        ProjectManifestTableModel model = new ProjectManifestTableModel();
        model.setConditionAxes(Arrays.asList(ConditionAxis.of("Condition"), ConditionAxis.of("Genotype")));
        model.addFile(new File("M1.tif"));
        model.setValueAt("M1", 0, ProjectManifestTableModel.COL_ANIMAL);
        int genotypeCol = model.conditionColumnForAxis("genotype");
        model.setValueAt("hAPP", 0, genotypeCol);          // user edit -> user-set
        assertTrue(model.isUserSet(0, genotypeCol));

        // Remove the Genotype axis, then save + reopen.
        model.removeConditionAxis("genotype");
        ProjectFile pf = model.toProjectFile("p", "/out", "w");
        ProjectManifestTableModel reloaded = new ProjectManifestTableModel();
        reloaded.loadFromProjectFile(pf);

        // Re-import a roster that re-introduces Genotype=WT.
        java.util.Map<String, java.util.Map<String, String>> byAnimal =
                new java.util.LinkedHashMap<String, java.util.Map<String, String>>();
        java.util.Map<String, String> vals = new java.util.LinkedHashMap<String, String>();
        vals.put("genotype", "WT");
        byAnimal.put("M1", vals);
        reloaded.importRoster(Arrays.asList(ConditionAxis.of("Genotype")), byAnimal, false);

        // The roster value lands — no stale user-set meta from the removed axis blocks it.
        assertEquals("WT", reloaded.getValueAt(0, reloaded.conditionColumnForAxis("genotype")));
    }

    @Test
    public void loadIgnoresOrphanedMetaForAbsentAxis() {
        // Simulate a project file written by an older build: meta for a "genotype" axis
        // that is NOT in the schema. It must not be restored as a user-set flag that
        // would block a later roster import for that axis.
        ProjectFile pf = new ProjectFile();
        ProjectFile.Item item = new ProjectFile.Item();
        item.path = new File("M1.tif").getAbsolutePath();
        item.include = true;
        item.animalId = "M1";
        ProjectFile.CellMetaData orphan = new ProjectFile.CellMetaData();
        orphan.userSet = true;
        orphan.confidence = "NONE";
        item.meta.put("genotype", orphan);        // no genotype axis in pf.conditionAxes
        pf.items.add(item);

        ProjectManifestTableModel model = new ProjectManifestTableModel();
        model.loadFromProjectFile(pf);

        java.util.Map<String, java.util.Map<String, String>> byAnimal =
                new java.util.LinkedHashMap<String, java.util.Map<String, String>>();
        java.util.Map<String, String> vals = new java.util.LinkedHashMap<String, String>();
        vals.put("genotype", "WT");
        byAnimal.put("M1", vals);
        model.importRoster(Arrays.asList(ConditionAxis.of("Genotype")), byAnimal, false);

        // The orphaned user-set flag was filtered out, so the roster value lands.
        assertEquals("WT", model.getValueAt(0, model.conditionColumnForAxis("genotype")));
    }

    @Test
    public void setConditionAxesReorderMigratesPrimaryValueAndMeta() {
        ProjectManifestTableModel model = new ProjectManifestTableModel();
        model.setConditionAxes(Arrays.asList(ConditionAxis.of("Genotype"), ConditionAxis.of("Timepoint")));
        model.addFile(new File("M1.tif"));
        model.setValueAt("M1", 0, ProjectManifestTableModel.COL_ANIMAL);
        model.setValueAt("hAPP", 0, ProjectManifestTableModel.COL_CONDITION);        // Genotype (primary)
        model.setValueAt("WeekFour", 0, ProjectManifestTableModel.COL_CONDITION + 1); // Timepoint

        // Reorder so Timepoint becomes the primary axis.
        model.setConditionAxes(Arrays.asList(ConditionAxis.of("Timepoint"), ConditionAxis.of("Genotype")));

        int tpCol = model.conditionColumnForAxis("timepoint");
        int genoCol = model.conditionColumnForAxis("genotype");
        assertEquals(ProjectManifestTableModel.COL_CONDITION, tpCol);   // timepoint now primary
        assertEquals("WeekFour", model.getValueAt(0, tpCol));           // value followed its axis
        assertEquals("hAPP", model.getValueAt(0, genoCol));            // genotype value preserved
        // user-set meta follows the axis id, not the primary slot
        assertTrue(model.isUserSet(0, genoCol));
    }

    @Test
    public void confirmedSeriesIdentitySeedsOrientationManifest() {
        ProjectFile project = new ProjectFile();
        ProjectFile.Item item = new ProjectFile.Item();
        item.path = new File(temp.getRoot(), "slide.lif").getAbsolutePath();
        item.include = true;
        ProjectFile.SeriesItem s = new ProjectFile.SeriesItem();
        s.index = 0;
        s.include = true;
        s.name = "M14_LH_SCN";
        s.animalId = "M14";
        s.hemisphere = "LH";
        s.region = "SCN";
        item.seriesMeta.add(s);
        project.items.add(item);

        List<OrientationManifestRow> rows = ProjectMetadataSeeder.orientationRowsFor(project);
        assertFalse(rows.isEmpty());
        OrientationManifestRow row = rows.get(0);
        assertEquals("M14", row.animalName);
        assertEquals(OrientationManifestRow.Hemisphere.LH, row.hemisphere);
        assertEquals("SCN", row.region);
        // confirmed, user-authoritative (so downstream honours it, not the parsed name)
        assertEquals(OrientationManifestRow.ConfirmationState.YES, row.confirmed);
        assertEquals(OrientationManifestRow.DecisionSource.MANUAL, row.decisionSource);
    }
}
