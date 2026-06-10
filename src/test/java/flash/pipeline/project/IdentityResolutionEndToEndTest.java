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
