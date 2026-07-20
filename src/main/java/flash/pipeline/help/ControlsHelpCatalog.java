package flash.pipeline.help;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Registry of the in-depth per-control "manual" content for analyses that have
 * documented their toggles, choices, and fields.
 *
 * <p>A single {@link ControlHelpTopic} per analysis feeds two surfaces from one
 * source of truth:
 * <ul>
 *   <li>the collapsible controls list under the analysis' single "?" button
 *       ({@link CollapsibleControlList}), and</li>
 *   <li>the hover tooltip attached to each matching widget on the dialog, so no
 *       extra "?" icons are needed ({@link #tooltipForControl}).</li>
 * </ul>
 */
public final class ControlsHelpCatalog {

    private static final int TOOLTIP_WIDTH = 340;

    private static final Map<Integer, ControlHelpTopic> TOPICS = buildTopics();

    private ControlsHelpCatalog() {
    }

    public static ControlHelpTopic forAnalysis(int analysisIndex) {
        return TOPICS.get(Integer.valueOf(analysisIndex));
    }

    public static boolean hasTopic(int analysisIndex) {
        return TOPICS.containsKey(Integer.valueOf(analysisIndex));
    }

    public static Map<Integer, ControlHelpTopic> all() {
        return TOPICS;
    }

    /**
     * Returns rich HTML help for the control whose on-screen label matches
     * {@code label} in the given analysis, suitable for
     * {@code setToolTipText(...)}, or {@code null} if the control is not
     * documented. Matching is case-insensitive and ignores a trailing colon so
     * "Object channel:" maps to the "Object channel" entry.
     */
    public static String tooltipForControl(int analysisIndex, String label) {
        ControlHelpTopic topic = forAnalysis(analysisIndex);
        if (topic == null || label == null) {
            return null;
        }
        String needle = normalize(label);
        if (needle.isEmpty()) {
            return null;
        }
        for (ControlHelpTopic.Group group : topic.groups) {
            for (ControlHelpTopic.Control control : group.controls) {
                if (normalize(control.label).equals(needle)) {
                    return tooltipHtml(control);
                }
            }
        }
        return null;
    }

    /** Builds the hover-box HTML for one control: bold summary plus detail bullets. */
    public static String tooltipHtml(ControlHelpTopic.Control control) {
        StringBuilder sb = new StringBuilder();
        sb.append("<html><body width='").append(TOOLTIP_WIDTH).append("'>");
        sb.append("<b>").append(CollapsibleControlList.escape(control.label)).append("</b>");
        if (control.badge != null) {
            sb.append(" <font color='#757575'>&mdash; ")
                    .append(CollapsibleControlList.escape(control.badge)).append("</font>");
        }
        sb.append("<br>").append(CollapsibleControlList.escape(control.summary));
        for (String line : control.details) {
            sb.append("<br>&bull;&nbsp;").append(CollapsibleControlList.escape(line));
        }
        sb.append("</body></html>");
        return sb.toString();
    }

    private static String normalize(String label) {
        String trimmed = label.trim();
        // Strip runtime artifact decoration (hyphen or em-dash variant) so
        // "Volumetric overlap - already present" still matches the documented
        // "Volumetric overlap" control.
        String[] suffixes = {
                " - already present", " - partially present",
                " — already present", " — partially present"};
        for (String suffix : suffixes) {
            int marker = trimmed.indexOf(suffix);
            if (marker >= 0) {
                trimmed = trimmed.substring(0, marker).trim();
                break;
            }
        }
        if (trimmed.endsWith(":")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1).trim();
        }
        return trimmed.toLowerCase(java.util.Locale.ROOT);
    }

    private static Map<Integer, ControlHelpTopic> buildTopics() {
        Map<Integer, ControlHelpTopic> topics = new LinkedHashMap<Integer, ControlHelpTopic>();
        // Content is registered here per analysis as it is authored. Pass 1:
        // Set Up Configuration, 3D Object, Spatial, Intensity.
        put(topics, SetupControlsHelp.topic());
        put(topics, DrawRoisControlsHelp.topic());
        put(topics, SplitMergeControlsHelp.topic());
        put(topics, ThreeDObjectControlsHelp.topic());
        put(topics, SpatialControlsHelp.topic());
        put(topics, IntensityControlsHelp.topic());
        put(topics, AggregationControlsHelp.topic());
        put(topics, StatisticsControlsHelp.topic());
        put(topics, ExcelControlsHelp.topic());
        put(topics, SpectralControlsHelp.topic());
        return Collections.unmodifiableMap(topics);
    }

    private static void put(Map<Integer, ControlHelpTopic> topics, ControlHelpTopic topic) {
        if (topics.put(Integer.valueOf(topic.analysisIndex), topic) != null) {
            throw new IllegalStateException("Duplicate controls help for analysis " + topic.analysisIndex);
        }
    }
}
