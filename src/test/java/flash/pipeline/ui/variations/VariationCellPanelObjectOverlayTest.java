package flash.pipeline.ui.variations;

import flash.pipeline.ui.preview.PreviewDisplaySettings;
import ij.ImagePlus;
import ij.process.ByteProcessor;

import org.junit.Test;

import javax.swing.SwingUtilities;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class VariationCellPanelObjectOverlayTest {

    @Test
    public void overlayToggleSwitchesBetweenOverlayAndBareLabelMap() throws Exception {
        final ParameterCombo combo = ParameterCombo.builder().build();
        final ImagePlus filtered = source("filtered");
        final ImagePlus raw = source("raw");
        final ImagePlus label = labels();
        final VariationCellPanel cell = new VariationCellPanel(combo, filtered, null, null);

        SwingUtilities.invokeAndWait(new Runnable() {
            @Override public void run() {
                cell.setObjectRawCrop(raw);
                cell.setLabel(label, null, 2, 5L);
            }
        });

        // Default: overlay on, drawn over the filtered crop.
        assertTrue(cell.objectOverlayEnabledForTest());
        assertTrue(cell.currentPreviewImageForTest().getTitle().startsWith("Object overlay"));
        assertTrue(cell.currentPreviewImageForTest().getTitle().contains("filtered"));

        // Overlay off: bare colour-coded label map, no source background.
        SwingUtilities.invokeAndWait(new Runnable() {
            @Override public void run() {
                cell.setObjectOverlayEnabled(false);
            }
        });
        assertFalse(cell.objectOverlayEnabledForTest());
        assertTrue(cell.currentPreviewImageForTest().getTitle().startsWith("Object label preview"));

        // Overlay back on, switched to the raw crop.
        SwingUtilities.invokeAndWait(new Runnable() {
            @Override public void run() {
                cell.setObjectOverlayEnabled(true);
                cell.setObjectOverlaySourceRaw(true);
            }
        });
        assertTrue(cell.objectOverlaySourceRawForTest());
        assertTrue(cell.currentPreviewImageForTest().getTitle().contains("raw"));
    }

    @Test
    public void displaySettingsAreAppliedToObjectOverlayBackground() throws Exception {
        final ParameterCombo combo = ParameterCombo.builder().build();
        final ImagePlus filtered = source("filtered");
        final ImagePlus label = labels();
        final VariationCellPanel cell = new VariationCellPanel(combo, filtered, null, null);
        final PreviewDisplaySettings grey = PreviewDisplaySettings.of(
                0.0, 255.0, PreviewDisplaySettings.LutMode.GREY, "Red");

        SwingUtilities.invokeAndWait(new Runnable() {
            @Override public void run() {
                cell.setObjectDisplaySettings(grey);
                cell.setLabel(label, null, 2, 5L);
            }
        });

        assertTrue(cell.objectDisplaySettingsForTest() == grey);
        // Overlay still renders over the source (settings only change its look).
        assertTrue(cell.currentPreviewImageForTest().getTitle().startsWith("Object overlay"));
    }

    private static ImagePlus source(String title) {
        ByteProcessor processor = new ByteProcessor(16, 16);
        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 16; x++) {
                processor.set(x, y, (x + y) * 4);
            }
        }
        return new ImagePlus(title, processor);
    }

    private static ImagePlus labels() {
        ByteProcessor processor = new ByteProcessor(16, 16);
        for (int y = 4; y < 8; y++) {
            for (int x = 4; x < 8; x++) {
                processor.set(x, y, 1);
            }
        }
        for (int y = 9; y < 13; y++) {
            for (int x = 9; x < 13; x++) {
                processor.set(x, y, 2);
            }
        }
        return new ImagePlus("Object labels", processor);
    }
}
