package flash.pipeline.ui.preview;

import flash.pipeline.ui.variations.AxisGutterPanel;
import flash.pipeline.ui.variations.CropSpec;
import flash.pipeline.ui.variations.FacetChipRow;
import flash.pipeline.ui.variations.MacroVariation;
import flash.pipeline.ui.variations.MacroVariationSet;
import flash.pipeline.ui.variations.ParameterCombo;
import flash.pipeline.ui.variations.ParameterId;
import flash.pipeline.ui.variations.ParameterSweep;
import flash.pipeline.ui.variations.ParameterValueList;

import org.junit.Test;

import javax.swing.AbstractButton;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Container;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class VariationMontageGridMacroTest {

    @Test
    public void macroOnlyMontageUsesFacetChipsWithDisplayNames()
            throws Exception {
        final MacroVariation blur = MacroVariation.pasted("Blur macro",
                "run(\"Gaussian Blur...\", \"sigma=1 stack\");");
        final MacroVariation median = MacroVariation.pasted("Median r=2",
                "run(\"Median...\", \"radius=2 stack\");");
        final ParameterSweep sweep = macroSweep(blur, median);
        final VariationMontageGrid grid = new VariationMontageGrid();

        SwingUtilities.invokeAndWait(() -> grid.setTiles(tilesFor(sweep), sweep, 1));

        SwingUtilities.invokeAndWait(() -> {
            assertTrue(findAll(grid, AxisGutterPanel.class).isEmpty());
            List<FacetChipRow> rows = findAll(grid, FacetChipRow.class);
            assertEquals(1, rows.size());
            assertTrue(rows.get(0).selectedValues().containsKey(ParameterId.MACRO));
            assertChipTexts(rows.get(0), blur.displayName(), median.displayName());
        });
    }

    @Test
    public void duplicateMacroDisplayNamesShowShortHashes()
            throws Exception {
        final MacroVariation first = MacroVariation.pasted("Same name",
                "run(\"Gaussian Blur...\", \"sigma=1 stack\");");
        final MacroVariation second = MacroVariation.pasted("Same name",
                "run(\"Gaussian Blur...\", \"sigma=2 stack\");");
        final ParameterSweep sweep = macroSweep(first, second);
        final VariationMontageGrid grid = new VariationMontageGrid();

        SwingUtilities.invokeAndWait(() -> grid.setTiles(tilesFor(sweep), sweep, 1));

        SwingUtilities.invokeAndWait(() -> {
            FacetChipRow row = findAll(grid, FacetChipRow.class).get(0);
            List<String> texts = buttonTexts(findAll(row, AbstractButton.class));
            assertEquals(2, texts.size());
            assertTrue(texts.get(0).startsWith("Same name "));
            assertTrue(texts.get(1).startsWith("Same name "));
            assertFalse(texts.get(0).equals(texts.get(1)));
        });
    }

    private static ParameterSweep macroSweep(MacroVariation blur,
                                             MacroVariation median) {
        Map<ParameterId, ParameterValueList> values =
                new LinkedHashMap<ParameterId, ParameterValueList>();
        values.put(ParameterId.MACRO,
                ParameterValueList.ofStrings(blur.token(), median.token()));
        return new ParameterSweep(ParameterSweep.Method.CLASSICAL, values,
                CropSpec.full(), "DAPI", "montage-grid-macro-test",
                MacroVariationSet.of(blur, median));
    }

    private static List<VariationMontageDialog.MontageTile> tilesFor(
            ParameterSweep sweep) {
        List<VariationMontageDialog.MontageTile> tiles =
                new ArrayList<VariationMontageDialog.MontageTile>();
        List<ParameterCombo> combos = sweep.combos();
        for (int i = 0; i < combos.size(); i++) {
            tiles.add(new VariationMontageDialog.MontageTile(
                    combos.get(i), null, 10 + i, Double.NaN));
        }
        return tiles;
    }

    private static void assertChipTexts(Container root, String... expected) {
        List<AbstractButton> buttons = findAll(root, AbstractButton.class);
        assertEquals(Arrays.asList(expected), buttonTexts(buttons));
        for (int i = 0; i < buttons.size(); i++) {
            assertFalse(buttons.get(i).getText().startsWith("macro:"));
            assertNotNull(buttons.get(i).getToolTipText());
        }
    }

    private static List<String> buttonTexts(List<AbstractButton> buttons) {
        List<String> out = new ArrayList<String>();
        for (int i = 0; i < buttons.size(); i++) {
            out.add(buttons.get(i).getText());
        }
        return out;
    }

    private static <T> List<T> findAll(Component root, Class<T> type) {
        List<T> out = new ArrayList<T>();
        collect(root, type, out);
        return out;
    }

    private static <T> void collect(Component component, Class<T> type,
                                    List<T> out) {
        if (component == null) {
            return;
        }
        if (type.isInstance(component)) {
            out.add(type.cast(component));
        }
        if (component instanceof Container) {
            Component[] children = ((Container) component).getComponents();
            for (int i = 0; i < children.length; i++) {
                collect(children[i], type, out);
            }
        }
    }
}
