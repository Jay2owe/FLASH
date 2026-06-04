package flash.pipeline.analyses.wizard;

import flash.pipeline.intelligence.MetadataDiagnostics;
import flash.pipeline.marker.MarkerLibrary;
import org.junit.Test;

import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class BinAutoDetectTest {

    @Test
    public void containerNamePreselectsDapiIba1GfapInNameOrder() throws Exception {
        MetadataDiagnostics.SeriesInfo info = new MetadataDiagnostics.SeriesInfo();
        info.sizeC = 3;
        info.file = "DAPI_Iba1_GFAP.lif";
        MarkerLibrary library = MarkerLibraryIO.loadBundled();

        Map<Integer, MarkerLibrary.Entry> detected = ChannelSetupSupport.autoDetectMarkers(info, library);

        assertEquals("nuclei_dapi", detected.get(Integer.valueOf(0)).getId());
        assertEquals("microglia_iba1", detected.get(Integer.valueOf(1)).getId());
        assertEquals("astrocytes_gfap", detected.get(Integer.valueOf(2)).getId());
    }

    @Test
    public void imageNameAloneDoesNotPreselectMarkers() throws Exception {
        MetadataDiagnostics.SeriesInfo info = new MetadataDiagnostics.SeriesInfo();
        info.sizeC = 1;
        info.imageName = "DAPI";
        MarkerLibrary library = MarkerLibraryIO.loadBundled();

        Map<Integer, MarkerLibrary.Entry> detected = ChannelSetupSupport.autoDetectMarkers(info, library);

        assertTrue(detected.isEmpty());
    }

    @Test
    public void broadSubstringsInContainerDoNotPreselectMarkers() throws Exception {
        MetadataDiagnostics.SeriesInfo info = new MetadataDiagnostics.SeriesInfo();
        info.sizeC = 3;
        info.file = "workflow_pipeline_sample.lif";
        MarkerLibrary library = MarkerLibraryIO.loadBundled();

        Map<Integer, MarkerLibrary.Entry> detected = ChannelSetupSupport.autoDetectMarkers(info, library);

        assertTrue(detected.isEmpty());
    }
}
