package flash.pipeline.project;

import org.junit.Test;

import java.io.File;

import static org.junit.Assert.assertEquals;

/** Stage 12: fill-down / fill-blanks / apply-to-same-animal. */
public class ProjectManifestTableModelFillTest {

    private static ProjectManifestTableModel threeFiles() {
        ProjectManifestTableModel model = new ProjectManifestTableModel();
        model.addFile(new File("a.tif"));
        model.addFile(new File("b.tif"));
        model.addFile(new File("c.tif"));
        return model;
    }

    @Test
    public void fillDown_copiesTopCellToSelection() {
        ProjectManifestTableModel model = threeFiles();
        model.setValueAt("Cortex", 0, ProjectManifestTableModel.COL_REGION);

        model.fillDown(new int[]{0, 1, 2}, ProjectManifestTableModel.COL_REGION);

        assertEquals("Cortex", model.getValueAt(0, ProjectManifestTableModel.COL_REGION));
        assertEquals("Cortex", model.getValueAt(1, ProjectManifestTableModel.COL_REGION));
        assertEquals("Cortex", model.getValueAt(2, ProjectManifestTableModel.COL_REGION));
    }

    @Test
    public void fillBlanks_onlyFillsEmptyCells() {
        ProjectManifestTableModel model = threeFiles();
        model.setValueAt("SCN", 0, ProjectManifestTableModel.COL_REGION);
        model.setValueAt("PVN", 1, ProjectManifestTableModel.COL_REGION);
        // row 2 left blank

        model.fillBlanks(new int[]{0, 1, 2}, ProjectManifestTableModel.COL_REGION);

        assertEquals("SCN", model.getValueAt(0, ProjectManifestTableModel.COL_REGION));
        assertEquals("PVN", model.getValueAt(1, ProjectManifestTableModel.COL_REGION)); // not clobbered
        assertEquals("PVN", model.getValueAt(2, ProjectManifestTableModel.COL_REGION)); // filled from above
    }

    @Test
    public void applyToSameAnimal_propagatesByAnimalName() {
        ProjectManifestTableModel model = threeFiles();
        // rows 0 & 2 are the same animal M1; row 1 is M2
        model.setValueAt("M1", 0, ProjectManifestTableModel.COL_ANIMAL);
        model.setValueAt("M2", 1, ProjectManifestTableModel.COL_ANIMAL);
        model.setValueAt("M1", 2, ProjectManifestTableModel.COL_ANIMAL);
        model.setValueAt("WT", 0, ProjectManifestTableModel.COL_CONDITION);

        model.applyToSameAnimal(new int[]{0}, ProjectManifestTableModel.COL_CONDITION);

        assertEquals("WT", model.getValueAt(0, ProjectManifestTableModel.COL_CONDITION));
        assertEquals("WT", model.getValueAt(2, ProjectManifestTableModel.COL_CONDITION)); // same animal M1
        assertEquals("", model.getValueAt(1, ProjectManifestTableModel.COL_CONDITION));   // different animal
    }

    @Test
    public void applyToSameAnimal_skipsConfirmedCellsOutsideSelection() {
        ProjectManifestTableModel model = threeFiles();
        model.setValueAt("M1", 0, ProjectManifestTableModel.COL_ANIMAL);
        model.setValueAt("M1", 1, ProjectManifestTableModel.COL_ANIMAL);
        model.setValueAt("WT", 0, ProjectManifestTableModel.COL_CONDITION);
        model.setValueAt("KO", 1, ProjectManifestTableModel.COL_CONDITION); // user-confirmed on row 1

        model.applyToSameAnimal(new int[]{0}, ProjectManifestTableModel.COL_CONDITION);

        assertEquals("WT", model.getValueAt(0, ProjectManifestTableModel.COL_CONDITION));
        assertEquals("KO", model.getValueAt(1, ProjectManifestTableModel.COL_CONDITION)); // confirmed, not clobbered
    }
}
