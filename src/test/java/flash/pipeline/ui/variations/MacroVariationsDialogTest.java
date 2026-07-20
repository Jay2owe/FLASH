package flash.pipeline.ui.variations;

import flash.pipeline.image.FilterMacroEditorModel;
import flash.pipeline.ui.config.ConfigQcContext;
import flash.pipeline.ui.config.FilterParameterStage;

import ij.ImagePlus;
import ij.ImageStack;
import ij.process.ByteProcessor;
import org.junit.Assume;
import org.junit.Test;

import javax.swing.Action;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import java.awt.GraphicsEnvironment;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.io.File;
import java.util.Collections;
import java.util.function.Consumer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class MacroVariationsDialogTest {

    @Test
    public void escapeAndDirectBackingCloseRouteThroughWrapper()
            throws Exception {
        Assume.assumeFalse("PipelineDialog creates a JDialog in this codebase.",
                GraphicsEnvironment.isHeadless());
        final TrackingImage escaped = new TrackingImage("macro-escaped");
        final TrackingImage escapedLate = new TrackingImage("macro-escaped-late");
        final TrackingImage direct = new TrackingImage("macro-direct");
        final TrackingImage directLate = new TrackingImage("macro-direct-late");
        final MacroVariationsDialog[] directDialog = new MacroVariationsDialog[1];
        final VariationCellPanel[] directCell = new VariationCellPanel[1];

        SwingUtilities.invokeAndWait(new Runnable() {
            @Override public void run() {
                MacroVariationsDialog escapedDialog = new MacroVariationsDialog(
                        null, context(), null);
                VariationCellPanel escapedCell = cellWithResult(escaped);
                escapedDialog.addCellForTest(escapedCell);
                JDialog escapedWindow = (JDialog) escapedDialog.getWindow();
                escapedWindow.pack();
                KeyStroke escape = KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0);
                Object key = escapedWindow.getRootPane()
                        .getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).get(escape);
                Action action = escapedWindow.getRootPane().getActionMap().get(key);
                assertNotNull(action);
                action.actionPerformed(new ActionEvent(escapedWindow,
                        ActionEvent.ACTION_PERFORMED, "escape"));
                assertFalse(escapedWindow.isDisplayable());
                escapedCell.setResult(VariationResult.success(escapedCell.combo(),
                        escapedLate, 1, 1L, null));
                action.actionPerformed(new ActionEvent(escapedWindow,
                        ActionEvent.ACTION_PERFORMED, "repeat-escape"));

                directDialog[0] = new MacroVariationsDialog(null, context(), null);
                directCell[0] = cellWithResult(direct);
                directDialog[0].addCellForTest(directCell[0]);
                directDialog[0].getWindow().pack();
                directDialog[0].getWindow().dispose();
            }
        });
        SwingUtilities.invokeAndWait(new Runnable() {
            @Override public void run() {
                assertFalse(directDialog[0].getWindow().isDisplayable());
                directCell[0].setResult(VariationResult.success(
                        directCell[0].combo(), directLate, 1, 1L, null));
                directDialog[0].dispose();
            }
        });

        assertEquals(1, escaped.closeCalls);
        assertEquals(1, escapedLate.closeCalls);
        assertEquals(1, direct.closeCalls);
        assertEquals(1, directLate.closeCalls);
    }

    @Test
    public void fatalCellCleanupReleasesDisposeGuardForExplicitRecovery()
            throws Exception {
        Assume.assumeFalse("PipelineDialog creates a JDialog in this codebase.",
                GraphicsEnvironment.isHeadless());
        VariationCleanupCoordinator.resetForTest();
        final ThreadDeath fatal = new ThreadDeath();
        final FatalOnceDisposer disposer = new FatalOnceDisposer(fatal);
        final MacroVariationsDialog[] holder = new MacroVariationsDialog[1];
        try {
            SwingUtilities.invokeAndWait(new Runnable() {
                @Override public void run() {
                    MacroVariationsDialog dialog = new MacroVariationsDialog(
                            null, context(), null);
                    holder[0] = dialog;
                    VariationCellPanel cell = new VariationCellPanel(
                            ParameterCombo.builder().build(),
                            stack("macro-fatal-source", 1), null, null);
                    cell.setResult(VariationResult.filterSuccess(cell.combo(),
                            new TrackingImage("macro-fatal-result"), 1L,
                            new int[256], 1.0d, 1.0d, disposer));
                    dialog.addCellForTest(cell);
                    dialog.getWindow().pack();

                    try {
                        dialog.dispose();
                        fail("Expected VM-fatal cleanup failure.");
                    } catch (ThreadDeath expected) {
                        assertSame(fatal, expected);
                    }
                    assertEquals(1, disposer.calls);
                }
            });
            Thread.sleep(400L);
            SwingUtilities.invokeAndWait(new Runnable() {
                @Override public void run() {
                    assertEquals("fatal cleanup must remain paused until explicit recovery",
                            1, disposer.calls);
                    holder[0].dispose();
                    assertFalse(holder[0].getWindow().isDisplayable());
                }
            });
            assertEquals(2, disposer.calls);
            assertEquals(1, disposer.successfulCloses);
        } finally {
            VariationCleanupCoordinator.resetForTest();
        }
    }

    @Test
    public void constructsWithParamsModeAndEmptyGrid() throws Exception {
        Assume.assumeFalse("PipelineDialog creates a JDialog in this codebase.",
                GraphicsEnvironment.isHeadless());
        final MacroVariationsDialog[] holder = new MacroVariationsDialog[1];

        SwingUtilities.invokeAndWait(new Runnable() {
            @Override public void run() {
                holder[0] = new MacroVariationsDialog(null, context(),
                        new Consumer<String>() {
                            @Override public void accept(String macro) {
                            }
                        });
            }
        });

        try {
            SwingUtilities.invokeAndWait(new Runnable() {
                @Override public void run() {
                    MacroVariationsDialog dialog = holder[0];
                    assertNotNull(dialog);
                    assertEquals(MacroVariationsDialog.Mode.SWEEP_PARAMETER,
                            dialog.modeForTest());
                    assertTrue(dialog.sweepParamButtonForTest().isEnabled());
                    assertTrue(dialog.sweepParamButtonForTest().isSelected());
                    assertTrue(dialog.sweepStepButtonForTest().isEnabled());
                    assertTrue(dialog.sweepPresetsButtonForTest().isEnabled());
                    assertTrue(dialog.fullSweepButtonForTest().isEnabled());
                    assertTrue(dialog.sweepStepButtonForTest()
                            .getToolTipText().contains("alternatives"));
                    assertEquals("Compare readable filter presets",
                            dialog.sweepPresetsButtonForTest().getToolTipText());
                    assertNull(dialog.gridWindowForTest());
                    assertFalse(dialog.useComboButtonForTest().isEnabled());
                    assertTrue(dialog.chainRibbonLabelForTest().getText()
                            .contains("Gaussian Blur"));
                    assertEquals(ParameterSweep.Method.FILTER,
                            dialog.editorForTest().currentSweep().method());
                }
            });
        } finally {
            SwingUtilities.invokeAndWait(new Runnable() {
                @Override public void run() {
                    if (holder[0] != null) {
                        holder[0].dispose();
                    }
                }
            });
        }
    }

    private static FilterVariationEngineContext context() {
        ImagePlus source = stack("source", 3);
        FilterMacroEditorModel.MacroDefinition macro =
                FilterMacroEditorModel.parse(macroText());
        ConfigQcContext config = ConfigQcContext.fromImages(new File("."),
                new File("target/macro-variations-dialog-test-bin"),
                null, Collections.singletonList(source),
                Collections.singletonList("DAPI"), 0);
        return new FilterVariationEngineContext(macro, source, CropSpec.centre256(),
                "DAPI", config, new StubPreviewAdapter());
    }

    private static VariationCellPanel cellWithResult(TrackingImage image) {
        VariationCellPanel cell = new VariationCellPanel(
                ParameterCombo.builder().build(), stack("macro-cell-source", 1),
                null, null);
        cell.setResult(VariationResult.success(cell.combo(), image, 1, 1L, null));
        return cell;
    }

    private static final class TrackingImage extends ImagePlus {
        int closeCalls;

        TrackingImage(String title) {
            super(title, new ByteProcessor(1, 1));
        }

        @Override public void close() {
            closeCalls++;
            super.close();
        }
    }

    private static final class FatalOnceDisposer
            implements VariationResult.ImageDisposer {
        private final ThreadDeath fatal;
        int calls;
        int successfulCloses;

        FatalOnceDisposer(ThreadDeath fatal) {
            this.fatal = fatal;
        }

        @Override public void dispose(ImagePlus image) {
            calls++;
            if (calls == 1) {
                throw fatal;
            }
            image.close();
            successfulCloses++;
        }
    }

    private static String macroText() {
        return "run(\"Gaussian Blur...\", \"sigma=2 stack\");\n"
                + "run(\"Subtract Background...\", \"rolling=20 stack\");";
    }

    private static ImagePlus stack(String title, int slices) {
        ImageStack stack = new ImageStack(16, 16);
        for (int z = 0; z < slices; z++) {
            ByteProcessor processor = new ByteProcessor(16, 16);
            processor.setValue(z + 1);
            processor.fill();
            stack.addSlice("z" + (z + 1), processor);
        }
        ImagePlus image = new ImagePlus(title, stack);
        image.setDimensions(1, slices, 1);
        return image;
    }

    private static final class StubPreviewAdapter
            implements FilterParameterStage.PreviewAdapter {
        @Override public ImagePlus createSource(ConfigQcContext context) {
            return stack("source", 1);
        }

        @Override public ImagePlus createFilteredPreview(ImagePlus source,
                                                         String macroContent) {
            return source == null ? null : source.duplicate();
        }

        @Override public void close(ImagePlus image) {
        }
    }
}
