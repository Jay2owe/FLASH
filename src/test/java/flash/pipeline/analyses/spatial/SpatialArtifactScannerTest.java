package flash.pipeline.analyses.spatial;

import flash.pipeline.io.FlashProjectLayout;
import flash.pipeline.naming.ChannelFilenameCodec;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SpatialArtifactScannerTest {
    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void scanDetectsColumnOnlySubAnalysesForAllSectionsInChannel() throws Exception {
        File project = temp.newFolder("project");
        FlashProjectLayout layout = FlashProjectLayout.forDirectory(project.getAbsolutePath());
        writeHeader(layout.tablesObjectsWriteDir(), "IBA1",
                "Label",
                "IBA1_CPCColoc_GFAP",
                "Morph_Area_um2",
                "Morph_Sphericity",
                "IBA1_DistToClosest_GFAP",
                "Voronoi_TerritoryArea_um2",
                "Cluster",
                "Morph_CMS",
                "Morph_SMSD",
                "Morph_IMDI",
                "Morph_TDR");

        List<SectionKey> sections = Arrays.asList(
                SectionKey.of("Animal1", "LH_SCN"),
                SectionKey.of("Animal1", "RH_SCN"));
        SpatialArtifactStatus status = new SpatialArtifactScanner().scan(
                project.getAbsolutePath(), Arrays.asList("IBA1"), sections);

        assertTrue(status.isFullyDone(SubAnalysis.CPC));
        assertTrue(status.isFullyDone(SubAnalysis.MORPHOLOGY_2D));
        assertTrue(status.isFullyDone(SubAnalysis.SHAPE_FEATURES_3D));
        assertTrue(status.isFullyDone(SubAnalysis.INTER_MARKER_DISTANCES));
        assertTrue(status.isFullyDone(SubAnalysis.VORONOI));
        assertTrue(status.isFullyDone(SubAnalysis.PHENOTYPING));
        assertTrue(status.isFullyDone(SubAnalysis.POPULATION_MORPHO));
        assertTrue(status.isFullyDone(SubAnalysis.SPATIAL_MORPHO));
        assertFalse(status.isFullyDone(SubAnalysis.LINE_DISTANCE));
    }

    @Test
    public void scanDetectsHeatmapsPerSectionChannelPairOnly() throws Exception {
        File project = temp.newFolder("project");
        FlashProjectLayout layout = FlashProjectLayout.forDirectory(project.getAbsolutePath());
        List<SectionKey> sections = Arrays.asList(
                SectionKey.of("Animal1", "LH_SCN"),
                SectionKey.of("Animal1", "RH_SCN"));
        touch(new File(layout.spatialImageOutputsWriteDir(),
                "Animal1/Heatmaps/Density_"
                        + ChannelFilenameCodec.toSafe("IBA1") + "_LH_SCN.tif"));

        SpatialArtifactStatus status = new SpatialArtifactScanner().scan(
                project.getAbsolutePath(), Arrays.asList("IBA1"), sections);

        assertTrue(status.isDone(SubAnalysis.DENSITY_HEATMAPS, sections.get(0), "IBA1"));
        assertFalse(status.isDone(SubAnalysis.DENSITY_HEATMAPS, sections.get(1), "IBA1"));
        assertTrue(status.isPartiallyDone(SubAnalysis.DENSITY_HEATMAPS));
        assertEquals(1, status.missingPairs(SubAnalysis.DENSITY_HEATMAPS).size());
    }

    @Test
    public void scanReadsLineDistanceOutputHeaders() throws Exception {
        File project = temp.newFolder("project");
        FlashProjectLayout layout = FlashProjectLayout.forDirectory(project.getAbsolutePath());
        writeHeader(layout.lineDistanceWriteDir(), "DAPI",
                "Label",
                "DAPI_DistTo_Midline");

        List<SectionKey> sections = Arrays.asList(SectionKey.of("Animal1", "LH_SCN"));
        SpatialArtifactStatus status = new SpatialArtifactScanner().scan(
                project.getAbsolutePath(), Arrays.asList("DAPI"), sections);

        assertTrue(status.isFullyDone(SubAnalysis.LINE_DISTANCE));
    }

    private static void writeHeader(File dir, String channel, String... columns) throws IOException {
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new IOException("Could not create " + dir);
        }
        File csv = new File(dir, ChannelFilenameCodec.toSafe(channel) + ".csv");
        PrintWriter out = new PrintWriter(Files.newBufferedWriter(csv.toPath(), StandardCharsets.UTF_8));
        try {
            for (int i = 0; i < columns.length; i++) {
                if (i > 0) out.print(',');
                out.print(columns[i]);
            }
            out.println();
        } finally {
            out.close();
        }
    }

    private static void touch(File file) throws IOException {
        File parent = file.getParentFile();
        if (!parent.isDirectory() && !parent.mkdirs()) {
            throw new IOException("Could not create " + parent);
        }
        Files.write(file.toPath(), new byte[0]);
    }
}
