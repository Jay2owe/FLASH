package flash.pipeline.help;

import org.junit.Test;

import java.awt.Dimension;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class AnalysisHelpAssetTest {

    @Test
    public void packagedResourcesCanBeLoadedAndScaled() {
        assertTrue(HelpImageLoader.resourceExists("/icons/status_done.png"));
        assertNotNull(HelpImageLoader.loadImage("/icons/status_done.png"));

        Dimension size = HelpImageLoader.scaledSize(1200, 600, 260, 150);

        assertEquals(260, size.width);
        assertEquals(130, size.height);
    }

    @Test
    public void missingOptionalResourcesAreAllowed() {
        AnalysisHelpTopic.HelpImage image = new AnalysisHelpTopic.HelpImage(
                "/help/analysis/_placeholder/setup.png",
                "Optional placeholder",
                "Later content stages may fill this image.",
                true);

        assertTrue(image.isOptional());
        assertTrue(HelpImageLoader.loadImage(image.resourcePath) == null);
    }

    @Test
    public void catalogImagesUseAnalysisHelpResourceFolder() {
        List<String> invalidPaths = new ArrayList<String>();
        for (AnalysisHelpTopic topic : AnalysisHelpCatalog.all().values()) {
            for (AnalysisHelpTopic.HelpImage image : topic.images) {
                String path = HelpImageLoader.normalizeResourcePath(image.resourcePath);
                if (path == null || !path.startsWith("/help/analysis/" + topic.key + "/")) {
                    invalidPaths.add(topic.key + ": " + image.resourcePath);
                }
            }
        }

        if (!invalidPaths.isEmpty()) {
            fail("analysis help images must live under /help/analysis/<topic-key>/: " + invalidPaths);
        }
    }

    @Test
    public void requiredCatalogImagesExistAndOptionalMissingImagesAreExplicit() {
        List<String> missingRequired = new ArrayList<String>();
        List<String> optionalPlaceholders = new ArrayList<String>();
        for (Map.Entry<Integer, AnalysisHelpTopic> entry : AnalysisHelpCatalog.all().entrySet()) {
            AnalysisHelpTopic topic = entry.getValue();
            for (AnalysisHelpTopic.HelpImage image : topic.images) {
                if (!HelpImageLoader.resourceExists(image.resourcePath)) {
                    if (image.isOptional()) {
                        optionalPlaceholders.add(topic.key + ": " + image.resourcePath);
                    } else {
                        missingRequired.add(topic.key + ": " + image.resourcePath);
                    }
                }
            }
        }

        if (!missingRequired.isEmpty()) {
            fail("missing required analysis help assets: " + missingRequired
                    + "; optional placeholders currently missing: " + optionalPlaceholders);
        }
    }
}
