package flash.pipeline.ui.config;

import flash.pipeline.bin.ChannelConfig;
import flash.pipeline.zslice.ZSliceMode;
import flash.pipeline.zslice.ZSliceRange;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class ConfigReviewPanelTest {

    private static final int SETTING_MIN_MAX = 1;
    private static final int SETTING_OBJECT_THRESHOLD = 3;
    private static final int SETTING_SEGMENTATION_METHOD = 5;

    @Test
    public void reviewModelMapsChannelIdentitySettingsAndQcValues() {
        ChannelConfig cfg = config();
        boolean[][] settings = new boolean[6][2];
        settings[SETTING_MIN_MAX][0] = true;
        settings[SETTING_OBJECT_THRESHOLD][0] = true;
        settings[SETTING_SEGMENTATION_METHOD][1] = true;

        ConfigReviewPanel.ReviewModel model = ConfigReviewPanel.ReviewModel.from(cfg, settings);

        assertEquals(6, model.steps.size());
        assertEquals("All steps done \u2713", model.footerText);
        assertEquals("Grays", valueFor(model.step(1), "C1 DAPI", "LUT color"));
        assertTrue(valueFor(model.step(1), "C1 DAPI", "Marker").contains("nuclei"));
        assertTrue(valueFor(model.step(1), "C1 DAPI", "Marker").contains("round"));
        assertTrue(valueFor(model.step(1), "C1 DAPI", "Marker").contains("crowding-sensitive"));
        assertTrue(valueFor(model.step(3), "C1 DAPI", "QC fields").contains("Display min-max"));
        assertTrue(valueFor(model.step(3), "C1 DAPI", "QC fields").contains("Object threshold"));
        assertTrue(valueFor(model.step(3), "C2 GFAP", "QC fields").contains("Segmentation method"));
        assertEquals("2-8", valueFor(model.step(4), "Series 0", "Range"));
        assertEquals("Ramified Cells (Microglia/Astrocytes)",
                valueFor(model.step(5), "C2 GFAP", "Filter preset"));
        assertEquals("120-Infinity", valueFor(model.step(5), "C2 GFAP", "Particle size"));
    }

    @Test
    public void reviewStepIsReadOnlyAndFirstStepIsDefaultSelection() {
        ConfigReviewPanel.ReviewModel model = ConfigReviewPanel.ReviewModel.from(config(), null);
        ConfigReviewPanel panel = new ConfigReviewPanel(model);

        assertEquals(1, panel.selectedStepIndexForTest());
        assertTrue(model.step(1).editable);
        assertTrue(!model.step(6).editable);
        assertEquals("Ready to save", valueFor(model.step(6), "Configuration", "Status"));
    }

    private static String valueFor(ConfigReviewPanel.ReviewStep step, String subject, String setting) {
        for (ConfigReviewPanel.ReviewRow row : step.rows) {
            if (!row.section && subject.equals(row.subject) && setting.equals(row.setting)) {
                return row.value;
            }
        }
        throw new AssertionError("Missing row: " + subject + " / " + setting);
    }

    private static ChannelConfig config() {
        ChannelConfig cfg = new ChannelConfig();
        cfg.zSliceMode = ZSliceMode.PER_IMAGE;
        cfg.zSliceSelections.put("0", new ZSliceRange(2, 8));
        cfg.channels.add(channel(0, "DAPI", "Grays", "nuclei", "round", true,
                "100", "50-Infinity", "0-255", "75", "classical", "Default"));
        cfg.channels.add(channel(1, "GFAP", "Green", "astrocytes", "", false,
                "140", "120-Infinity", "10-200", "85", "stardist:0.5:0.4",
                "Ramified Cells (Microglia/Astrocytes)"));
        return cfg;
    }

    private static ChannelConfig.Channel channel(int index, String name, String color,
                                                  String markerId, String markerShape,
                                                  boolean crowding, String threshold,
                                                  String size, String minmax,
                                                  String intensity, String segmentation,
                                                  String filter) {
        ChannelConfig.Channel channel = new ChannelConfig.Channel();
        channel.index = index;
        channel.name = name;
        channel.color = color;
        channel.markerId = markerId;
        channel.markerShape = markerShape;
        channel.markerCrowdingSensitive = crowding;
        channel.threshold = threshold;
        channel.size = size;
        channel.minmax = minmax;
        channel.intensityThreshold = intensity;
        channel.segmentationMethod = segmentation;
        channel.filterPreset = filter;
        return channel;
    }
}
