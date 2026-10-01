package flash.pipeline.io;

import flash.pipeline.naming.OrientationManifestRow;
import flash.pipeline.project.ProjectFileIO;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class OrientationManifestIOTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void getFile_usesProjectSummaryManifestName() throws Exception {
        File dir = temp.newFolder("project");

        File manifest = OrientationManifestIO.getFile(dir.getAbsolutePath());

        assertEquals("Image Orientation.csv", manifest.getName());
        assertEquals("Project Summary", manifest.getParentFile().getName());
        assertEquals("Tables", manifest.getParentFile().getParentFile().getName());
        assertEquals("Results", manifest.getParentFile().getParentFile().getParentFile().getName());
    }

    @Test
    public void readIfExists_readsProjectSummaryManifest() throws Exception {
        File dir = temp.newFolder("current");
        File manifest = OrientationManifestIO.getFile(dir.getAbsolutePath());
        assertTrue(manifest.getParentFile().mkdirs());
        PrintWriter pw = CsvSupport.newWriter(manifest);
        try {
            pw.println("ImageKey,SourceFile,SeriesIndex,OriginalName,DisplayName,AnimalName,Hemisphere,Region,RotateDegrees,FlipHorizontal,FlipVertical,ViewPolicy,DecisionSource,Confirmed,Notes");
            pw.println("KEY,source.tif,1,Original,Display,Animal,LH,SCN,0,No,No,ManualOnly,Manual,Yes,current");
        } finally {
            pw.close();
        }

        List<OrientationManifestRow> rows = OrientationManifestIO.readIfExists(dir.getAbsolutePath());

        assertEquals(1, rows.size());
        assertEquals("current", rows.get(0).notes);
    }

    @Test
    public void readAndWriteUseProjectRootForProjectSelectionVariants() throws Exception {
        File root = temp.newFolder("selection-root");
        FlashProjectLayout layout = FlashProjectLayout.forDirectory(root.getAbsolutePath());
        File settings = layout.configurationWriteDir();
        assertTrue(settings.mkdirs());
        File projectJson = new File(settings, ProjectFileIO.FILE_NAME);
        assertTrue(projectJson.createNewFile());

        OrientationManifestIO.saveRows(root.getAbsolutePath(),
                Arrays.asList(row("ROOT", "root-row")));

        String[] selections = {
                new File(root, FlashProjectLayout.FLASH_DIR).getAbsolutePath(),
                layout.visibleConfigurationDir().getAbsolutePath(),
                settings.getAbsolutePath(),
                projectJson.getAbsolutePath()
        };

        for (String selection : selections) {
            List<OrientationManifestRow> rows = OrientationManifestIO.readIfExists(selection);
            assertEquals("Expected root manifest for " + selection, 1, rows.size());
            assertEquals("root-row", rows.get(0).notes);
        }

        OrientationManifestIO.saveRows(settings.getAbsolutePath(),
                Arrays.asList(row("SETTINGS", "settings-write")));

        List<OrientationManifestRow> rootRows =
                OrientationManifestIO.readIfExists(root.getAbsolutePath());
        assertEquals(1, rootRows.size());
        assertEquals("settings-write", rootRows.get(0).notes);
    }

    @Test
    public void writeAndRead_roundTripsQuotedFieldsBlanksAndNotes() throws Exception {
        File dir = temp.newFolder("project");
        OrientationManifestRow row = new OrientationManifestRow(
                OrientationManifestRow.buildImageKey("CONTAINER", "Exp1.lif", 1, "Series 001"),
                "Exp1.lif",
                1,
                "Series 001",
                "Mouse, \"Alpha\"",
                "Mouse Alpha",
                OrientationManifestRow.Hemisphere.RH,
                "",
                OrientationManifestRow.RotationDegrees.DEG_90,
                true,
                false,
                OrientationManifestRow.ViewPolicy.STANDARDIZE_TO_LEFT,
                OrientationManifestRow.DecisionSource.MANUAL,
                OrientationManifestRow.ConfirmationState.YES,
                "keep comma, quote \"ok\", and\nline");

        OrientationManifestIO.saveRows(dir.getAbsolutePath(), Arrays.asList(row));

        List<OrientationManifestRow> rows = OrientationManifestIO.read(
                OrientationManifestIO.getFile(dir.getAbsolutePath()));
        assertEquals(1, rows.size());
        OrientationManifestRow readBack = rows.get(0);
        assertEquals(row.imageKey, readBack.imageKey);
        assertEquals(row.displayName, readBack.displayName);
        assertEquals("", readBack.region);
        assertEquals(row.rotateDegrees, readBack.rotateDegrees);
        assertTrue(readBack.flipHorizontal);
        assertFalse(readBack.flipVertical);
        assertEquals(row.viewPolicy, readBack.viewPolicy);
        assertEquals(row.decisionSource, readBack.decisionSource);
        assertEquals(row.confirmed, readBack.confirmed);
        assertEquals(row.notes, readBack.notes);
    }

    @Test
    public void readIfExists_missingManifestReturnsEmptyListAndMap() throws Exception {
        File dir = temp.newFolder("missing");

        assertTrue(OrientationManifestIO.readIfExists(dir.getAbsolutePath()).isEmpty());
        assertTrue(OrientationManifestIO.readByImageKeyIfExists(dir.getAbsolutePath()).isEmpty());
    }

    @Test
    public void malformedExistingManifestRefusesFilenameFallback() throws Exception {
        File root = temp.newFolder("broken-orientation");
        File manifest = OrientationManifestIO.getFile(root.getAbsolutePath());
        Files.createDirectories(manifest.getParentFile().toPath());
        Files.write(manifest.toPath(), "ImageKey,SourceFile\nKEY,\"unterminated\n"
                .getBytes(StandardCharsets.UTF_8));
        try {
            flash.pipeline.naming.ImageOrientationResolver.resolve(root.getAbsolutePath(),
                    "Exp-Mouse1_LH_SCN", 1);
            org.junit.Assert.fail("malformed persisted orientation must not silently relabel an image");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("refusing filename fallback"));
            assertTrue(expected.getCause() instanceof java.io.IOException);
        }
    }

    @Test
    public void truncatedConfirmedRowFailsInsteadOfDefaultingTransforms() throws Exception {
        File root = temp.newFolder("truncated-orientation");
        File manifest = OrientationManifestIO.getFile(root.getAbsolutePath());
        OrientationManifestIO.saveRows(root.getAbsolutePath(), Arrays.asList(row("KEY", "valid")));
        String header = Files.readAllLines(manifest.toPath(), StandardCharsets.UTF_8).get(0);
        Files.write(manifest.toPath(), (header + "\nKEY,source.tif,1,Name,Display,Animal,LH\n")
                .getBytes(StandardCharsets.UTF_8));
        try {
            OrientationManifestIO.readIfExists(root.getAbsolutePath());
            org.junit.Assert.fail("truncated row must fail");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getCause().getMessage().contains("expected 15"));
        }
    }

    @Test
    public void read_skipsBlankRowsAndRowsWithoutImageKey() throws Exception {
        File dir = temp.newFolder("malformed");
        File manifest = OrientationManifestIO.getFile(dir.getAbsolutePath());
        assertTrue(manifest.getParentFile().mkdirs());

        PrintWriter pw = CsvSupport.newWriter(manifest);
        try {
            pw.println("ImageKey,SourceFile,SeriesIndex,OriginalName,DisplayName,AnimalName,Hemisphere,Region,RotateDegrees,FlipHorizontal,FlipVertical,ViewPolicy,DecisionSource,Confirmed,Notes");
            pw.println("");
            pw.println(",source.tif,1,Name,Display,Animal,LH,SCN,0,No,No,ManualOnly,Manual,No,no key");
            pw.println("TIFF|input/Mouse8_Left_SCN.tif|1|Mouse8_Left_SCN,input/Mouse8_Left_SCN.tif,1,Mouse8_Left_SCN,Mouse8_Left_SCN,Mouse8,LH,SCN,180,Yes,No,KeepAsAcquired,StrictFilename,Yes,valid");
        } finally {
            pw.close();
        }

        List<OrientationManifestRow> rows = OrientationManifestIO.read(manifest);

        assertEquals(1, rows.size());
        assertEquals("Mouse8", rows.get(0).animalName);
        assertEquals(OrientationManifestRow.RotationDegrees.DEG_180, rows.get(0).rotateDegrees);
    }

    @Test
    public void read_normalizesBooleanAndEnumFieldsToCanonicalValuesOnWrite() throws Exception {
        File dir = temp.newFolder("normalise");
        File manifest = OrientationManifestIO.getFile(dir.getAbsolutePath());
        assertTrue(manifest.getParentFile().mkdirs());

        PrintWriter pw = CsvSupport.newWriter(manifest);
        try {
            pw.println("ImageKey,SourceFile,SeriesIndex,OriginalName,DisplayName,AnimalName,Hemisphere,Region,RotateDegrees,FlipHorizontal,FlipVertical,ViewPolicy,DecisionSource,Confirmed,Notes");
            pw.println("KEY,source.tif,1,Original,Display,Animal,rh,SCN,270,true,1,standardize_to_right,folder alias,true,normalised");
            pw.println("BADROT,source.tif,bad,Original,Display,Animal,bad,SCN,45,false,no,bad,bad,false,safe defaults");
        } finally {
            pw.close();
        }

        List<OrientationManifestRow> rows = OrientationManifestIO.read(manifest);
        assertEquals(2, rows.size());
        assertEquals(1, rows.get(0).seriesIndex);
        assertEquals(OrientationManifestRow.Hemisphere.RH, rows.get(0).hemisphere);
        assertEquals(OrientationManifestRow.RotationDegrees.DEG_270, rows.get(0).rotateDegrees);
        assertTrue(rows.get(0).flipHorizontal);
        assertTrue(rows.get(0).flipVertical);
        assertEquals(OrientationManifestRow.ViewPolicy.STANDARDIZE_TO_RIGHT, rows.get(0).viewPolicy);
        assertEquals(OrientationManifestRow.DecisionSource.FOLDER_ALIAS, rows.get(0).decisionSource);
        assertTrue(rows.get(0).isConfirmed());

        assertEquals(OrientationManifestRow.Hemisphere.UNKNOWN, rows.get(1).hemisphere);
        assertEquals(OrientationManifestRow.RotationDegrees.DEG_0, rows.get(1).rotateDegrees);
        assertEquals(OrientationManifestRow.ViewPolicy.MANUAL_ONLY, rows.get(1).viewPolicy);
        assertEquals(OrientationManifestRow.DecisionSource.UNKNOWN, rows.get(1).decisionSource);
        assertFalse(rows.get(1).isConfirmed());

        File rewritten = OrientationManifestIO.getFile(temp.newFolder("rewritten").getAbsolutePath());
        OrientationManifestIO.write(rewritten, rows);

        List<OrientationManifestRow> reread = OrientationManifestIO.read(rewritten);
        assertEquals(rows.size(), reread.size());
        assertEquals(OrientationManifestRow.Hemisphere.RH, reread.get(0).hemisphere);
        assertEquals(OrientationManifestRow.ViewPolicy.MANUAL_ONLY, reread.get(1).viewPolicy);
    }

    @Test
    public void indexByImageKey_preservesLastRowForDuplicateKey() {
        OrientationManifestRow first = row("KEY", "first");
        OrientationManifestRow second = row("KEY", "second");
        OrientationManifestRow third = row("OTHER", "third");

        LinkedHashMap<String, OrientationManifestRow> byKey =
                OrientationManifestIO.indexByImageKey(Arrays.asList(first, second, third));

        assertEquals(2, byKey.size());
        assertEquals("second", byKey.get("KEY").notes);
        assertEquals("third", byKey.get("OTHER").notes);
    }

    @Test
    public void invalidConfirmedTransformFailsRatherThanBecomingZeroRotation() throws Exception {
        File root = temp.newFolder("bad-confirmed-transform");
        File manifest = OrientationManifestIO.getFile(root.getAbsolutePath());
        OrientationManifestIO.saveRows(root.getAbsolutePath(), Arrays.asList(row("KEY", "valid")));
        String header = Files.readAllLines(manifest.toPath(), StandardCharsets.UTF_8).get(0);
        for (String transform : new String[]{"45,No,No", "0,garbled,No"}) {
            Files.write(manifest.toPath(), (header
                    + "\nKEY,source.tif,1,Original,Display,Animal,LH,SCN," + transform
                    + ",ManualOnly,Manual,Yes,invalid\n").getBytes(StandardCharsets.UTF_8));
            try {
                OrientationManifestIO.readIfExists(root.getAbsolutePath());
                org.junit.Assert.fail("confirmed transform must not silently change");
            } catch (IllegalStateException expected) {
                assertTrue(expected.getCause().getMessage().contains("invalid"));
            }
        }
    }

    private static OrientationManifestRow row(String key, String notes) {
        return new OrientationManifestRow(
                key,
                "source.tif",
                1,
                "Original",
                "Display",
                "Animal",
                OrientationManifestRow.Hemisphere.UNKNOWN,
                "",
                OrientationManifestRow.RotationDegrees.DEG_0,
                false,
                false,
                OrientationManifestRow.ViewPolicy.MANUAL_ONLY,
                OrientationManifestRow.DecisionSource.UNKNOWN,
                OrientationManifestRow.ConfirmationState.NO,
                notes);
    }
}
