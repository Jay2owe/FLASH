package flash.pipeline.project;

import org.junit.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Covers the pure mapping from a {@link ProjectFile} to the
 * {@code animalId → condition} dictionary written to Conditions.csv.
 * Swing-free so it can run cleanly in CI.
 */
public class ProjectBuilderConditionAssignmentsTest {

    @Test
    public void nullProject_returnsEmptyMap() {
        LinkedHashMap<String, String> out = ProjectBuilderDialog.deriveConditionAssignments(null);
        assertNotNull(out);
        assertTrue(out.isEmpty());
    }

    @Test
    public void projectWithoutItems_returnsEmptyMap() {
        ProjectFile project = new ProjectFile();
        LinkedHashMap<String, String> out = ProjectBuilderDialog.deriveConditionAssignments(project);
        assertTrue(out.isEmpty());
    }

    @Test
    public void includesExplicitAnimalConditionPairs() {
        ProjectFile project = projectWith(
                item("Mouse3", "WT", true),
                item("Mouse7", "KO", true));

        LinkedHashMap<String, String> out = ProjectBuilderDialog.deriveConditionAssignments(project);

        assertEquals(2, out.size());
        assertEquals("WT", out.get("Mouse3"));
        assertEquals("KO", out.get("Mouse7"));
    }

    @Test
    public void omitsItemsWithBlankCondition() {
        // A blank condition means "use the auto-detection fallback in
        // ConditionManifestIO.resolveAssignments" — must NOT appear in the
        // explicit manifest.
        ProjectFile project = projectWith(
                item("Mouse3", "WT", true),
                item("Mouse4", "  ", true),
                item("Mouse5", null, true));

        LinkedHashMap<String, String> out = ProjectBuilderDialog.deriveConditionAssignments(project);

        assertEquals(1, out.size());
        assertTrue(out.containsKey("Mouse3"));
        assertFalse(out.containsKey("Mouse4"));
        assertFalse(out.containsKey("Mouse5"));
    }

    @Test
    public void omitsItemsWithBlankAnimal() {
        ProjectFile project = projectWith(
                item("", "WT", true),
                item(null, "KO", true),
                item("Mouse9", "WT", true));

        LinkedHashMap<String, String> out = ProjectBuilderDialog.deriveConditionAssignments(project);

        assertEquals(1, out.size());
        assertEquals("WT", out.get("Mouse9"));
    }

    @Test
    public void excludedItemsAreSkipped() {
        ProjectFile project = projectWith(
                item("Mouse3", "WT", false),
                item("Mouse7", "KO", true));

        LinkedHashMap<String, String> out = ProjectBuilderDialog.deriveConditionAssignments(project);

        assertEquals(1, out.size());
        assertFalse(out.containsKey("Mouse3"));
        assertEquals("KO", out.get("Mouse7"));
    }

    @Test
    public void duplicateAnimalIdLastWriteWins() {
        ProjectFile project = projectWith(
                item("Mouse3", "WT", true),
                item("Mouse3", "KO", true));

        LinkedHashMap<String, String> out = ProjectBuilderDialog.deriveConditionAssignments(project);

        assertEquals(1, out.size());
        assertEquals("KO", out.get("Mouse3"));
    }

    @Test
    public void trimsWhitespaceFromAnimalAndCondition() {
        ProjectFile project = projectWith(
                item("  Mouse3  ", "  WT  ", true));

        LinkedHashMap<String, String> out = ProjectBuilderDialog.deriveConditionAssignments(project);

        assertEquals(1, out.size());
        assertEquals("WT", out.get("Mouse3"));
    }

    @Test
    public void preservesInsertionOrder() {
        ProjectFile project = projectWith(
                item("Z", "WT", true),
                item("A", "KO", true),
                item("M", "WT", true));

        LinkedHashMap<String, String> out = ProjectBuilderDialog.deriveConditionAssignments(project);

        List<String> keys = new ArrayList<String>(out.keySet());
        assertEquals("Z", keys.get(0));
        assertEquals("A", keys.get(1));
        assertEquals("M", keys.get(2));
    }

    private static ProjectFile projectWith(ProjectFile.Item... items) {
        ProjectFile project = new ProjectFile();
        for (ProjectFile.Item item : items) {
            project.items.add(item);
        }
        return project;
    }

    private static ProjectFile.Item item(String animalId, String condition, boolean include) {
        ProjectFile.Item item = new ProjectFile.Item();
        item.path = "/dummy/" + animalId + ".lif";
        item.animalId = animalId;
        item.condition = condition;
        item.include = include;
        return item;
    }
}
