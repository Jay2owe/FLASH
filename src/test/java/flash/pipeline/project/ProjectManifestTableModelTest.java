package flash.pipeline.project;

import flash.pipeline.ui.wizard.RegionTableCellEditor;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import javax.swing.JTable;
import javax.swing.JTextField;
import java.io.File;
import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ProjectManifestTableModelTest {
    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void addFile_parsesNameAndPrefillsMetadata() throws Exception {
        ProjectManifestTableModel model = new ProjectManifestTableModel();
        File source = touch("MyExp-Mouse5_LH_Cortex_WT.lif");

        int idx = model.addFile(source);

        ProjectManifestTableModel.Row row = model.get(idx);
        assertEquals("Mouse5", row.animalId);
        assertEquals("LH", row.hemisphere);
        assertEquals("Cortex", row.region);
        assertEquals("WT", row.condition);
        assertTrue(row.include);
    }

    @Test
    public void addFile_conditionFallsBackToParentFolder() throws Exception {
        ProjectManifestTableModel model = new ProjectManifestTableModel();
        File wtDir = temp.newFolder("WT");
        File source = new File(wtDir, "MyExp-Mouse5_LH_Cortex.lif");
        assertTrue(source.createNewFile());

        int idx = model.addFile(source);

        assertEquals("WT", model.get(idx).condition);
    }

    @Test
    public void addFile_conventionTokenWinsOverParentFolder() throws Exception {
        ProjectManifestTableModel model = new ProjectManifestTableModel();
        File koDir = temp.newFolder("KO");
        File source = new File(koDir, "MyExp-Mouse5_LH_Cortex_WT.lif");
        assertTrue(source.createNewFile());

        int idx = model.addFile(source);

        // Filename token wins; parent folder fallback only applies when the
        // convention itself does not embed a condition.
        assertEquals("WT", model.get(idx).condition);
    }

    @Test
    public void addFile_nonConformingNameLeavesMetadataBlank() throws Exception {
        ProjectManifestTableModel model = new ProjectManifestTableModel();
        File source = touch("random_image.tif");

        int idx = model.addFile(source);

        ProjectManifestTableModel.Row row = model.get(idx);
        assertEquals("", row.hemisphere);
        assertEquals("", row.region);
    }

    @Test
    public void containsSource_detectsDuplicates() throws Exception {
        ProjectManifestTableModel model = new ProjectManifestTableModel();
        File source = touch("Exp-A_LH_X.lif");

        model.addFile(source);

        assertTrue(model.containsSource(source));
        assertFalse(model.containsSource(touch("Other-B_RH_Y.lif")));
    }

    @Test
    public void seriesDisplay_reflectsCountAndSelection() throws Exception {
        ProjectManifestTableModel model = new ProjectManifestTableModel();
        File source = touch("Exp-A_LH_X.lif");
        int idx = model.addFile(source);

        assertEquals("", model.getValueAt(idx, ProjectManifestTableModel.COL_SERIES));

        model.setSeriesCount(idx, 4);
        assertEquals("all (4)", model.getValueAt(idx, ProjectManifestTableModel.COL_SERIES));

        model.setSelectedSeries(idx, Arrays.asList(Integer.valueOf(0), Integer.valueOf(2)));
        assertEquals("2 of 4", model.getValueAt(idx, ProjectManifestTableModel.COL_SERIES));

        model.setSeriesCount(idx, 1);
        assertEquals("1", model.getValueAt(idx, ProjectManifestTableModel.COL_SERIES));
    }

    @Test
    public void setValueAt_persistsEditsAndFiresUpdate() throws Exception {
        ProjectManifestTableModel model = new ProjectManifestTableModel();
        model.addFile(touch("Exp-A_LH_X.lif"));

        model.setValueAt("renamed", 0, ProjectManifestTableModel.COL_ANIMAL);
        model.setValueAt(Boolean.FALSE, 0, ProjectManifestTableModel.COL_INCLUDE);

        ProjectManifestTableModel.Row row = model.get(0);
        assertEquals("renamed", row.animalId);
        assertFalse(row.include);
    }

    @Test
    public void regionTableEditorCommitsCanonicalAtlasTextToModel() throws Exception {
        ProjectManifestTableModel model = new ProjectManifestTableModel();
        model.addFile(touch("Exp-A_LH_X.lif"));
        JTable table = new JTable(model);
        table.getColumnModel().getColumn(ProjectManifestTableModel.COL_REGION)
                .setCellEditor(new RegionTableCellEditor());

        assertTrue(table.editCellAt(0, ProjectManifestTableModel.COL_REGION));
        JTextField editor = (JTextField) table.getEditorComponent();
        editor.setText("286");
        assertTrue(table.getCellEditor().stopCellEditing());

        assertEquals("SCH", model.get(0).region);
    }

    @Test
    public void isCellEditable_columnRules() throws Exception {
        ProjectManifestTableModel model = new ProjectManifestTableModel();
        model.addFile(touch("Exp-A_LH_X.lif"));

        assertTrue(model.isCellEditable(0, ProjectManifestTableModel.COL_INCLUDE));
        assertTrue(model.isCellEditable(0, ProjectManifestTableModel.COL_ANIMAL));
        assertTrue(model.isCellEditable(0, ProjectManifestTableModel.COL_CONDITION));
        assertFalse(model.isCellEditable(0, ProjectManifestTableModel.COL_FILE));
        assertFalse(model.isCellEditable(0, ProjectManifestTableModel.COL_SERIES));
    }

    @Test
    public void setConditionForRows_bulkAssign() throws Exception {
        ProjectManifestTableModel model = new ProjectManifestTableModel();
        model.addFile(touch("Exp-A_LH_X.lif"));
        model.addFile(touch("Exp-B_RH_Y.lif"));
        model.addFile(touch("Exp-C_LH_Z.lif"));
        String row1Original = model.get(1).condition;

        model.setConditionForRows(new int[]{0, 2}, "KO");

        assertEquals("KO", model.get(0).condition);
        // Bulk-assign must not touch rows outside the index array.
        assertEquals(row1Original, model.get(1).condition);
        assertEquals("KO", model.get(2).condition);
    }

    @Test
    public void toProjectFile_writesAllRowsAndMetadata() throws Exception {
        ProjectManifestTableModel model = new ProjectManifestTableModel();
        int idx = model.addFile(touch("Exp-A_LH_X_WT.lif"));
        model.setSeriesCount(idx, 4);
        model.setSelectedSeries(idx, Arrays.asList(Integer.valueOf(0), Integer.valueOf(2)));
        model.setValueAt("note", idx, ProjectManifestTableModel.COL_NOTES);

        ProjectFile project = model.toProjectFile("Cohort A", "D:/out", "FLASH-test");

        assertEquals("Cohort A", project.name);
        assertEquals("D:/out", project.outputRoot);
        assertEquals("FLASH-test", project.writerId);
        assertEquals(1, project.items.size());
        ProjectFile.Item item = project.items.get(0);
        assertEquals("A", item.animalId);
        assertEquals("LH", item.hemisphere);
        assertEquals("X", item.region);
        assertEquals("WT", item.condition);
        assertEquals("note", item.notes);
        assertEquals(Arrays.asList(Integer.valueOf(0), Integer.valueOf(2)), item.series);
    }

    @Test
    public void loadFromProjectFile_restoresAllFields() throws Exception {
        ProjectFile project = new ProjectFile();
        ProjectFile.Item item = new ProjectFile.Item();
        item.path = touch("Exp-A_LH_X.lif").getAbsolutePath();
        item.include = false;
        item.animalId = "A";
        item.hemisphere = "LH";
        item.region = "X";
        item.condition = "KO";
        item.notes = "n";
        item.series.addAll(Arrays.asList(Integer.valueOf(1)));
        project.items.add(item);

        ProjectManifestTableModel model = new ProjectManifestTableModel();
        model.loadFromProjectFile(project);

        assertEquals(1, model.getRowCount());
        ProjectManifestTableModel.Row row = model.get(0);
        assertFalse(row.include);
        assertEquals("KO", row.condition);
        assertEquals(Arrays.asList(Integer.valueOf(1)), row.selectedSeries);
    }

    @Test
    public void removeRow_removesAndShiftsIndexes() throws Exception {
        ProjectManifestTableModel model = new ProjectManifestTableModel();
        model.addFile(touch("Exp-A_LH_X.lif"));
        model.addFile(touch("Exp-B_RH_Y.lif"));

        model.removeRow(0);

        assertEquals(1, model.getRowCount());
        assertEquals("B", model.get(0).animalId);
    }

    private File touch(String name) throws Exception {
        File file = new File(temp.getRoot(), name);
        if (!file.exists()) {
            assertTrue(file.createNewFile());
        }
        return file;
    }
}
