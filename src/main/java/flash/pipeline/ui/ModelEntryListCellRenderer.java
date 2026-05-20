package flash.pipeline.ui;

import flash.pipeline.segmentation.catalog.ModelEntry;

import javax.swing.DefaultListCellRenderer;
import javax.swing.JList;
import java.awt.Component;

public final class ModelEntryListCellRenderer extends DefaultListCellRenderer {
    public interface EntryAdapter {
        ModelEntry modelEntry();
        boolean showUserSeparator();
    }

    public static final class Presentation {
        public final String displayText;
        public final String tooltipText;
        public final boolean userEntry;
        public final boolean separatorBefore;

        Presentation(String displayText,
                     String tooltipText,
                     boolean userEntry,
                     boolean separatorBefore) {
            this.displayText = displayText == null ? "" : displayText;
            this.tooltipText = tooltipText == null ? "" : tooltipText;
            this.userEntry = userEntry;
            this.separatorBefore = separatorBefore;
        }
    }

    @Override
    public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                  boolean isSelected, boolean cellHasFocus) {
        super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
        Presentation presentation = presentationFor(value);
        setText(presentation.displayText);
        if (list != null) {
            list.setToolTipText(presentation.tooltipText.isEmpty()
                    ? null : presentation.tooltipText);
        }
        return this;
    }

    public static Presentation presentationFor(Object value) {
        if (value instanceof EntryAdapter) {
            EntryAdapter adapter = (EntryAdapter) value;
            return presentation(adapter.modelEntry(), adapter.showUserSeparator());
        }
        if (value instanceof ModelEntry) {
            return presentation((ModelEntry) value, false);
        }
        return new Presentation(value == null ? "" : String.valueOf(value), "", false, false);
    }

    public static Presentation presentation(ModelEntry entry, boolean separatorBefore) {
        if (entry == null) {
            return new Presentation("", "", false, separatorBefore);
        }
        boolean user = !entry.isStock();
        String text = displayName(entry);
        String advanced = advancedText(entry);
        if (!advanced.isEmpty()) {
            text += " " + advanced;
        }
        if (user) {
            text += "    " + entry.source.jsonValue();
            String date = dateLabel(entry);
            if (!date.isEmpty()) {
                text += "  " + date;
            }
        }
        if (separatorBefore && user) {
            text = "- - - User models - - -  " + text;
        }
        return new Presentation(text, tooltip(entry), user, separatorBefore && user);
    }

    public static String displayName(ModelEntry entry) {
        if (entry == null) return "";
        String name = entry.name == null || entry.name.trim().isEmpty()
                ? entry.modelKey
                : entry.name.trim();
        if (!entry.isStock() && name.toLowerCase().indexOf("custom") < 0) {
            name += " (custom)";
        }
        return name;
    }

    public static String tooltip(ModelEntry entry) {
        if (entry == null) return "";
        StringBuilder sb = new StringBuilder();
        if (entry.description != null && !entry.description.trim().isEmpty()) {
            sb.append(entry.description.trim());
        }
        String quality = metadataText(entry, "qualityFlag");
        if (!quality.isEmpty()) {
            if (sb.length() > 0) sb.append(" | ");
            sb.append("Quality: ").append(quality);
        }
        String advanced = advancedText(entry);
        if (!advanced.isEmpty()) {
            if (sb.length() > 0) sb.append(" | ");
            sb.append(advanced.substring(1, advanced.length() - 1));
        }
        return sb.toString();
    }

    public static String dateLabel(ModelEntry entry) {
        if (entry == null) return "";
        String text = metadataText(entry, "importedAt");
        if (text.isEmpty()) text = metadataText(entry, "updatedAt");
        if (text.isEmpty()) text = metadataText(entry, "trainedAt");
        if (text.isEmpty()) text = metadataText(entry, "createdAt");
        return text.length() >= 10 ? text.substring(0, 10) : text;
    }

    public static String advancedText(ModelEntry entry) {
        if (!metadataBoolean(entry, "advanced")) return "";
        if (metadataBoolean(entry, "rgbOnly")) return "[advanced - RGB]";
        return "[advanced]";
    }

    private static String metadataText(ModelEntry entry, String key) {
        if (entry == null || key == null) return "";
        Object value = entry.metadata.get(key);
        String text = value == null ? "" : String.valueOf(value).trim();
        return "null".equalsIgnoreCase(text) ? "" : text;
    }

    private static boolean metadataBoolean(ModelEntry entry, String key) {
        if (entry == null || key == null) return false;
        Object value = entry.metadata.get(key);
        if (value instanceof Boolean) return ((Boolean) value).booleanValue();
        return value != null && Boolean.parseBoolean(String.valueOf(value));
    }
}
