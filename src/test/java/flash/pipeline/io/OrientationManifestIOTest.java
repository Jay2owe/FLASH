package flash.pipeline.io;

import flash.pipeline.naming.OrientationManifestRow;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.PrintWriter;
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
            pw.println("KEY,source.tif,0,Original,Display,Animal,rh,SCN,270,true,1,standardize_to_right,folder alias,true,normalised");
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
