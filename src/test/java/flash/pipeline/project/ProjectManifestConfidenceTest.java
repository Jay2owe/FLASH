package flash.pipeline.project;

import flash.pipeline.intelligence.identity.Confidence;
import flash.pipeline.intelligence.identity.IdentityCandidate;
import flash.pipeline.intelligence.identity.IdentityResolver;
import flash.pipeline.intelligence.identity.IdentityResolvers;
import flash.pipeline.intelligence.identity.SourceRecord;
import org.junit.Test;

import java.io.File;
import java.util.Collections;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Stage 09: per-cell confidence/provenance grid + user-set rules. */
public class ProjectManifestConfidenceTest {

    @Test
    public void confidenceGridPopulatedFromResolver() {
        ProjectManifestTableModel model = new ProjectManifestTableModel();
        model.addFile(new File("placeholder.tif"));

        SourceRecord r = SourceRecord.looseFile("M14_LH_SCN.tif", null, null);
        IdentityResolver resolver = IdentityResolvers.standard();
        Map<SourceRecord, IdentityCandidate> resolved =
                resolver.resolve(Collections.singletonList(r));
        model.applyResolved(0, resolved.get(r));

        assertEquals("LH", model.getValueAt(0, ProjectManifestTableModel.COL_HEMISPHERE));
        assertEquals(Confidence.HIGH, model.confidenceAt(0, ProjectManifestTableModel.COL_HEMISPHERE));
        assertFalse(model.provenanceAt(0, ProjectManifestTableModel.COL_HEMISPHERE).isEmpty());

        assertEquals("M14", model.getValueAt(0, ProjectManifestTableModel.COL_ANIMAL));
        assertTrue(model.confidenceAt(0, ProjectManifestTableModel.COL_ANIMAL) != Confidence.NONE);
        // an auto-detected (not user-confirmed) cell
        assertFalse(model.isUserSet(0, ProjectManifestTableModel.COL_HEMISPHERE));
    }

    @Test
    public void userEditMarksNeutralAndBlocksAutoOverwrite() {
        ProjectManifestTableModel model = new ProjectManifestTableModel();
        model.addFile(new File("placeholder.tif"));

        model.setAutoValue(0, ProjectManifestTableModel.COL_REGION, "SCN", Confidence.MEDIUM, "vocab");
        assertEquals(Confidence.MEDIUM, model.confidenceAt(0, ProjectManifestTableModel.COL_REGION));
        assertFalse(model.isUserSet(0, ProjectManifestTableModel.COL_REGION));

        model.setValueAt("Cortex", 0, ProjectManifestTableModel.COL_REGION);
        assertTrue(model.isUserSet(0, ProjectManifestTableModel.COL_REGION));
        assertEquals(Confidence.NONE, model.confidenceAt(0, ProjectManifestTableModel.COL_REGION));

        // auto-fill must not clobber a user-confirmed cell
        model.setAutoValue(0, ProjectManifestTableModel.COL_REGION, "SCN", Confidence.HIGH, "grammar");
        assertEquals("Cortex", model.getValueAt(0, ProjectManifestTableModel.COL_REGION));
        assertTrue(model.isUserSet(0, ProjectManifestTableModel.COL_REGION));
    }

    @Test
    public void lowConfidenceNavigationAndCount() {
        ProjectManifestTableModel model = new ProjectManifestTableModel();
        model.addFile(new File("a.tif"));
        model.addFile(new File("b.tif"));

        model.setAutoValue(0, ProjectManifestTableModel.COL_ANIMAL, "M1", Confidence.HIGH, "grammar");
        model.setAutoValue(1, ProjectManifestTableModel.COL_ANIMAL, "weird_title", Confidence.LOW, "fallback");

        assertEquals(1, model.nextLowConfidenceRow(-1));
        assertEquals(1, model.highConfidenceRowCount());
    }
}
