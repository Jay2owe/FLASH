package flash.pipeline.ui.config;

import javax.swing.JPanel;
import javax.swing.Scrollable;
import javax.swing.SwingConstants;
import java.awt.Dimension;
import java.awt.LayoutManager;
import java.awt.Rectangle;

/**
 * Width-tracking, non-height-tracking scrollable body for ConfigQc stage controls.
 * Mirrors PipelineDialog.BodyPanel so stage panels scroll vertically while their
 * content keeps filling the viewport width.
 */
final class ConfigQcScrollableBody extends JPanel implements Scrollable {

    ConfigQcScrollableBody(LayoutManager layout) {
        super(layout);
        setOpaque(false);
    }

    @Override public Dimension getPreferredScrollableViewportSize() {
        return getPreferredSize();
    }

    @Override public int getScrollableUnitIncrement(Rectangle visibleRect, int orientation, int direction) {
        return 16;
    }

    @Override public int getScrollableBlockIncrement(Rectangle visibleRect, int orientation, int direction) {
        return orientation == SwingConstants.VERTICAL
                ? Math.max(16, visibleRect.height - 32)
                : Math.max(16, visibleRect.width - 32);
    }

    @Override public boolean getScrollableTracksViewportWidth() {
        return true;
    }

    @Override public boolean getScrollableTracksViewportHeight() {
        return false;
    }
}
