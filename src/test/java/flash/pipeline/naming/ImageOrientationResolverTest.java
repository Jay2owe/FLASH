package flash.pipeline.naming;

import flash.pipeline.io.FlashProjectLayout;
import flash.pipeline.io.OrientationManifestIO;
import flash.pipeline.project.ProjectFileIO;
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

    @Test
    public void resolve_readsManifestFromProjectJsonSelection() throws Exception {
        File root = temp.newFolder("project-selection");
        FlashProjectLayout layout = FlashProjectLayout.forDirectory(root.getAbsolutePath());
        File settings = layout.configurationWriteDir();
        assertTrue(settings.mkdirs());
        File projectJson = new File(settings, ProjectFileIO.FILE_NAME);
        assertTrue(projectJson.createNewFile());
        String title = "slide.lif - ProjectMouse_RH_SCN";
        OrientationManifestRow row = confirmedRow(
                OrientationManifestRow.buildImageKey("CONTAINER", "slide.lif", 1, title),
                "slide.lif", 1, title, "ProjectMouse_RH_SCN",
                "ProjectMouse", OrientationManifestRow.Hemisphere.RH,
                OrientationManifestRow.RotationDegrees.DEG_270, true);
        OrientationManifestIO.saveRows(root.getAbsolutePath(), Arrays.asList(row));

        ResolvedImageMetadata metadata = ImageOrientationResolver.resolve(
                projectJson.getAbsolutePath(), "ProjectMouse_RH_SCN", 1);

        assertEquals(ResolvedImageMetadata.Source.SAVED_MANIFEST, metadata.source);
        assertEquals("ProjectMouse", metadata.animalName);
        assertEquals("RH", metadata.hemisphere);
        assertEquals(OrientationManifestRow.RotationDegrees.DEG_270, metadata.rotateDegrees);
        assertTrue(metadata.flipHorizontal);
    }

    @Test
    public void resolve_uniqueTitleMatchSurvivesSeriesIndexMismatch() throws Exception {
        File dir = temp.newFolder("series-index-mismatch");
        String title = "slide.lif - ProjectMouse_LH_SCN";
        OrientationManifestRow row = confirmedRow(
                OrientationManifestRow.buildImageKey("CONTAINER", "slide.lif", 6, title),
                "slide.lif", 6, title, "ProjectMouse_LH_SCN",
                "ProjectMouse", OrientationManifestRow.Hemisphere.LH,
                OrientationManifestRow.RotationDegrees.DEG_180, false);
        OrientationManifestIO.saveRows(dir.getAbsolutePath(), Arrays.asList(row));

        ResolvedImageMetadata metadata = ImageOrientationResolver.resolve(
                dir.getAbsolutePath(), "ProjectMouse_LH_SCN", 1);

        assertEquals(ResolvedImageMetadata.Source.SAVED_MANIFEST, metadata.source);
        assertEquals("ProjectMouse", metadata.animalName);
        assertEquals(OrientationManifestRow.RotationDegrees.DEG_180, metadata.rotateDegrees);
    }

    @Test
    public void resolve_ambiguousTitleFallbackStillAllowsLaterExactSeriesMatch() throws Exception {
        File dir = temp.newFolder("ambiguous-title-exact-later");
        String title = "slide.lif - Repeated_LH_SCN";
        OrientationManifestRow first = confirmedRow(
                OrientationManifestRow.buildImageKey("CONTAINER", "slide.lif", 1, title),
                "slide.lif", 1, title, "Repeated_LH_SCN",
                "FirstMouse", OrientationManifestRow.Hemisphere.LH,
                OrientationManifestRow.RotationDegrees.DEG_90, false);
        OrientationManifestRow second = confirmedRow(
                OrientationManifestRow.buildImageKey("CONTAINER", "slide.lif", 2, title),
                "slide.lif", 2, title, "Repeated_LH_SCN",
                "SecondMouse", OrientationManifestRow.Hemisphere.LH,
                OrientationManifestRow.RotationDegrees.DEG_180, false);
        OrientationManifestRow exact = confirmedRow(
                OrientationManifestRow.buildImageKey("CONTAINER", "slide.lif", 3, title),
                "slide.lif", 3, title, "Repeated_LH_SCN",
                "ExactMouse", OrientationManifestRow.Hemisphere.LH,
                OrientationManifestRow.RotationDegrees.DEG_270, false);
        OrientationManifestIO.saveRows(dir.getAbsolutePath(),
                Arrays.asList(first, second, exact));

        ResolvedImageMetadata metadata = ImageOrientationResolver.resolve(
                dir.getAbsolutePath(), title, 3);

        assertEquals(ResolvedImageMetadata.Source.SAVED_MANIFEST, metadata.source);
        assertEquals("ExactMouse", metadata.animalName);
        assertEquals(OrientationManifestRow.RotationDegrees.DEG_270, metadata.rotateDegrees);
    }

    @Test
    public void resolve_ambiguousTitleFallbackWithoutExactMatchUsesFilename() throws Exception {
        File dir = temp.newFolder("ambiguous-title-no-exact");
        String title = "slide.lif - Repeated_LH_SCN";
        OrientationManifestRow first = confirmedRow(
                OrientationManifestRow.buildImageKey("CONTAINER", "slide.lif", 1, title),
                "slide.lif", 1, title, "Repeated_LH_SCN",
                "FirstMouse", OrientationManifestRow.Hemisphere.LH,
                OrientationManifestRow.RotationDegrees.DEG_90, false);
        OrientationManifestRow second = confirmedRow(
                OrientationManifestRow.buildImageKey("CONTAINER", "slide.lif", 2, title),
                "slide.lif", 2, title, "Repeated_LH_SCN",
                "SecondMouse", OrientationManifestRow.Hemisphere.LH,
                OrientationManifestRow.RotationDegrees.DEG_180, false);
        OrientationManifestIO.saveRows(dir.getAbsolutePath(),
                Arrays.asList(first, second));

        ResolvedImageMetadata metadata = ImageOrientationResolver.resolve(
                dir.getAbsolutePath(), title, 3);

        assertEquals(ResolvedImageMetadata.Source.STRICT_FILENAME, metadata.source);
        assertEquals("Repeated", metadata.animalName);
        assertEquals(OrientationManifestRow.RotationDegrees.DEG_270, metadata.rotateDegrees);
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

    private static OrientationManifestRow confirmedRow(
            String imageKey,
            String sourceFile,
            int seriesIndex,
            String originalName,
            String displayName,
            String animalName,
            OrientationManifestRow.Hemisphere hemisphere,
            OrientationManifestRow.RotationDegrees rotateDegrees,
            boolean flipHorizontal) {
        return new OrientationManifestRow(
                imageKey,
                sourceFile,
                seriesIndex,
                originalName,
                displayName,
                animalName,
                hemisphere,
                "SCN",
                rotateDegrees,
                flipHorizontal,
                false,
                OrientationManifestRow.ViewPolicy.MANUAL_ONLY,
                OrientationManifestRow.DecisionSource.MANUAL,
                OrientationManifestRow.ConfirmationState.YES,
                "project metadata");
    }
}
