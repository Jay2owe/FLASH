package flash.pipeline.ui.sandbox.variation;

import flash.pipeline.image.dag.DagToIjmEmitter;
import flash.pipeline.image.variation.VariantPlan;
import ij.IJ;

import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.util.ArrayList;
import java.util.List;

public final class IjmClipboardExporter {

    private final VariantGridFrame grid;

    public IjmClipboardExporter(VariantGridFrame grid) {
        if (grid == null) throw new IllegalArgumentException("grid must not be null");
        this.grid = grid;
    }

    public void copyVisibleVariantsToClipboard() {
        String text = buildText(grid.visibleVariantPlansInDisplayOrder());
        if (text.length() == 0) {
            IJ.showMessage("Variations", "No visible variant macro code is available.");
            return;
        }
        Toolkit.getDefaultToolkit().getSystemClipboard()
                .setContents(new StringSelection(text), null);
    }

    static String buildText(List<VariantPlan> plans) {
        if (plans == null || plans.isEmpty()) return "";
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < plans.size(); i++) {
            VariantPlan plan = plans.get(i);
            if (plan == null || plan.dag == null) continue;
            out.append("// VARIANT: ").append(plan.label == null ? "" : plan.label).append('\n');
            out.append(DagToIjmEmitter.emit(plan.dag));
            if (out.length() == 0 || out.charAt(out.length() - 1) != '\n') out.append('\n');
            out.append('\n');
        }
        return out.toString();
    }

    static String buildTextFromTiles(List<TilePanel> tiles) {
        List<VariantPlan> plans = new ArrayList<VariantPlan>();
        if (tiles != null) {
            for (int i = 0; i < tiles.size(); i++) {
                TilePanel tile = tiles.get(i);
                if (tile != null && tile.plan() != null) plans.add(tile.plan());
            }
        }
        return buildText(plans);
    }
}
