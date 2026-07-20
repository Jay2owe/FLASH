package flash.pipeline.ui.variations;

import flash.pipeline.image.FilterMacroEditorModel;
import flash.pipeline.ui.config.ConfigQcContext;
import flash.pipeline.ui.config.FilterParameterStage;

import ij.ImagePlus;
import ij.ImageStack;
import ij.process.ByteProcessor;
import org.junit.Ignore;
import org.junit.Test;

import javax.swing.SwingUtilities;
import java.awt.GraphicsEnvironment;
import java.io.File;
import java.util.Collections;
import java.util.function.Consumer;

@Ignore("TODO(manual-ui): manual launcher for visual checks; re-enable only if converted to an automated headless UI test.")
public final class MacroVariationsDialogDemoMain {

    @Test
    public void manualLauncher() {
        main(new String[0]);
    }

    public static void main(String[] args) {
        if (GraphicsEnvironment.isHeadless()) {
            System.err.println("MacroVariationsDialogDemoMain requires a non-headless JVM.");
            return;
        }
        SwingUtilities.invokeLater(new Runnable() {
            @Override public void run() {
                ImagePlus source = syntheticImage();
                FilterMacroEditorModel.MacroDefinition macro =
                        FilterMacroEditorModel.parse(macroText());
                ConfigQcContext config = ConfigQcContext.fromImages(new File("."),
                        new File("target/macro-variation-demo-bin"),
                        null,
                        Collections.singletonList(source),
                        Collections.singletonList("DAPI"),
                        0);
                FilterVariationEngineContext context =
                        new FilterVariationEngineContext(macro, source,
                                CropSpec.centre256(), "DAPI", config,
                                new DemoPreviewAdapter());
                MacroVariationsDialog dialog = new MacroVariationsDialog(null,
                        context, new Consumer<String>() {
                    @Override public void accept(String macroContent) {
                        System.out.println("Accepted macro:");
                        System.out.println(macroContent);
                    }
                });
                dialog.showDialog();
            }
        });
    }

    private static String macroText() {
        return "run(\"Gaussian Blur...\", \"sigma=2 stack\");\n"
                + "run(\"Subtract Background...\", \"rolling=20 stack\");\n"
                + "run(\"Auto Local Threshold\", \"radius=15 method=Bernsen white stack\");";
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

    private static final class DemoPreviewAdapter
            implements FilterParameterStage.PreviewAdapter {
        @Override public ImagePlus createSource(ConfigQcContext context) {
            return syntheticImage();
        }

        @Override public ImagePlus createFilteredPreview(ImagePlus source,
                                                         String macroContent) {
            return source == null ? null : source.duplicate();
        }

        @Override public void close(ImagePlus image) {
            if (image != null) {
                image.close();
            }
        }
    }
}
