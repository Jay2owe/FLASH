package flash.pipeline.ui;

import flash.pipeline.segmentation.catalog.ModelEntry;
import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ModelEntryListCellRendererTest {

    @Test
    public void formatsStockAndUserEntriesDifferently() {
        ModelEntry stock = entry("stardist_versatile", "StarDist - Versatile",
                ModelEntry.Source.STOCK_RESOURCE, null);
        ModelEntry user = entry("user_iba1", "Iba1 microglia",
                ModelEntry.Source.USER_IMPORTED, metadata("importedAt", "2026-05-14T10:00:00"));

        ModelEntryListCellRenderer.Presentation stockPresentation =
                ModelEntryListCellRenderer.presentation(stock, false);
        ModelEntryListCellRenderer.Presentation userPresentation =
                ModelEntryListCellRenderer.presentation(user, true);

        assertFalse(stockPresentation.userEntry);
        assertTrue(userPresentation.userEntry);
        assertTrue(userPresentation.separatorBefore);
        assertTrue(userPresentation.displayText.contains("user_imported"));
        assertTrue(userPresentation.displayText.contains("2026-05-14"));
    }

    @Test
    public void tooltipIncludesDescriptionAndQualityFlag() {
        Map<String, Object> metadata = metadata("qualityFlag", "USER_PROVIDED");
        ModelEntry entry = entry("user", "User", ModelEntry.Source.USER_IMPORTED, metadata);

        String tooltip = ModelEntryListCellRenderer.tooltip(entry);

        assertTrue(tooltip.contains("Useful model"));
        assertTrue(tooltip.contains("USER_PROVIDED"));
    }

    @Test
    public void advancedRgbFlagAppearsInPresentationText() {
        Map<String, Object> metadata = metadata("advanced", Boolean.TRUE);
        metadata.put("rgbOnly", Boolean.TRUE);
        ModelEntry entry = entry("he", "StarDist - H&E",
                ModelEntry.Source.STOCK_RESOURCE, metadata);

        assertTrue(ModelEntryListCellRenderer.presentation(entry, false)
                .displayText.contains("[advanced - RGB]"));
    }

    private static ModelEntry entry(String key, String name,
                                    ModelEntry.Source source,
                                    Map<String, Object> metadata) {
        return new ModelEntry(key, name, "Useful model",
                ModelEntry.Engine.STARDIST, source,
                source == ModelEntry.Source.USER_IMPORTED ? "files/" + key + "/model.zip" : null,
                source == ModelEntry.Source.STOCK_RESOURCE ? "models/" + key + ".zip" : null,
                null, null, null, null, metadata, false);
    }

    private static Map<String, Object> metadata(String key, Object value) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put(key, value);
        return out;
    }
}
