package flash.pipeline.ui.preview;

import ij.ImagePlus;
import ij.process.ByteProcessor;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class ThresholdControlPanelTest {

    @Test
    public void thresholdValuesClampToImageDomain() {
        ThresholdControlPanel panel = new ThresholdControlPanel();
        panel.setImage(image(0, 25, 100));

        panel.setThreshold(-20.0, 120.0);

        assertEquals(0.0, panel.getLowerThreshold(), 0.0001);
        assertEquals(100.0, panel.getUpperThreshold(), 0.0001);

        panel.setThreshold(90.0, 10.0);

        assertEquals(10.0, panel.getLowerThreshold(), 0.0001);
        assertEquals(90.0, panel.getUpperThreshold(), 0.0001);
    }

    @Test
    public void lowerSliderCannotCrossUpperThreshold() {
        ThresholdControlPanel panel = new ThresholdControlPanel();
        panel.setImage(image(0, 100));
        panel.setThreshold(20.0, 40.0);
        final List<String> events = new ArrayList<String>();
        panel.setListener(new RecordingThresholdListener(events));

        panel.lowerSliderForTest().sliderForTest().setValue(900);

        assertEquals(40.0, panel.getLowerThreshold(), 0.0001);
        assertEquals(40.0, panel.getUpperThreshold(), 0.0001);
        assertEquals("threshold:40:40:false", events.get(events.size() - 1));
    }

    @Test
    public void autoAndResetUpdateStateBeforeActionEvents() {
        ThresholdControlPanel panel = new ThresholdControlPanel();
        panel.setImage(image(0, 0, 255, 255));
        final List<String> events = new ArrayList<String>();
        panel.setListener(new RecordingThresholdListener(events));

        panel.autoButtonForTest().doClick();

        assertTrue(panel.getLowerThreshold() >= 0.0);
        assertTrue(panel.getUpperThreshold() <= 255.0);
        assertTrue(events.get(0).startsWith("threshold:"));
        assertEquals("auto:Default:Dark", events.get(1));

        panel.setThreshold(40.0, 60.0);
        events.clear();
        panel.resetButtonForTest().doClick();

        assertEquals(0.0, panel.getLowerThreshold(), 0.0001);
        assertEquals(255.0, panel.getUpperThreshold(), 0.0001);
        assertEquals("threshold:0:255:false", events.get(0));
        assertEquals("reset", events.get(1));
    }

    @Test
    public void controlsCanBeConstructedHeadlessly() {
        ThresholdControlPanel threshold = new ThresholdControlPanel();
        threshold.setImage(image(0, 10, 20));

        MinMaxControlPanel minMax = new MinMaxControlPanel();
        minMax.setImage(image(0, 10, 20));

        assertEquals(0.0, threshold.getLowerThreshold(), 0.0001);
        assertEquals(20.0, threshold.getUpperThreshold(), 0.0001);
        assertEquals(0.0, minMax.getMinValue(), 0.0001);
        assertEquals(20.0, minMax.getMaxValue(), 0.0001);
    }

    @Test
    public void minMaxFallsBackSafelyWhenImageHasBeenClosed() {
        ImagePlus temporary = image(0, 10, 20);
        MinMaxControlPanel minMax = new MinMaxControlPanel();

        temporary.flush();
        minMax.setImage(temporary);

        assertEquals(0.0, minMax.getMinValue(), 0.0001);
        assertEquals(255.0, minMax.getMaxValue(), 0.0001);
    }

    private static ImagePlus image(int... values) {
        ByteProcessor processor = new ByteProcessor(values.length, 1);
        for (int i = 0; i < values.length; i++) {
            processor.set(i, 0, values[i]);
        }
        return new ImagePlus("threshold", processor);
    }

    private static final class RecordingThresholdListener implements ThresholdControlPanel.Listener {
        private final List<String> events;

        RecordingThresholdListener(List<String> events) {
            this.events = events;
        }

        @Override public void thresholdChanged(double lower, double upper, boolean adjusting) {
            events.add("threshold:" + Math.round(lower) + ":" + Math.round(upper) + ":" + adjusting);
        }

        @Override public void autoRequested(String method, String background) {
            events.add("auto:" + method + ":" + background);
        }

        @Override public void resetRequested() {
            events.add("reset");
        }

        @Override public void setRequested() {
            events.add("set");
        }
    }
}
