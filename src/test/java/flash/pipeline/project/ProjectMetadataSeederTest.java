package flash.pipeline.project;

import flash.pipeline.io.OrientationManifestIO;
import flash.pipeline.naming.ImageOrientationResolver;
import flash.pipeline.naming.OrientationManifestRow;
import flash.pipeline.naming.ResolvedImageMetadata;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

/**
 * Proves the "full wiring": per-series identity assigned in the Project Builder
 * is written to {@code Image Orientation.csv} with the exact image key the
 * analysis pipeline looks up, so {@link ImageOrientationResolver} returns those
 * values instead of re-parsing the series name.
 */
public class ProjectMetadataSeederTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void orientationRowsFor_buildsConfirmedRowsWithMatchingKey() {
        ProjectFile project = projectWithContainer("slide.lif",
                series(0, "Mouse3_LH_CA1", "Mouse3", "LH", "CA1", "WT", true),
                series(1, "Mouse4_RH_DG", "Mouse4", "RH", "DG", "KO", true),
                series(2, "skip-me", "Mouse9", "LH", "DG", "WT", false));

        List<OrientationManifestRow> rows = ProjectMetadataSeeder.orientationRowsFor(project);

        // The excluded series is not emitted.
        assertEquals(2, rows.size());
        OrientationManifestRow row0 = rows.get(0);
        assertEquals(OrientationManifestRow.buildImageKey("CONTAINER", "slide.lif", 1, "Mouse3_LH_CA1"),
                row0.imageKey);
        assertEquals("slide.lif", row0.sourceFile);
        assertEquals(1, row0.seriesIndex);
        assertEquals("Mouse3_LH_CA1", row0.originalName);
        assertEquals("Mouse3", row0.animalName);
        assertEquals(OrientationManifestRow.Hemisphere.LH, row0.hemisphere);
        assertEquals("CA1", row0.region);
        assertTrue(row0.isConfirmed());
        assertEquals(OrientationManifestRow.DecisionSource.MANUAL, row0.decisionSource);
    }

    @Test
    public void seededManifestIsHonouredByResolver() throws Exception {
        File root = temp.newFolder("proj");
        String dir = root.getAbsolutePath();
        ProjectFile project = projectWithContainer("slide.lif",
                series(0, "Mouse3_LH_CA1", "Mouse3", "LH", "CA1", "WT", true),
                series(1, "Mouse4_RH_DG", "Mouse4", "RH", "DG", "KO", true));

        ProjectMetadataSeeder.seedOrientationManifest(root, project);

        ResolvedImageMetadata first = ImageOrientationResolver.resolve(dir, "Mouse3_LH_CA1", 1);
        assertEquals(ResolvedImageMetadata.Source.SAVED_MANIFEST, first.source);
        assertEquals("Mouse3", first.animalName);
        assertEquals("LH", first.hemisphere);
        assertEquals("CA1", first.region);

        ResolvedImageMetadata second = ImageOrientationResolver.resolve(dir, "Mouse4_RH_DG", 2);
        assertEquals("Mouse4", second.animalName);
        assertEquals("RH", second.hemisphere);
        assertEquals("DG", second.region);
    }

    @Test
    public void seriesWithoutHemisphereFallsBackToNameParsing() throws Exception {
        // Documents the resolver gate: a row with no LH/RH hemisphere and no
        // transform is not "usable", so identity reverts to the series name.
        File root = temp.newFolder("proj");
        String dir = root.getAbsolutePath();
        ProjectFile project = projectWithContainer("slide.lif",
                series(0, "plain-series", "Mouse5", "", "DG", "WT", true));

        ProjectMetadataSeeder.seedOrientationManifest(root, project);

        ResolvedImageMetadata resolved = ImageOrientationResolver.resolve(dir, "plain-series", 1);
        assertNotEquals(ResolvedImageMetadata.Source.SAVED_MANIFEST, resolved.source);
        assertEquals("", resolved.region);
    }

    @Test
    public void seedingPreservesUnrelatedManifestRows() throws Exception {
        File root = temp.newFolder("proj");
        String dir = root.getAbsolutePath();
        OrientationManifestRow existing = new OrientationManifestRow(
                OrientationManifestRow.buildImageKey("CONTAINER", "other.lif", 1, "Old_LH_X"),
                "other.lif", 1, "Old_LH_X", "Old_LH_X", "OldAnimal",
                OrientationManifestRow.Hemisphere.LH, "X",
                OrientationManifestRow.RotationDegrees.DEG_0, false, false,
                OrientationManifestRow.ViewPolicy.MANUAL_ONLY,
                OrientationManifestRow.DecisionSource.MANUAL,
                OrientationManifestRow.ConfirmationState.YES, "prior");
        OrientationManifestIO.saveRows(dir, Arrays.asList(existing));

        ProjectFile project = projectWithContainer("slide.lif",
                series(0, "Mouse3_LH_CA1", "Mouse3", "LH", "CA1", "WT", true));
        ProjectMetadataSeeder.seedOrientationManifest(root, project);

        Map<String, OrientationManifestRow> byKey =
                OrientationManifestIO.readByImageKeyIfExists(dir);
        assertTrue(byKey.containsKey(existing.imageKey));
        assertTrue(byKey.containsKey(
                OrientationManifestRow.buildImageKey("CONTAINER", "slide.lif", 1, "Mouse3_LH_CA1")));
    }

    @Test
    public void bareTiffAndFileLevelOnlyItemsAreNotSeeded() {
        ProjectFile project = new ProjectFile();
        // A bare TIFF item with seriesMeta is out of scope (single series).
        ProjectFile.Item tiff = new ProjectFile.Item();
        tiff.path = "D:/data/section.tif";
        tiff.seriesMeta.add(series(0, "section", "Mouse1", "LH", "CA1", "WT", true));
        project.items.add(tiff);
        // A container item with no per-series rows contributes nothing.
        ProjectFile.Item unexpanded = new ProjectFile.Item();
        unexpanded.path = "D:/data/whole.lif";
        unexpanded.animalId = "Mouse2";
        unexpanded.hemisphere = "RH";
        project.items.add(unexpanded);

        assertTrue(ProjectMetadataSeeder.orientationRowsFor(project).isEmpty());
    }

    @Test
    public void excludedItemContributesNoRows() {
        ProjectFile project = projectWithContainer("slide.lif",
                series(0, "Mouse3_LH_CA1", "Mouse3", "LH", "CA1", "WT", true));
        project.items.get(0).include = false;

        assertTrue(ProjectMetadataSeeder.orientationRowsFor(project).isEmpty());
    }

    private static ProjectFile projectWithContainer(String fileName, ProjectFile.SeriesItem... series) {
        ProjectFile project = new ProjectFile();
        ProjectFile.Item item = new ProjectFile.Item();
        item.path = "D:/raw/" + fileName;
        item.include = true;
        for (ProjectFile.SeriesItem s : series) {
            item.seriesMeta.add(s);
        }
        project.items.add(item);
        return project;
    }

    private static ProjectFile.SeriesItem series(int index, String name, String animal,
                                                 String hemisphere, String region,
                                                 String condition, boolean include) {
        ProjectFile.SeriesItem s = new ProjectFile.SeriesItem();
        s.index = index;
        s.name = name;
        s.animalId = animal;
        s.hemisphere = hemisphere;
        s.region = region;
        s.condition = condition;
        s.include = include;
        return s;
    }
}
