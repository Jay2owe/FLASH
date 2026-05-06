package flash.pipeline.bin;

import org.junit.Test;
import static org.junit.Assert.*;

public class BinConfigTest {

    private BinConfig makeConfig(String... methods) {
        BinConfig cfg = new BinConfig();
        cfg.channelNames.add("DAPI");
        cfg.channelNames.add("GFP");
        cfg.channelColors.add("Blue");
        cfg.channelColors.add("Green");
        cfg.channelThresholds.add("default");
        cfg.channelThresholds.add("500");
        cfg.channelSizes.add("100-Infinity");
        cfg.channelSizes.add("50-5000");
        cfg.channelMinMax.add("None");
        cfg.channelMinMax.add("0-4095");
        cfg.channelIntensityThresholds.add("default");
        cfg.channelIntensityThresholds.add("750");
        for (String m : methods) {
            cfg.segmentationMethods.add(m);
        }
        return cfg;
    }

    @Test
    public void numChannels_returnsCorrectCount() {
        BinConfig cfg = makeConfig("classical", "classical");
        assertEquals(2, cfg.numChannels());
    }

    @Test
    public void numChannels_emptyConfig() {
        BinConfig cfg = new BinConfig();
        assertEquals(0, cfg.numChannels());
    }

    @Test
    public void filterMacroFilename_index0() {
        BinConfig cfg = new BinConfig();
        assertEquals("C1_Filters.ijm", cfg.filterMacroFilenameForChannelIndex(0));
    }

    @Test
    public void filterMacroFilename_index2() {
        BinConfig cfg = new BinConfig();
        assertEquals("C3_Filters.ijm", cfg.filterMacroFilenameForChannelIndex(2));
    }

    @Test
    public void isStarDist_trueForStarDistMethod() {
        BinConfig cfg = makeConfig("classical", "stardist:0.5:0.4");
        assertTrue(cfg.isStarDist(1));
    }

    @Test
    public void isStarDist_falseForClassical() {
        BinConfig cfg = makeConfig("classical", "stardist:0.5:0.4");
        assertFalse(cfg.isStarDist(0));
    }

    @Test
    public void isStarDist_negativeIndex() {
        BinConfig cfg = makeConfig("classical");
        assertFalse(cfg.isStarDist(-1));
    }

    @Test
    public void isStarDist_outOfBoundsIndex() {
        BinConfig cfg = makeConfig("classical");
        assertFalse(cfg.isStarDist(99));
    }

    @Test
    public void isStarDist_emptySegmentationList() {
        BinConfig cfg = new BinConfig();
        assertFalse(cfg.isStarDist(0));
    }

    @Test
    public void getStarDistProbThresh_parsesCorrectly() {
        BinConfig cfg = makeConfig("classical", "stardist:0.6:0.3");
        assertEquals(0.6, cfg.getStarDistProbThresh(1), 0.001);
    }

    @Test
    public void getStarDistProbThresh_defaultForClassical() {
        BinConfig cfg = makeConfig("classical");
        assertEquals(0.5, cfg.getStarDistProbThresh(0), 0.001);
    }

    @Test
    public void getStarDistProbThresh_malformedNoColons() {
        BinConfig cfg = makeConfig("stardist");
        // "stardist" starts with "stardist" so isStarDist=true, but split gives only 1 part
        assertEquals(0.5, cfg.getStarDistProbThresh(0), 0.001);
    }

    @Test
    public void getStarDistProbThresh_nonNumeric() {
        BinConfig cfg = makeConfig("stardist:abc:def");
        assertEquals(0.5, cfg.getStarDistProbThresh(0), 0.001);
    }

    @Test
    public void getStarDistNmsThresh_parsesCorrectly() {
        BinConfig cfg = makeConfig("classical", "stardist:0.6:0.3");
        assertEquals(0.3, cfg.getStarDistNmsThresh(1), 0.001);
    }

    @Test
    public void getStarDistNmsThresh_defaultForClassical() {
        BinConfig cfg = makeConfig("classical");
        assertEquals(0.4, cfg.getStarDistNmsThresh(0), 0.001);
    }

    @Test
    public void getStarDistNmsThresh_malformedTwoPartsOnly() {
        BinConfig cfg = makeConfig("stardist:0.5");
        // Only 2 parts, so NMS (parts[2]) is missing -> default 0.4
        assertEquals(0.4, cfg.getStarDistNmsThresh(0), 0.001);
    }

    // ── Post-detection filter tests ──

    @Test
    public void getStarDistLinkingMaxDistance_parsesCorrectly() {
        BinConfig cfg = makeConfig("stardist:0.5:0.4:linking=4.5");
        assertEquals(4.5, cfg.getStarDistLinkingMaxDistance(0), 0.001);
    }

    @Test
    public void getStarDistLinkingMaxDistance_defaultsWhenMissing() {
        BinConfig cfg = makeConfig("stardist:0.5:0.4");
        assertEquals(BinConfig.DEFAULT_STARDIST_LINKING_MAX_DISTANCE,
                cfg.getStarDistLinkingMaxDistance(0), 0.001);
    }

    @Test
    public void getStarDistGapClosingMaxDistance_parsesCorrectly() {
        BinConfig cfg = makeConfig("stardist:0.5:0.4:gapClosing=6.5");
        assertEquals(6.5, cfg.getStarDistGapClosingMaxDistance(0), 0.001);
    }

    @Test
    public void getStarDistGapClosingMaxDistance_defaultsWhenMissing() {
        BinConfig cfg = makeConfig("stardist:0.5:0.4");
        assertEquals(BinConfig.DEFAULT_STARDIST_GAP_CLOSING_MAX_DISTANCE,
                cfg.getStarDistGapClosingMaxDistance(0), 0.001);
    }

    @Test
    public void getStarDistMaxFrameGap_parsesCorrectly() {
        BinConfig cfg = makeConfig("stardist:0.5:0.4:frameGap=3");
        assertEquals(3, cfg.getStarDistMaxFrameGap(0));
    }

    @Test
    public void getStarDistMaxFrameGap_defaultsWhenMissing() {
        BinConfig cfg = makeConfig("stardist:0.5:0.4");
        assertEquals(BinConfig.DEFAULT_STARDIST_MAX_FRAME_GAP, cfg.getStarDistMaxFrameGap(0));
    }

    @Test
    public void getStarDistAreaMin_parsesCorrectly() {
        BinConfig cfg = makeConfig("stardist:0.5:0.4:area=50.0-5000.0");
        assertEquals(50.0, cfg.getStarDistAreaMin(0), 0.001);
    }

    @Test
    public void getStarDistAreaMax_parsesCorrectly() {
        BinConfig cfg = makeConfig("stardist:0.5:0.4:area=50.0-5000.0");
        assertEquals(5000.0, cfg.getStarDistAreaMax(0), 0.001);
    }

    @Test
    public void getStarDistAreaMax_infinity() {
        BinConfig cfg = makeConfig("stardist:0.5:0.4:area=50.0-Infinity");
        assertTrue(Double.isInfinite(cfg.getStarDistAreaMax(0)));
    }

    @Test
    public void getStarDistAreaMin_defaultWhenMissing() {
        BinConfig cfg = makeConfig("stardist:0.5:0.4");
        assertEquals(0, cfg.getStarDistAreaMin(0), 0.001);
    }

    @Test
    public void getStarDistAreaMax_defaultWhenMissing() {
        BinConfig cfg = makeConfig("stardist:0.5:0.4");
        assertTrue(Double.isInfinite(cfg.getStarDistAreaMax(0)));
    }

    @Test
    public void getStarDistQualityMin_parsesCorrectly() {
        BinConfig cfg = makeConfig("stardist:0.5:0.4:quality=0.3");
        assertEquals(0.3, cfg.getStarDistQualityMin(0), 0.001);
    }

    @Test
    public void getStarDistQualityMin_defaultWhenMissing() {
        BinConfig cfg = makeConfig("stardist:0.5:0.4");
        assertEquals(0, cfg.getStarDistQualityMin(0), 0.001);
    }

    @Test
    public void getStarDistIntensityMin_parsesCorrectly() {
        BinConfig cfg = makeConfig("stardist:0.5:0.4:intensity=100.0");
        assertEquals(100.0, cfg.getStarDistIntensityMin(0), 0.001);
    }

    @Test
    public void getStarDistIntensityMin_defaultWhenMissing() {
        BinConfig cfg = makeConfig("stardist:0.5:0.4");
        assertEquals(0, cfg.getStarDistIntensityMin(0), 0.001);
    }

    @Test
    public void getStarDistFilters_allPresent() {
        BinConfig cfg = makeConfig("stardist:0.5:0.4:area=25.0-10000.0:quality=0.2:intensity=500.0");
        assertEquals(25.0, cfg.getStarDistAreaMin(0), 0.001);
        assertEquals(10000.0, cfg.getStarDistAreaMax(0), 0.001);
        assertEquals(0.2, cfg.getStarDistQualityMin(0), 0.001);
        assertEquals(500.0, cfg.getStarDistIntensityMin(0), 0.001);
    }

    @Test
    public void getStarDistFilters_classicalChannel() {
        BinConfig cfg = makeConfig("classical");
        assertEquals(0, cfg.getStarDistAreaMin(0), 0.001);
        assertTrue(Double.isInfinite(cfg.getStarDistAreaMax(0)));
        assertEquals(0, cfg.getStarDistQualityMin(0), 0.001);
        assertEquals(0, cfg.getStarDistIntensityMin(0), 0.001);
    }

    @Test
    public void getStarDistFilters_partialFilters() {
        BinConfig cfg = makeConfig("stardist:0.5:0.4:quality=0.15");
        assertEquals(0, cfg.getStarDistAreaMin(0), 0.001);
        assertTrue(Double.isInfinite(cfg.getStarDistAreaMax(0)));
        assertEquals(0.15, cfg.getStarDistQualityMin(0), 0.001);
        assertEquals(0, cfg.getStarDistIntensityMin(0), 0.001);
    }

    @Test
    public void isCellpose_trueForCellposeMethod() {
        BinConfig cfg = makeConfig("classical", "cellpose:30.0:cyto3:0.4:0.0:gpu=true");
        assertTrue(cfg.isCellpose(1));
        assertFalse(cfg.isCellpose(0));
    }

    @Test
    public void usesLabelImageSegmentation_trueForCellposeAndStarDist() {
        BinConfig cfg = makeConfig("stardist:0.5:0.4", "cellpose:30.0:cyto3:0.4:0.0:gpu=true");
        assertTrue(cfg.usesLabelImageSegmentation(0));
        assertTrue(cfg.usesLabelImageSegmentation(1));
    }

    @Test
    public void getCellposeDiameter_parsesCorrectly() {
        BinConfig cfg = makeConfig("cellpose:22.5:cyto3:0.3:-1.0:gpu=false");
        assertEquals(22.5, cfg.getCellposeDiameter(0), 0.001);
    }

    @Test
    public void getCellposeModel_parsesCorrectly() {
        BinConfig cfg = makeConfig("cellpose:22.5:nuclei:0.3:-1.0:gpu=false");
        assertEquals("nuclei", cfg.getCellposeModel(0));
    }

    @Test
    public void getCellposeFlowThreshold_parsesCorrectly() {
        BinConfig cfg = makeConfig("cellpose:22.5:cyto3:0.3:-1.0:gpu=false");
        assertEquals(0.3, cfg.getCellposeFlowThreshold(0), 0.001);
    }

    @Test
    public void getCellposeCellprobThreshold_parsesCorrectly() {
        BinConfig cfg = makeConfig("cellpose:22.5:cyto3:0.3:-1.0:gpu=false");
        assertEquals(-1.0, cfg.getCellposeCellprobThreshold(0), 0.001);
    }

    @Test
    public void getCellposeUseGpu_parsesCorrectly() {
        BinConfig cfg = makeConfig("cellpose:22.5:cyto3:0.3:-1.0:gpu=false");
        assertFalse(cfg.getCellposeUseGpu(0));
    }

    @Test
    public void getCellposeSecondChannel_parsesOptionalChan2Key() {
        BinConfig cfg = makeConfig("cellpose:22.5:cyto3:0.3:-1.0:gpu=true:chan2=1");
        assertEquals(1, cfg.getCellposeSecondChannel(0));
    }

    @Test
    public void getCellposeSecondChannel_defaultsWhenMissingOrInvalid() {
        BinConfig missing = makeConfig("cellpose:22.5:cyto3:0.3:-1.0:gpu=true");
        BinConfig invalid = makeConfig("cellpose:22.5:cyto3:0.3:-1.0:gpu=true:chan2=abc");
        assertEquals(-1, missing.getCellposeSecondChannel(0));
        assertEquals(-1, invalid.getCellposeSecondChannel(0));
    }

    @Test
    public void getCellposeDefaults_forClassicalChannel() {
        BinConfig cfg = makeConfig("classical");
        assertEquals(BinConfig.DEFAULT_CELLPOSE_DIAMETER, cfg.getCellposeDiameter(0), 0.001);
        assertEquals(BinConfig.DEFAULT_CELLPOSE_MODEL, cfg.getCellposeModel(0));
        assertEquals(BinConfig.DEFAULT_CELLPOSE_FLOW_THRESHOLD, cfg.getCellposeFlowThreshold(0), 0.001);
        assertEquals(BinConfig.DEFAULT_CELLPOSE_CELLPROB_THRESHOLD, cfg.getCellposeCellprobThreshold(0), 0.001);
        assertEquals(BinConfig.DEFAULT_CELLPOSE_USE_GPU, cfg.getCellposeUseGpu(0));
        assertEquals(-1, cfg.getCellposeSecondChannel(0));
    }
}
