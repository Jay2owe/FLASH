package flash.pipeline.ui.wizard;

import flash.pipeline.atlas.AtlasRegionLibrary;
import flash.pipeline.atlas.AtlasRegionLibraryIO;
import org.junit.Test;

import javax.swing.JList;
import javax.swing.JTextField;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

public class RegionTextFieldSupportTest {

    @Test
    public void supportSuggestsAgainstPlainTextField() throws Exception {
        JTextField field = new JTextField();
        RegionTextFieldSupport.Handle handle =
                RegionTextFieldSupport.install(field, AtlasRegionLibraryIO.loadBundled());
        try {
            field.setText("scn");

            assertFalse(handle.currentSuggestions().isEmpty());
            assertEquals("SCH", handle.currentSuggestions().get(0).getAcronym());
        } finally {
            handle.dispose();
        }
    }

    @Test
    public void selectedRegionWritesCanonicalAcronym() throws Exception {
        AtlasRegionLibrary library = AtlasRegionLibraryIO.loadBundled();
        JTextField field = new JTextField();
        RegionTextFieldSupport.Handle handle = RegionTextFieldSupport.install(field, library);
        try {
            handle.setSelectedRegionForTest(library.byAcronym("CA1"));

            assertEquals("CA1", field.getText());
            assertEquals("CA1", handle.canonicalText());
        } finally {
            handle.dispose();
        }
    }

    @Test
    public void exactAliasCanonicalizesButFreeformStaysFreeform() throws Exception {
        JTextField field = new JTextField();
        RegionTextFieldSupport.Handle handle =
                RegionTextFieldSupport.install(field, AtlasRegionLibraryIO.loadBundled());
        try {
            field.setText("SCN");
            assertEquals("SCH", handle.canonicalText());

            field.setText("My custom region");
            assertEquals("My custom region", handle.canonicalText());
        } finally {
            handle.dispose();
        }
    }

    @Test
    public void mouseClickSelectsSuggestion() throws Exception {
        JTextField field = new JTextField();
        RegionTextFieldSupport.Handle handle =
                RegionTextFieldSupport.install(field, AtlasRegionLibraryIO.loadBundled());
        try {
            field.setText("scn");
            JList<?> list = handle.suggestionListForTest();
            list.setSize(240, 120);
            MouseEvent event = new MouseEvent(
                    list,
                    MouseEvent.MOUSE_CLICKED,
                    System.currentTimeMillis(),
                    0,
                    1,
                    1,
                    1,
                    false);
            for (MouseListener listener : list.getMouseListeners()) {
                listener.mouseClicked(event);
            }

            assertEquals("SCH", field.getText());
            assertEquals("SCH", handle.canonicalText());
        } finally {
            handle.dispose();
        }
    }
}
