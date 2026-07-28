package flash.pipeline.project;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class SeriesIncludeRangeSelectionTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void shiftClickDeselectsThroughTargetUsingTargetsNewState() throws Exception {
        ProjectManifestTableModel model = modelWithSeries("slide.lif", 5);
        SeriesIncludeRangeSelection selection = new SeriesIncludeRangeSelection();

        assertFalse(selection.handlePress(model, 2, false));
        model.setValueAt(Boolean.FALSE, 2, ProjectManifestTableModel.COL_INCLUDE);

        assertTrue(selection.handlePress(model, 5, true));
        assertIncluded(model, 1, true);
        for (int row = 2; row <= 5; row++) assertIncluded(model, row, false);
    }

    @Test
    public void shiftClickSelectsThroughTargetUsingTargetsNewState() throws Exception {
        ProjectManifestTableModel model = modelWithSeries("slide.lif", 5);
        model.setValueAt(Boolean.FALSE, 0, ProjectManifestTableModel.COL_INCLUDE);
        SeriesIncludeRangeSelection selection = new SeriesIncludeRangeSelection();

        assertFalse(selection.handlePress(model, 2, false));
        model.setValueAt(Boolean.TRUE, 2, ProjectManifestTableModel.COL_INCLUDE);

        assertTrue(selection.handlePress(model, 5, true));
        assertIncluded(model, 1, false);
        for (int row = 2; row <= 5; row++) assertIncluded(model, row, true);
    }

    @Test
    public void shiftClickAcrossContainersFallsBackToNormalToggle() throws Exception {
        ProjectManifestTableModel model = modelWithSeries("first.lif", 2);
        int second = model.addFile(touch("second.lif"));
        model.setSeriesEntries(second, entries(2));
        model.setExpanded(3, true);
        SeriesIncludeRangeSelection selection = new SeriesIncludeRangeSelection();

        assertFalse(selection.handlePress(model, 1, false));
        assertFalse(selection.handlePress(model, 4, true));
    }

    private ProjectManifestTableModel modelWithSeries(String fileName, int count) throws Exception {
        ProjectManifestTableModel model = new ProjectManifestTableModel();
        int fileIndex = model.addFile(touch(fileName));
        model.setSeriesEntries(fileIndex, entries(count));
        model.setExpanded(0, true);
        return model;
    }

    private List<ProjectManifestTableModel.SeriesEntry> entries(int count) {
        List<ProjectManifestTableModel.SeriesEntry> entries =
                new ArrayList<ProjectManifestTableModel.SeriesEntry>();
        for (int series = 0; series < count; series++) {
            entries.add(new ProjectManifestTableModel.SeriesEntry(series, "s" + series));
        }
        return entries;
    }

    private File touch(String name) throws Exception {
        File file = new File(temp.getRoot(), name);
        assertTrue(file.createNewFile());
        return file;
    }

    private static void assertIncluded(ProjectManifestTableModel model, int row, boolean included) {
        if (included) {
            assertTrue(Boolean.TRUE.equals(
                    model.getValueAt(row, ProjectManifestTableModel.COL_INCLUDE)));
        } else {
            assertFalse(Boolean.TRUE.equals(
                    model.getValueAt(row, ProjectManifestTableModel.COL_INCLUDE)));
        }
    }
}
