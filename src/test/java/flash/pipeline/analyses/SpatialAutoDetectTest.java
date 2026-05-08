package flash.pipeline.analyses;

import flash.pipeline.io.CsvTableIO;
import flash.pipeline.io.CsvTableIO.ChannelData;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.PrintWriter;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

public class SpatialAutoDetectTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void detectsReusable3DObjectColumnsFromObjectCsvs() throws Exception {
        File objectsDir = temp.newFolder("objects-present");
        writeChannel(objectsDir, "A.csv",
                "Label,Volume (micron^3),Surface (micron^2),XM,YM,ZM,Colocalisation with B,"
                        + "A_VolColoc30_B,A_VolContains30_B,A_DistToClosest_B,A_ClosestTo_B,"
                        + "A_CPCColoc_B,A_CPCContains_B,A_CPCTargetsHit,A_CPCPattern,Length,"
                        + "Morph_Sphericity,Morph_Compactness,Morph_Elongation,Morph_Flatness,"
                        + "Morph_Spareness,Morph_MajorRadius_um,Morph_Feret3D_um,"
                        + "Morph_Moment1,Morph_Moment2,Morph_Moment3,Morph_Moment4,Morph_Moment5,"
                        + "Morph_DistCenter_Min_um,Morph_DistCenter_Max_um,"
                        + "Morph_DistCenter_Mean_um,Morph_DistCenter_SD_um",
                "1,10,5,1,2,3,45,1,2,8,1,1,1,1,B,123,"
                        + "0.5,0.1,1.2,1.1,0.8,4,9,0.1,0.2,0.3,0.4,0.5,1,2,1.5,0.2");
        writeChannel(objectsDir, "B.csv",
                "Label,Volume (micron^3),Surface (micron^2),XM,YM,ZM,Colocalisation with A,"
                        + "B_VolColoc30_A,B_VolContains30_A,B_DistToClosest_A,B_ClosestTo_A,"
                        + "B_CPCColoc_A,B_CPCContains_A,B_CPCTargetsHit,B_CPCPattern,Length,"
                        + "Morph_Sphericity,Morph_Compactness,Morph_Elongation,Morph_Flatness,"
                        + "Morph_Spareness,Morph_MajorRadius_um,Morph_Feret3D_um,"
                        + "Morph_Moment1,Morph_Moment2,Morph_Moment3,Morph_Moment4,Morph_Moment5,"
                        + "Morph_DistCenter_Min_um,Morph_DistCenter_Max_um,"
                        + "Morph_DistCenter_Mean_um,Morph_DistCenter_SD_um",
                "1,12,6,2,2,3,40,1,3,8,1,1,1,1,A,456,"
                        + "0.6,0.2,1.3,1.2,0.7,5,10,0.2,0.3,0.4,0.5,0.6,1,2,1.5,0.2");

        SpatialAnalysis.SpatialObjectDataAvailability detected = detect(objectsDir);

        assertTrue(detected.hasObjectSizeDataForAllChannels(Arrays.asList("A", "B")));
        assertTrue(detected.hasProcessLengthData());
        assertTrue(detected.hasDirectedVolumetricOverlap("A", "B"));
        assertTrue(detected.hasVolumetricPair("A", "B", 30, 30));
        assertTrue(detected.hasDistancePair("A", "B"));
        assertTrue(detected.hasCompleteCpcForAllChannels(Arrays.asList("A", "B")));
        assertTrue(detected.has3DMorphologyForAllChannels(Arrays.asList("A", "B")));
        assertTrue(detected.morphometryHelperText().contains("process length data"));
    }

    @Test
    public void treatsAbsentReusableColumnsAsMissing() throws Exception {
        File objectsDir = temp.newFolder("objects-missing");
        writeChannel(objectsDir, "A.csv",
                "Label,XM,YM,ZM",
                "1,1,2,3");
        writeChannel(objectsDir, "B.csv",
                "Label,XM,YM,ZM",
                "1,2,2,3");

        SpatialAnalysis.SpatialObjectDataAvailability detected = detect(objectsDir);

        assertFalse(detected.hasObjectSizeDataForAllChannels(Arrays.asList("A", "B")));
        assertFalse(detected.hasProcessLengthData());
        assertFalse(detected.hasDirectedVolumetricOverlap("A", "B"));
        assertFalse(detected.hasVolumetricPair("A", "B", 30, 30));
        assertFalse(detected.hasDistancePair("A", "B"));
        assertFalse(detected.hasCompleteCpcForAllChannels(Arrays.asList("A", "B")));
        assertFalse(detected.has3DMorphologyForAllChannels(Arrays.asList("A", "B")));
        assertTrue(detected.colocalizationHelperText().contains("No saved colocalization/contact"));
    }

    private SpatialAnalysis.SpatialObjectDataAvailability detect(File objectsDir) {
        Map<String, ChannelData> channels = new LinkedHashMap<String, ChannelData>();
        channels.put("A", CsvTableIO.loadChannelCsv(new File(objectsDir, "A.csv"), "A"));
        channels.put("B", CsvTableIO.loadChannelCsv(new File(objectsDir, "B.csv"), "B"));
        List<String> channelNames = Arrays.asList("A", "B");
        return SpatialAnalysis.SpatialObjectDataAvailability.detect(channels, channelNames);
    }

    private void writeChannel(File objectsDir, String filename, String header, String row) throws Exception {
        File csv = new File(objectsDir, filename);
        PrintWriter pw = new PrintWriter(csv, "UTF-8");
        try {
            pw.println(header);
            pw.println(row);
        } finally {
            pw.close();
        }
    }
}
