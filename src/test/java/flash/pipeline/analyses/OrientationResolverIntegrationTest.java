package flash.pipeline.analyses;

import flash.pipeline.image.OrientationOps;
import flash.pipeline.io.OrientationManifestIO;
import flash.pipeline.naming.ImageOrientationResolver;
import flash.pipeline.naming.NameParts;
import flash.pipeline.naming.OrientationManifestRow;
import flash.pipeline.naming.ResolvedImageMetadata;
import ij.ImagePlus;
import ij.ImageStack;
import ij.process.ByteProcessor;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.util.Arrays;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

public class OrientationResolverIntegrationTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void noManifestUsesStrictFilenameMetadataAndLegacyTransform() throws Exception {
        File dir = temp.newFolder("project");
        String title = "Exp-Mouse2_RH_SCN";
        ResolvedImageMetadata metadata = ImageOrientationResolver.resolve(
                dir.getAbsolutePath(), title, 1);
        NameParts parts = metadata.toNameParts();

        ImagePlus resolved = image(title);
        ImagePlus legacy = image(title);
        OrientationOps.applyTransform(resolved, metadata);
        OrientationOps.orientateThreadSafe(legacy, "RH");

        assertEquals("Mouse2", parts.animal);
        assertEquals("RH", parts.hemisphere);
        assertEquals("SCN", parts.csvRegion());
        assertArrayEquals(pixels(legacy), pixels(resolved));
    }

    @Test
    public void manifestRowForObscureNameProvidesSavedLeftMetadataAndTransform() throws Exception {
        File dir = temp.newFolder("project");
        String title = "series1left1.tif";
        OrientationManifestRow row = new OrientationManifestRow(
                OrientationManifestRow.buildImageKey("TIFF", "input.tif", 1, title),
                "input.tif",
                1,
                title,
                title,
                "SavedLeft",
                OrientationManifestRow.Hemisphere.LH,
                "SCN",
                OrientationManifestRow.RotationDegrees.DEG_180,
                false,
                false,
                OrientationManifestRow.ViewPolicy.MANUAL_ONLY,
                OrientationManifestRow.DecisionSource.MANUAL,
                OrientationManifestRow.ConfirmationState.YES,
                "");
        OrientationManifestIO.saveRows(dir.getAbsolutePath(), Arrays.asList(row));

        ResolvedImageMetadata metadata = ImageOrientationResolver.resolve(
                dir.getAbsolutePath(), title, 1);
        NameParts parts = metadata.toNameParts();
        ImagePlus resolved = image(title);
        OrientationOps.applyTransform(resolved, metadata);

        assertEquals(ResolvedImageMetadata.Source.SAVED_MANIFEST, metadata.source);
        assertEquals("SavedLeft", parts.animal);
        assertEquals("LH", parts.hemisphere);
        assertEquals("SCN", parts.csvRegion());
        assertArrayEquals(new int[] { 4, 3, 2, 1 }, pixels(resolved));
    }

    @Test
    public void manifestRowOverridesFilenameMetadataAndTransform() throws Exception {
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
                OrientationManifestRow.RotationDegrees.DEG_180,
                false,
                false,
                OrientationManifestRow.ViewPolicy.MANUAL_ONLY,
                OrientationManifestRow.DecisionSource.MANUAL,
                OrientationManifestRow.ConfirmationState.YES,
                "");
        OrientationManifestIO.saveRows(dir.getAbsolutePath(), Arrays.asList(row));

        ResolvedImageMetadata metadata = ImageOrientationResolver.resolve(
                dir.getAbsolutePath(), title, 1);
        NameParts parts = metadata.toNameParts();
        ImagePlus resolved = image(title);
        OrientationOps.applyTransform(resolved, metadata);

        assertEquals("ManifestMouse", parts.animal);
        assertEquals("RH", parts.hemisphere);
        assertEquals("PVN", parts.csvRegion());
        assertArrayEquals(new int[] { 4, 3, 2, 1 }, pixels(resolved));
    }

    @Test
    public void unknownHemisphereManifestStillAppliesManualRotationOnly() throws Exception {
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
                OrientationManifestRow.RotationDegrees.DEG_180,
                false,
                false,
                OrientationManifestRow.ViewPolicy.MANUAL_ONLY,
                OrientationManifestRow.DecisionSource.MANUAL,
                OrientationManifestRow.ConfirmationState.YES,
                "");
        OrientationManifestIO.saveRows(dir.getAbsolutePath(), Arrays.asList(row));

        ResolvedImageMetadata metadata = ImageOrientationResolver.resolve(
                dir.getAbsolutePath(), title, 1);
        NameParts parts = metadata.toNameParts();
        ImagePlus resolved = image(title);
        OrientationOps.applyTransform(resolved, metadata);

        assertEquals("ManualMouse", parts.animal);
        assertEquals("", parts.hemisphere);
        assertEquals("SCN", parts.csvRegion());
        assertFalse(metadata.hasKnownHemisphere());
        assertArrayEquals(new int[] { 4, 3, 2, 1 }, pixels(resolved));
    }

    @Test
    public void missingManifestRowFallsBackPerImage() throws Exception {
        File dir = temp.newFolder("project");
        String savedTitle = "series1left1.tif";
        OrientationManifestRow row = new OrientationManifestRow(
                OrientationManifestRow.buildImageKey("TIFF", "input.tif", 1, savedTitle),
                "input.tif",
                1,
                savedTitle,
                savedTitle,
                "SavedLeft",
                OrientationManifestRow.Hemisphere.LH,
                "SCN",
                OrientationManifestRow.RotationDegrees.DEG_180,
                false,
                false,
                OrientationManifestRow.ViewPolicy.MANUAL_ONLY,
                OrientationManifestRow.DecisionSource.MANUAL,
                OrientationManifestRow.ConfirmationState.YES,
                "");
        OrientationManifestIO.saveRows(dir.getAbsolutePath(), Arrays.asList(row));

        ResolvedImageMetadata strict = ImageOrientationResolver.resolve(
                dir.getAbsolutePath(), "Exp-Mouse3_RH_SCN", 1);
        ResolvedImageMetadata fallback = ImageOrientationResolver.resolve(
                dir.getAbsolutePath(), "not_in_manifest.tif", 1);

        assertEquals(ResolvedImageMetadata.Source.STRICT_FILENAME, strict.source);
        assertEquals("Mouse3", strict.animalName);
        assertEquals("RH", strict.hemisphere);
        assertEquals(ResolvedImageMetadata.Source.FILENAME_FALLBACK, fallback.source);
        assertEquals("not_in_manifest", fallback.animalName);
        assertFalse(fallback.hasTransform());
    }

    private static ImagePlus image(String title) {
        ImageStack stack = new ImageStack(2, 2);
        stack.addSlice(new ByteProcessor(2, 2, new byte[] {
                1, 2,
                3, 4
        }, null));
        return new ImagePlus(title, stack);
    }

    private static int[] pixels(ImagePlus imp) {
        int[] values = new int[imp.getWidth() * imp.getHeight()];
        int i = 0;
        for (int y = 0; y < imp.getHeight(); y++) {
            for (int x = 0; x < imp.getWidth(); x++) {
                values[i++] = imp.getProcessor().getPixel(x, y);
            }
        }
        return values;
    }
}
