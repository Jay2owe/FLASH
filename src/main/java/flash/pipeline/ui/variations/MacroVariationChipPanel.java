package flash.pipeline.ui.variations;

import flash.pipeline.ui.FlashTheme;

import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.event.ChangeEvent;
import javax.swing.event.ChangeListener;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class MacroVariationChipPanel extends JPanel {

    private static final Color CHIP_BG = new Color(0xF4, 0xF7, 0xFA);
    private static final Color CHIP_FG = new Color(0x2F, 0x3A, 0x43);
    private static final Color CHIP_BORDER = new Color(0xA8, 0xB3, 0xBD);
    private static final Color CHIP_DISABLED_BG = new Color(0xE5, 0xE8, 0xEB);
    private static final Color CHIP_DISABLED_FG = new Color(0x92, 0x98, 0x9E);
    private static final Color ADD_BG = new Color(0xE8, 0xF4, 0xFB);
    private static final Color ADD_BORDER = new Color(0x56, 0xB4, 0xE9);

    private final MacroVariationCatalog catalog;
    private final LinkedHashMap<String, MacroVariation> variations =
            new LinkedHashMap<String, MacroVariation>();
    private final LinkedHashMap<String, String> tokenReplacements =
            new LinkedHashMap<String, String>();
    private final List<String> values = new ArrayList<String>();
    private final List<ChangeListener> listeners =
            new ArrayList<ChangeListener>();

    public MacroVariationChipPanel(ParameterValueList initialValues,
                                   MacroVariationCatalog catalog) {
        super(new FlowLayout(FlowLayout.LEFT, 4, 2));
        this.catalog = catalog == null ? MacroVariationCatalog.empty() : catalog;
        setOpaque(false);
        setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 0));
        variations.put(MacroToken.NONE_VALUE, MacroVariation.none());
        rememberCatalogChoices();
        setValues(initialValues == null
                ? Collections.singletonList(MacroToken.NONE_VALUE)
                : initialValues.values());
    }

    public ParameterValueList currentValueList() {
        return new ParameterValueList(new ArrayList<Object>(values));
    }

    public MacroVariationSet selectedVariationSet() {
        List<MacroVariation> selected = new ArrayList<MacroVariation>();
        for (int i = 0; i < values.size(); i++) {
            selected.add(resolve(values.get(i)));
        }
        return new MacroVariationSet(selected);
    }

    public void setMacroVariationSet(MacroVariationSet macroVariationSet) {
        tokenReplacements.clear();
        if (macroVariationSet == null) {
            return;
        }
        List<MacroVariation> known = macroVariationSet.variations();
        for (int i = 0; i < known.size(); i++) {
            MacroVariation saved = known.get(i);
            MacroVariation live = rehydrateFromCatalog(saved);
            remember(live);
            rememberReplacement(saved, live);
        }
        rebuild();
    }

    public void setValues(List<?> newValues) {
        values.clear();
        if (newValues != null) {
            for (int i = 0; i < newValues.size(); i++) {
                addTokenForValue(newValues.get(i));
            }
        }
        if (values.isEmpty()) {
            values.add(MacroToken.NONE_VALUE);
        }
        rebuild();
        fireChanged();
    }

    public void addChangeListener(ChangeListener listener) {
        if (listener != null) {
            listeners.add(listener);
        }
    }

    void addVariationForTest(MacroVariation variation) {
        addVariation(variation);
    }

    private void promptAndAdd() {
        List<MacroVariation> picked = MacroVariationPickerDialog.show(this, catalog);
        if (picked == null || picked.isEmpty()) {
            return;
        }
        for (int i = 0; i < picked.size(); i++) {
            addVariation(picked.get(i));
        }
    }

    private void promptAndReplace(int index) {
        if (index < 0 || index >= values.size()) {
            return;
        }
        List<MacroVariation> picked = MacroVariationPickerDialog.show(this, catalog);
        if (picked == null || picked.isEmpty()) {
            return;
        }
        replaceVariation(index, picked.get(0));
        for (int i = 1; i < picked.size(); i++) {
            addVariation(picked.get(i));
        }
    }

    private void addVariation(MacroVariation variation) {
        MacroVariation remembered = rememberReusingExistingScript(variation);
        if (remembered == null) {
            return;
        }
        String token = remembered.token();
        if (!values.contains(token)) {
            values.add(token);
        }
        rebuild();
        fireChanged();
    }

    private void replaceVariation(int index, MacroVariation variation) {
        MacroVariation remembered = rememberReusingExistingScript(variation);
        if (remembered == null) {
            return;
        }
        String token = remembered.token();
        if (values.contains(token) && !token.equals(values.get(index))) {
            values.remove(index);
        } else {
            values.set(index, token);
        }
        if (values.isEmpty()) {
            values.add(MacroToken.NONE_VALUE);
        }
        rebuild();
        fireChanged();
    }

    private void removeValue(int index) {
        if (index < 0 || index >= values.size()) {
            return;
        }
        String token = values.get(index);
        boolean removable = values.size() > 1 || !MacroToken.NONE_VALUE.equals(token);
        if (!removable) {
            return;
        }
        values.remove(index);
        if (values.isEmpty()) {
            values.add(MacroToken.NONE_VALUE);
        }
        rebuild();
        fireChanged();
    }

    private void addTokenForValue(Object value) {
        String token;
        if (value instanceof MacroVariation) {
            MacroVariation variation = (MacroVariation) value;
            remember(variation);
            token = variation.token();
        } else if (value instanceof MacroToken) {
            token = ((MacroToken) value).value();
        } else {
            String text = value == null ? "" : String.valueOf(value).trim();
            if (text.isEmpty()) {
                token = MacroToken.NONE_VALUE;
            } else if (MacroToken.isMacroToken(text)) {
                token = text;
            } else {
                MacroVariation variation = MacroVariation.pasted("Pasted macro",
                        String.valueOf(value));
                remember(variation);
                token = variation.token();
            }
        }
        if (token == null || token.trim().isEmpty()) {
            token = MacroToken.NONE_VALUE;
        }
        token = replacementFor(token);
        if (!variations.containsKey(token)) {
            variations.put(token, placeholderFor(token));
        }
        if (!values.contains(token)) {
            values.add(token);
        }
    }

    private MacroVariation rememberReusingExistingScript(MacroVariation variation) {
        if (variation == null) {
            return null;
        }
        if (!MacroToken.NONE_VALUE.equals(variation.token())) {
            MacroVariation existing = existingWithHash(variation.normalizedScriptHash());
            if (existing != null) {
                return existing;
            }
        }
        remember(variation);
        return resolve(variation.token());
    }

    private MacroVariation existingWithHash(String hash) {
        String safeHash = hash == null ? "" : hash.trim();
        if (safeHash.isEmpty()) {
            return null;
        }
        for (MacroVariation variation : variations.values()) {
            if (variation == null || MacroToken.NONE_VALUE.equals(variation.token())) {
                continue;
            }
            if (safeHash.equals(variation.normalizedScriptHash())) {
                return variation;
            }
        }
        return null;
    }

    private void remember(MacroVariation variation) {
        if (variation == null) {
            return;
        }
        String token = variation.token();
        if (token == null || token.trim().isEmpty()) {
            return;
        }
        variations.put(token.trim(), variation);
    }

    private void rememberCatalogChoices() {
        List<MacroVariation> choices = catalog.choices();
        for (int i = 0; i < choices.size(); i++) {
            remember(choices.get(i));
        }
    }

    private MacroVariation rehydrateFromCatalog(MacroVariation saved) {
        if (saved == null || MacroToken.NONE_VALUE.equals(saved.token())) {
            return MacroVariation.none();
        }
        MacroVariation exact = variations.get(saved.token());
        if (hasScript(exact)) {
            return exact;
        }
        MacroVariation bySource = liveVariationForSameExternalSource(saved);
        return bySource == null ? saved : bySource;
    }

    private MacroVariation liveVariationForSameExternalSource(MacroVariation saved) {
        if (!isRefreshableExternalSource(saved)) {
            return null;
        }
        for (MacroVariation candidate : variations.values()) {
            if (!hasScript(candidate)
                    || !safe(candidate.sourceKind()).equals(safe(saved.sourceKind()))) {
                continue;
            }
            if (sameNonEmpty(candidate.sourceName(), saved.sourceName())
                    || sameNonEmpty(candidate.displayName(), saved.displayName())) {
                return candidate;
            }
        }
        return null;
    }

    private void rememberReplacement(MacroVariation saved, MacroVariation live) {
        if (saved == null || live == null) {
            return;
        }
        String savedToken = saved.token();
        String liveToken = live.token();
        if (savedToken == null || liveToken == null || savedToken.equals(liveToken)) {
            return;
        }
        tokenReplacements.put(savedToken, liveToken);
    }

    private String replacementFor(String token) {
        String safeToken = token == null ? "" : token.trim();
        String replacement = tokenReplacements.get(safeToken);
        return replacement == null ? safeToken : replacement;
    }

    private static boolean hasScript(MacroVariation variation) {
        return variation != null
                && variation.scriptText() != null
                && !variation.scriptText().trim().isEmpty();
    }

    private static boolean isRefreshableExternalSource(MacroVariation variation) {
        if (variation == null) {
            return false;
        }
        String kind = safe(variation.sourceKind());
        return MacroVariation.SOURCE_CURRENT_CHANNEL.equals(kind)
                || MacroVariation.SOURCE_BUNDLED_PRESET.equals(kind)
                || MacroVariation.SOURCE_SAVED_PRESET.equals(kind);
    }

    private static boolean sameNonEmpty(String left, String right) {
        String safeLeft = safe(left);
        String safeRight = safe(right);
        return !safeLeft.isEmpty() && safeLeft.equals(safeRight);
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }

    private MacroVariation resolve(String token) {
        String safeToken = token == null || token.trim().isEmpty()
                ? MacroToken.NONE_VALUE
                : token.trim();
        MacroVariation variation = variations.get(safeToken);
        return variation == null ? placeholderFor(safeToken) : variation;
    }

    private MacroVariation placeholderFor(String token) {
        if (MacroToken.NONE_VALUE.equals(token)) {
            return MacroVariation.none();
        }
        MacroToken parsed = MacroToken.parse(token);
        String hash = parsed.normalizedScriptHash();
        String display = hash == null || hash.isEmpty()
                ? "Macro"
                : "Macro " + shortHash(hash);
        return new MacroVariation(parsed, display, parsed.sourceName(), "");
    }

    private void rebuild() {
        removeAll();
        for (int i = 0; i < values.size(); i++) {
            add(createChip(i));
        }
        add(createAddChip());
        revalidate();
        repaint();
    }

    private JPanel createChip(final int index) {
        JPanel chip = new JPanel();
        chip.setOpaque(false);
        chip.setLayout(new FlowLayout(FlowLayout.LEFT, 0, 0));

        final MacroVariation variation = resolve(values.get(index));
        Chip valueChip = new Chip(displayName(variation), CHIP_BG, CHIP_FG, CHIP_BORDER);
        valueChip.setFont(FlashTheme.bodyMedium().deriveFont(11f));
        valueChip.setToolTipText(identityTooltip(variation));
        valueChip.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                promptAndReplace(index);
            }
        });
        chip.add(valueChip);

        boolean removable = values.size() > 1
                || !MacroToken.NONE_VALUE.equals(variation.token());
        Chip removeChip = new Chip("x",
                removable ? CHIP_BG : CHIP_DISABLED_BG,
                removable ? CHIP_FG : CHIP_DISABLED_FG,
                CHIP_BORDER);
        removeChip.setFont(FlashTheme.bodyMedium().deriveFont(11f));
        removeChip.setToolTipText(removable
                ? "Remove macro"
                : "At least None is required");
        removeChip.setEnabled(removable);
        if (removable) {
            removeChip.addMouseListener(new MouseAdapter() {
                @Override public void mouseClicked(MouseEvent e) {
                    removeValue(index);
                }
            });
        }
        chip.add(removeChip);
        return chip;
    }

    private Chip createAddChip() {
        Chip addChip = new Chip("+", ADD_BG, CHIP_FG, ADD_BORDER);
        addChip.setFont(FlashTheme.bodyMedium().deriveFont(12f));
        addChip.setToolTipText("Pick macro");
        addChip.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                promptAndAdd();
            }
        });
        return addChip;
    }

    private void fireChanged() {
        ChangeEvent event = new ChangeEvent(this);
        for (int i = 0; i < listeners.size(); i++) {
            listeners.get(i).stateChanged(event);
        }
    }

    private static String displayName(MacroVariation variation) {
        if (variation == null) {
            return "Macro";
        }
        if (MacroToken.NONE_VALUE.equals(variation.token())) {
            return "None";
        }
        return variation.displayName();
    }

    private static String identityTooltip(MacroVariation variation) {
        if (variation == null) {
            return null;
        }
        String hash = variation.normalizedScriptHash();
        String preview = MacroVariationCatalog.scriptPreview(variation.scriptText());
        StringBuilder out = new StringBuilder("<html>");
        out.append("<b>").append(escape(displayName(variation))).append("</b>");
        out.append("<br>source: ")
                .append(escape(MacroVariationCatalog.sourceLabel(variation)));
        String sourceName = variation.sourceName();
        if (sourceName != null && !sourceName.trim().isEmpty()) {
            out.append(" / ").append(escape(sourceName.trim()));
        }
        out.append("<br>hash: ").append(escape(hash == null ? "" : hash));
        if (preview != null && !preview.isEmpty()) {
            out.append("<br>script: ").append(escape(preview));
        }
        out.append("</html>");
        return out.toString();
    }

    private static String shortHash(String hash) {
        String safe = hash == null ? "" : hash.trim();
        return safe.length() <= 8 ? safe : safe.substring(0, 8);
    }

    private static String escape(String text) {
        String value = text == null ? "" : text;
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    private static final class Chip extends JLabel {
        private final Color bg;
        private final Color fg;
        private final Color border;

        Chip(String text, Color bg, Color fg, Color border) {
            super(text);
            this.bg = bg;
            this.fg = fg;
            this.border = border;
            setOpaque(false);
            setForeground(fg);
            setBorder(BorderFactory.createEmptyBorder(2, 8, 2, 8));
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        }

        @Override public void setEnabled(boolean enabled) {
            super.setEnabled(enabled);
            setCursor(enabled
                    ? Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                    : Cursor.getDefaultCursor());
        }

        @Override public Dimension getPreferredSize() {
            Dimension size = super.getPreferredSize();
            return new Dimension(size.width, Math.max(22, size.height));
        }

        @Override protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);
            int w = getWidth();
            int h = getHeight();
            int arc = h;
            g2.setColor(isEnabled() ? bg : CHIP_DISABLED_BG);
            g2.fillRoundRect(0, 0, w - 1, h - 1, arc, arc);
            g2.setColor(border);
            g2.setStroke(new BasicStroke(1.5f));
            g2.drawRoundRect(1, 1, Math.max(1, w - 3), Math.max(1, h - 3),
                    Math.max(1, arc - 2), Math.max(1, arc - 2));
            g2.dispose();
            setForeground(isEnabled() ? fg : CHIP_DISABLED_FG);
            super.paintComponent(g);
        }
    }
}
