package flash.pipeline.naming;

import flash.pipeline.io.OrientationManifestIO;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ImageOrientationResolverTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void resolve_noManifestPreservesStrictFilenameHemisphere() throws Exception {
        File dir = temp.newFolder("project");

        ResolvedImageMetadata metadata = ImageOrientationResolver.resolve(
                dir.getAbsolutePath(), "Exp-Mouse_RH_SCN", 1);

        assertEquals(ResolvedImageMetadata.Source.STRICT_FILENAME, metadata.source);
        assertEquals("Mouse", metadata.animalName);
        assertEquals("RH", metadata.hemisphere);
        assertEquals("SCN", metadata.region);
        assertEquals(OrientationManifestRow.RotationDegrees.DEG_270, metadata.rotateDegrees);
        assertTrue(metadata.flipHorizontal);
    }

    @Test
    public void resolve_confirmedManifestWinsOverFilename() throws Exception {
        File dir = temp.newFolder("project");
        String title = "Exp-FilenameMouse_LH_SCN";
        OrientationManifestRow row = new OrientationManifestRow(
                OrientationManifestRow.buildImageKey("TIFF", "input.tif", 1, title),
                "input.tif",
                1,
                title,
                title,
                "ManifestMouse",
                OrientationManifestRow.Hemisphere.RH,
                "PVN",
                OrientationManifestRow.RotationDegrees.DEG_90,
                true,
                false,
                OrientationManifestRow.ViewPolicy.STANDARDIZE_TO_LEFT,
                OrientationManifestRow.DecisionSource.MANUAL,
                OrientationManifestRow.ConfirmationState.YES,
                "manual override");
        OrientationManifestIO.saveRows(dir.getAbsolutePath(), Arrays.asList(row));

        ResolvedImageMetadata metadata = ImageOrientationResolver.resolve(
                dir.getAbsolutePath(), title, 1);

        assertEquals(ResolvedImageMetadata.Source.SAVED_MANIFEST, metadata.source);
        assertEquals("ManifestMouse", metadata.animalName);
        assertEquals("RH", metadata.hemisphere);
        assertEquals("PVN", metadata.region);
        assertEquals(OrientationManifestRow.RotationDegrees.DEG_90, metadata.rotateDegrees);
        assertTrue(metadata.flipHorizontal);
        assertEquals(OrientationManifestRow.ViewPolicy.STANDARDIZE_TO_LEFT, metadata.viewPolicy);
    }

    @Test
    public void resolve_unconfirmedManifestDoesNotOverrideStrictFilename() throws Exception {
        File dir = temp.newFolder("project");
        String title = "Exp-FilenameMouse_LH_SCN";
        OrientationManifestRow row = row(title,
                OrientationManifestRow.Hemisphere.RH,
                OrientationManifestRow.ConfirmationState.NO);
        OrientationManifestIO.saveRows(dir.getAbsolutePath(), Arrays.asList(row));

        ResolvedImageMetadata metadata = ImageOrientationResolver.resolve(
                dir.getAbsolutePath(), title, 1);

        assertEquals(ResolvedImageMetadata.Source.STRICT_FILENAME, metadata.source);
        assertEquals("FilenameMouse", metadata.animalName);
        assertEquals("LH", metadata.hemisphere);
        assertEquals("SCN", metadata.region);
    }

    @Test
    public void resolve_malformedManifestDoesNotOverrideStrictFilename() throws Exception {
        File dir = temp.newFolder("project");
        String title = "Exp-FilenameMouse_LH_SCN";
        OrientationManifestRow row = row(title,
                OrientationManifestRow.Hemisphere.UNKNOWN,
                OrientationManifestRow.ConfirmationState.YES);
        OrientationManifestIO.saveRows(dir.getAbsolutePath(), Arrays.asList(row));

        ResolvedImageMetadata metadata = ImageOrientationResolver.resolve(
                dir.getAbsolutePath(), title, 1);

        assertEquals(ResolvedImageMetadata.Source.STRICT_FILENAME, metadata.source);
        assertEquals("FilenameMouse", metadata.animalName);
        assertEquals("LH", metadata.hemisphere);
    }

    @Test
    public void resolve_nonStrictFilenameUsesExistingFallback() throws Exception {
        File dir = temp.newFolder("project");

        ResolvedImageMetadata metadata = ImageOrientationResolver.resolve(
                dir.getAbsolutePath(), "series1left1.tif", 1);

        assertEquals(ResolvedImageMetadata.Source.FILENAME_FALLBACK, metadata.source);
        assertEquals("series1left1", metadata.animalName);
        assertEquals("", metadata.hemisphere);
        assertFalse(metadata.hasKnownHemisphere());
    }

    @Test
    public void resolve_unknownHemisphereManifestCanApplyManualRotation() throws Exception {
        File dir = temp.newFolder("project");
        String title = "series1left1.tif";
        OrientationManifestRow row = new OrientationManifestRow(
                OrientationManifestRow.buildImageKey("TIFF", "input.tif", 1, title),
                "input.tif",
                1,
                title,
                title,
                "ManualMouse",
                OrientationManifestRow.Hemisphere.UNKNOWN,
                "SCN",
                OrientationManifestRow.RotationDegrees.DEG_90,
                false,
                false,
                OrientationManifestRow.ViewPolicy.MANUAL_ONLY,
                OrientationManifestRow.DecisionSource.MANUAL,
                OrientationManifestRow.ConfirmationState.YES,
                "manual rotation");
        OrientationManifestIO.saveRows(dir.getAbsolutePath(), Arrays.asList(row));

        ResolvedImageMetadata metadata = ImageOrientationResolver.resolve(
                dir.getAbsolutePath(), title, 1);

        assertEquals(ResolvedImageMetadata.Source.SAVED_MANIFEST, metadata.source);
        assertEquals("ManualMouse", metadata.animalName);
        assertEquals("", metadata.hemisphere);
        assertEquals("SCN", metadata.region);
        assertEquals(OrientationManifestRow.RotationDegrees.DEG_90, metadata.rotateDegrees);
    }

    private static OrientationManifestRow row(String title,
                                              OrientationManifestRow.Hemisphere hemisphere,
                                              OrientationManifestRow.ConfirmationState confirmed) {
        return new OrientationManifestRow(
                OrientationManifestRow.buildImageKey("TIFF", "input.tif", 1, title),
                "input.tif",
                1,
                title,
                title,
                "ManifestMouse",
                hemisphere,
                "PVN",
                OrientationManifestRow.RotationDegrees.DEG_0,
                false,
                false,
                OrientationManifestRow.ViewPolicy.MANUAL_ONLY,
                OrientationManifestRow.DecisionSource.MANUAL,
                confirmed,
                "");
    }
}
