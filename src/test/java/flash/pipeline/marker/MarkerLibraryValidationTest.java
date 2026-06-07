package flash.pipeline.marker;

import flash.pipeline.image.NamedFilterLoader;
import flash.pipeline.ui.wizard.JsonIO;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class MarkerLibraryValidationTest {

    private static final Set<String> LUTS = new HashSet<String>(Arrays.asList(
            "Grays", "Red", "Green", "Blue", "Cyan", "Magenta", "Yellow"));

    private static final Set<String> SHAPES = new HashSet<String>(Arrays.asList(
            "round", "complex", "puncta_like", "diffuse"));

    @Test
    public void bundledLibraryLoadsAndSearchesExpectedMarkers() throws Exception {
        MarkerLibrary library = MarkerLibrary.loadBundled();

        MarkerLibrary.Entry iba1 = library.byId("microglia_iba1");
        assertNotNull(iba1);
        assertEquals("IBA1", iba1.getDisplayName());
        assertFalse(iba1.getAliases().isEmpty());
        assertEquals("microglia", iba1.getCategory());
        assertEquals("ramified_processes", iba1.getStainingPattern());
        assertFalse(iba1.getTypicalObjectSizeNote().isEmpty());
        assertEquals("Green", iba1.getConventionalLUT());
        assertEquals("Ramified Cells (Microglia/Astrocytes)", iba1.getFilterPreset());
        assertEquals("complex", iba1.getShape());
        assertTrue(iba1.isCrowdingSensitive());
        assertEquals("medium", iba1.getParticleSizeHint());
        assertEquals("typical", iba1.getIntensityLevel());
        assertFalse(iba1.getNameHints().isEmpty());
        assertFalse(iba1.getNotes().isEmpty());

        assertEquals("microglia_iba1", library.search("iba", 5).get(0).getId());
        assertEquals("amyloid_h31l21", library.search("h31l21", 5).get(0).getId());
        assertTrue(containsNuclearEntryInTopThree(library.search("405", 5)));
        assertTrue(library.byCategory("microglia").contains(iba1));
        assertTrue(library.byCategory("amyloid").contains(library.byId("amyloid_h31l21")));
    }

    @Test
    public void bundledJsonMatchesSchemaAndReferenceCrosswalk() throws Exception {
        String json = readResource("/marker_library/markers.json");
        Map<String, Object> root = JsonIO.parseObject(json);
        MarkerLibrary library = MarkerLibrary.fromJson(json);

        assertTrue(library.libraryVersion() >= 1);

        Set<String> categories = new HashSet<String>(library.categories());
        Set<String> filters = new HashSet<String>(Arrays.asList(NamedFilterLoader.FILTER_NAMES));
        Set<String> ids = new HashSet<String>();
        Set<String> displayNames = new HashSet<String>();

        List<Object> rawMarkers = JsonIO.asList(root.get("markers"));
        assertEquals(rawMarkers.size(), library.entries().size());
        for (int i = 0; i < rawMarkers.size(); i++) {
            Map<String, Object> raw = JsonIO.asObject(rawMarkers.get(i));
            MarkerLibrary.Entry entry = library.entries().get(i);

            assertRequiredString(entry.getId(), "id");
            assertRequiredString(entry.getDisplayName(), "displayName");
            assertRequiredString(entry.getCategory(), "category");
            assertRequiredString(entry.getStainingPattern(), "stainingPattern");
            assertRequiredString(entry.getConventionalLUT(), "conventionalLUT");
            assertRequiredString(entry.getFilterPreset(), "filterPreset");
            assertRequiredString(entry.getShape(), "shape");
            assertTrue("crowdingSensitive missing for " + entry.getId(), raw.containsKey("crowdingSensitive"));
            assertTrue("crowdingSensitive must be boolean for " + entry.getId(), raw.get("crowdingSensitive") instanceof Boolean);
            assertRequiredString(entry.getParticleSizeHint(), "particleSizeHint");
            assertRequiredString(entry.getIntensityLevel(), "intensityLevel");
            assertFalse("nameHints empty for " + entry.getId(), entry.getNameHints().isEmpty());

            assertTrue("duplicate id " + entry.getId(), ids.add(entry.getId()));
            assertTrue("duplicate displayName " + entry.getDisplayName(), displayNames.add(entry.getDisplayName()));
            assertTrue("unknown category " + entry.getCategory(), categories.contains(entry.getCategory()));
            for (String additional : entry.getAdditionalCategories()) {
                assertTrue("unknown additionalCategory " + additional + " on " + entry.getId(),
                        categories.contains(additional));
                assertFalse("additionalCategories must not duplicate primary on " + entry.getId(),
                        additional.equals(entry.getCategory()));
            }
            assertTrue("unknown filter preset " + entry.getFilterPreset(), filters.contains(entry.getFilterPreset()));
            assertTrue("unknown LUT " + entry.getConventionalLUT(), LUTS.contains(entry.getConventionalLUT()));
            assertTrue("unknown shape " + entry.getShape(), SHAPES.contains(entry.getShape()));
            assertNameHintsAreLowercaseAndNonBlank(entry);
        }

        Map<String, List<String>> crosswalk = referenceCrosswalk();
        for (Map.Entry<String, List<String>> row : crosswalk.entrySet()) {
            for (String id : row.getValue()) {
                MarkerLibrary.Entry entry = library.byId(id);
                assertNotNull("Crosswalk target missing for " + row.getKey() + ": " + id, entry);
                assertCrosswalkLabelIndexed(row.getKey(), entry);
            }
        }
    }

    @Test
    public void markersInMultipleCategoriesAreFoundUnderEach() throws Exception {
        MarkerLibrary library = MarkerLibrary.loadBundled();

        // CD68 is a microglial activation marker but biologically a lysosomal protein:
        // it must be findable under both "microglia" (its primary) and "lysosomes".
        MarkerLibrary.Entry cd68 = library.byId("microglia_cd68");
        assertNotNull(cd68);
        assertEquals("microglia", cd68.getCategory());
        assertTrue("CD68 must list lysosomes as an additional category",
                cd68.getAdditionalCategories().contains("lysosomes"));
        assertTrue("byCategory(microglia) must still contain CD68",
                library.byCategory("microglia").contains(cd68));
        assertTrue("byCategory(lysosomes) must contain CD68 via additionalCategories",
                library.byCategory("lysosomes").contains(cd68));

        // Synapsin is pan-presynaptic — should appear under all three synapse buckets.
        MarkerLibrary.Entry synapsin = library.byId("synapse_synapsin");
        assertNotNull(synapsin);
        assertTrue(library.byCategory("synaptic_excitatory").contains(synapsin));
        assertTrue(library.byCategory("synaptic_presynaptic").contains(synapsin));
        assertTrue(library.byCategory("synaptic_inhibitory").contains(synapsin));

        // Specific markers stay specific: TMEM119 should ONLY be under microglia.
        MarkerLibrary.Entry tmem119 = library.byId("microglia_tmem119");
        assertNotNull(tmem119);
        assertTrue("TMEM119 should have no additional categories",
                tmem119.getAdditionalCategories().isEmpty());
    }

    @Test
    public void bundledLibraryContainsIggMarkersAndExactTextMatches() throws Exception {
        MarkerLibrary library = MarkerLibrary.loadBundled();

        MarkerLibrary.Entry igg = library.byId("immune_igg");
        assertNotNull(igg);
        assertEquals("IgG", igg.getDisplayName());
        assertEquals("immune_igg", library.exactMatch("IgG").getId());

        MarkerLibrary.Entry iggHL = library.byId("immune_igg_h_l");
        assertNotNull(iggHL);
        assertEquals("IgG H+L", iggHL.getDisplayName());
        assertEquals("immune_igg_h_l", library.exactMatch("IgG H+L").getId());
        assertEquals("immune_igg_h_l", library.exactMatch("anti-IgG H+L").getId());

        MarkerLibrary.Entry mCherry = library.exactMatch("mCherry");
        assertNotNull(mCherry);
        assertEquals("reporter_mcherry", mCherry.getId());
        assertEquals("Red", mCherry.getConventionalLUT());
    }

    private static boolean containsNuclearEntryInTopThree(List<MarkerLibrary.Entry> entries) {
        int limit = Math.min(3, entries.size());
        for (int i = 0; i < limit; i++) {
            if ("nuclear".equals(entries.get(i).getCategory())) {
                return true;
            }
        }
        return false;
    }

    private static void assertRequiredString(String value, String field) {
        assertNotNull(field, value);
        assertFalse(field, value.trim().isEmpty());
    }

    private static void assertNameHintsAreLowercaseAndNonBlank(MarkerLibrary.Entry entry) {
        for (String hint : entry.getNameHints()) {
            assertFalse("blank nameHint for " + entry.getId(), hint.trim().isEmpty());
            assertEquals("nameHint not lowercase for " + entry.getId(), hint, hint.toLowerCase(Locale.ROOT));
        }
    }

    private static void assertCrosswalkLabelIndexed(String label, MarkerLibrary.Entry entry) {
        String normalizedLabel = normalize(label);
        List<String> indexed = new ArrayList<String>();
        indexed.add(entry.getDisplayName());
        indexed.addAll(entry.getAliases());
        indexed.addAll(entry.getNameHints());
        for (String value : indexed) {
            if (normalize(value).equals(normalizedLabel)) {
                return;
            }
        }
        assertTrue("Crosswalk label not indexed for " + entry.getId() + ": " + label, false);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "");
    }

    private static String readResource(String path) throws IOException {
        InputStream stream = MarkerLibraryValidationTest.class.getResourceAsStream(path);
        assertNotNull("Missing test resource " + path, stream);
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = stream.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            return out.toString("UTF-8");
        } finally {
            stream.close();
        }
    }

    private static Map<String, List<String>> referenceCrosswalk() {
        Map<String, List<String>> crosswalk = new LinkedHashMap<String, List<String>>();
        crosswalk.put("ALDH1L1", Arrays.asList("astrocytes_aldh1l1"));
        crosswalk.put("ALDH1A1", Arrays.asList("astrocyte_aldh1a1"));
        crosswalk.put("Amyloid Beta H31L21", Arrays.asList("amyloid_h31l21"));
        crosswalk.put("Amyloid Beta 1-38", Arrays.asList("amyloid_abeta38"));
        crosswalk.put("AQP1", Arrays.asList("vascular_aqp1"));
        crosswalk.put("AQP4", Arrays.asList("astrocytes_aqp4"));
        crosswalk.put("AT8", Arrays.asList("tau_phospho_epitopes"));
        crosswalk.put("Bmal1", Arrays.asList("clock_bmal1"));
        crosswalk.put("Cathepsin-D", Arrays.asList("lyso_cathepsin_d"));
        crosswalk.put("Cleaved Caspase-3", Arrays.asList("apop_cleaved_casp3"));
        crosswalk.put("CD3", Arrays.asList("immune_cd3"));
        crosswalk.put("CD19", Arrays.asList("immune_cd19_cd20"));
        crosswalk.put("CD169", Arrays.asList("myeloid_cd169"));
        crosswalk.put("CD206", Arrays.asList("microglia_cd206"));
        crosswalk.put("CD44", Arrays.asList("astrocytes_cd44"));
        crosswalk.put("CD68", Arrays.asList("microglia_cd68"));
        crosswalk.put("CD86", Arrays.asList("microglia_cd86"));
        crosswalk.put("CIRBP", Arrays.asList("cold_cirbp"));
        crosswalk.put("Collagen IV", Arrays.asList("vascular_collagen4"));
        crosswalk.put("Connexin-43", Arrays.asList("astrocytes_cx43"));
        crosswalk.put("dsDNA", Arrays.asList("nuclear_dsdna"));
        crosswalk.put("EAAT3", Arrays.asList("neurons_eaat3"));
        crosswalk.put("FoxG1", Arrays.asList("stem_foxg1"));
        crosswalk.put("GABA", Arrays.asList("neurotransmitter_gaba"));
        crosswalk.put("GAD 65/67", Arrays.asList("synapse_gad65", "synapse_gad67"));
        crosswalk.put("GFAP", Arrays.asList("astrocytes_gfap"));
        crosswalk.put("GFP", Arrays.asList("reporter_gfp"));
        crosswalk.put("HEVIN", Arrays.asList("astrocyte_hevin"));
        crosswalk.put("IBA1", Arrays.asList("microglia_iba1"));
        crosswalk.put("KCa1.1", Arrays.asList("channel_kca11"));
        crosswalk.put("LAMININ BETA 1", Arrays.asList("vascular_laminin_b1"));
        crosswalk.put("L-Glutamate", Arrays.asList("neurotransmitter_glu"));
        crosswalk.put("Luciferase", Arrays.asList("reporter_luciferase"));
        crosswalk.put("MAP2", Arrays.asList("neurons_map2"));
        crosswalk.put("mCherry", Arrays.asList("reporter_mcherry"));
        crosswalk.put("MOAB-2", Arrays.asList("amyloid_moab2"));
        crosswalk.put("MAO-B", Arrays.asList("astrocyte_mao_b"));
        crosswalk.put("Nanog", Arrays.asList("stem_nanog"));
        crosswalk.put("NeuN", Arrays.asList("neurons_neun"));
        crosswalk.put("NFIA", Arrays.asList("astrocyte_nfia"));
        crosswalk.put("NG2", Arrays.asList("opc_ng2"));
        crosswalk.put("Oct-4", Arrays.asList("stem_oct4"));
        crosswalk.put("Otx1/2", Arrays.asList("stem_otx12"));
        crosswalk.put("P16", Arrays.asList("senescence_p16"));
        crosswalk.put("P21", Arrays.asList("senescence_p21"));
        crosswalk.put("Pax6", Arrays.asList("stem_pax6"));
        crosswalk.put("PER2", Arrays.asList("clock_per2"));
        crosswalk.put("PSD-95", Arrays.asList("synapse_psd95"));
        crosswalk.put("PHF-1", Arrays.asList("tau_phospho_epitopes"));
        crosswalk.put("RBM3", Arrays.asList("cold_rbm3"));
        crosswalk.put("RFP", Arrays.asList("reporter_rfp"));
        crosswalk.put("RoRa", Arrays.asList("clock_rora"));
        crosswalk.put("RoRb", Arrays.asList("clock_rorb"));
        crosswalk.put("S100B", Arrays.asList("astrocytes_s100b"));
        crosswalk.put("Sox2", Arrays.asList("stem_sox2"));
        crosswalk.put("SPARC", Arrays.asList("astrocyte_sparc"));
        crosswalk.put("SSEA4", Arrays.asList("stem_ssea4"));
        crosswalk.put("Synaptophysin-1", Arrays.asList("synapse_syp"));
        crosswalk.put("Tau AT8 epitope", Arrays.asList("tau_phospho_epitopes"));
        crosswalk.put("THBS4", Arrays.asList("astrocyte_thbs4"));
        crosswalk.put("TNFa", Arrays.asList("cytokine_tnfa"));
        crosswalk.put("Tub3", Arrays.asList("neurons_tuj1"));
        crosswalk.put("VAMP2", Arrays.asList("synapse_vamp2"));
        crosswalk.put("VGAT", Arrays.asList("synapse_vgat"));
        crosswalk.put("VGLUT1", Arrays.asList("synapse_vglut1"));
        crosswalk.put("VPAC2", Arrays.asList("scn_vipr2_vpac2"));
        crosswalk.put("CK1d", Arrays.asList("clock_ck1d"));
        crosswalk.put("CK1e", Arrays.asList("clock_ck1e"));
        crosswalk.put("VIP", Arrays.asList("interneurons_vip"));
        crosswalk.put("AVP", Arrays.asList("scn_avp"));
        crosswalk.put("LEAP-2", Arrays.asList("hypothal_leap2"));
        crosswalk.put("Tyrosine Hydroxylase", Arrays.asList("dopaminergic_th"));
        crosswalk.put("GHSR", Arrays.asList("hypothal_ghsr"));
        crosswalk.put("mOC98", Arrays.asList("amyloid_moc98"));
        crosswalk.put("Tau T22", Arrays.asList("tau_t22_oligomer"));
        crosswalk.put("Somatostatin", Arrays.asList("interneurons_sst"));
        crosswalk.put("Tmem119", Arrays.asList("microglia_tmem119"));
        crosswalk.put("Methoxy-X04", Arrays.asList("amyloid_methoxy_x04"));
        crosswalk.put("X-34", Arrays.asList("amyloid_methoxy_x04"));
        crosswalk.put("MOAB-2 AF647", Arrays.asList("amyloid_moab2"));
        crosswalk.put("FluoroJade C", Arrays.asList("apop_fluorojade"));
        crosswalk.put("Texas Red 3kDa dextran", Arrays.asList("tracer_texas_red_3k"));
        return crosswalk;
    }
}
