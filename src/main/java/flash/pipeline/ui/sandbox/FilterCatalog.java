package flash.pipeline.ui.sandbox;

import flash.pipeline.image.FilterMacroParser.OpType;
import ij.Menus;

import javax.swing.BorderFactory;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.GraphicsEnvironment;
import java.awt.Menu;
import java.awt.MenuBar;
import java.awt.MenuItem;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

public final class FilterCatalog extends JPanel {

    public interface AddRequestListener {
        void onAddRequested(Entry entry);
    }

    private static volatile List<Entry> cachedTierTwoEntries;

    private final JTextField search = new JTextField();
    private final DefaultListModel<Entry> model = new DefaultListModel<Entry>();
    private final JList<Entry> list = new JList<Entry>(model);
    private final List<Entry> entries = new ArrayList<Entry>();
    private AddRequestListener addListener;

    public FilterCatalog() {
        this(null);
    }

    FilterCatalog(List<Entry> tierTwoEntries) {
        super(new BorderLayout(6, 6));
        setBorder(BorderFactory.createTitledBorder("Available steps"));

        add(search, BorderLayout.NORTH);
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setCellRenderer(new Renderer());
        add(new JScrollPane(list), BorderLayout.CENTER);

        seedTierOne();
        addTierTwoEntries(tierTwoEntries);
        refresh();

        search.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { refresh(); }
            @Override public void removeUpdate(DocumentEvent e) { refresh(); }
            @Override public void changedUpdate(DocumentEvent e) { refresh(); }
        });

        list.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2 && SwingUtilities.isLeftMouseButton(e)
                        && addListener != null) {
                    int index = list.locationToIndex(e.getPoint());
                    if (index < 0) return;
                    Entry entry = model.getElementAt(index);
                    if (entry == null || entry.stub) return;
                    addListener.onAddRequested(entry);
                }
            }
        });
    }

    public Entry getSelectedEntry() {
        return list.getSelectedValue();
    }

    /**
     * Returns the first non-stub entry whose label equals {@code label}
     * (case-insensitive), or {@code null} if none matches. Used by stage 04's
     * inline {@code + Add filter…} popover to look up a catalog entry by name.
     */
    public Entry findEntryByLabel(String label) {
        if (label == null) return null;
        String needle = label.trim();
        if (needle.isEmpty()) return null;
        for (int i = 0; i < entries.size(); i++) {
            Entry entry = entries.get(i);
            if (!entry.stub && needle.equalsIgnoreCase(entry.label)) return entry;
        }
        return null;
    }

    /** Public ordered snapshot of all non-stub catalog entries. */
    public List<Entry> getAllEntries() {
        List<Entry> out = new ArrayList<Entry>(entries.size());
        for (int i = 0; i < entries.size(); i++) {
            Entry e = entries.get(i);
            if (!e.stub) out.add(e);
        }
        return Collections.unmodifiableList(out);
    }

    public void setAddRequestListener(AddRequestListener listener) {
        this.addListener = listener;
    }

    public void focusSearch() {
        search.requestFocusInWindow();
    }

    private void refresh() {
        String q = search.getText() == null ? "" : search.getText().trim().toLowerCase(Locale.ROOT);
        Entry selected = getSelectedEntry();
        model.clear();
        for (int i = 0; i < entries.size(); i++) {
            Entry entry = entries.get(i);
            if (q.length() == 0 || entry.label.toLowerCase(Locale.ROOT).contains(q)
                    || entry.category.toLowerCase(Locale.ROOT).contains(q)
                    || entry.menuPath.toLowerCase(Locale.ROOT).contains(q)
                    || entry.badge().toLowerCase(Locale.ROOT).contains(q)) {
                model.addElement(entry);
            }
        }
        if (selected != null) list.setSelectedValue(selected, true);
        if (list.getSelectedIndex() < 0 && model.size() > 0) list.setSelectedIndex(0);
    }

    private void seedTierOne() {
        add("Smoothing", "Gaussian Blur", OpType.GAUSSIAN_BLUR, "sigma=2 stack");
        add("Smoothing", "Median", OpType.MEDIAN, "radius=2 stack");
        add("Smoothing", "Mean", OpType.MEAN, "radius=2 stack");
        add("Smoothing", "Minimum", OpType.MINIMUM, "radius=2 stack");
        add("Smoothing", "Maximum", OpType.MAXIMUM, "radius=2 stack");
        add("Smoothing", "Variance", OpType.VARIANCE, "radius=2 stack");

        add("Background", "Subtract Background", OpType.SUBTRACT_BACKGROUND, "rolling=50 stack");

        add("Morphology", "Dilate", OpType.DILATE, "");
        add("Morphology", "Erode", OpType.ERODE, "");
        add("Morphology", "Open", OpType.OPEN, "");
        add("Morphology", "Close-", OpType.CLOSE_, "");
        add("Morphology", "Fill Holes", OpType.FILL_HOLES, "");
        add("Morphology", "Skeletonize", OpType.SKELETONIZE, "");
        add("Morphology", "Unsharp Mask", OpType.UNSHARP_MASK, "radius=10 mask=0.60 stack");

        add("Thresholding", "Auto Local Threshold", OpType.AUTO_LOCAL_THRESHOLD,
                "method=Bernsen radius=15 parameter_1=0 parameter_2=0 white");

        add("Math", "Invert", OpType.INVERT, "");
        add("Math", "Add", OpType.ADD, "value=1 stack");
        add("Math", "Subtract", OpType.SUBTRACT, "value=1 stack");
        add("Math", "Multiply", OpType.MULTIPLY, "value=1 stack");
        add("Math", "Divide", OpType.DIVIDE, "value=1 stack");

        add("Bit depth", "8-bit", OpType.CONVERT_8BIT, "");
        add("Bit depth", "16-bit", OpType.CONVERT_16BIT, "");
        add("Bit depth", "32-bit", OpType.CONVERT_32BIT, "");

        add("Enhance", "Enhance Contrast", OpType.ENHANCE_CONTRAST, "saturated=0.35 normalize stack");
    }

    private void addTierTwoEntries(List<Entry> tierTwoEntries) {
        entries.addAll(tierTwoEntries == null ? getCachedTierTwoEntries() : tierTwoEntries);
    }

    private void add(String category, String label, OpType type, String args) {
        entries.add(Entry.fast(category, label, type, args));
    }

    static List<Entry> getCachedTierTwoEntries() {
        List<Entry> cached = cachedTierTwoEntries;
        if (cached != null) return cached;
        synchronized (FilterCatalog.class) {
            if (cachedTierTwoEntries == null) {
                cachedTierTwoEntries = Collections.unmodifiableList(loadTierTwoEntries());
            }
            return cachedTierTwoEntries;
        }
    }

    static void clearTierTwoCacheForTests() {
        cachedTierTwoEntries = null;
    }

    private static List<Entry> loadTierTwoEntries() {
        if (GraphicsEnvironment.isHeadless()) {
            return Collections.emptyList();
        }
        try {
            return collectTierTwoFromMenuBar(Menus.getMenuBar());
        } catch (Throwable t) {
            return Collections.emptyList();
        }
    }

    static List<Entry> collectTierTwoFromMenuBar(MenuBar menuBar) {
        List<Entry> out = new ArrayList<Entry>();
        if (menuBar == null) return out;
        for (int i = 0; i < menuBar.getMenuCount(); i++) {
            Menu menu = menuBar.getMenu(i);
            if (menu == null) continue;
            String label = normalizeLabel(menu.getLabel());
            if (includeWholeTopMenu(label)) {
                walkMenu(menu, label, label, out);
            } else if ("Plugins".equals(label)) {
                walkPluginsMenu(menu, label, out);
            }
        }
        return out;
    }

    private static void walkPluginsMenu(Menu menu, String path, List<Entry> out) {
        for (int i = 0; i < menu.getItemCount(); i++) {
            MenuItem item = menu.getItem(i);
            if (!(item instanceof Menu)) continue;
            String label = normalizeLabel(item.getLabel());
            if ("Filters".equals(label) || "Process".equals(label)) {
                walkMenu((Menu) item, "Tier 2", path + " > " + label, out);
            }
        }
    }

    private static void walkMenu(Menu menu, String category, String path, List<Entry> out) {
        for (int i = 0; i < menu.getItemCount(); i++) {
            MenuItem item = menu.getItem(i);
            if (item == null) continue;
            String rawLabel = item.getLabel();
            String label = normalizeLabel(rawLabel);
            if (label.length() == 0 || "-".equals(label) || shouldSkipCommand(label)) continue;
            String itemPath = path + " > " + normalizePathLabel(rawLabel);
            if (item instanceof Menu) {
                walkMenu((Menu) item, category, itemPath, out);
            } else {
                out.add(Entry.legacy(category, label, itemPath));
            }
        }
    }

    private static boolean includeWholeTopMenu(String label) {
        return "Process".equals(label) || "Image".equals(label) || "Analyze".equals(label);
    }

    private static boolean shouldSkipCommand(String label) {
        String lower = label.toLowerCase(Locale.ROOT);
        return lower.startsWith("about ")
                || lower.equals("help")
                || lower.equals("update")
                || lower.equals("refresh menus")
                || lower.equals("compile and run")
                || lower.equals("edit");
    }

    static String normalizeLabel(String label) {
        String normalized = label == null ? "" : label.trim();
        normalized = normalized.replace("&", "");
        while (normalized.endsWith(".")) {
            normalized = normalized.substring(0, normalized.length() - 1).trim();
        }
        return normalized;
    }

    private static String normalizePathLabel(String label) {
        String normalized = label == null ? "" : label.trim();
        normalized = normalized.replace("&", "");
        return normalized;
    }

    List<Entry> visibleEntriesForTests() {
        List<Entry> visible = new ArrayList<Entry>();
        for (int i = 0; i < model.size(); i++) {
            visible.add(model.getElementAt(i));
        }
        return visible;
    }

    void setSearchTextForTests(String text) {
        search.setText(text == null ? "" : text);
    }

    public static final class Entry {
        public final String category;
        public final String label;
        public final OpType type;
        public final String defaultArgs;
        public final boolean stub;
        public final boolean legacy;
        public final String commandName;
        public final String menuPath;

        private Entry(String category, String label, OpType type, String defaultArgs,
                      boolean stub, boolean legacy, String commandName, String menuPath) {
            this.category = category;
            this.label = label;
            this.type = type;
            this.defaultArgs = defaultArgs == null ? "" : defaultArgs;
            this.stub = stub;
            this.legacy = legacy;
            this.commandName = commandName == null ? "" : commandName;
            this.menuPath = menuPath == null ? "" : menuPath;
        }

        static Entry fast(String category, String label, OpType type, String args) {
            return new Entry(category, label, type, args, false, false, label, "");
        }

        static Entry legacy(String category, String commandName, String menuPath) {
            return new Entry(category, commandName, OpType.UNKNOWN, "", false, true, commandName, menuPath);
        }

        public String badge() {
            return legacy ? "[legacy]" : "[fast]";
        }

        @Override public String toString() {
            return category + " - " + label;
        }
    }

    private static final class Renderer extends DefaultListCellRenderer {
        @Override
        public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                      boolean isSelected, boolean cellHasFocus) {
            JLabel label = (JLabel) super.getListCellRendererComponent(
                    list, value, index, isSelected, cellHasFocus);
            if (value instanceof Entry) {
                Entry entry = (Entry) value;
                String path = entry.menuPath.length() > 0 ? entry.menuPath : entry.category;
                label.setText("<html><b>" + entry.label + " " + entry.badge()
                        + "</b><br><span style='font-size:9px;'>"
                        + path + (entry.stub ? " - placeholder" : "") + "</span></html>");
                if (entry.stub) label.setEnabled(false);
            }
            return label;
        }
    }
}
