package flash.pipeline.ui.variations;

import flash.pipeline.ui.config.ConfigQcContext;
import flash.pipeline.ui.preview.VariationMontageDialog;

import ij.ImagePlus;
import ij.ImageStack;
import ij.process.ByteProcessor;
import org.junit.Assume;
import org.junit.Test;

import javax.swing.JButton;
import javax.swing.SwingUtilities;
import java.awt.GraphicsEnvironment;
import java.io.File;
import java.lang.reflect.Field;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class VariationsDialogTest {

    @Test
    public void constructStartAndCancelCycleCompletesPlaceholderSweep() throws Exception {
        Assume.assumeFalse("PipelineDialog creates a JDialog in this codebase.",
                GraphicsEnvironment.isHeadless());
        final VariationsDialog[] holder = new VariationsDialog[1];
        final AtomicReference<ParameterCombo> accepted =
                new AtomicReference<ParameterCombo>();

        SwingUtilities.invokeAndWait(new Runnable() {
            @Override public void run() {
                VariationsDialog dialog = new VariationsDialog(null,
                        context(),
                        new java.util.function.Consumer<ParameterCombo>() {
                            @Override public void accept(ParameterCombo combo) {
                                accepted.set(combo);
                            }
                        });
                dialog.setSweepForTest(twoAxisSweep());
                dialog.setStrategyForTest(new EchoStrategy());
                dialog.start();
                holder[0] = dialog;
            }
        });

        holder[0].waitForDoneForTest(5000L);

        assertEquals(6, holder[0].cellCountForTest());
        assertEquals(6, holder[0].completedCountForTest());
        assertEquals(6, holder[0].gridPanelForTest().cellCountForTest());

        SwingUtilities.invokeAndWait(new Runnable() {
            @Override public void run() {
                holder[0].setGlobalZForTest(3);
                java.util.List<VariationCellPanel> cells =
                        holder[0].gridPanelForTest().cellsForTest();
                for (int i = 0; i < cells.size(); i++) {
                    assertEquals(3, cells.get(i).currentZForTest());
                    assertTrue(cells.get(i).cachedLabelForTest() != null);
                }
                cells.get(0).clickForTest(false);
            }
        });
        assertTrue(accepted.get() != null);

        SwingUtilities.invokeAndWait(new Runnable() {
            @Override public void run() {
                holder[0].cancelForTest();
                holder[0].dispose();
            }
        });
    }

    @Test
    public void openMontageUsesCroppedSourceChoices() throws Exception {
        Assume.assumeFalse("Variation montage creates a JDialog.",
                GraphicsEnvironment.isHeadless());

        final ImagePlus raw = stack("raw-large", 3, 320, 300);
        final ImagePlus filtered = stack("filtered-large", 3, 320, 300);
        final VariationsDialog dialog =
                startedDialog(context(raw, filtered), oneCellSweep());
        try {
            SwingUtilities.invokeAndWait(new Runnable() {
                @Override public void run() {
                    dialog.openMontageForTest();
                    VariationMontageDialog montage = dialog.montageDialogForTest();
                    assertTrue(montage != null);

                    ImagePlus actualRaw = (ImagePlus) field(montage, "rawSourceImage");
                    ImagePlus actualFiltered =
                            (ImagePlus) field(montage, "filteredSourceImage");
                    ImagePlus expectedRaw = dialog.croppedForComparisonForTest(raw);
                    ImagePlus expectedFiltered =
                            dialog.croppedForComparisonForTest(filtered);

                    assertSameDimensions(expectedRaw, actualRaw);
                    assertSameDimensions(expectedFiltered, actualFiltered);
                    assertEquals(256, actualRaw.getWidth());
                    assertEquals(256, actualRaw.getHeight());
                    assertEquals(256, actualFiltered.getWidth());
                    assertEquals(256, actualFiltered.getHeight());
                }
            });
        } finally {
            dispose(dialog);
            raw.flush();
            filtered.flush();
        }
    }

    @Test
    public void montageDisplayButtonsInvokeContextDelegate() throws Exception {
        Assume.assumeFalse("Variation montage creates a JDialog.",
                GraphicsEnvironment.isHeadless());

        final AtomicInteger brightnessRequests = new AtomicInteger();
        final AtomicInteger lutRequests = new AtomicInteger();
        final ImagePlus source = stack("delegate-source", 2, 320, 300);
        VariationEngineContext context = context(source, source);
        context.setMontageDisplayActionDelegate(new MontageDisplayActionDelegate() {
            @Override public void adjustBrightnessContrast() {
                brightnessRequests.incrementAndGet();
            }

            @Override public void toggleGreyLut() {
                lutRequests.incrementAndGet();
            }
        });

        final VariationsDialog dialog = startedDialog(context, oneCellSweep());
        try {
            SwingUtilities.invokeAndWait(new Runnable() {
                @Override public void run() {
                    dialog.openMontageForTest();
                    VariationMontageDialog montage = dialog.montageDialogForTest();
                    assertTrue(montage != null);

                    JButton brightnessButton =
                            (JButton) field(montage, "displayControlsButton");
                    JButton lutButton = (JButton) field(montage, "lutToggleButton");

                    brightnessButton.doClick();
                    lutButton.doClick();

                    assertEquals(1, brightnessRequests.get());
                    assertEquals(1, lutRequests.get());
                }
            });
        } finally {
            dispose(dialog);
            source.flush();
        }
    }

    private static VariationEngineContext context() {
        ImagePlus source = stack("synthetic", 10);
        return context(source, source);
    }

    private static VariationEngineContext context(ImagePlus rawSource,
                                                  ImagePlus filteredSource) {
        File bin = new File("target/variation-dialog-test-bin-"
                + rawSource.getTitle().replaceAll("[^A-Za-z0-9_.-]", "_"));
        ConfigQcContext config = ConfigQcContext.fromImages(new File("."), bin, null,
                Collections.singletonList(rawSource),
                Collections.singletonList("DAPI"),
                0);
        ParameterCombo base = ParameterCombo.builder()
                .put(ParameterId.THRESHOLD, Integer.valueOf(100))
                .put(ParameterId.MIN_SIZE, Integer.valueOf(50))
                .put(ParameterId.MAX_SIZE, Integer.valueOf(500))
                .build();
        return VariationEngineContext.forClassical("DAPI", rawSource, filteredSource,
                config, base, null);
    }

    private static VariationsDialog startedDialog(
            final VariationEngineContext context,
            final ParameterSweep sweep) throws Exception {
        final AtomicReference<VariationsDialog> ref =
                new AtomicReference<VariationsDialog>();
        SwingUtilities.invokeAndWait(new Runnable() {
            @Override public void run() {
                VariationsDialog dialog = new VariationsDialog(null,
                        context,
                        new java.util.function.Consumer<ParameterCombo>() {
                            @Override public void accept(ParameterCombo combo) {
                            }
                        });
                dialog.setSweepForTest(sweep);
                dialog.setStrategyForTest(new NoopStrategy(1));
                dialog.start();
                ref.set(dialog);
            }
        });
        VariationsDialog dialog = ref.get();
        dialog.waitForDoneForTest(5000L);
        return dialog;
    }

    private static void dispose(final VariationsDialog dialog) throws Exception {
        if (dialog == null) {
            return;
        }
        SwingUtilities.invokeAndWait(new Runnable() {
            @Override public void run() {
                dialog.dispose();
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
                CropSpec.centre256(), "DAPI", "hash");
    }

    private static ParameterSweep oneCellSweep() {
        Map<ParameterId, ParameterValueList> values =
                new LinkedHashMap<ParameterId, ParameterValueList>();
        values.put(ParameterId.THRESHOLD, ParameterValueList.ofInts(100));
        values.put(ParameterId.MIN_SIZE, ParameterValueList.ofInts(50));
        values.put(ParameterId.MAX_SIZE, ParameterValueList.ofInts(500));
        return new ParameterSweep(ParameterSweep.Method.CLASSICAL, values,
                CropSpec.centre256(), "DAPI", "hash");
    }

    private static ImagePlus stack(String title, int slices) {
        return stack(title, slices, 16, 16);
    }

    private static ImagePlus stack(String title, int slices, int width, int height) {
        ImageStack stack = new ImageStack(width, height);
        for (int z = 0; z < slices; z++) {
            ByteProcessor processor = new ByteProcessor(width, height);
            processor.setValue(z + 1);
            processor.fill();
            stack.addSlice("z" + (z + 1), processor);
        }
        ImagePlus image = new ImagePlus(title, stack);
        image.setDimensions(1, slices, 1);
        return image;
    }

    private static void assertSameDimensions(ImagePlus expected, ImagePlus actual) {
        assertTrue(expected != null);
        assertTrue(actual != null);
        assertEquals(expected.getWidth(), actual.getWidth());
        assertEquals(expected.getHeight(), actual.getHeight());
        assertEquals(expected.getNSlices(), actual.getNSlices());
    }

    private static Object field(Object target, String fieldName) {
        try {
            Field field = target.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            return field.get(target);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }
}
