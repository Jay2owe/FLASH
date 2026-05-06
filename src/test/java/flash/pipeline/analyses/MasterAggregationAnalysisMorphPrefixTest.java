package flash.pipeline.analyses;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Regression guard for the Step 14g fix: a hypothetical future composite
 * column whose name already encodes channel identity (e.g.
 * {@code Morph_PairwiseSpread_GFAP_Iba1}) must NOT be re-prefixed by the
 * master aggregator, otherwise we'd double the channel namespace into
 * {@code <channel>_Morph_PairwiseSpread_GFAP_Iba1}.
 */
public class MasterAggregationAnalysisMorphPrefixTest {

    @Test
    public void hypotheticalChannelEncodedMorphColumnIsNotPrefixed() {
        assertFalse("future Morph_*_<chA>_<chB> columns must not be auto-prefixed",
                MasterAggregationAnalysis.needsChannelPrefix("Morph_PairwiseSpread_GFAP_Iba1"));
        assertFalse(MasterAggregationAnalysis.needsChannelPrefix("Morph_NewComposite_GFAP"));
        assertFalse(MasterAggregationAnalysis.needsChannelPrefix("Morph_AnotherFuture_Iba1_NeuN"));
    }

    @Test
    public void knownChannelAgnosticMorphColumnsArePrefixed() {
        assertTrue(MasterAggregationAnalysis.needsChannelPrefix("Morph_Sphericity"));
        assertTrue(MasterAggregationAnalysis.needsChannelPrefix("Morph_CMS"));
        assertTrue(MasterAggregationAnalysis.needsChannelPrefix("Morph_SMSD"));
        assertTrue(MasterAggregationAnalysis.needsChannelPrefix("Morph_IMDI"));
        assertTrue(MasterAggregationAnalysis.needsChannelPrefix("Morph_Area_um2"));
        assertTrue(MasterAggregationAnalysis.needsChannelPrefix("Morph_Moment3"));
        assertTrue(MasterAggregationAnalysis.needsChannelPrefix("Morph_FEV_Mag"));
        assertTrue(MasterAggregationAnalysis.needsChannelPrefix("Morph_TDR"));
    }

    @Test
    public void voronoiAndClusterStillPrefixed() {
        assertTrue(MasterAggregationAnalysis.needsChannelPrefix("Voronoi_AreaMean"));
        assertTrue(MasterAggregationAnalysis.needsChannelPrefix("Cluster"));
    }

    @Test
    public void nonChannelAgnosticHeadersAreNotPrefixed() {
        assertFalse(MasterAggregationAnalysis.needsChannelPrefix("GFAP_Pearson_Iba1"));
        assertFalse(MasterAggregationAnalysis.needsChannelPrefix("Cluster_Tag"));
        assertFalse(MasterAggregationAnalysis.needsChannelPrefix("Volume"));
        assertFalse(MasterAggregationAnalysis.needsChannelPrefix(""));
    }

    @Test
    public void allowlistContainsAllExpectedChannelAgnosticColumns() {
        // Spot-check tier coverage so future re-orderings of the static block
        // don't silently drop columns from the allowlist.
        String[] expected = new String[] {
                "Morph_Area_um2", "Morph_Perimeter_um", "Morph_Circularity",
                "Morph_Solidity", "Morph_AspectRatio", "Morph_Feret_um",
                "Morph_Extent", "Morph_ConvexHullArea_um2",
                "Morph_Sphericity", "Morph_Compactness", "Morph_Elongation",
                "Morph_Flatness", "Morph_Spareness", "Morph_MajorRadius_um",
                "Morph_Feret3D_um",
                "Morph_Moment1", "Morph_Moment2", "Morph_Moment3",
                "Morph_Moment4", "Morph_Moment5",
                "Morph_DistCenter_Min_um", "Morph_DistCenter_Max_um",
                "Morph_DistCenter_Mean_um", "Morph_DistCenter_SD_um",
                "Morph_RI", "Morph_SRI", "Morph_PB", "Morph_MP", "Morph_VSD",
                "Morph_CMS", "Morph_SMSD", "Morph_IMDI",
                "Morph_TDR", "Morph_FEV_Mag"
        };
        for (String col : expected) {
            assertTrue("expected " + col + " in CHANNEL_AGNOSTIC_MORPH_COLS",
                    MasterAggregationAnalysis.CHANNEL_AGNOSTIC_MORPH_COLS.contains(col));
        }
    }
}
