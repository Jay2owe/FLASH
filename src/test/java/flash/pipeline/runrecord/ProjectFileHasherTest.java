package flash.pipeline.runrecord;

import flash.pipeline.project.ProjectFile;
import flash.pipeline.project.ProjectFileCodec;
import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

public class ProjectFileHasherTest {

    private static ProjectFile example() {
        ProjectFile project = new ProjectFile();
        project.name = "Cohort A";
        project.outputRoot = "D:/out";
        ProjectFile.Item item = new ProjectFile.Item();
        item.path = "D:/raw/Exp1-03_LH_Hb.lif";
        item.series.addAll(Arrays.asList(Integer.valueOf(0), Integer.valueOf(1)));
        item.animalId = "03";
        item.condition = "WT";
        project.items.add(item);
        return project;
    }

    @Test
    public void sameProjectSameHash() {
        assertEquals(ProjectFileHasher.hash(example()), ProjectFileHasher.hash(example()));
    }

    @Test
    public void codecRoundTripPreservesHash() throws Exception {
        ProjectFile project = example();
        String original = ProjectFileHasher.hash(project);
        ProjectFile roundTripped = ProjectFileCodec.decode(ProjectFileCodec.encode(project));
        assertEquals(original, ProjectFileHasher.hash(roundTripped));
    }

    @Test
    public void changedItemChangesHash() {
        ProjectFile a = example();
        ProjectFile b = example();
        b.items.get(0).condition = "KO";
        assertNotEquals(ProjectFileHasher.hash(a), ProjectFileHasher.hash(b));
    }

    @Test
    public void nullProjectHashesToEmpty() {
        assertEquals("", ProjectFileHasher.hash(null));
    }

    @Test
    public void hashIsNonEmptyForRealProject() {
        assertTrue(ProjectFileHasher.hash(example()).length() > 0);
    }
}
