package flash.pipeline.ui.wizard;

import flash.pipeline.atlas.AtlasRegionLibrary;
import flash.pipeline.atlas.AtlasRegionLibraryIO;

import javax.swing.AbstractCellEditor;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.table.TableCellEditor;
import java.awt.Component;

/** JTable cell editor that adds atlas-region autocomplete and returns canonical exact matches. */
public final class RegionTableCellEditor extends AbstractCellEditor implements TableCellEditor {
    private final JTextField field = new JTextField();
    private final RegionTextFieldSupport.Handle support;

    public RegionTableCellEditor() {
        this(AtlasRegionLibraryIO.loadBundledQuietly());
    }

    public RegionTableCellEditor(AtlasRegionLibrary library) {
        support = RegionTextFieldSupport.install(field, library);
    }

    @Override
    public Component getTableCellEditorComponent(JTable table,
                                                 Object value,
                                                 boolean isSelected,
                                                 int row,
                                                 int column) {
        field.setText(value == null ? "" : String.valueOf(value));
        return field;
    }

    @Override
    public Object getCellEditorValue() {
        return support.canonicalText();
    }
}
