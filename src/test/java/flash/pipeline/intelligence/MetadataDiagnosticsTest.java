package flash.pipeline.intelligence;

import flash.pipeline.deconv.RefractiveIndexEstimator;
import flash.pipeline.io.FlashProjectLayout;
import flash.pipeline.project.ProjectFile;
import flash.pipeline.project.ProjectFileIO;
import ij.IJ;
import ij.ImagePlus;
import loci.formats.meta.MetadataRetrieve;
import ome.units.UNITS;
import ome.units.quantity.Length;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class MetadataDiagnosticsTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void metadataScanIgnoresDirectoriesAndPrefersRawContainers() throws Exception {
        File dir = temp.newFolder("cohort");
        File lif = new File(dir, "cohort.lif");
        File tif = new File(dir, "derived_image.tif");
        File tifFolder = new File(dir, "derived_stack.tif");
        File junk = new File(dir, "~$cohort.lif");

        assertTrue(lif.createNewFile());
        assertTrue(tif.createNewFile());
        assertTrue(tifFolder.mkdir());
        assertTrue(junk.createNewFile());

        List<File> candidates = MetadataDiagnostics.listCandidateFilesForMetadataScan(dir);

        assertEquals(1, candidates.size());
        assertEquals("cohort.lif", candidates.get(0).getName());
    }

    @Test
    public void scanDirectoryUsesProjectManifestSourceOutsideOutputRoot() throws Exception {
        File outputRoot = temp.newFolder("manifest-output");
        File sourceRoot = temp.newFolder("manifest-source");
        File staleRootTiff = new File(outputRoot, "stale-root.tif");
        File manifestTiff = new File(sourceRoot, "manifest-source.tif");
        writeSyntheticTiff(staleRootTiff, "stale", 3, 2, 1);
        writeSyntheticTiff(manifestTiff, "manifest", 7, 5, 1);

        ProjectFile project = new ProjectFile();
        project.outputRoot = outputRoot.getAbsolutePath();
        project.items.add(projectItem(manifestTiff));
        writeProject(outputRoot, project);

        List<MetadataDiagnostics.SeriesInfo> series =
                MetadataDiagnostics.scanDirectory(outputRoot.getAbsolutePath());

        assertEquals(1, series.size());
        assertEquals("manifest-source.tif", series.get(0).file);
        assertEquals(7, series.get(0).sizeX);
        assertEquals(5, series.get(0).sizeY);
    }

    @Test
    public void readOneSeriesInfoStringUsesProjectManifestSource() throws Exception {
        File outputRoot = temp.newFolder("one-series-output");
        File sourceRoot = temp.newFolder("one-series-source");
        File manifestTiff = new File(sourceRoot, "one-source.tif");
        writeSyntheticTiff(manifestTiff, "one-source", 9, 4, 1);

        ProjectFile project = new ProjectFile();
        project.outputRoot = outputRoot.getAbsolutePath();
        project.items.add(projectItem(manifestTiff));
        writeProject(outputRoot, project);

        MetadataDiagnostics.SeriesInfo info =
                MetadataDiagnostics.readOneSeriesInfo(outputRoot.getAbsolutePath(), 0);

        assertEquals("one-source.tif", info.file);
        assertEquals(9, info.sizeX);
        assertEquals(4, info.sizeY);
    }

    @Test
    public void acquisitionDatesWarnWhenConditionAndDateDistributionsDiffer() {
        List<MetadataDiagnostics.SeriesInfo> series = new ArrayList<MetadataDiagnostics.SeriesInfo>();
        series.add(series("Project - WT1_LH_SCN", "2026-01-01T09:00:00"));
        series.add(series("Project - WT2_LH_SCN", "2026-01-01T10:00:00"));
        series.add(series("Project - APP1_LH_SCN", "2026-01-03T09:00:00"));

        DiagnosticsReport.Section section = new DiagnosticsReport("x").addSection("Acquisition dates");
        MetadataDiagnostics.checkAcquisitionDates(series, section);

        assertTrue(hasFinding(section, DiagnosticsReport.Severity.WARN,
                "Acquisition-date distribution differs across conditions"));
        assertTrue(hasFinding(section, DiagnosticsReport.Severity.INFO,
                "Condition WT: 2026-01-01 (2)"));
        assertTrue(hasFinding(section, DiagnosticsReport.Severity.INFO,
                "Condition APP: 2026-01-03 (1)"));
    }

    @Test
    public void acquisitionDatesDoNotWarnWhenParsedConditionsShareTheSameDateDistribution() {
        List<MetadataDiagnostics.SeriesInfo> series = new ArrayList<MetadataDiagnostics.SeriesInfo>();
        series.add(series("Project - WT1_LH_SCN", "2026-01-01T09:00:00"));
        series.add(series("Project - WT2_LH_SCN", "2026-01-03T09:00:00"));
        series.add(series("Project - APP1_LH_SCN", "2026-01-01T11:00:00"));
        series.add(series("Project - APP2_LH_SCN", "2026-01-03T11:00:00"));

        DiagnosticsReport.Section section = new DiagnosticsReport("x").addSection("Acquisition dates");
        MetadataDiagnostics.checkAcquisitionDates(series, section);

        assertFalse(hasSeverity(section, DiagnosticsReport.Severity.WARN));
        assertTrue(hasFinding(section, DiagnosticsReport.Severity.OK,
                "Acquisition dates look balanced across the parsed conditions."));
    }

    @Test
    public void instrumentFingerprintGroupsKnownFingerprintsAndUnknownFiles() {
        List<MetadataDiagnostics.SeriesInfo> series = new ArrayList<MetadataDiagnostics.SeriesInfo>();
        series.add(instrumentSeries("cohort.lif", "Mouse1_LH_SCN",
                "Leica SP8", "HC PL APO", 40.0, 1.3, "Oil", null, "3.5.7"));
        series.add(instrumentSeries("cohort.lif", "Mouse2_LH_SCN",
                "Leica SP8", "HC PL APO", 40.0, 1.3, "Oil", null, "3.5.7"));
        series.add(instrumentSeries("other.lif", "Mouse3_LH_SCN",
                null, null, null, null, null, null, null));

        DiagnosticsReport.Section section = new DiagnosticsReport("x").addSection("Instrument");
        MetadataDiagnostics.checkInstrumentFingerprint(series, section);

        assertTrue(hasFinding(section, DiagnosticsReport.Severity.INFO,
                "2 files: Leica SP8 / HC PL APO 40x 1.3 Oil / software 3.5.7"));
        assertTrue(hasFinding(section, DiagnosticsReport.Severity.INFO,
                "unknown instrument - check acquisition"));
    }

    @Test
    public void populateDeconvolutionMetadataReadsEmissionWavelengthsAndSampleRi() throws Exception {
        MetadataDiagnostics.SeriesInfo info = new MetadataDiagnostics.SeriesInfo();
        info.sizeC = 3;
        info.objectiveImmersion = "oil";

        MetadataRetrieve retrieve = mock(MetadataRetrieve.class);
        when(retrieve.getChannelEmissionWavelength(0, 0))
                .thenReturn(new Length(Double.valueOf(488.0), UNITS.NANOMETER));
        when(retrieve.getChannelEmissionWavelength(0, 1))
                .thenReturn(new Length(Double.valueOf(568.0), UNITS.NANOMETER));
        when(retrieve.getChannelEmissionWavelength(0, 2))
                .thenReturn(new Length(Double.valueOf(647.0), UNITS.NANOMETER));

        MetadataDiagnostics.populateDeconvolutionMetadata(info, retrieve, 0);

        assertArrayEquals(new double[]{488.0, 568.0, 647.0}, info.emissionWavelengthNm, 0.0);
        assertEquals(RefractiveIndexEstimator.immersionRI("oil"),
                info.sampleRefractiveIndex.doubleValue(), 0.0);
    }

    private static MetadataDiagnostics.SeriesInfo series(String imageName, String acquisitionDate) {
        MetadataDiagnostics.SeriesInfo info = new MetadataDiagnostics.SeriesInfo();
        info.file = "placeholder.lif";
        info.imageName = imageName;
        info.acquisitionDate = acquisitionDate;
        info.extension = "lif";
        return info;
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

    private static void writeSyntheticTiff(File target, String title,
                                           int width, int height, int slices) {
        ImagePlus image = IJ.createImage(title, "8-bit ramp", width, height, slices);
        try {
            IJ.saveAsTiff(image, target.getAbsolutePath());
        } finally {
            image.close();
            image.flush();
        }
    }

    private static MetadataDiagnostics.SeriesInfo instrumentSeries(String file,
                                                                   String imageName,
                                                                   String microscope,
                                                                   String objectiveModel,
                                                                   Double objectiveMag,
                                                                   Double objectiveNa,
                                                                   String immersion,
                                                                   String softwareName,
                                                                   String softwareVersion) {
        MetadataDiagnostics.SeriesInfo info = new MetadataDiagnostics.SeriesInfo();
        info.file = file;
        info.imageName = imageName;
        info.extension = "lif";
        info.microscopeModel = microscope;
        info.objectiveModel = objectiveModel;
        info.objectiveMag = objectiveMag;
        info.objectiveNA = objectiveNa;
        info.objectiveImmersion = immersion;
        info.acquisitionSoftware = softwareName;
        info.acquisitionSoftwareVersion = softwareVersion;
        return info;
    }

    private static boolean hasSeverity(DiagnosticsReport.Section section, DiagnosticsReport.Severity severity) {
        for (DiagnosticsReport.Finding finding : section.findings) {
            if (finding.severity == severity) return true;
        }
        return false;
    }

    private static boolean hasFinding(DiagnosticsReport.Section section,
                                      DiagnosticsReport.Severity severity,
                                      String snippet) {
        for (DiagnosticsReport.Finding finding : section.findings) {
            if (finding.severity == severity && finding.message.contains(snippet)) return true;
        }
        return false;
    }
}
