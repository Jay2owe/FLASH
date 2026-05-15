package flash.pipeline.help;

import org.junit.Test;

import java.util.Locale;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class SpatialHelpCatalogTest {

    @Test
    public void allSixSectionTopicsAreRegistered() {
        Map<String, SetupHelpTopic> all = SpatialHelpCatalog.all();
        assertEquals(6, all.size());
        assertNotNull(all.get("spatial-distances"));
        assertNotNull(all.get("spatial-colocalization"));
        assertNotNull(all.get("spatial-voronoi"));
        assertNotNull(all.get("spatial-morphometry"));
        assertNotNull(all.get("spatial-phenotyping"));
        assertNotNull(all.get("spatial-heatmaps"));
    }

    @Test
    public void forKeyReturnsRegisteredTopics() {
        assertNotNull(SpatialHelpCatalog.forKey("spatial-distances"));
        assertNotNull(SpatialHelpCatalog.forKey("spatial-heatmaps"));
    }

    @Test
    public void everyTopicHasNonBlankTitleSummaryAndSections() {
        for (SetupHelpTopic topic : SpatialHelpCatalog.all().values()) {
            assertNonBlank(topic.title, topic.key + " title");
            assertNonBlank(topic.summary, topic.key + " summary");
            assertFalse(topic.key + " has no sections", topic.sections.isEmpty());
            for (SetupHelpTopic.Section section : topic.sections) {
                assertNonBlank(section.heading, topic.key + " section heading");
                assertFalse(topic.key + " section " + section.heading + " is empty",
                        section.items.isEmpty());
                for (String item : section.items) {
                    assertNonBlank(item, topic.key + " > " + section.heading + " item");
                }
            }
        }
    }

    @Test
    public void everyTopicCoversTheFourStandardSections() {
        for (SetupHelpTopic topic : SpatialHelpCatalog.all().values()) {
            assertHasSection(topic, "Sub-analyses");
            assertHasSection(topic, "When to use");
            assertHasSection(topic, "Requires");
            assertHasSection(topic, "Watch out");
        }
    }

    @Test
    public void distancesTopicMentionsAllThreeSubAnalyses() {
        assertSubAnalysesContain(SpatialHelpCatalog.DISTANCES,
                "nearest neighbor", "line distance", "ripley");
    }

    @Test
    public void colocalizationTopicMentionsBothMethods() {
        assertSubAnalysesContain(SpatialHelpCatalog.COLOCALIZATION,
                "volumetric overlap", "centroid coincidence");
    }

    @Test
    public void morphometryTopicMentionsAllFiveLayeredToggles() {
        assertSubAnalysesContain(SpatialHelpCatalog.MORPHOMETRY,
                "2d morphology", "3d shape features",
                "complex shape", "population morphometric", "spatial-morphometric");
    }

    @Test
    public void phenotypingTopicMentionsKmeansAndClusterK() {
        assertSubAnalysesContain(SpatialHelpCatalog.PHENOTYPING,
                "k-means", "k = 0");
    }

    @Test
    public void heatmapsTopicMentionsKdeBandwidthAndLut() {
        assertSubAnalysesContain(SpatialHelpCatalog.HEATMAPS,
                "kde bandwidth", "lut");
    }

    @Test
    public void allTopicKeysAreUnique() {
        Map<String, SetupHelpTopic> all = SpatialHelpCatalog.all();
        assertEquals("each topic key must appear exactly once",
                all.size(), all.keySet().size());
    }

    private static void assertHasSection(SetupHelpTopic topic, String heading) {
        for (SetupHelpTopic.Section section : topic.sections) {
            if (heading.equalsIgnoreCase(section.heading)) {
                return;
            }
        }
        fail(topic.key + " missing section: " + heading);
    }

    private static void assertSubAnalysesContain(SetupHelpTopic topic, String... needles) {
        SetupHelpTopic.Section subAnalyses = findSection(topic, "Sub-analyses");
        String haystack = String.join(" | ", subAnalyses.items).toLowerCase(Locale.ROOT);
        for (String needle : needles) {
            assertTrue(topic.key + " > Sub-analyses missing '" + needle + "': " + haystack,
                    haystack.contains(needle.toLowerCase(Locale.ROOT)));
        }
    }

    private static SetupHelpTopic.Section findSection(SetupHelpTopic topic, String heading) {
        for (SetupHelpTopic.Section section : topic.sections) {
            if (heading.equalsIgnoreCase(section.heading)) {
                return section;
            }
        }
        throw new AssertionError(topic.key + " missing section: " + heading);
    }

    private static void assertNonBlank(String value, String label) {
        assertNotNull(label + " is null", value);
        assertFalse(label + " is blank", value.trim().isEmpty());
    }
}
