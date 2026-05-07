package flash.pipeline.orientation;

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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class RoiOrientationManifestServiceTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void identityForContainerUsesContainerSourceFileAndOneBasedSeriesIndex() throws Exception {
        File dir = temp.newFolder("container");
        assertTrue(new File(dir, "Experiment.lif").createNewFile());

        OrientationImageIdentity identity = OrientationImageIdentity.fromProjectSeries(
                dir.getAbsolutePath(), 1, "Experiment.lif - Mouse1_LH_SCN");

        assertEquals("Experiment.lif", identity.sourceFile);
        assertEquals(2, identity.seriesIndex);
        assertEquals("Experiment.lif - Mouse1_LH_SCN", identity.originalName);
        assertEquals("Mouse1_LH_SCN", identity.displayName);
        assertEquals(
                OrientationManifestRow.buildImageKey(
                        "CONTAINER", "Experiment.lif", 2, "Experiment.lif - Mouse1_LH_SCN"),
                identity.imageKey);
    }

    @Test
    public void identityForTiffsUsesInputPrefixOnlyForInputSubfolder() throws Exception {
        File inputDir = temp.newFolder("input-project");
        File input = new File(inputDir, "input");
        assertTrue(input.mkdirs());
        assertTrue(new File(input, "B_Mouse_RH_SCN.tif").createNewFile());

        OrientationImageIdentity inputIdentity = OrientationImageIdentity.fromProjectSeries(
                inputDir.getAbsolutePath(), 0, "InputProject - B_Mouse_RH_SCN.tif");

        assertEquals("input/B_Mouse_RH_SCN.tif", inputIdentity.sourceFile);
        assertEquals(
                OrientationManifestRow.buildImageKey(
                        "TIFF",
                        "input/B_Mouse_RH_SCN.tif",
                        1,
                        "InputProject - B_Mouse_RH_SCN.tif"),
                inputIdentity.imageKey);

        File looseDir = temp.newFolder("loose-project");
        assertTrue(new File(looseDir, "A_Mouse_LH_SCN.tif").createNewFile());

        OrientationImageIdentity looseIdentity = OrientationImageIdentity.fromProjectSeries(
                looseDir.getAbsolutePath(), 0, "LooseProject - A_Mouse_LH_SCN.tif");

        assertEquals("A_Mouse_LH_SCN.tif", looseIdentity.sourceFile);
        assertEquals(
                OrientationManifestRow.buildImageKey(
                        "TIFF",
                        "A_Mouse_LH_SCN.tif",
                        1,
                        "LooseProject - A_Mouse_LH_SCN.tif"),
                looseIdentity.imageKey);
    }

    @Test
    public void upsertDecisionReplacesExistingImageKeyAndPreservesUnrelatedRows() throws Exception {
        File dir = temp.newFolder("upsert");
        assertTrue(new File(dir, "ImageA.tif").createNewFile());
        OrientationImageIdentity identity = OrientationImageIdentity.fromProjectSeries(
                dir.getAbsolutePath(), 0, "ImageA");
        OrientationManifestRow oldTarget = row(identity.imageKey, identity.sourceFile, "old target");
        OrientationManifestRow other = row("OTHER", "other.tif", "other row");
        OrientationManifestIO.saveRows(dir.getAbsolutePath(), Arrays.asList(oldTarget, other));

        RoiOrientationManifestService service =
                new RoiOrientationManifestService(dir.getAbsolutePath());
        OrientationManifestRow updated = service.upsertDecision(
                identity,
                fallbackMetadata("SeedMouse", "LH", "SCN"),
                OrientationTransformState.fromCsv("90", true, false),
                "ManualMouse",
                OrientationManifestRow.Hemisphere.RH,
                "PVN",
                "new target");

        assertEquals("ManualMouse", updated.animalName);
        assertEquals(OrientationManifestRow.Hemisphere.RH, updated.hemisphere);
        assertEquals("PVN", updated.region);
        assertEquals(OrientationManifestRow.RotationDegrees.DEG_90, updated.rotateDegrees);
        assertTrue(updated.flipHorizontal);
        assertFalse(updated.flipVertical);

        List<OrientationManifestRow> rows =
                OrientationManifestIO.read(OrientationManifestIO.getFile(dir.getAbsolutePath()));
        assertEquals(2, rows.size());
        assertEquals(identity.imageKey, rows.get(0).imageKey);
        assertEquals("new target", rows.get(0).notes);
        assertEquals("OTHER", rows.get(1).imageKey);
        assertEquals("other row", rows.get(1).notes);
    }

    @Test
    public void skippedImageCanSaveIdentityTransformAsConfirmedManualRow() throws Exception {
        File dir = temp.newFolder("skipped");
        assertTrue(new File(dir, "Skipped_Mouse_LH_SCN.tif").createNewFile());
        OrientationImageIdentity identity = OrientationImageIdentity.fromProjectSeries(
                dir.getAbsolutePath(), 0, "Skipped_Mouse_LH_SCN");

        RoiOrientationManifestService service =
                new RoiOrientationManifestService(dir.getAbsolutePath());
        OrientationManifestRow row = service.upsertDecision(
                identity,
                fallbackMetadata("SkippedMouse", "LH", "SCN"),
                OrientationTransformState.identity(),
                "",
                OrientationManifestRow.Hemisphere.UNKNOWN,
                "",
                "placeholder ROIs padded");

        assertEquals(OrientationManifestRow.RotationDegrees.DEG_0, row.rotateDegrees);
        assertFalse(row.flipHorizontal);
        assertFalse(row.flipVertical);
        assertEquals(OrientationManifestRow.ViewPolicy.MANUAL_ONLY, row.viewPolicy);
        assertEquals(OrientationManifestRow.DecisionSource.MANUAL, row.decisionSource);
        assertEquals(OrientationManifestRow.ConfirmationState.YES, row.confirmed);
        assertEquals("SkippedMouse", row.animalName);
        assertEquals(OrientationManifestRow.Hemisphere.LH, row.hemisphere);
    }

    @Test
    public void upsertWithoutStateFlattensStrictFilenameMetadataIntoManualOnlyRow() throws Exception {
        File dir = temp.newFolder("flatten");
        assertTrue(new File(dir, "ImageA.tif").createNewFile());
        String title = "Exp-Mouse_RH_SCN";
        OrientationImageIdentity identity = OrientationImageIdentity.fromProjectSeries(
                dir.getAbsolutePath(), 0, title);
        ResolvedImageMetadata seed =
                ImageOrientationResolver.resolve(dir.getAbsolutePath(), title, 1);

        RoiOrientationManifestService service =
                new RoiOrientationManifestService(dir.getAbsolutePath());
        OrientationManifestRow row = service.upsertDecision(
                identity,
                seed,
                null,
                "",
                OrientationManifestRow.Hemisphere.UNKNOWN,
                "",
                "flattened strict filename");

        assertEquals("Mouse", row.animalName);
        assertEquals(OrientationManifestRow.Hemisphere.RH, row.hemisphere);
        assertEquals("SCN", row.region);
        assertEquals(OrientationManifestRow.RotationDegrees.DEG_270, row.rotateDegrees);
        assertTrue(row.flipHorizontal);
        assertFalse(row.flipVertical);
        assertEquals(OrientationManifestRow.ViewPolicy.MANUAL_ONLY, row.viewPolicy);
        assertEquals(OrientationManifestRow.DecisionSource.MANUAL, row.decisionSource);
        assertEquals(OrientationManifestRow.ConfirmationState.YES, row.confirmed);
    }

    private static ResolvedImageMetadata fallbackMetadata(String animal,
                                                         String hemisphere,
                                                         String region) {
        return new ResolvedImageMetadata(
                "",
                "",
                animal,
                animal,
                hemisphere,
                region,
                OrientationManifestRow.RotationDegrees.DEG_0,
                false,
                false,
                OrientationManifestRow.ViewPolicy.MANUAL_ONLY,
                ResolvedImageMetadata.Source.FILENAME_FALLBACK);
    }

    private static OrientationManifestRow row(String key, String sourceFile, String notes) {
        return new OrientationManifestRow(
                key,
                sourceFile,
                1,
                "Original",
                "Display",
                "Animal",
                OrientationManifestRow.Hemisphere.LH,
                "SCN",
                OrientationManifestRow.RotationDegrees.DEG_0,
                false,
                false,
                OrientationManifestRow.ViewPolicy.MANUAL_ONLY,
                OrientationManifestRow.DecisionSource.MANUAL,
                OrientationManifestRow.ConfirmationState.YES,
                notes);
    }
}
