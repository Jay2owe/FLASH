package flash.pipeline.project;

import flash.pipeline.intelligence.identity.IdentityResolvers;
import flash.pipeline.intelligence.identity.NamingGrammar;
import org.junit.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** Stage 10: the grammar live-preview computes without mutating the model. */
public class PatternPreviewPanelTest {

    private static List<String> snapshot(ProjectManifestTableModel model) {
        List<String> cells = new ArrayList<String>();
        for (int r = 0; r < model.getRowCount(); r++) {
            for (int c = 0; c < model.getColumnCount(); c++) {
                Object v = model.getValueAt(r, c);
                cells.add(v == null ? "" : v.toString());
            }
        }
        return cells;
    }

    @Test
    public void previewDoesNotMutateModel() {
        ProjectManifestTableModel model = new ProjectManifestTableModel();
        model.addFile(new File("M14_LH_SCN.tif"));
        // user-set region differs from what the grammar/vocab would detect
        model.setValueAt("Cortex", 0, ProjectManifestTableModel.COL_REGION);

        List<String> before = snapshot(model);
        PatternPreviewPanel.Outcome outcome =
                PatternPreviewPanel.previewGrammar(model, new NamingGrammar("empty", new ArrayList<flash.pipeline.intelligence.identity.FieldRule>()));
        List<String> after = snapshot(model);

        assertEquals("preview must not mutate the model", before, after);
        // and it produced a usable outcome (hemisphere detected as LH)
        assertTrue(outcome.rowLabels.size() == 1);
        assertEquals("LH", outcome.after.get(0).get("hemisphere").value);
    }

    @Test
    public void previewIncludesImplicitConditionAxis() {
        // A legacy single-condition project has no explicit axes, but the preview
        // must still surface the implicit "Condition" column so condition changes show.
        ProjectManifestTableModel model = new ProjectManifestTableModel();
        model.addFile(new File("placeholder.tif"));
        model.setValueAt("Old", 0, ProjectManifestTableModel.COL_CONDITION);

        java.util.List<flash.pipeline.intelligence.identity.FieldRule> rules =
                new ArrayList<flash.pipeline.intelligence.identity.FieldRule>();
        rules.add(flash.pipeline.intelligence.identity.FieldRule.alias(
                flash.pipeline.intelligence.identity.FieldRule.Type.CONDITION, "Condition",
                java.util.Arrays.asList(new flash.pipeline.intelligence.identity.ValuePattern(
                        "New", java.util.Collections.singletonList("placeholder")))));
        NamingGrammar g = new NamingGrammar("force", rules);

        PatternPreviewPanel.Outcome outcome = PatternPreviewPanel.previewGrammar(model, g);
        assertTrue(outcome.fieldIds.contains("condition"));
        assertEquals("Old", outcome.before.get(0).get("condition"));
        assertEquals("New", outcome.after.get(0).get("condition").value);
    }

    @Test
    public void previewCountsChangesAndConflicts() {
        ProjectManifestTableModel model = new ProjectManifestTableModel();
        model.addFile(new File("placeholder.tif"));   // no parseable identity
        // current region "Cortex" conflicts with the SCN the resolver finds below
        model.setValueAt("Cortex", 0, ProjectManifestTableModel.COL_REGION);

        // Resolve a *different* seed by previewing the model whose only row seed is
        // "placeholder.tif" — to force a detection, use a grammar that captures region.
        List<flash.pipeline.intelligence.identity.FieldRule> rules =
                new ArrayList<flash.pipeline.intelligence.identity.FieldRule>();
        rules.add(flash.pipeline.intelligence.identity.FieldRule.alias(
                flash.pipeline.intelligence.identity.FieldRule.Type.REGION, "",
                java.util.Arrays.asList(new flash.pipeline.intelligence.identity.ValuePattern(
                        "SCN", java.util.Collections.singletonList("placeholder")))));
        NamingGrammar g = new NamingGrammar("force", rules);

        PatternPreviewPanel.Outcome outcome = PatternPreviewPanel.previewGrammar(model, g);
        assertTrue(outcome.changes >= 1);
        assertTrue(outcome.conflicts >= 1);   // Cortex (current) vs SCN (detected)
    }
}
