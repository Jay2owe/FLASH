package flash.pipeline.analyses;

import flash.pipeline.io.DeferredImageSupplier;
import flash.pipeline.naming.NameParts;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Tests for the pre-flight skip-existing worklist builder in
 * {@link SplitAndMergeImageChannelsAnalysis}.
 * <p>
 * These tests exercise the static helper directly, without loading
 * any images, to verify that output-existence checks correctly
 * filter the worklist.
 */
public class SplitAndMergeSkipExistingTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    // ── buildPreflightWorklist ──

    @Test
    public void buildWorklist_includesAllWhenNoOutputsExist() throws Exception {
        File outRoot = temp.newFolder("Images");

        // No DeferredImageSupplier available — pass null supplier with totalImages=3.
        // When metadata reading fails (no .lif file), all series are included.
        List<Integer> work = SplitAndMergeImageChannelsAnalysis.buildPreflightWorklist(
                temp.getRoot().getAbsolutePath(), null, 3,
                new String[]{"DAPI", "NeuN"}, outRoot);

        assertEquals(3, work.size());
        assertEquals(Integer.valueOf(0), work.get(0));
        assertEquals(Integer.valueOf(1), work.get(1));
        assertEquals(Integer.valueOf(2), work.get(2));
    }

    @Test
    public void buildWorklist_includesAllWhenSupplierIsNull() throws Exception {
        File outRoot = temp.newFolder("Images");

        // null supplier → metadata read fails → all series included
        List<Integer> work = SplitAndMergeImageChannelsAnalysis.buildPreflightWorklist(
                temp.getRoot().getAbsolutePath(), null, 5,
                new String[]{"DAPI"}, outRoot);

        assertEquals(5, work.size());
    }

    @Test
    public void buildWorklist_returnsEmptyListWhenTotalIsZero() throws Exception {
        File outRoot = temp.newFolder("Images");

        List<Integer> work = SplitAndMergeImageChannelsAnalysis.buildPreflightWorklist(
                temp.getRoot().getAbsolutePath(), null, 0,
                new String[]{"DAPI"}, outRoot);

        assertTrue(work.isEmpty());
    }

    @Test
    public void skipExistingDetectsImageInsidePresentationImagesDir() throws Exception {
        File project = temp.newFolder("newProject");
        File newOutRoot = SplitAndMergeImageChannelsAnalysis.splitMergeImageWriteRoot(project.getAbsolutePath());
        NameParts parts = new NameParts("Experiment", "Animal1", "RH", "PVN");

        File newOutput = new File(new File(newOutRoot, "Animal1"), "DAPI_RH_PVN.png");
        assertTrue(newOutput.getParentFile().mkdirs());
        Files.write(newOutput.toPath(), "new".getBytes(StandardCharsets.UTF_8));

        assertTrue(SplitAndMergeImageChannelsAnalysis.splitMergePrimaryChannelOutputExists(
                project.getAbsolutePath(), newOutRoot, parts, "DAPI"));
    }

    @Test
    public void skipExistingReturnsFalseWhenNoOutputUnderPresentationImagesDir() throws Exception {
        File project = temp.newFolder("missingProject");
        File newOutRoot = SplitAndMergeImageChannelsAnalysis.splitMergeImageWriteRoot(project.getAbsolutePath());
        NameParts parts = new NameParts("Experiment", "Animal1", "LH", "SCN");

        assertFalse(SplitAndMergeImageChannelsAnalysis.splitMergePrimaryChannelOutputExists(
                project.getAbsolutePath(), newOutRoot, parts, "DAPI"));
    }

    @Test
    public void skipExistingIgnoresImageAtProjectRoot() throws Exception {
        File project = temp.newFolder("projectWithLegacyImage");
        File newOutRoot = SplitAndMergeImageChannelsAnalysis.splitMergeImageWriteRoot(project.getAbsolutePath());
        NameParts parts = new NameParts("Experiment", "Animal1", "LH", "SCN");

        File rootImage = new File(new File(new File(project, "Images"), "Animal1"), "DAPI_LH_SCN.png");
        assertTrue(rootImage.getParentFile().mkdirs());
        Files.write(rootImage.toPath(), "legacy".getBytes(StandardCharsets.UTF_8));

        assertFalse(SplitAndMergeImageChannelsAnalysis.splitMergePrimaryChannelOutputExists(
                project.getAbsolutePath(), newOutRoot, parts, "DAPI"));
    }
}
