package flash.pipeline.io;

import flash.pipeline.project.ProjectFile;
import flash.pipeline.project.ProjectFileIO;
import ij.IJ;
import ij.ImagePlus;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.PrintStream;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Discovery + error tests for {@link ImageSourceDispatcher}. These exercise
 * {@link ImageSourceDispatcher#detectMode(String)} and the file-listing
 * helpers using empty placeholder files, so they don't need a Bio-Formats
 * decode pass.
 */
public class ImageSourceDispatcherTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Before
    public void resetCalibrationWarningState() {
        ImageSourceDispatcher.clearCalibrationWarningThrottleForTests();
        ImageSourceDispatcher.clearContainerChoiceCacheForTests();
        ImageSourceDispatcher.setCalibrationWarningHeadlessOverrideForTests(Boolean.TRUE);
        ImageSourceDispatcher.setContainerChoiceOverrideForTests(null);
    }

    @After
    public void clearCalibrationWarningHeadlessOverride() {
        ImageSourceDispatcher.setCalibrationWarningHeadlessOverrideForTests(null);
        ImageSourceDispatcher.setContainerChoiceOverrideForTests(null);
        ImageSourceDispatcher.clearContainerChoiceCacheForTests();
    }

    @Test
    public void detectMode_singleLif_isContainer() throws Exception {
        File dir = temp.newFolder("single-lif");
        new File(dir, "experiment.lif").createNewFile();

        assertEquals(ImageSourceDispatcher.SourceMode.CONTAINER,
                ImageSourceDispatcher.detectMode(dir.getAbsolutePath()));
    }

    @Test
    public void detectMode_singleCzi_isContainer() throws Exception {
        File dir = temp.newFolder("single-czi");
        new File(dir, "experiment.czi").createNewFile();

        assertEquals(ImageSourceDispatcher.SourceMode.CONTAINER,
                ImageSourceDispatcher.detectMode(dir.getAbsolutePath()));
    }

    @Test
    public void detectMode_multipleContainers_isContainer() throws Exception {
        File dir = temp.newFolder("multi-containers");
        new File(dir, "alpha.lif").createNewFile();
        new File(dir, "beta.czi").createNewFile();

        assertEquals(ImageSourceDispatcher.SourceMode.CONTAINER,
                ImageSourceDispatcher.detectMode(dir.getAbsolutePath()));
    }

    @Test
    public void detectMode_containerPlusLooseTiff_isContainer() throws Exception {
        File dir = temp.newFolder("container-plus-tiff");
        new File(dir, "experiment.lif").createNewFile();
        new File(dir, "stray.tif").createNewFile();

        assertEquals(ImageSourceDispatcher.SourceMode.CONTAINER,
                ImageSourceDispatcher.detectMode(dir.getAbsolutePath()));
    }

    @Test
    public void detectMode_containerPlusInputTiff_isContainer() throws Exception {
        File dir = temp.newFolder("container-plus-sub-tiff");
        new File(dir, "experiment.lif").createNewFile();
        File sub = new File(dir, "input");
        assertTrue(sub.mkdirs());
        new File(sub, "stray.tif").createNewFile();

        assertEquals(ImageSourceDispatcher.SourceMode.CONTAINER,
                ImageSourceDispatcher.detectMode(dir.getAbsolutePath()));
    }

    @Test
    public void selectContainer_singleContainer_returnsIt() throws Exception {
        File dir = temp.newFolder("single-pick");
        File lif = new File(dir, "experiment.lif");
        lif.createNewFile();

        File picked = ImageSourceDispatcher.selectContainer(dir);
        assertEquals(lif.getAbsolutePath(), picked.getAbsolutePath());
    }

    @Test
    public void selectContainer_multipleContainers_headlessThrowsListingAll() throws Exception {
        File dir = temp.newFolder("multi-pick-headless");
        new File(dir, "alpha.lif").createNewFile();
        new File(dir, "beta.czi").createNewFile();

        try {
            ImageSourceDispatcher.selectContainer(dir);
            fail("Expected IllegalArgumentException in headless multi-container case");
        } catch (IllegalArgumentException e) {
            assertTrue("message should mention 'Multiple container'",
                    e.getMessage().contains("Multiple container"));
            assertTrue("message should list alpha.lif",
                    e.getMessage().contains("alpha.lif"));
            assertTrue("message should list beta.czi",
                    e.getMessage().contains("beta.czi"));
        }
    }

    @Test
    public void selectContainer_overrideHonouredAndCached() throws Exception {
        File dir = temp.newFolder("multi-pick-override");
        new File(dir, "alpha.lif").createNewFile();
        File beta = new File(dir, "beta.czi");
        beta.createNewFile();

        ImageSourceDispatcher.setContainerChoiceOverrideForTests("beta.czi");
        File first = ImageSourceDispatcher.selectContainer(dir);
        assertEquals(beta.getName(), first.getName());

        // Cached: second call must return the same file even after the override is cleared.
        ImageSourceDispatcher.setContainerChoiceOverrideForTests(null);
        File second = ImageSourceDispatcher.selectContainer(dir);
        assertEquals(beta.getName(), second.getName());
    }

    @Test
    public void detectMode_inputSubfolderWithTiffs_isInputSubfolder() throws Exception {
        File dir = temp.newFolder("input-sub");
        File sub = new File(dir, "input");
        assertTrue(sub.mkdirs());
        new File(sub, "alpha.tif").createNewFile();
        new File(sub, "beta.tiff").createNewFile();

        assertEquals(ImageSourceDispatcher.SourceMode.TIFF_INPUT_SUBFOLDER,
                ImageSourceDispatcher.detectMode(dir.getAbsolutePath()));
    }

    @Test
    public void detectMode_inputSubfolderEmpty_fallsBackToError() throws Exception {
        File dir = temp.newFolder("empty-input-sub");
        File sub = new File(dir, "input");
        assertTrue(sub.mkdirs());

        try {
            ImageSourceDispatcher.detectMode(dir.getAbsolutePath());
            fail("Expected IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            assertTrue("message should mention 'No compatible input'",
                    e.getMessage().contains("No compatible input"));
        }
    }

    @Test
    public void detectMode_looseTiffs_isLoose() throws Exception {
        File dir = temp.newFolder("loose-tiffs");
        new File(dir, "alpha.tif").createNewFile();
        new File(dir, "beta.tiff").createNewFile();

        assertEquals(ImageSourceDispatcher.SourceMode.TIFF_LOOSE,
                ImageSourceDispatcher.detectMode(dir.getAbsolutePath()));
    }

    @Test
    public void projectJsonWithTiffSources_usesManifestInsteadOfFolderScan() throws Exception {
        File outputRoot = temp.newFolder("manifest-tiff-output");
        File sourceRoot = temp.newFolder("manifest-tiff-sources");
        File alpha = new File(sourceRoot, "alpha.tif");
        File beta = new File(sourceRoot, "beta.tiff");
        assertTrue(alpha.createNewFile());
        assertTrue(beta.createNewFile());

        ProjectFile project = new ProjectFile();
        project.name = "Manifest TIFFs";
        project.outputRoot = outputRoot.getAbsolutePath();
        project.items.add(projectItem(alpha));
        project.items.add(projectItem(beta));
        writeProject(outputRoot, project);

        assertEquals(ImageSourceDispatcher.SourceMode.TIFF_LOOSE,
                ImageSourceDispatcher.detectMode(outputRoot.getAbsolutePath()));

        DeferredImageSupplier supplier =
                ImageSourceDispatcher.createSupplier(outputRoot.getAbsolutePath());
        assertEquals(DeferredImageSupplier.Mode.TIFF_FOLDER, supplier.getMode());
        assertEquals(2, supplier.getTotalSeries());
        assertEquals(alpha.getAbsolutePath(),
                supplier.getContainerFileForSeries(0).getAbsolutePath());
        assertEquals(beta.getAbsolutePath(),
                supplier.getContainerFileForSeries(1).getAbsolutePath());
    }

    @Test
    public void projectJsonMixingContainerAndTiff_throwsInsteadOfFallingBack() throws Exception {
        File outputRoot = temp.newFolder("manifest-mixed-output");
        File sourceRoot = temp.newFolder("manifest-mixed-sources");
        File lif = new File(sourceRoot, "alpha.lif");
        File tiff = new File(sourceRoot, "beta.tif");
        assertTrue(lif.createNewFile());
        assertTrue(tiff.createNewFile());

        ProjectFile project = new ProjectFile();
        project.items.add(projectItem(lif));
        project.items.add(projectItem(tiff));
        writeProject(outputRoot, project);

        try {
            ImageSourceDispatcher.detectMode(outputRoot.getAbsolutePath());
            fail("Expected mixed manifest to throw");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("mixes multi-series container files and bare TIFF files"));
        }
    }

    @Test
    public void projectJsonWithInvalidTiffSeries_throwsClearly() throws Exception {
        File outputRoot = temp.newFolder("manifest-invalid-tiff-output");
        File sourceRoot = temp.newFolder("manifest-invalid-tiff-sources");
        File tiff = new File(sourceRoot, "alpha.tif");
        assertTrue(tiff.createNewFile());

        ProjectFile project = new ProjectFile();
        ProjectFile.Item item = projectItem(tiff);
        item.series.addAll(Arrays.asList(Integer.valueOf(0), Integer.valueOf(1)));
        project.items.add(item);
        writeProject(outputRoot, project);

        try {
            ImageSourceDispatcher.detectMode(outputRoot.getAbsolutePath());
            fail("Expected invalid TIFF series to throw");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("single-series TIFF"));
        }
    }

    @Test
    public void detectMode_emptyDir_throws() throws Exception {
        File dir = temp.newFolder("empty");

        try {
            ImageSourceDispatcher.detectMode(dir.getAbsolutePath());
            fail("Expected IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            assertTrue("message should mention 'No compatible input'",
                    e.getMessage().contains("No compatible input"));
        }
    }

    @Test
    public void detectMode_subAndLoose_throwsMixedSources() throws Exception {
        File dir = temp.newFolder("sub-and-loose");
        File sub = new File(dir, "input");
        assertTrue(sub.mkdirs());
        new File(sub, "alpha.tif").createNewFile();
        new File(dir, "stray.tif").createNewFile();

        try {
            ImageSourceDispatcher.detectMode(dir.getAbsolutePath());
            fail("Expected IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            assertTrue("message should mention 'Mixed'",
                    e.getMessage().contains("Mixed"));
            assertTrue("message should list alpha.tif",
                    e.getMessage().contains("alpha.tif"));
            assertTrue("message should list stray.tif",
                    e.getMessage().contains("stray.tif"));
        }
    }

    @Test
    public void listContainers_isSortedCaseInsensitive() throws Exception {
        File dir = temp.newFolder("sort-containers");
        new File(dir, "Zebra.lif").createNewFile();
        new File(dir, "alpha.czi").createNewFile();
        new File(dir, "Beta.nd2").createNewFile();

        List<File> containers = ImageSourceDispatcher.listContainers(dir);
        assertEquals(3, containers.size());
        assertEquals("alpha.czi", containers.get(0).getName());
        assertEquals("Beta.nd2", containers.get(1).getName());
        assertEquals("Zebra.lif", containers.get(2).getName());
    }

    @Test
    public void listTiffs_isSortedCaseInsensitive() throws Exception {
        File dir = temp.newFolder("sort-tiffs");
        new File(dir, "Zebra.tif").createNewFile();
        new File(dir, "alpha.tiff").createNewFile();
        new File(dir, "Beta.tif").createNewFile();

        List<File> tiffs = ImageSourceDispatcher.listTiffs(dir);
        assertEquals(3, tiffs.size());
        assertEquals("alpha.tiff", tiffs.get(0).getName());
        assertEquals("Beta.tif", tiffs.get(1).getName());
        assertEquals("Zebra.tif", tiffs.get(2).getName());
    }

    @Test
    public void omeTiff_countsAsContainerNotTiff() throws Exception {
        File dir = temp.newFolder("ome-tiff");
        new File(dir, "experiment.ome.tif").createNewFile();
        new File(dir, "other.ome.tiff").createNewFile();

        // .ome.tif* files belong to the container list ...
        List<File> containers = ImageSourceDispatcher.listContainers(dir);
        assertEquals(2, containers.size());
        // ... and must NOT be reported as bare TIFFs.
        List<File> tiffs = ImageSourceDispatcher.listTiffs(dir);
        assertTrue("ome.tif should not be in TIFF list", tiffs.isEmpty());
    }

    @Test
    public void omeTiff_singleFile_detectsAsContainer() throws Exception {
        File dir = temp.newFolder("single-ome-tiff");
        new File(dir, "experiment.ome.tif").createNewFile();

        assertEquals(ImageSourceDispatcher.SourceMode.CONTAINER,
                ImageSourceDispatcher.detectMode(dir.getAbsolutePath()));
    }

    @Test
    public void multipleOmeTiff_isContainer() throws Exception {
        File dir = temp.newFolder("multi-ome-tiff");
        new File(dir, "a.ome.tif").createNewFile();
        new File(dir, "b.ome.tif").createNewFile();

        assertEquals(ImageSourceDispatcher.SourceMode.CONTAINER,
                ImageSourceDispatcher.detectMode(dir.getAbsolutePath()));
    }

    @Test
    public void detectMode_ignoresJunkFiles() throws Exception {
        File dir = temp.newFolder("junk-mixed");
        new File(dir, "experiment.lif").createNewFile();
        new File(dir, "._experiment.lif").createNewFile();
        new File(dir, "Thumbs.db").createNewFile();
        new File(dir, ".DS_Store").createNewFile();

        assertEquals(ImageSourceDispatcher.SourceMode.CONTAINER,
                ImageSourceDispatcher.detectMode(dir.getAbsolutePath()));
        List<File> containers = ImageSourceDispatcher.listContainers(dir);
        assertEquals(1, containers.size());
        assertEquals("experiment.lif", containers.get(0).getName());
    }

    @Test
    public void detectMode_nonExistentDir_throws() {
        try {
            ImageSourceDispatcher.detectMode(
                    new File(temp.getRoot(), "does-not-exist").getAbsolutePath());
            fail("Expected IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            assertTrue("message should mention 'Not a directory'",
                    e.getMessage().contains("Not a directory"));
        }
    }

    @Test
    public void maybeWarnUncalibrated_headlessLogsWarningForUncalibratedTiff() throws Exception {
        File dir = createProjectWithInputTiff("uncalibrated-warning");

        String added = captureLogOutput(new ThrowingRunnable() {
            @Override
            public void run() {
                ImageSourceDispatcher.maybeWarnUncalibrated(dir.getAbsolutePath());
            }
        });

        assertTrue("warning should mention missing physical calibration",
                added.contains("no physical calibration"));
    }

    @Test
    public void maybeWarnUncalibrated_projectRootMarkerDoesNotSuppressWarning() throws Exception {
        File dir = createProjectWithInputTiff("uncalibrated-suppressed");
        assertTrue(new File(dir,
                ImageSourceDispatcher.SUPPRESS_CALIBRATION_WARNING_MARKER).createNewFile());

        String added = captureLogOutput(new ThrowingRunnable() {
            @Override
            public void run() {
                ImageSourceDispatcher.maybeWarnUncalibrated(dir.getAbsolutePath());
            }
        });

        assertTrue("project-root marker should not suppress calibration warning",
                added.contains("no physical calibration"));
    }

    @Test
    public void maybeWarnUncalibrated_projectStatusMarkerSuppressesWarning() throws Exception {
        File dir = createProjectWithInputTiff("uncalibrated-suppressed-new");
        ProjectStatusStore.setMarker(dir,
                ImageSourceDispatcher.SUPPRESS_CALIBRATION_WARNING_MARKER, true);

        String added = captureLogOutput(new ThrowingRunnable() {
            @Override
            public void run() {
                ImageSourceDispatcher.maybeWarnUncalibrated(dir.getAbsolutePath());
            }
        });

        assertFalse("project status marker should suppress calibration warning",
                added.contains("no physical calibration"));
    }

    @Test
    public void maybeWarnUncalibrated_secondCallDoesNotRewarnSameSession() throws Exception {
        File dir = createProjectWithInputTiff("uncalibrated-throttle");

        String added = captureLogOutput(new ThrowingRunnable() {
            @Override
            public void run() {
                ImageSourceDispatcher.maybeWarnUncalibrated(dir.getAbsolutePath());
                ImageSourceDispatcher.maybeWarnUncalibrated(dir.getAbsolutePath());
            }
        });

        assertEquals("same directory should warn only once per JVM session",
                1, countOccurrences(added, "no physical calibration"));
    }

    private File createProjectWithInputTiff(String name) throws Exception {
        File dir = temp.newFolder(name);
        File input = new File(dir, "input");
        assertTrue(input.mkdirs());

        ImagePlus imp = IJ.createImage("uncalibrated", "8-bit ramp", 8, 6, 1);
        File target = new File(input, "uncalibrated.tif");
        IJ.saveAsTiff(imp, target.getAbsolutePath());
        imp.close();
        assertTrue("synthesised TIFF must exist", target.isFile() && target.length() > 0);
        return dir;
    }

    private static ProjectFile.Item projectItem(File source) {
        ProjectFile.Item item = new ProjectFile.Item();
        item.path = source.getAbsolutePath();
        item.include = true;
        return item;
    }

    private static void writeProject(File outputRoot, ProjectFile project) throws Exception {
        ProjectFileIO.write(
                FlashProjectLayout.forDirectory(outputRoot.getAbsolutePath()).configurationWriteDir(),
                project);
    }

    private static int countOccurrences(String text, String token) {
        int count = 0;
        int index = 0;
        while (text != null && token != null && token.length() > 0) {
            index = text.indexOf(token, index);
            if (index < 0) break;
            count++;
            index += token.length();
        }
        return count;
    }

    private static String captureLogOutput(ThrowingRunnable action) throws Exception {
        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        PrintStream capture = new PrintStream(bytes, true, "UTF-8");
        try {
            System.setOut(capture);
            System.setErr(capture);
            action.run();
        } finally {
            System.setOut(originalOut);
            System.setErr(originalErr);
            capture.close();
        }
        return bytes.toString("UTF-8");
    }

    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
