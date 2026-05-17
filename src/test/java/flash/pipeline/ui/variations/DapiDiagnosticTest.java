package flash.pipeline.ui.variations;

import flash.pipeline.image.FilterMacroEditorModel;
import flash.pipeline.ui.config.ConfigQcContext;
import flash.pipeline.ui.config.FilterParameterStage;
import flash.pipeline.ui.variations.strategy.FilterSweepStrategy;

import ij.ImagePlus;
import ij.ImageStack;
import ij.process.ByteProcessor;
import ij.process.ImageProcessor;

import org.junit.Test;

import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

/**
 * Replicates the runtime path used by the DAPI filter variation sweep:
 *  - parses the bundled defaultFilter.ijm style macro,
 *  - sweeps a single parameter (sigma) across three distinct values,
 *  - dispatches through FilterSweepStrategy with a real PreviewAdapter that
 *    runs the rendered macro on a synthetic byte stack (mirroring
 *    CreateBinFileAnalysis.createFilteredPreview line 6987-6995),
 *  - and asserts that BOTH the rendered macro strings AND the resulting
 *    pixel hashes are distinct across combos.
 *
 * Used to localise the DAPI variations regression to (a) macro rendering,
 * (b) filter execution, or (c) display pipeline. Kept as a regression test.
 */
public class DapiDiagnosticTest {

    private static final String BASE_MACRO =
            "// === STANDARD CLEANUP ===\n"
                    + "run(\"Gaussian Blur...\", \"sigma=2 stack\");\n";

    @Test
    public void renderedMacrosDifferAcrossSigmaSweep() {
        FilterMacroEditorModel.MacroDefinition baseMacro =
                FilterMacroEditorModel.parse(BASE_MACRO);
        FilterParameterId sigma =
                new FilterParameterId(0, 0, 0, "Gaussian Blur", "sigma");

        FilterSweepStrategy strategy = new FilterSweepStrategy(
                baseMacro,
                new MacroCapturingAdapter(),
                syntheticDapiStack(),
                null);

        Set<String> rendered = new HashSet<String>();
        double[] sigmas = { 1.0d, 2.0d, 4.0d };
        for (double s : sigmas) {
            Map<ParameterKey, Object> values =
                    new LinkedHashMap<ParameterKey, Object>();
            values.put(sigma, Double.valueOf(s));
            ParameterCombo combo = new ParameterCombo(values);
            String macro = strategy.renderMacroForCombo(combo);
            rendered.add(macro);
        }

        assertEquals("FilterSweepStrategy.renderMacroForCombo should produce "
                        + "distinct macros for distinct sigma values",
                3, rendered.size());
    }

    @Test
    public void pixelsDifferEvenWhenAdapterAppliesBlueLut() {
        FilterMacroEditorModel.MacroDefinition baseMacro =
                FilterMacroEditorModel.parse(BASE_MACRO);
        FilterParameterId sigma =
                new FilterParameterId(0, 0, 0, "Gaussian Blur", "sigma");
        Map<ParameterKey, ParameterValueList> sweepValues =
                new LinkedHashMap<ParameterKey, ParameterValueList>();
        sweepValues.put(sigma, ParameterValueList.ofDoubles(1.0d, 2.0d, 4.0d));
        ParameterSweep sweep = new ParameterSweep(ParameterSweep.Method.FILTER,
                sweepValues, CropSpec.full(), "C1 (DAPI)",
                "dapi-source", "filter:macrohash");

        final List<String> macros = new ArrayList<String>();
        final List<String> pixelHashes = new ArrayList<String>();

        FilterSweepStrategy strategy = new FilterSweepStrategy(
                baseMacro,
                new ProductionMirrorAdapter(macros, pixelHashes, "Blue"),
                syntheticDapiStack(),
                null);

        strategy.dispatch(sweep,
                new Consumer<VariationResult>() {
                    @Override public void accept(VariationResult result) { }
                },
                new BooleanSupplier() {
                    @Override public boolean getAsBoolean() { return false; }
                });

        assertEquals(3, macros.size());
        assertEquals(3, pixelHashes.size());
        assertEquals("With Blue LUT applied (DAPI production path), pixels "
                        + "must still differ across combos",
                3, new HashSet<String>(pixelHashes).size());
    }

    @Test
    public void pixelsDifferAcrossSigmaSweepWhenAdapterRunsMacro() {
        FilterMacroEditorModel.MacroDefinition baseMacro =
                FilterMacroEditorModel.parse(BASE_MACRO);
        FilterParameterId sigma =
                new FilterParameterId(0, 0, 0, "Gaussian Blur", "sigma");
        Map<ParameterKey, ParameterValueList> sweepValues =
                new LinkedHashMap<ParameterKey, ParameterValueList>();
        sweepValues.put(sigma, ParameterValueList.ofDoubles(1.0d, 2.0d, 4.0d));
        ParameterSweep sweep = new ParameterSweep(ParameterSweep.Method.FILTER,
                sweepValues, CropSpec.full(), "DAPI",
                "dapi-source", "filter:macrohash");

        final List<String> macros = new ArrayList<String>();
        final List<String> pixelHashes = new ArrayList<String>();

        FilterSweepStrategy strategy = new FilterSweepStrategy(
                baseMacro,
                new GaussianBlurAdapter(macros, pixelHashes),
                syntheticDapiStack(),
                null);

        strategy.dispatch(sweep,
                new Consumer<VariationResult>() {
                    @Override public void accept(VariationResult result) {
                        // no-op; capture happens inside the adapter so we
                        // record exactly what was applied to the image.
                    }
                },
                new BooleanSupplier() {
                    @Override public boolean getAsBoolean() {
                        return false;
                    }
                });

        assertEquals("expected 3 sweep combos to dispatch", 3, macros.size());
        assertEquals(3, pixelHashes.size());
        assertEquals("rendered macros must differ across combos",
                3, new HashSet<String>(macros).size());
        assertEquals("filtered pixel hashes must differ across combos",
                3, new HashSet<String>(pixelHashes).size());
    }

    private static ImagePlus syntheticDapiStack() {
        ImageStack stack = new ImageStack(32, 32);
        for (int z = 1; z <= 3; z++) {
            ByteProcessor bp = new ByteProcessor(32, 32);
            // Bright central dot with z-varying offset; sigma=1 vs sigma=4
            // will produce visibly different blurred footprints.
            byte[] px = (byte[]) bp.getPixels();
            int cx = 12 + z;
            int cy = 12 + z;
            for (int y = 0; y < 32; y++) {
                for (int x = 0; x < 32; x++) {
                    if (Math.abs(x - cx) <= 1 && Math.abs(y - cy) <= 1) {
                        px[y * 32 + x] = (byte) 255;
                    }
                }
            }
            stack.addSlice("z" + z, bp);
        }
        ImagePlus imp = new ImagePlus("Filter source | DAPI | synthetic", stack);
        imp.setDimensions(1, 3, 1);
        return imp;
    }

    private static String sha1Hex(byte[] data) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-1").digest(data);
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (int i = 0; i < digest.length; i++) {
                sb.append(String.format("%02x", Integer.valueOf(digest[i] & 0xff)));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static byte[] firstSlicePixelsCopy(ImagePlus imp) {
        ImageProcessor ip = imp.getStack().getProcessor(1);
        byte[] src = (byte[]) ip.getPixels();
        byte[] copy = new byte[src.length];
        System.arraycopy(src, 0, copy, 0, src.length);
        return copy;
    }

    private static final class MacroCapturingAdapter
            implements FilterParameterStage.PreviewAdapter {

        @Override public ImagePlus createSource(ConfigQcContext context) {
            return syntheticDapiStack();
        }

        @Override public ImagePlus createFilteredPreview(ImagePlus source,
                                                         String macroContent) {
            return source;
        }

        @Override public void close(ImagePlus image) { }
    }

    /**
     * Runs the rendered macro through FilterExecutor — the real production
     * path used by CreateBinFileAnalysis.createFilteredPreview at line 6991
     * — and records both the macro text and the SHA-1 of the first slice's
     * pixel buffer so the test can distinguish "macros differ but pixels
     * collapse" from "everything differs".
     */
    private static final class GaussianBlurAdapter
            implements FilterParameterStage.PreviewAdapter {

        private final List<String> macros;
        private final List<String> pixelHashes;
        private final AtomicReference<ImagePlus> last = new AtomicReference<ImagePlus>();

        GaussianBlurAdapter(List<String> macros, List<String> pixelHashes) {
            this.macros = macros;
            this.pixelHashes = pixelHashes;
        }

        @Override public ImagePlus createSource(ConfigQcContext context) {
            return syntheticDapiStack();
        }

        @Override public ImagePlus createFilteredPreview(ImagePlus source,
                                                         String macroContent) {
            assertNotNull(source);
            ImagePlus filtered = source.duplicate();
            flash.pipeline.image.FilterExecutor.runThreadSafe(filtered, macroContent);
            macros.add(macroContent);
            pixelHashes.add(sha1Hex(firstSlicePixelsCopy(filtered)));
            last.set(filtered);
            return filtered;
        }

        @Override public void close(ImagePlus image) { }
    }

    /**
     * Mirrors CreateBinFileAnalysis.createFilteredPreview line 6987-6995 —
     * source.duplicate(), FilterExecutor.runThreadSafe, setTitle, and IJ.run
     * with the configured LUT name. Used to check whether applyPreviewLut
     * collapses per-combo pixel differences (it does not).
     */
    private static final class ProductionMirrorAdapter
            implements FilterParameterStage.PreviewAdapter {

        private final List<String> macros;
        private final List<String> pixelHashes;
        private final String lutName;

        ProductionMirrorAdapter(List<String> macros,
                                List<String> pixelHashes,
                                String lutName) {
            this.macros = macros;
            this.pixelHashes = pixelHashes;
            this.lutName = lutName;
        }

        @Override public ImagePlus createSource(ConfigQcContext context) {
            return syntheticDapiStack();
        }

        @Override public ImagePlus createFilteredPreview(ImagePlus source,
                                                         String macroContent) {
            ImagePlus filtered = source.duplicate();
            if (macroContent != null && !macroContent.trim().isEmpty()) {
                flash.pipeline.image.FilterExecutor.runThreadSafe(filtered, macroContent);
            }
            filtered.setTitle("Filter preview | C1 (DAPI) | " + source.getTitle());
            try {
                ij.IJ.run(filtered, lutName, "");
            } catch (RuntimeException ignored) {
                // The real applyPreviewLut also swallows LUT failures.
            }
            macros.add(macroContent);
            pixelHashes.add(sha1Hex(firstSlicePixelsCopy(filtered)));
            return filtered;
        }

        @Override public void close(ImagePlus image) { }
    }
}
