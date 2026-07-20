package flash.pipeline.intelligence;

import flash.pipeline.io.FlashProjectLayout;
import flash.pipeline.project.ProjectFile;
import flash.pipeline.project.ProjectFileIO;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.RandomAccessFile;
import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class PreFlightChecksDiskSpaceTest {
    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void projectDiskEstimateScalesContainerBySelectedSeries() throws Exception {
        File outputRoot = temp.newFolder("project");
        File sourceRoot = temp.newFolder("sources");
        File lif = sizedFile(new File(sourceRoot, "cohort.lif"), 10000L);

        ProjectFile project = new ProjectFile();
        project.outputRoot = outputRoot.getAbsolutePath();
        ProjectFile.Item item = new ProjectFile.Item();
        item.path = lif.getAbsolutePath();
        item.include = true;
        item.series.addAll(Arrays.asList(Integer.valueOf(1), Integer.valueOf(3), Integer.valueOf(8)));
        for (int i = 0; i < 10; i++) {
            item.seriesMeta.add(series(i));
        }
        project.items.add(item);
        writeProject(outputRoot, project);

        PreFlightChecks.DiskSpaceResult result =
                PreFlightChecks.checkDiskSpace(outputRoot.getAbsolutePath());

        assertTrue(result.projectScoped);
        assertEquals(3000L, result.inputBytes);
        assertEquals(12000L, result.estimatedOutputBytes);
    }

    @Test
    public void projectDiskEstimateIgnoresExcludedSources() throws Exception {
        File outputRoot = temp.newFolder("project-tiffs");
        File sourceRoot = temp.newFolder("tiff-sources");
        File included = sizedFile(new File(sourceRoot, "included.tif"), 2000L);
        File excluded = sizedFile(new File(sourceRoot, "excluded.tif"), 5000L);

        ProjectFile project = new ProjectFile();
        project.outputRoot = outputRoot.getAbsolutePath();
        project.items.add(tiffItem(included, true));
        project.items.add(tiffItem(excluded, false));
        writeProject(outputRoot, project);

        PreFlightChecks.DiskSpaceResult result =
                PreFlightChecks.checkDiskSpace(outputRoot.getAbsolutePath());

        assertTrue(result.projectScoped);
        assertEquals(2000L, result.inputBytes);
        assertEquals(8000L, result.estimatedOutputBytes);
    }

    private static ProjectFile.Item tiffItem(File file, boolean include) {
        ProjectFile.Item item = new ProjectFile.Item();
        item.path = file.getAbsolutePath();
        item.include = include;
        return item;
    }

    private static ProjectFile.SeriesItem series(int index) {
        ProjectFile.SeriesItem series = new ProjectFile.SeriesItem();
        series.index = index;
        series.include = true;
        series.name = "series-" + index;
        return series;
    }

    private static void writeProject(File outputRoot, ProjectFile project) throws Exception {
        File settingsDir = FlashProjectLayout.forDirectory(outputRoot.getAbsolutePath())
                .configurationWriteDir();
        assertTrue(settingsDir.mkdirs());
        ProjectFileIO.write(settingsDir, project);
    }

    private static File sizedFile(File file, long bytes) throws Exception {
        RandomAccessFile raf = new RandomAccessFile(file, "rw");
        try {
            raf.setLength(bytes);
        } finally {
            raf.close();
        }
        return file;
    }
}
