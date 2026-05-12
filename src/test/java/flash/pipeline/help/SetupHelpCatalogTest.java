package flash.pipeline.help;

import org.junit.Test;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class SetupHelpCatalogTest {

    @Test
    public void requiredSetupHelpersExist() {
        assertTopic(SetupHelpCatalog.CHANNEL_IDENTITY);
        assertTopic(SetupHelpCatalog.ANALYSIS_SCOPE);
        assertTopic(SetupHelpCatalog.Z_SLICE_SUBSET);
        assertTopic(SetupHelpCatalog.Z_SLICE_PARTIAL_APPLY);
        assertTopic(SetupHelpCatalog.Z_SLICE_FINALISE);
        assertTopic(SetupHelpCatalog.Z_SLICE_SAME_COUNT);
        assertTopic(SetupHelpCatalog.SETTINGS_MODE);
        assertTopic(SetupHelpCatalog.QC_IMAGE_SELECTION);
        assertTopic(SetupHelpCatalog.SEGMENTATION_METHOD);
        assertTopic(SetupHelpCatalog.CLASSICAL_OBJECT_SEGMENTATION);
        assertTopic(SetupHelpCatalog.STARDIST);
        assertTopic(SetupHelpCatalog.CELLPOSE);
        assertTopic(SetupHelpCatalog.FILTER_PARAMETERS);
        assertTopic(SetupHelpCatalog.DISPLAY_RANGE);
        assertTopic(SetupHelpCatalog.CHANNEL_THRESHOLD);
    }

    @Test
    public void topicKeysAreUniqueAndLookupIsStable() {
        Map<String, SetupHelpTopic> all = SetupHelpCatalog.all();
        Set<String> keys = new HashSet<String>();
        for (SetupHelpTopic topic : all.values()) {
            assertTrue("duplicate setup helper key " + topic.key, keys.add(topic.key));
            assertEquals(topic, SetupHelpCatalog.forKey(topic.key));
        }
    }

    @Test(expected = UnsupportedOperationException.class)
    public void allReturnsImmutableMap() {
        SetupHelpCatalog.all().clear();
    }

    @Test(expected = UnsupportedOperationException.class)
    public void topicSectionsAreImmutable() {
        SetupHelpCatalog.DISPLAY_RANGE.sections.clear();
    }

    @Test
    public void displayRangeNamesPresentationReadyImagesAndDataBoundary() {
        assertContains(SetupHelpCatalog.DISPLAY_RANGE.summary,
                "presentation-ready images from the main UI");
        assertContains(SetupHelpCatalog.DISPLAY_RANGE.summary,
                "does not change raw data or measurement values");
    }

    @Test
    public void objectSegmentationTopicsRemainMethodSpecific() {
        assertContains(SetupHelpCatalog.CLASSICAL_OBJECT_SEGMENTATION.summary,
                "signal threshold");
        assertContains(SetupHelpCatalog.CLASSICAL_OBJECT_SEGMENTATION.summary,
                "voxel-size range");
        String classicalText = topicText(SetupHelpCatalog.CLASSICAL_OBJECT_SEGMENTATION);
        assertContains(classicalText, "left Threshold preview");
        assertContains(classicalText, "right Object preview");
        assertContains(classicalText, "Large view");
        assertFalse(classicalText.contains("Raw: preview"));
        assertFalse(classicalText.contains("Filtered: preview"));
        assertFalse(classicalText.contains("Overlay objects"));
        assertContains(SetupHelpCatalog.STARDIST.summary,
                "star-convex objects");
        assertContains(SetupHelpCatalog.CELLPOSE.summary,
                "Cellpose model");
    }

    @Test
    public void setupHelpDialogBuildsContentPanel() {
        assertTrue(SetupHelpDialog.buildContentPanel(SetupHelpCatalog.QC_IMAGE_SELECTION)
                .getComponentCount() > 3);
    }

    private static void assertTopic(SetupHelpTopic topic) {
        assertNotNull(topic);
        assertNonBlank(topic.key);
        assertNonBlank(topic.title);
        assertNonBlank(topic.summary);
        assertFalse(topic.sections.isEmpty());
        for (SetupHelpTopic.Section section : topic.sections) {
            assertNonBlank(section.heading);
            assertFalse(section.items.isEmpty());
        }
    }

    private static void assertNonBlank(String value) {
        assertNotNull(value);
        assertFalse(value.trim().isEmpty());
    }

    private static void assertContains(String value, String expected) {
        assertNotNull(value);
        assertTrue("expected text to contain: " + expected, value.contains(expected));
    }

    private static String topicText(SetupHelpTopic topic) {
        StringBuilder text = new StringBuilder();
        text.append(topic.title).append('\n').append(topic.summary);
        for (SetupHelpTopic.Section section : topic.sections) {
            text.append('\n').append(section.heading);
            for (String item : section.items) {
                text.append('\n').append(item);
            }
        }
        return text.toString();
    }
}
