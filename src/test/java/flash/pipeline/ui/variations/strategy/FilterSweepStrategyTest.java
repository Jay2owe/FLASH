package flash.pipeline.ui.variations.strategy;

import flash.pipeline.image.FilterMacroEditorModel;
import flash.pipeline.ui.config.ConfigQcContext;
import flash.pipeline.ui.config.FilterParameterStage;
import flash.pipeline.ui.variations.CropSpec;
import flash.pipeline.ui.variations.FilterParameterId;
import flash.pipeline.ui.variations.FilterVariationEngineContext;
import flash.pipeline.ui.variations.ParameterCombo;
import flash.pipeline.ui.variations.ParameterKey;
import flash.pipeline.ui.variations.ParameterSweep;
import flash.pipeline.ui.variations.ParameterValueList;
import flash.pipeline.ui.variations.PresetSweepCombo;
import flash.pipeline.ui.variations.PresetSweepKey;
import flash.pipeline.ui.variations.SlotSubstitutionKey;
import flash.pipeline.ui.variations.VariationCache;
import flash.pipeline.ui.variations.VariationResult;

import ij.ImagePlus;
import ij.process.ByteProcessor;
import ij.process.FloatProcessor;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

public class FilterSweepStrategyTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

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

    @Test
    public void renderMacroForComboCanSubstituteFocusedSlot() {
        Map<ParameterKey, Object> values = new LinkedHashMap<ParameterKey, Object>();
        values.put(SlotSubstitutionKey.filterAxis(0, "SMOOTHING"), "Median");
        values.put(SlotSubstitutionKey.scaleAxis(0, "SMOOTHING"), "large");
        FilterMacroEditorModel.MacroDefinition macro = FilterMacroEditorModel.parse(
                "run(\"Gaussian Blur...\", \"sigma=1 stack\");\n"
                        + "run(\"Subtract Background...\", \"rolling=4 stack\");\n");
        FilterSweepStrategy strategy = new FilterSweepStrategy(macro,
                new SyntheticPreviewAdapter(), sourceImage(), null);

        String rendered = strategy.renderMacroForCombo(new ParameterCombo(values));

        assertFalse(rendered.contains("Gaussian Blur"));
        assertEquals("8", optionValue(rendered, "radius"));
        assertEquals("4", optionValue(rendered, "rolling"));
    }

    @Test
    public void renderMacroForComboSubstitutesFocusedDuplicateFilterSlot() {
        Map<ParameterKey, Object> values = new LinkedHashMap<ParameterKey, Object>();
        values.put(SlotSubstitutionKey.filterAxis(2, "SMOOTHING"), "Mean");
        values.put(SlotSubstitutionKey.scaleAxis(2, "SMOOTHING"), "large");
        FilterMacroEditorModel.MacroDefinition macro = FilterMacroEditorModel.parse(
                "run(\"Gaussian Blur...\", \"sigma=1 stack\");\n"
                        + "run(\"Median...\", \"radius=2 stack\");\n"
                        + "run(\"Gaussian Blur...\", \"sigma=3 stack\");\n");
        FilterSweepStrategy strategy = new FilterSweepStrategy(macro,
                new SyntheticPreviewAdapter(), sourceImage(), null);

        String[] lines = strategy.renderMacroForCombo(new ParameterCombo(values))
                .split("\\n");

        assertEquals("run(\"Gaussian Blur...\", \"sigma=1 stack\");", lines[0]);
        assertEquals("run(\"Median...\", \"radius=2 stack\");", lines[1]);
        assertEquals("run(\"Mean...\", \"radius=8 stack\");", lines[2]);
    }

    @Test
    public void renderMacroForPresetOnlyComboLeavesPresetMacroVerbatim() {
        final String presetMacro =
                "run(\"Gaussian Blur...\", \"sigma=1 stack\");\n"
                        + "run(\"Subtract Background...\", \"rolling=4 stack\");\n";
        PresetSweepCombo presetOnly = PresetSweepCombo.forPresetOnly("Baked");
        assertNull(presetOnly.xParamKey());
        assertNull(presetOnly.xValue());
        Map<ParameterKey, Object> values = new LinkedHashMap<ParameterKey, Object>();
        values.put(PresetSweepKey.presetName(), presetOnly.presetName());
        FilterMacroEditorModel.MacroDefinition base = FilterMacroEditorModel.parse(
                "run(\"Gaussian Blur...\", \"sigma=9 stack\");\n");
        FilterSweepStrategy strategy = new FilterSweepStrategy(base,
                new SyntheticPreviewAdapter(), sourceImage(), null,
                new FilterSweepStrategy.MacroPostProcessor() {
                    @Override public String apply(String macroContent) {
                        return macroContent + "// post";
                    }
                },
                new FilterVariationEngineContext.PresetMacroLoader() {
                    @Override public String loadPresetMacro(String presetName) {
                        return presetMacro;
                    }
                });

        String rendered = strategy.renderMacroForCombo(new ParameterCombo(values));

        assertEquals(presetMacro + "// post", rendered);
    }

    @Test
    public void dispatchStopsImmediatelyWhenThreadIsInterrupted() {
        FilterParameterId sigma =
                new FilterParameterId(0, 0, 0, "Gaussian Blur", "sigma");
        Map<ParameterKey, ParameterValueList> values =
                new LinkedHashMap<ParameterKey, ParameterValueList>();
        values.put(sigma, ParameterValueList.ofDoubles(1.0d, 2.0d));
        ParameterSweep sweep = new ParameterSweep(ParameterSweep.Method.FILTER,
                values, CropSpec.full(), "DAPI", "image-a", "filter:macrohash");
        FilterMacroEditorModel.MacroDefinition macro = FilterMacroEditorModel.parse(
                "run(\"Gaussian Blur...\", \"sigma=1\");\n");
        final AtomicInteger published = new AtomicInteger();
        FilterSweepStrategy strategy = new FilterSweepStrategy(macro,
                new SyntheticPreviewAdapter(), sourceImage(), null);

        Thread.currentThread().interrupt();
        try {
            strategy.dispatch(sweep,
                    new Consumer<VariationResult>() {
                        @Override public void accept(VariationResult result) {
                            published.incrementAndGet();
                        }
                    },
                    new BooleanSupplier() {
                        @Override public boolean getAsBoolean() {
                            return false;
                        }
                    });
        } finally {
            Thread.interrupted();
        }

        assertEquals(0, published.get());
    }

    @Test
    public void cancellationBeforePublishDoesNotPopulateDiskCache() throws Exception {
        FilterParameterId sigma =
                new FilterParameterId(0, 0, 0, "Gaussian Blur", "sigma");
        Map<ParameterKey, ParameterValueList> values =
                new LinkedHashMap<ParameterKey, ParameterValueList>();
        values.put(sigma, ParameterValueList.ofDoubles(1.0d));
        final ParameterSweep sweep = new ParameterSweep(ParameterSweep.Method.FILTER,
                values, CropSpec.full(), "DAPI", "image-a", "filter:macrohash");
        FilterMacroEditorModel.MacroDefinition macro = FilterMacroEditorModel.parse(
                "run(\"Gaussian Blur...\", \"sigma=1\");\n"
                        + "run(\"Subtract Background...\", \"rolling=4\");\n");
        File bin = temp.newFolder("filter-sweep-cancel-cache-test");
        VariationCache cache = new VariationCache(bin);
        FilterSweepStrategy strategy = new FilterSweepStrategy(macro,
                new SyntheticPreviewAdapter(), sourceImage(), cache);
        final List<VariationResult> published = new ArrayList<VariationResult>();
        final AtomicInteger checks = new AtomicInteger();

        strategy.dispatch(sweep,
                new Consumer<VariationResult>() {
                    @Override public void accept(VariationResult result) {
                        published.add(result);
                    }
                },
                new BooleanSupplier() {
                    @Override public boolean getAsBoolean() {
                        return checks.incrementAndGet() >= 4;
                    }
                });

        String cacheKey = VariationCache.keyFor(sweep, sweep.combos().get(0));
        File cacheFile = new File(new File(bin, "variations_cache"),
                cacheKey + ".tif");
        assertEquals(0, published.size());
        assertFalse(cacheFile.exists());
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
