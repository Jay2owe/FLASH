package flash.pipeline.project;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/** Pure-logic tests for {@link ProjectRegionEditor#locate} and region helpers. */
public class ProjectRegionEditorTest {

    private static ProjectFile.Item tiffItem(String path, String region) {
        ProjectFile.Item item = new ProjectFile.Item();
        item.path = path;
        item.region = region;
        return item;
    }

    private static ProjectFile.Item containerItem(String path, int... seriesIndexes) {
        ProjectFile.Item item = new ProjectFile.Item();
        item.path = path;
        for (int idx : seriesIndexes) {
            ProjectFile.SeriesItem s = new ProjectFile.SeriesItem();
            s.index = idx;
            s.name = "Series" + idx;
            item.seriesMeta.add(s);
        }
        return item;
    }

    @Test
    public void tiffItem_regionLivesOnItem() {
        ProjectFile project = new ProjectFile();
        project.items.add(tiffItem("/data/SCN_img.tif", "SCN"));

        ProjectRegionEditor.Target t = ProjectRegionEditor.locate(project, "SCN_img.tif", 1);
        assertEquals("SCN", t.region());
        assertNull("no seriesMeta -> item-level", t.series);

        t.setRegion("Cortex");
        assertEquals("Cortex", project.items.get(0).region);
    }

    @Test
    public void tiffItem_matchesIgnoringFolderPrefixAndCase() {
        ProjectFile project = new ProjectFile();
        project.items.add(tiffItem("/data/Img001.TIF", "SCN"));
        // identity sourceFile may carry a prefix like "input/img001.tif"
        ProjectRegionEditor.Target t = ProjectRegionEditor.locate(project, "input/img001.tif", 1);
        assertEquals("SCN", t.region());
    }

    @Test
    public void containerItem_regionLivesOnMatchingSeries() {
        ProjectFile project = new ProjectFile();
        ProjectFile.Item lif = containerItem("/data/exp.lif", 0, 1, 2);
        lif.seriesMeta.get(1).region = "SCN"; // index 1 == 1-based series 2
        project.items.add(lif);

        ProjectRegionEditor.Target t = ProjectRegionEditor.locate(project, "exp.lif", 2);
        assertSame(lif.seriesMeta.get(1), t.series);
        assertEquals("SCN", t.region());

        t.setRegion("Hippocampus");
        assertEquals("Hippocampus", lif.seriesMeta.get(1).region);
    }

    @Test
    public void containerItem_partialInclusion_mapsByEnumerationPositionNotBioFormatsIndex() {
        // A LIF with only series 0, 2, 4 included (project order). The global enumeration index
        // (what the identity layer produces) must map to the Nth INCLUDED series, NOT the
        // SeriesItem whose Bio-Formats index equals N-1.
        ProjectFile project = new ProjectFile();
        ProjectFile.Item lif = new ProjectFile.Item();
        lif.path = "/data/exp.lif";
        for (int idx : new int[]{0, 2, 4}) {
            ProjectFile.SeriesItem s = new ProjectFile.SeriesItem();
            s.index = idx;
            s.region = "R" + idx;
            lif.seriesMeta.add(s);
            lif.series.add(Integer.valueOf(idx));
        }
        project.items.add(lif);

        // global enumeration index 2 -> 2nd included series -> Bio-Formats index 2 ("R2"),
        // which a naive index==1 match would have missed (it would wrongly pick index 1, absent).
        ProjectRegionEditor.Target t = ProjectRegionEditor.locate(project, "exp.lif", 2);
        assertSame(lif.seriesMeta.get(1), t.series);
        assertEquals("R2", t.region());

        // 3rd included series -> Bio-Formats index 4 ("R4").
        assertEquals("R4", ProjectRegionEditor.locate(project, "exp.lif", 3).region());
    }

    @Test
    public void containerItem_missingSeriesIndexReturnsNull() {
        ProjectFile project = new ProjectFile();
        project.items.add(containerItem("/data/exp.lif", 0, 1));
        // 1-based 5 -> zero-based 4, not present -> do not guess
        assertNull(ProjectRegionEditor.locate(project, "exp.lif", 5));
    }

    @Test
    public void ambiguousBasenameReturnsNull() {
        ProjectFile project = new ProjectFile();
        project.items.add(tiffItem("/a/img.tif", "SCN"));
        project.items.add(tiffItem("/b/img.tif", "Cortex"));
        assertNull(ProjectRegionEditor.locate(project, "img.tif", 1));
    }

    @Test
    public void noMatchReturnsNull() {
        ProjectFile project = new ProjectFile();
        project.items.add(tiffItem("/data/other.tif", "SCN"));
        assertNull(ProjectRegionEditor.locate(project, "missing.tif", 1));
        assertNull(ProjectRegionEditor.locate(null, "x.tif", 1));
        assertNull(ProjectRegionEditor.locate(project, null, 1));
    }

    @Test
    public void sameRegion_isCaseInsensitiveTrimmedNullSafe() {
        assertTrue(ProjectRegionEditor.sameRegion("SCN", " scn "));
        assertTrue(ProjectRegionEditor.sameRegion(null, ""));
        assertTrue(ProjectRegionEditor.sameRegion("  ", null));
        assertFalse(ProjectRegionEditor.sameRegion("SCN", "Cortex"));
        assertFalse(ProjectRegionEditor.sameRegion("SCN", ""));
    }
}
