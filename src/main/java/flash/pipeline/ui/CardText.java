package flash.pipeline.ui;

import javax.swing.JComponent;
import javax.swing.JLabel;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.util.List;

/** Shared text helpers for selectable card-style controls. */
public final class CardText {

    private CardText() {}

    public static JLabel centered(String text, Font font, int wrapWidth) {
        JLabel label = wrapped(text, font, wrapWidth, "center");
        label.setHorizontalAlignment(JLabel.CENTER);
        return label;
    }

    public static JLabel left(String text, Font font, int wrapWidth) {
        JLabel label = wrapped(text, font, wrapWidth, "left");
        label.setHorizontalAlignment(JLabel.LEFT);
        return label;
    }

    public static Dimension fitCardToContent(JComponent card,
                                             int preferredWidth,
                                             int minimumWidth,
                                             int minimumHeight) {
        Dimension content = card.getPreferredSize();
        int height = Math.max(minimumHeight, content.height);
        Dimension preferred = new Dimension(preferredWidth, height);
        card.setPreferredSize(preferred);
        card.setMinimumSize(new Dimension(Math.min(minimumWidth, preferredWidth), height));
        return preferred;
    }

    public static Dimension rowSizeForCards(List<? extends Component> cards,
                                            int rowWidth,
                                            int minimumHeight) {
        int height = minimumHeight;
        if (cards != null) {
            for (Component card : cards) {
                if (card != null) {
                    height = Math.max(height, card.getPreferredSize().height);
                }
            }
        }
        return new Dimension(rowWidth, height);
    }

    public static String htmlEscape(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }

    private static JLabel wrapped(String text, Font font, int wrapWidth, String align) {
        JLabel label = new JLabel("<html><body width='" + wrapWidth
                + "' style='text-align:" + align + "'>"
                + htmlEscape(text) + "</body></html>");
        label.setFont(font);
        label.setToolTipText(text);
        return label;
    }
}
