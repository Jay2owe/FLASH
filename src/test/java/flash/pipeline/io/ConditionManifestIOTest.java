package flash.pipeline.io;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ConditionManifestIOTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void buildAssignmentsFromSeriesNames_deduplicatesAnimalsInSeriesOrder() {
        LinkedHashMap<String, String> assignments = ConditionManifestIO.buildAssignmentsFromSeriesNames(
                Arrays.asList(
                        "project.lif - Syn1WeekTwo_LH_SCN",
                        "project.lif - Syn1WeekTwo_RH_SCN",
                        "project.lif - hAPP2WeekEight_LH_SCN",
                        "project.lif - hAPP2WeekEight_RH_SCN"));

        assertEquals(Arrays.asList("Syn1WeekTwo", "hAPP2WeekEight"),
                new ArrayList<String>(assignments.keySet()));
        assertEquals("SynWeekTwo", assignments.get("Syn1WeekTwo"));
        assertEquals("hAPPWeekEight", assignments.get("hAPP2WeekEight"));
    }

    @Test
    public void extractAnimalName_prefersSeriesTitleAfterBioFormatsPrefix() {
        assertEquals("NLGF11",
                ConditionManifestIO.extractAnimalName("experiment-file.lif - NLGF11_LH_SCN"));
        assertEquals("Syn1WeekTwo",
                ConditionManifestIO.extractAnimalName("Syn1WeekTwo_RH_SCN"));
    }

    @Test
    public void writeAndRead_roundTripsAssignments() throws Exception {
        File dir = temp.newFolder("project");

        LinkedHashMap<String, String> written = new LinkedHashMap<String, String>();
        written.put("Syn1WeekTwo", "SynWeekTwo");
        written.put("hAPP2WeekEight", "hAPPWeekEight");

        File manifest = newManifest(dir);
        ConditionManifestIO.write(manifest, written);

        Map<String, String> readBack = ConditionManifestIO.read(manifest);
        assertEquals(written, readBack);
    }

    @Test
    public void writeAndRead_roundTripsQuotedAssignments() throws Exception {
        File dir = temp.newFolder("quoted-project");

        LinkedHashMap<String, String> written = new LinkedHashMap<String, String>();
        written.put("Mouse, \"Alpha\"", "Cond, \"Beta\"");
        written.put("Mouse Two", "Line1\nLine2");

        File manifest = newManifest(dir);
        ConditionManifestIO.write(manifest, written);

        Map<String, String> readBack = ConditionManifestIO.read(manifest);
        assertEquals(written, readBack);
    }

    @Test
    public void resolveAssignments_prefersManifestAndFallsBackForMissingAnimals() throws Exception {
        File dir = temp.newFolder("project");

        LinkedHashMap<String, String> manifestAssignments = new LinkedHashMap<String, String>();
        manifestAssignments.put("Syn1WeekTwo", "SynWeekTwo");
        ConditionManifestIO.write(newManifest(dir), manifestAssignments);

        LinkedHashMap<String, String> resolved = ConditionManifestIO.resolveAssignments(
                dir.getAbsolutePath(),
                new java.util.LinkedHashSet<String>(Arrays.asList("Syn1WeekTwo", "hAPP2WeekEight")));

        assertEquals("SynWeekTwo", resolved.get("Syn1WeekTwo"));
        assertEquals("hAPPWeekEight", resolved.get("hAPP2WeekEight"));
    }

    @Test
    public void resolveAssignments_upgradesLegacyCollapsedManifestGroups() throws Exception {
        File dir = temp.newFolder("project");

        LinkedHashMap<String, String> manifestAssignments = new LinkedHashMap<String, String>();
        manifestAssignments.put("hAPP3Week2", "hAPP");
        manifestAssignments.put("hAPP11Week4", "hAPP");
        manifestAssignments.put("hAPP12Week8", "hAPP");
        manifestAssignments.put("NLGF11", "NLGF");
        ConditionManifestIO.write(newManifest(dir), manifestAssignments);

        LinkedHashSet<String> animals = new LinkedHashSet<String>(Arrays.asList(
                "hAPP3Week2",
                "hAPP11Week4",
                "hAPP12Week8",
                "NLGF11"));

        LinkedHashMap<String, String> resolved =
                ConditionManifestIO.resolveAssignments(dir.getAbsolutePath(), animals);

        assertEquals("hAPPWeek2", resolved.get("hAPP3Week2"));
        assertEquals("hAPPWeek4", resolved.get("hAPP11Week4"));
        assertEquals("hAPPWeek8", resolved.get("hAPP12Week8"));
        assertEquals("NLGF", resolved.get("NLGF11"));

        Map<String, String> persisted =
                ConditionManifestIO.read(ConditionManifestIO.getFile(dir.getAbsolutePath()));
        assertEquals("hAPPWeek2", persisted.get("hAPP3Week2"));
        assertEquals("hAPPWeek4", persisted.get("hAPP11Week4"));
        assertEquals("hAPPWeek8", persisted.get("hAPP12Week8"));
        assertEquals("NLGF", persisted.get("NLGF11"));
    }

    @Test
    public void ensureExists_doesNotRegenerateWhenManifestAlreadyExists() throws Exception {
        File dir = temp.newFolder("existing");

        // Write a manifest manually
        LinkedHashMap<String, String> original = new LinkedHashMap<String, String>();
        original.put("Animal1", "ConditionA");
        File manifest = newManifest(dir);
        ConditionManifestIO.write(manifest, original);
        long modifiedBefore = manifest.lastModified();

        // Place a .lif file (would normally trigger generation)
        new java.io.File(dir, "experiment.lif").createNewFile();

        // ensureExists should see the existing manifest and leave it alone
        ConditionManifestIO.ensureExists(dir.getAbsolutePath());

        Map<String, String> readBack = ConditionManifestIO.read(manifest);
        assertEquals("Manifest should not have been overwritten", original, readBack);
    }

    @Test(timeout = 2000)
    public void ensureExists_doesNotCreateManifestWhenMultipleLifsExist() throws Exception {
        File dir = temp.newFolder("multi_lif");

        new java.io.File(dir, "alpha.lif").createNewFile();
        new java.io.File(dir, "beta.lif").createNewFile();

        // Should not throw, but should also not create a manifest
        ConditionManifestIO.ensureExists(dir.getAbsolutePath());

        File manifest = ConditionManifestIO.getFile(dir.getAbsolutePath());
        assertFalse("Manifest should not be created when multiple .lif files exist",
                manifest.exists());
    }

    @Test
    public void saveAssignments_writesCleanedManifest() throws Exception {
        File dir = temp.newFolder("project");

        LinkedHashMap<String, String> assignments = new LinkedHashMap<String, String>();
        assignments.put("Syn1", "Control");
        assignments.put("  hAPP1  ", "  Treatment  "); // whitespace to trim
        assignments.put("", "Ghost");                   // blank key — should be dropped
        assignments.put("NLGF1", "");                   // blank value — should be dropped

        ConditionManifestIO.saveAssignments(dir.getAbsolutePath(), assignments);

        Map<String, String> readBack =
                ConditionManifestIO.read(ConditionManifestIO.getFile(dir.getAbsolutePath()));

        assertEquals(2, readBack.size());
        assertEquals("Control", readBack.get("Syn1"));
        assertEquals("Treatment", readBack.get("hAPP1"));
    }

    @Test
    public void saveAssignments_createsAggregationDirIfNeeded() throws Exception {
        File dir = temp.newFolder("fresh");
        // Do not create the FLASH aggregation folder; saveAssignments should create it.
        LinkedHashMap<String, String> assignments = new LinkedHashMap<String, String>();
        assignments.put("Animal1", "CondA");

        ConditionManifestIO.saveAssignments(dir.getAbsolutePath(), assignments);

        File manifest = ConditionManifestIO.getFile(dir.getAbsolutePath());
        assertTrue("Manifest file should be created", manifest.exists());
        assertEquals("Results Export", manifest.getParentFile().getName());
        assertEquals("FLASH", manifest.getParentFile().getParentFile().getName());
    }

    @Test
    public void readAssignmentsIfExists_readsLegacyManifestWhenNewMissing() throws Exception {
        File dir = temp.newFolder("legacy");
        LinkedHashMap<String, String> legacyAssignments = new LinkedHashMap<String, String>();
        legacyAssignments.put("Legacy1", "Control");
        ConditionManifestIO.write(legacyManifest(dir), legacyAssignments);

        assertEquals(legacyAssignments,
                ConditionManifestIO.readAssignmentsIfExists(dir.getAbsolutePath()));
        assertEquals(legacyManifest(dir).getAbsolutePath(),
                ConditionManifestIO.getReadFile(dir.getAbsolutePath()).getAbsolutePath());
    }

    @Test
    public void readAssignmentsIfExists_prefersNewManifestOverLegacy() throws Exception {
        File dir = temp.newFolder("mixed");
        LinkedHashMap<String, String> legacyAssignments = new LinkedHashMap<String, String>();
        legacyAssignments.put("Mouse1", "Legacy");
        ConditionManifestIO.write(legacyManifest(dir), legacyAssignments);

        LinkedHashMap<String, String> newAssignments = new LinkedHashMap<String, String>();
        newAssignments.put("Mouse1", "Current");
        ConditionManifestIO.write(newManifest(dir), newAssignments);

        assertEquals(newAssignments,
                ConditionManifestIO.readAssignmentsIfExists(dir.getAbsolutePath()));
        assertEquals(ConditionManifestIO.getFile(dir.getAbsolutePath()).getAbsolutePath(),
                ConditionManifestIO.getReadFile(dir.getAbsolutePath()).getAbsolutePath());
    }

    @Test
    public void resolveAssignments_preservesManualNonLegacyGroups() throws Exception {
        File dir = temp.newFolder("project");

        LinkedHashMap<String, String> manifestAssignments = new LinkedHashMap<String, String>();
        manifestAssignments.put("hAPP3Week2", "AD");
        manifestAssignments.put("hAPP11Week4", "AD");
        ConditionManifestIO.write(newManifest(dir), manifestAssignments);

        LinkedHashSet<String> animals = new LinkedHashSet<String>(Arrays.asList(
                "hAPP3Week2",
                "hAPP11Week4"));

        LinkedHashMap<String, String> resolved =
                ConditionManifestIO.resolveAssignments(dir.getAbsolutePath(), animals);

        assertEquals("AD", resolved.get("hAPP3Week2"));
        assertEquals("AD", resolved.get("hAPP11Week4"));
    }

    private static File newManifest(File dir) {
        File manifest = ConditionManifestIO.getFile(dir.getAbsolutePath());
        assertTrue(manifest.getParentFile().isDirectory() || manifest.getParentFile().mkdirs());
        return manifest;
    }

    private static File legacyManifest(File dir) {
        File manifest = new File(new File(dir, "ImageJ Exports"), ConditionManifestIO.LEGACY_FILE_NAME);
        assertTrue(manifest.getParentFile().isDirectory() || manifest.getParentFile().mkdirs());
        return manifest;
    }
}
