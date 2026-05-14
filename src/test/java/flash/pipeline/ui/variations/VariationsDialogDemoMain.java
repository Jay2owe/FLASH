package flash.pipeline.ui.variations;

import flash.pipeline.ui.config.ConfigQcContext;

import ij.ImagePlus;
import ij.ImageStack;
import ij.process.ByteProcessor;
import org.junit.Ignore;
import org.junit.Test;

import javax.swing.SwingUtilities;
import java.io.File;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

@Ignore("Manual Fiji launcher for the parameter variations dialog.")
public final class VariationsDialogDemoMain {

    @Test
    public void manualLauncher() {
        main(new String[0]);
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(new Runnable() {
            @Override public void run() {
                ImagePlus source = syntheticImage();
                ConfigQcContext config = ConfigQcContext.fromImages(new File("."),
                        new File("target/variation-demo-bin"),
                        null,
                        Collections.singletonList(source),
                        Collections.singletonList("DAPI"),
                        0);
                ParameterCombo base = ParameterCombo.builder()
                        .put(ParameterId.THRESHOLD, Integer.valueOf(100))
                        .put(ParameterId.MIN_SIZE, Integer.valueOf(50))
                        .put(ParameterId.MAX_SIZE, Integer.valueOf(500))
                        .build();
                VariationEngineContext context = VariationEngineContext.forClassical(
                        "DAPI", source, source, config, base, null);
                VariationsDialog dialog = new VariationsDialog(null, context,
                        new java.util.function.Consumer<ParameterCombo>() {
                            @Override public void accept(ParameterCombo combo) {
                                System.out.println("Accepted combo: " + combo);
                            }
                        });
                dialog.setSweepForTest(twoAxisSweep());
                dialog.showDialog();
            }
        });
    }

    private static ParameterSweep twoAxisSweep() {
        Map<ParameterId, ParameterValueList> values =
                new LinkedHashMap<ParameterId, ParameterValueList>();
        values.put(ParameterId.THRESHOLD, ParameterValueList.ofInts(80, 100, 120));
        values.put(ParameterId.MIN_SIZE, ParameterValueList.ofInts(20, 40));
        values.put(ParameterId.MAX_SIZE, ParameterValueList.ofInts(500));
        return new ParameterSweep(ParameterSweep.Method.CLASSICAL, values,
                CropSpec.centre256(), "DAPI", "demo");
    }

    private static ImagePlus syntheticImage() {
        ImageStack stack = new ImageStack(256, 256);
        for (int z = 0; z < 10; z++) {
            ByteProcessor processor = new ByteProcessor(256, 256);
            for (int y = 0; y < 256; y++) {
                for (int x = 0; x < 256; x++) {
                    processor.set(x, y, (x + y + z * 12) & 0xff);
                }
            }
            stack.addSlice("z" + (z + 1), processor);
        }
        ImagePlus image = new ImagePlus("Synthetic 256x256x10", stack);
        image.setDimensions(1, 10, 1);
        return image;
    }
}
