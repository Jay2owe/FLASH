package flash.pipeline.ui.variations.strategy;

import flash.pipeline.analyses.CreateBinFileAnalysis;
import flash.pipeline.ui.config.ClassicalSegmentationStage;
import flash.pipeline.ui.variations.CropSpec;
import flash.pipeline.ui.variations.ParameterId;
import flash.pipeline.ui.variations.ParameterSweep;
import flash.pipeline.ui.variations.ParameterValueList;
import flash.pipeline.ui.variations.VariationResult;

import ij.ImagePlus;
import ij.ImageStack;
import ij.process.ByteProcessor;
import ij.process.ImageProcessor;

import org.junit.Test;

import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class ClassicalSweepThreadSafetyTest {

    @Test
    public void realCreateBinPreviewProducesLabelsAcrossRepeatedParallelSweeps()
            throws Exception {
        ClassicalSegmentationStage.PreviewAdapter adapter =
                realCreateBinPreviewAdapter();
        ParameterSweep sweep = fourThresholdSweep();

        for (int run = 0; run < 10; run++) {
            ImagePlus source = syntheticBlobStack();
            ClassicalSweep strategy = new ClassicalSweep(source, CropSpec.full(),
                    null, adapter, 4);
            List<VariationResult> results =
                    Collections.synchronizedList(new ArrayList<VariationResult>());

            strategy.dispatch(sweep, results::add, () -> false);

            assertEquals("run " + run, 4, results.size());
            for (int i = 0; i < results.size(); i++) {
                VariationResult result = results.get(i);
                assertFalse("run " + run + " cell " + i + " failed: "
                        + result.error(), result.hasError());
                assertNotNull("run " + run + " cell " + i, result.label());
                assertTrue("run " + run + " threshold "
                                + result.combo().get(ParameterId.THRESHOLD),
                        labelledPixels(result.label()) > 0);
                result.label().flush();
            }
            source.flush();
        }
    }

    private static ClassicalSegmentationStage.PreviewAdapter
    realCreateBinPreviewAdapter() throws Exception {
        CreateBinFileAnalysis analysis = new CreateBinFileAnalysis();
        Object cfg = binUserConfig();
        Method factory = CreateBinFileAnalysis.class.getDeclaredMethod(
                "createClassicalSegmentationStage",
                cfg.getClass(),
                File.class,
                int.class);
        factory.setAccessible(true);
        ClassicalSegmentationStage stage = (ClassicalSegmentationStage)
                factory.invoke(analysis,
                        cfg,
                        new File(System.getProperty("java.io.tmpdir")),
                        Integer.valueOf(0));

        Field previewAdapter = ClassicalSegmentationStage.class
                .getDeclaredField("previewAdapter");
        previewAdapter.setAccessible(true);
        return (ClassicalSegmentationStage.PreviewAdapter)
                previewAdapter.get(stage);
    }

    private static Object binUserConfig() throws Exception {
        Class<?> cfgClass = Class.forName(
                "flash.pipeline.analyses.CreateBinFileAnalysis$BinUserConfig");
        Constructor<?> constructor = cfgClass.getDeclaredConstructor(
                List.class,
                List.class,
                List.class,
                List.class,
                List.class,
                List.class,
                List.class);
        constructor.setAccessible(true);
        return constructor.newInstance(
                list("DAPI"),
                list("Grays"),
                list("40"),
                list("1-Infinity"),
                list("0-255"),
                list("None"),
                list("default"));
    }

    private static ParameterSweep fourThresholdSweep() {
        Map<ParameterId, ParameterValueList> values =
                new LinkedHashMap<ParameterId, ParameterValueList>();
        values.put(ParameterId.THRESHOLD,
                ParameterValueList.ofInts(40, 80, 120, 180));
        values.put(ParameterId.MIN_SIZE, ParameterValueList.ofInts(1));
        values.put(ParameterId.MAX_SIZE,
                ParameterValueList.ofInts(Integer.MAX_VALUE));
        return new ParameterSweep(ParameterSweep.Method.CLASSICAL,
                values,
                CropSpec.full(),
                "DAPI",
                "synthetic-thread-safety");
    }

    private static ImagePlus syntheticBlobStack() {
        int width = 96;
        int height = 96;
        int slices = 4;
        ImageStack stack = new ImageStack(width, height);
        for (int z = 1; z <= slices; z++) {
            ByteProcessor processor = new ByteProcessor(width, height);
            paintDisk(processor, 16, 16, 4, 220);
            paintDisk(processor, 48, 18, 4, 220);
            paintDisk(processor, 76, 24, 4, 220);
            paintDisk(processor, 28, 68, 4, 220);
            paintDisk(processor, 68, 70, 4, 220);
            stack.addSlice("z" + z, processor);
        }
        ImagePlus image = new ImagePlus("synthetic classical blobs", stack);
        image.setDimensions(1, slices, 1);
        return image;
    }

    private static void paintDisk(ByteProcessor processor,
                                  int centerX,
                                  int centerY,
                                  int radius,
                                  int value) {
        int radiusSquared = radius * radius;
        for (int y = centerY - radius; y <= centerY + radius; y++) {
            for (int x = centerX - radius; x <= centerX + radius; x++) {
                int dx = x - centerX;
                int dy = y - centerY;
                if (dx * dx + dy * dy <= radiusSquared) {
                    processor.set(x, y, value);
                }
            }
        }
    }

    private static int labelledPixels(ImagePlus label) {
        int count = 0;
        for (int slice = 1; slice <= label.getStackSize(); slice++) {
            ImageProcessor processor = label.getStack().getProcessor(slice);
            for (int i = 0; i < processor.getPixelCount(); i++) {
                if (processor.getf(i) > 0.0f) {
                    count++;
                }
            }
        }
        return count;
    }

    private static List<String> list(String value) {
        return new ArrayList<String>(Arrays.asList(value));
    }
}
