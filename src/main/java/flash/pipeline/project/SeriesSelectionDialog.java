package flash.pipeline.project;

import flash.pipeline.ui.FlashTheme;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.SwingConstants;
import javax.swing.WindowConstants;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Window;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Modal dialog for narrowing which Bio-Formats series to include from a
 * single source file. Pure UI — series metadata is supplied by the caller
 * so this class stays testable and Bio-Formats-free.
 */
public final class SeriesSelectionDialog {

    public static final class SeriesEntry {
        public final int index;
        public final String name;

        public SeriesEntry(int index, String name) {
            this.index = index;
            this.name = name == null ? "" : name;
        }
    }

    private SeriesSelectionDialog() {
    }

    /**
     * Show the dialog modally. Returns the selected series indices, or
     * {@code null} if the user cancelled. An empty returned list is the
     * caller's signal for "include all" — the dialog never returns an
     * empty list (it forces at least one selection or cancellation).
     */
    public static List<Integer> show(Window owner,
                                     String fileLabel,
                                     List<SeriesEntry> entries,
                                     List<Integer> initiallySelected) {
        if (entries == null || entries.isEmpty()) {
            return Collections.emptyList();
        }

        final JDialog dialog = new JDialog(owner, "Configure series", JDialog.ModalityType.APPLICATION_MODAL);
        dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);

        JPanel content = new JPanel(new BorderLayout(0, 8));
        content.setBorder(BorderFactory.createEmptyBorder(12, 14, 12, 14));
        content.setBackground(FlashTheme.SURFACE);

        JLabel header = new JLabel("Choose series to include from " + safeFileLabel(fileLabel) + ":");
        header.setForeground(FlashTheme.TEXT_HEADER);
        content.add(header, BorderLayout.NORTH);

        final Set<Integer> initial = new HashSet<Integer>();
        if (initiallySelected != null) {
            initial.addAll(initiallySelected);
        }
        final boolean includeAll = initial.isEmpty();

        JPanel list = new JPanel();
        list.setLayout(new BoxLayout(list, BoxLayout.Y_AXIS));
        list.setBackground(FlashTheme.SURFACE_RAISED);

        final List<JCheckBox> boxes = new ArrayList<JCheckBox>();
        for (SeriesEntry entry : entries) {
            String label = "[" + entry.index + "] " + (entry.name.isEmpty() ? "(unnamed)" : entry.name);
            JCheckBox box = new JCheckBox(label, includeAll || initial.contains(Integer.valueOf(entry.index)));
            box.setBackground(FlashTheme.SURFACE_RAISED);
            box.setAlignmentX(Component.LEFT_ALIGNMENT);
            box.putClientProperty("seriesIndex", Integer.valueOf(entry.index));
            list.add(box);
            boxes.add(box);
        }

        JScrollPane scroll = new JScrollPane(list);
        scroll.setPreferredSize(new Dimension(380, Math.min(360, 28 * entries.size() + 24)));
        scroll.setBorder(BorderFactory.createLineBorder(FlashTheme.BORDER));
        content.add(scroll, BorderLayout.CENTER);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        buttons.setBackground(FlashTheme.SURFACE);

        JButton selectAll = new JButton("Select all");
        JButton selectNone = new JButton("Select none");
        JButton cancel = new JButton("Cancel");
        JButton ok = new JButton("OK");

        selectAll.addActionListener(new ActionListener() {
            @Override public void actionPerformed(ActionEvent e) {
                for (JCheckBox b : boxes) b.setSelected(true);
            }
        });
        selectNone.addActionListener(new ActionListener() {
            @Override public void actionPerformed(ActionEvent e) {
                for (JCheckBox b : boxes) b.setSelected(false);
            }
        });

        final AtomicReference<List<Integer>> resultHolder = new AtomicReference<List<Integer>>();

        cancel.addActionListener(new ActionListener() {
            @Override public void actionPerformed(ActionEvent e) {
                resultHolder.set(null);
                dialog.dispose();
            }
        });
        ok.addActionListener(new ActionListener() {
            @Override public void actionPerformed(ActionEvent e) {
                List<Integer> chosen = new ArrayList<Integer>();
                for (JCheckBox b : boxes) {
                    if (b.isSelected()) {
                        chosen.add((Integer) b.getClientProperty("seriesIndex"));
                    }
                }
                if (chosen.isEmpty()) {
                    // Treat "no boxes ticked" as "include none → cancel" since
                    // an empty manifest series list otherwise means "include all".
                    resultHolder.set(null);
                } else if (chosen.size() == entries.size()) {
                    // Full selection collapses to "all" for round-trip stability.
                    resultHolder.set(Collections.<Integer>emptyList());
                } else {
                    resultHolder.set(chosen);
                }
                dialog.dispose();
            }
        });

        buttons.add(selectAll);
        buttons.add(selectNone);
        buttons.add(Box.createHorizontalStrut(12));
        buttons.add(cancel);
        buttons.add(ok);
        content.add(buttons, BorderLayout.SOUTH);

        dialog.setContentPane(content);
        dialog.pack();
        dialog.setLocationRelativeTo(owner);

        dialog.getRootPane().setDefaultButton(ok);
        // Center the header text on smaller dialogs.
        header.setHorizontalAlignment(SwingConstants.LEFT);

        dialog.setVisible(true);
        return resultHolder.get();
    }

    private static String safeFileLabel(String label) {
        return label == null || label.isEmpty() ? "this file" : label;
    }
}
