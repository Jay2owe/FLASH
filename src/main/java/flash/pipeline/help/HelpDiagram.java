package flash.pipeline.help;

import com.github.weisj.jsvg.SVGDocument;
import com.github.weisj.jsvg.attributes.ViewBox;
import com.github.weisj.jsvg.parser.SVGLoader;

import javax.swing.Icon;
import javax.swing.ImageIcon;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * Maps help topic IDs to bundled SVG diagram resources. Diagrams render via jsvg
 * and appear above the text in help dialogs that opt in.
 *
 * <p>Topic IDs match {@link SetupHelpCatalog} / {@link AnalysisHelpCatalog} entries
 * by string identity. Register a new diagram by adding a static block entry below
 * and dropping its SVG under {@code src/main/resources/flash/help/diagrams/}.</p>
 */
public final class HelpDiagram {

    private static final SVGLoader LOADER = new SVGLoader();
    private static final Map<String, String> TOPIC_TO_RESOURCE = new HashMap<String, String>();

    static {
        // Topic IDs match SetupHelpTopic.key (kebab-case).
        register("channel-threshold",             "/flash/help/diagrams/otsu-histogram.svg");
        register("classical-object-segmentation", "/flash/help/diagrams/particle-size-range.svg");
    }

    private HelpDiagram() {}

    public static void register(String topicId, String resourcePath) {
        if (topicId == null || resourcePath == null) return;
        TOPIC_TO_RESOURCE.put(topicId, resourcePath);
    }

    public static boolean has(String topicId) {
        return topicId != null && TOPIC_TO_RESOURCE.containsKey(topicId);
    }

    /** Renders the diagram for the given topic at the requested width.
     *  Height scales to preserve the SVG's intrinsic aspect ratio (defaults to 100 px if unknown). */
    public static Icon diagramFor(String topicId, int width) {
        if (topicId == null) return null;
        String resource = TOPIC_TO_RESOURCE.get(topicId);
        if (resource == null) return null;
        try (InputStream in = HelpDiagram.class.getResourceAsStream(resource)) {
            if (in == null) return null;
            SVGDocument doc = LOADER.load(in);
            if (doc == null) return null;
            float intrinsicW = doc.size().width;
            float intrinsicH = doc.size().height;
            int height = (intrinsicW > 0 && intrinsicH > 0)
                    ? Math.round(width * (intrinsicH / intrinsicW))
                    : 100;
            BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = img.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            doc.render(null, g, new ViewBox(0, 0, width, height));
            g.dispose();
            return new ImageIcon(img);
        } catch (Exception ex) {
            return null;
        }
    }
}
