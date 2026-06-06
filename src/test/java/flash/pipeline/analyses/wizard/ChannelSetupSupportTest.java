package flash.pipeline.analyses.wizard;

import flash.pipeline.intelligence.MetadataDiagnostics;
import flash.pipeline.marker.MarkerLibrary;
import flash.pipeline.ui.wizard.SegmentationEnginePicker;
import ij.ImagePlus;
import ij.ImageStack;
import ij.io.FileSaver;
import ij.process.ByteProcessor;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ChannelSetupSupportTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    private static final String[] TWELVE_MARKERS = {
            "nuclei_dapi",
            "microglia_iba1",
            "astrocytes_gfap",
            "neurons_neun",
            "synapse_syp",
            "synapse_psd95",
            "amyloid_abeta_pan",
            "tau_phospho_epitopes",
            "microglia_cd68",
            "oligo_olig2",
            "mito_tom20",
            "reporter_mcherry"
    };

    @Test
    public void derivesConfigForTwelveMarkersAcrossSignalAndCrowdingAnswers() throws Exception {
        MarkerLibrary library = MarkerLibraryIO.loadBundled();
        MetadataDiagnostics.SeriesInfo info = seriesInfo(12, 40, "uint16", "twelve");
        SegmentationEnginePicker.EngineAvailability availability =
                new SegmentationEnginePicker.EngineAvailability(true, true);

        for (String signal : new String[]{
                ChannelSetupSupport.SIGNAL_DIM,
                ChannelSetupSupport.SIGNAL_TYPICAL,
                ChannelSetupSupport.SIGNAL_BRIGHT}) {
            for (String crowding : new String[]{
                    ChannelSetupSupport.CROWDING_SPARSE,
                    ChannelSetupSupport.CROWDING_CROWDED}) {
                Map<String, Object> answers = new LinkedHashMap<String, Object>();
                answers.put("channelCount", Integer.valueOf(TWELVE_MARKERS.length));
                for (int i = 0; i < TWELVE_MARKERS.length; i++) {
                    answers.put("channel" + (i + 1) + ".markerId", TWELVE_MARKERS[i]);
                    answers.put("channel" + (i + 1) + ".signal", signal);
                    answers.put("channel" + (i + 1) + ".crowding", crowding);
                }
                answers.put("zSliceMode", "Middle 40%");

                ChannelSetupSupport.DerivedConfig derived =
                        ChannelSetupSupport.deriveConfig(info, answers, library, availability);

                assertEquals(TWELVE_MARKERS.length, derived.names.size());
                assertEquals(TWELVE_MARKERS.length, derived.filterPresets.size());
                assertFalse(derived.objectThresholds.contains("default"));
                assertEquals("PER_IMAGE", derived.zSliceMode.name());
                assertFalse(derived.zSliceSelections.isEmpty());
            }
        }
    }

    @Test
    public void dimSignalHalvesThresholdAgainstTypical() throws Exception {
        MarkerLibrary library = MarkerLibraryIO.loadBundled();
        MetadataDiagnostics.SeriesInfo info = seriesInfo(1, 10, "uint16", "reporter");

        Map<String, Object> typical = oneChannel("reporter_mcherry", ChannelSetupSupport.SIGNAL_TYPICAL);
        Map<String, Object> dim = oneChannel("reporter_mcherry", ChannelSetupSupport.SIGNAL_DIM);

        double typicalThreshold = Double.parseDouble(ChannelSetupSupport
                .deriveConfig(info, typical, library, null).objectThresholds.get(0));
        double dimThreshold = Double.parseDouble(ChannelSetupSupport
                .deriveConfig(info, dim, library, null).objectThresholds.get(0));

        assertEquals(typicalThreshold / 2.0, dimThreshold, 0.0001);
    }

    @Test
    public void customMarkerLeavesChannelManual() throws Exception {
        MarkerLibrary library = MarkerLibraryIO.loadBundled();
        MetadataDiagnostics.SeriesInfo info = seriesInfo(2, 10, "uint16", "unnamed");
        Map<String, Object> answers = new LinkedHashMap<String, Object>();
        answers.put("channelCount", Integer.valueOf(2));
        answers.put("channel1.markerId", "nuclei_dapi");
        answers.put("channel2.markerId", ChannelSetupSupport.MARKER_CUSTOM);

        ChannelSetupSupport.DerivedConfig derived = ChannelSetupSupport.deriveConfig(info, answers, library, null);

        assertFalse(derived.manual[0]);
        assertTrue(derived.manual[1]);
        assertEquals("default", derived.objectThresholds.get(1));
    }

    @Test
    public void firstSeriesInfoReadsChannelCountFromInputSubfolderMetadata() throws Exception {
        File project = temp.newFolder("input-subfolder-metadata");
        File input = new File(project, "input");
        assertTrue(input.mkdirs());
        File imageFile = new File(input, "three-channel.tif");

        assertTrue(new FileSaver(threeChannelImage()).saveAsTiffStack(imageFile.getAbsolutePath()));

        MetadataDiagnostics.SeriesInfo info =
                ChannelSetupSupport.firstSeriesInfo(project.getAbsolutePath());

        assertTrue(info != null);
        assertEquals(3, info.sizeC);
    }

    private static Map<String, Object> oneChannel(String markerId, String signal) {
        Map<String, Object> answers = new LinkedHashMap<String, Object>();
        answers.put("channelCount", Integer.valueOf(1));
        answers.put("channel1.markerId", markerId);
        answers.put("channel1.signal", signal);
        return answers;
    }

    private static ImagePlus threeChannelImage() {
        ImageStack stack = new ImageStack(2, 2);
        stack.addSlice(new ByteProcessor(2, 2));
        stack.addSlice(new ByteProcessor(2, 2));
        stack.addSlice(new ByteProcessor(2, 2));
        ImagePlus image = new ImagePlus("three-channel", stack);
        image.setDimensions(3, 1, 1);
        return image;
    }

    private static MetadataDiagnostics.SeriesInfo seriesInfo(int channels, int z, String pixelType, String name) {
        MetadataDiagnostics.SeriesInfo info = new MetadataDiagnostics.SeriesInfo();
        info.sizeC = channels;
        info.sizeZ = z;
        info.pixelType = pixelType;
        info.imageName = name;
        return info;
    }
}
