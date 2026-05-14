package flash.pipeline.ui.variations.strategy;

import flash.pipeline.image.FilterMacroEditorModel;
import flash.pipeline.ui.config.ConfigQcContext;
import flash.pipeline.ui.config.FilterParameterStage;
import flash.pipeline.ui.variations.CropSpec;
import flash.pipeline.ui.variations.FilterParameterId;
import flash.pipeline.ui.variations.ParameterCombo;
import flash.pipeline.ui.variations.ParameterKey;
import flash.pipeline.ui.variations.ParameterSweep;
import flash.pipeline.ui.variations.ParameterValueList;
import flash.pipeline.ui.variations.VariationResult;

import ij.ImagePlus;
import ij.process.ByteProcessor;
import ij.process.FloatProcessor;

import org.junit.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;

public class FilterSweepStrategyTest {

    @Test
    public void publishesFilterResultsInSweepDispatchOrder() {
        FilterParameterId sigma =
                new FilterParameterId(0, 0, 0, "Gaussian Blur", "sigma");
        FilterParameterId rolling =
                new FilterParameterId(0, 1, 0, "Subtract Background", "rolling");
        Map<ParameterKey, ParameterValueList> values =
                new LinkedHashMap<ParameterKey, ParameterValueList>();
        values.put(sigma, ParameterValueList.ofDoubles(1.0d, 2.0d, 3.0d));
        values.put(rolling, ParameterValueList.ofDoubles(4.0d, 5.0d));
        final ParameterSweep sweep = new ParameterSweep(ParameterSweep.Method.FILTER,
                values, CropSpec.full(), "DAPI", "image-a", "filter:macrohash");
        FilterMacroEditorModel.MacroDefinition macro = FilterMacroEditorModel.parse(
                "// ===== Filters =====\n"
                        + "run(\"Gaussian Blur...\", \"sigma=1\");\n"
                        + "run(\"Subtract Background...\", \"rolling=4\");\n");
        FilterSweepStrategy strategy = new FilterSweepStrategy(macro,
                new SyntheticPreviewAdapter(), sourceImage(), null);
        final List<VariationResult> published = new ArrayList<VariationResult>();

        strategy.dispatch(sweep,
                new Consumer<VariationResult>() {
                    @Override public void accept(VariationResult result) {
                        published.add(result);
                    }
                },
                new BooleanSupplier() {
                    @Override public boolean getAsBoolean() {
                        return false;
                    }
                });

        List<ParameterCombo> ordered = SweepDispatchOrder.order(sweep);
        assertEquals(6, published.size());
        for (int i = 0; i < published.size(); i++) {
            VariationResult result = published.get(i);
            ParameterCombo combo = ordered.get(i);
            assertEquals(combo, result.combo());
            assertEquals(VariationResult.Kind.FILTER, result.kind());
            assertNotNull(result.previewImage());
            double expected = ((Number) combo.get(sigma)).doubleValue() * 10.0d
                    + ((Number) combo.get(rolling)).doubleValue();
            assertEquals(expected,
                    result.previewImage().getProcessor().getPixelValue(8, 8),
                    0.0001d);
            assertNotNull(result.histogram());
            assertEquals(256, result.histogram().length);
            assertFalse(Double.isNaN(result.snr()));
            assertFalse(Double.isNaN(result.bgSigma()));
        }
    }

    @Test
    public void renderMacroForComboUpdatesTargetParameters() {
        FilterParameterId sigma =
                new FilterParameterId(0, 0, 0, "Gaussian Blur", "sigma");
        FilterParameterId rolling =
                new FilterParameterId(0, 1, 0, "Subtract Background", "rolling");
        Map<ParameterKey, Object> values = new LinkedHashMap<ParameterKey, Object>();
        values.put(sigma, Double.valueOf(2.0d));
        values.put(rolling, Double.valueOf(7.0d));
        FilterMacroEditorModel.MacroDefinition macro = FilterMacroEditorModel.parse(
                "// ===== Filters =====\n"
                        + "run(\"Gaussian Blur...\", \"sigma=1\");\n"
                        + "run(\"Subtract Background...\", \"rolling=4\");\n");
        FilterSweepStrategy strategy = new FilterSweepStrategy(macro,
                new SyntheticPreviewAdapter(), sourceImage(), null);

        String rendered = strategy.renderMacroForCombo(new ParameterCombo(values));

        assertArrayEquals(new String[] { "2.0", "7.0" },
                new String[] {
                        optionValue(rendered, "sigma"),
                        optionValue(rendered, "rolling")
                });
    }

    private static ImagePlus sourceImage() {
        ByteProcessor processor = new ByteProcessor(16, 16);
        processor.setValue(1);
        processor.fill();
        return new ImagePlus("source", processor);
    }

    private static String optionValue(String macro, String key) {
        Pattern pattern = Pattern.compile(key + "=([^\\s\";)]+)");
        Matcher matcher = pattern.matcher(macro);
        return matcher.find() ? matcher.group(1) : "";
    }

    private static final class SyntheticPreviewAdapter
            implements FilterParameterStage.PreviewAdapter {

        @Override
        public ImagePlus createSource(ConfigQcContext context) {
            return sourceImage();
        }

        @Override
        public ImagePlus createFilteredPreview(ImagePlus source, String macroContent) {
            double sigma = Double.parseDouble(optionValue(macroContent, "sigma"));
            double rolling = Double.parseDouble(optionValue(macroContent, "rolling"));
            float value = (float) (sigma * 10.0d + rolling);
            FloatProcessor processor = new FloatProcessor(16, 16);
            for (int y = 0; y < 16; y++) {
                for (int x = 0; x < 16; x++) {
                    processor.setf(x, y, value);
                }
            }
            return new ImagePlus("filtered", processor);
        }

        @Override
        public void close(ImagePlus image) {
        }
    }
}
